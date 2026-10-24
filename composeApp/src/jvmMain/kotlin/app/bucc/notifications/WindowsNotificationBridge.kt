package app.bucc.notifications

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Windows notification bridge kept separate so the platform entry point can wire it explicitly. */
internal class WindowsNotificationBridge(
    private val launcher: File,
    private val appId: String = "app.bucc.desktop",
    private val scheme: String = "bucc-notification",
) {
  private val mutex = Mutex()

  suspend fun schedule(plan: List<CourseReminderPlanItem>, dailyTodo: Boolean): String =
      mutex.withLock {
        if (!isWindows()) return "unsupported_platform"
        val launcherPath = validLauncherPath() ?: return "launcher_missing"
        if (!isSafeIdentity(appId, scheme)) return "invalid_configuration"
        val times = windowsNotificationTimes(plan, System.currentTimeMillis())
        val limited = times.take(MAX_SCHEDULED_TOASTS)
        val data =
            buildWindowsNotificationData(
                operation = "schedule",
                launcher = launcherPath,
                appId = appId,
                scheme = scheme,
                triggerAtMillis = limited,
                dailyTodo = dailyTodo,
            )
        val status = runPowerShell(data) ?: return "schedule_failed"
        if (status == "PERMISSION_REQUIRED") return "permission_required"
        if (status != "OK") return "schedule_failed"
        windowsNotificationCapacityStatus(times.size)
      }

  suspend fun cancel(): Unit =
      mutex.withLock {
        if (!isWindows() || !isSafeIdentity(appId, scheme)) return@withLock
        runPowerShell(buildWindowsNotificationData("cancel", "", appId, scheme))
        Unit
      }

  suspend fun show(destination: String): Boolean =
      mutex.withLock {
        if (
            !isWindows() ||
                !isSupportedNotificationDestination(destination) ||
                !isSafeIdentity(appId, scheme)
        )
            return@withLock false
        val launcherPath = validLauncherPath() ?: return@withLock false
        runPowerShell(
            buildWindowsNotificationData(
                "show",
                launcherPath,
                appId,
                scheme,
                destination = destination,
            )
        ) == "OK"
      }

  private fun validLauncherPath(): String? {
    if (!launcher.isFile || !launcher.name.endsWith(".exe", ignoreCase = true)) return null
    val path = runCatching { launcher.canonicalPath }.getOrNull() ?: return null
    return path.takeIf { isSafeWindowsNotificationLauncher(it) }
  }

  private suspend fun runPowerShell(data: String): String? {
    val encodedCommand = encodeWindowsPowerShellCommand(data) ?: return null
    val executable = windowsPowerShellExecutable() ?: return null

    return suspendCancellableCoroutine { continuation ->
      WINDOWS_NOTIFICATION_EXECUTOR.execute {
        if (!continuation.isActive) return@execute
        var process: Process? = null
        try {
          val running =
              ProcessBuilder(
                      executable.path,
                      "-NoProfile",
                      "-NonInteractive",
                      "-EncodedCommand",
                      encodedCommand,
                  )
                  .redirectError(ProcessBuilder.Redirect.DISCARD)
                  .start()
          process = running
          continuation.invokeOnCancellation { running.destroyForcibly() }
          if (!running.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            running.destroyForcibly()
            if (continuation.isActive) continuation.resume(null)
          } else {
            val status =
                running.inputStream.bufferedReader(StandardCharsets.UTF_8).use {
                  it.readText().trim()
                }
            if (continuation.isActive) continuation.resume(status)
          }
        } catch (cancelled: CancellationException) {
          process?.destroyForcibly()
          if (continuation.isActive) continuation.cancel(cancelled)
        } catch (_: Throwable) {
          process?.destroyForcibly()
          if (continuation.isActive) continuation.resume(null)
        }
      }
    }
  }

  private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

internal const val MAX_SCHEDULED_TOASTS = 64
private const val PROCESS_TIMEOUT_SECONDS = 20L
private const val MAX_ENCODED_COMMAND_CHARS = 30_000
internal val NOTIFICATION_DESTINATIONS = setOf("todo", "schedule")

internal fun isSupportedNotificationDestination(destination: String): Boolean =
    destination in NOTIFICATION_DESTINATIONS

internal fun isSafeWindowsNotificationLauncher(
    path: String,
    isAbsolute: Boolean = File(path).isAbsolute,
): Boolean = isAbsolute && path.endsWith(".exe", ignoreCase = true) && '"' !in path

internal fun windowsPowerShellExecutable(systemRoot: String? = System.getenv("SystemRoot")): File? {
  if (systemRoot.isNullOrBlank()) return null
  val root = File(systemRoot)
  if (!root.isAbsolute) return null
  return File(root, "System32/WindowsPowerShell/v1.0/powershell.exe").takeIf { it.isFile }
}

internal fun windowsNotificationCapacityStatus(triggerCount: Int): String =
    if (triggerCount > MAX_SCHEDULED_TOASTS) "partial_capacity" else "scheduled"

internal fun windowsNotificationTimes(
    plan: List<CourseReminderPlanItem>,
    nowMillis: Long,
): List<Long> =
    plan
        .asSequence()
        .map { it.triggerAtMillis }
        .filter { it > nowMillis }
        .distinct()
        .sorted()
        .toList()

internal fun windowsNotificationTaskName(appId: String, userSid: String): String {
  val bytes =
      MessageDigest.getInstance("SHA-256")
          .digest("$appId\u0000$userSid".toByteArray(StandardCharsets.UTF_8))
  val suffix = bytes.take(12).joinToString("") { "%02x".format(it) }
  return "BUCC-DailyTodo-$suffix"
}

internal fun encodeWindowsPowerShellCommand(data: String): String? {
  val encodedData = Base64.getEncoder().encodeToString(data.toByteArray(StandardCharsets.UTF_8))
  val source = WINDOWS_NOTIFICATION_SCRIPT.replace("__DATA__", encodedData)
  val encodedCommand =
      Base64.getEncoder().encodeToString(source.toByteArray(StandardCharsets.UTF_16LE))
  return encodedCommand.takeIf { it.length <= MAX_ENCODED_COMMAND_CHARS }
}

internal fun buildWindowsNotificationData(
    operation: String,
    launcher: String,
    appId: String,
    scheme: String,
    triggerAtMillis: List<Long> = emptyList(),
    dailyTodo: Boolean = false,
    destination: String = "",
): String =
    buildJsonObject {
          put("operation", operation)
          put("launcher", launcher)
          put("appId", appId)
          put("scheme", scheme)
          put("dailyTodo", dailyTodo)
          put("destination", destination)
          put(
              "triggerAtMillis",
              buildJsonArray { triggerAtMillis.forEach { add(JsonPrimitive(it)) } },
          )
        }
        .toString()

private fun isSafeIdentity(appId: String, scheme: String): Boolean =
    appId.matches(Regex("[A-Za-z0-9._-]{1,100}")) && scheme.matches(Regex("[a-z][a-z0-9+.-]{2,40}"))

private const val WINDOWS_NOTIFICATION_SCRIPT =
    """
${'$'}ProgressPreference = 'SilentlyContinue'
${'$'}ErrorActionPreference = 'Stop'
try {
  ${'$'}raw = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__DATA__'))
  ${'$'}data = ConvertFrom-Json -InputObject ${'$'}raw
  ${'$'}appId = [string]${'$'}data.appId
  ${'$'}scheme = [string]${'$'}data.scheme
  ${'$'}launcher = [string]${'$'}data.launcher
  ${'$'}classes = 'HKCU:\Software\Classes'
  if ([string]${'$'}data.operation -ne 'cancel') {
    ${'$'}appKey = Join-Path ${'$'}classes ('AppUserModelId\' + ${'$'}appId)
    New-Item -Path ${'$'}appKey -Force | Out-Null
    New-ItemProperty -Path ${'$'}appKey -Name DisplayName -Value 'BUCC' -PropertyType String -Force | Out-Null
    ${'$'}protocolKey = Join-Path ${'$'}classes ${'$'}scheme
    New-Item -Path ${'$'}protocolKey -Force | Out-Null
    Set-Item -Path ${'$'}protocolKey -Value 'URL:BUCC notification link' -Force | Out-Null
    New-ItemProperty -Path ${'$'}protocolKey -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
    ${'$'}commandKey = Join-Path ${'$'}protocolKey 'shell\open\command'
    New-Item -Path ${'$'}commandKey -Force | Out-Null
    ${'$'}quotedLauncher = '"' + ${'$'}launcher + '" --bucc-notification-uri "%1"'
    Set-Item -Path ${'$'}commandKey -Value ${'$'}quotedLauncher -Force | Out-Null
  }
  ${'$'}taskHash = [Security.Cryptography.SHA256]::Create()
  ${'$'}sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  ${'$'}hashBytes = ${'$'}taskHash.ComputeHash([Text.Encoding]::UTF8.GetBytes(${'$'}appId + [char]0 + ${'$'}sid))
  ${'$'}suffix = ([BitConverter]::ToString(${'$'}hashBytes[0..11])).Replace('-', '').ToLowerInvariant()
  ${'$'}taskName = 'BUCC-DailyTodo-' + ${'$'}suffix
  [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
  [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
  [Windows.UI.Notifications.ScheduledToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
  [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
  ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}appId)
  function Test-BuccNotificationEnabled {
    ${'$'}settingProperty = ${'$'}notifier.GetType().GetProperty('Setting')
    try {
      return ${'$'}settingProperty.GetValue(${'$'}notifier).ToString() -eq 'Enabled'
    } catch {
      ${'$'}exception = ${'$'}_.Exception
      while (${'$'}null -ne ${'$'}exception.InnerException) { ${'$'}exception = ${'$'}exception.InnerException }
      if (${'$'}exception.HResult -ne -2147023728) { throw }
      ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
      ${'$'}xml.LoadXml('<toast><visual><binding template="ToastGeneric"><text>BUCC 通知已启用</text></binding></visual><audio silent="true"/></toast>')
      ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
      ${'$'}toast.SuppressPopup = ${'$'}true
      ${'$'}toast.Tag = 'bucc-init'
      ${'$'}toast.Group = 'registration'
      try {
        ${'$'}notifier.Show(${'$'}toast)
        for (${'$'}attempt = 0; ${'$'}attempt -lt 5; ${'$'}attempt++) {
          Start-Sleep -Milliseconds 200
          try {
            if (${'$'}settingProperty.GetValue(${'$'}notifier).ToString() -eq 'Enabled') { return ${'$'}true }
          } catch {
            ${'$'}probeException = ${'$'}_.Exception
            while (${'$'}null -ne ${'$'}probeException.InnerException) { ${'$'}probeException = ${'$'}probeException.InnerException }
            if (${'$'}probeException.HResult -ne -2147023728) { throw }
          }
        }
        return ${'$'}false
      } finally {
        ${'$'}notifier.GetType() | Out-Null
        [Windows.UI.Notifications.ToastNotificationManager]::History.Remove('bucc-init', 'registration', ${'$'}appId)
      }
    }
  }
  switch ([string]${'$'}data.operation) {
    'schedule' {
      if (-not (Test-BuccNotificationEnabled)) { 'PERMISSION_REQUIRED'; return }
      foreach (${'$'}old in @(${'$'}notifier.GetScheduledToastNotifications())) { ${'$'}notifier.RemoveFromSchedule(${'$'}old) }
      foreach (${'$'}millis in @(${'$'}data.triggerAtMillis)) {
        ${'$'}when = [DateTimeOffset]::FromUnixTimeMilliseconds([long]${'$'}millis).ToLocalTime()
        if (${'$'}when -le [DateTimeOffset]::Now) { continue }
        ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
        ${'$'}xml.LoadXml('<toast activationType="protocol"><visual><binding template="ToastGeneric"><text>课程即将开始</text></binding></visual></toast>')
        ${'$'}xml.DocumentElement.SetAttribute('launch', (${'$'}scheme + '://open/schedule'))
        ${'$'}toast = [Windows.UI.Notifications.ScheduledToastNotification]::new(${'$'}xml, ${'$'}when)
        ${'$'}notifier.AddToSchedule(${'$'}toast)
      }
      if ([bool]${'$'}data.dailyTodo) {
        ${'$'}action = New-ScheduledTaskAction -Execute ${'$'}launcher -Argument '--bucc-notify-fire todo'
        ${'$'}trigger = New-ScheduledTaskTrigger -Daily -At ([DateTime]::Today.AddHours(9))
        ${'$'}principal = New-ScheduledTaskPrincipal -UserId ${'$'}sid -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName ${'$'}taskName -Action ${'$'}action -Trigger ${'$'}trigger -Principal ${'$'}principal -Force | Out-Null
      } else {
        Unregister-ScheduledTask -TaskName ${'$'}taskName -Confirm:${'$'}false -ErrorAction SilentlyContinue
      }
      'OK'
    }
    'cancel' {
      foreach (${'$'}old in @(${'$'}notifier.GetScheduledToastNotifications())) { ${'$'}notifier.RemoveFromSchedule(${'$'}old) }
      [Windows.UI.Notifications.ToastNotificationManager]::History.Clear(${'$'}appId)
      Unregister-ScheduledTask -TaskName ${'$'}taskName -Confirm:${'$'}false -ErrorAction SilentlyContinue
      'OK'
    }
    'show' {
      if (-not (Test-BuccNotificationEnabled)) { 'PERMISSION_REQUIRED'; return }
      ${'$'}destination = [string]${'$'}data.destination
      if (${'$'}destination -notin @('todo', 'schedule')) { throw 'invalid destination' }
      ${'$'}title = if (${'$'}destination -eq 'schedule') { '课程即将开始' } else { '查看待办' }
      ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
      ${'$'}xml.LoadXml('<toast activationType="protocol"><visual><binding template="ToastGeneric"><text></text></binding></visual></toast>')
      ${'$'}xml.GetElementsByTagName('text').Item(0).InnerText = ${'$'}title
      ${'$'}xml.DocumentElement.SetAttribute('launch', (${'$'}scheme + '://open/' + ${'$'}destination))
      ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
      ${'$'}notifier.Show(${'$'}toast)
      'OK'
    }
    default { throw 'unsupported operation' }
  }
} catch { 'FAILED' }
"""

private val WINDOWS_NOTIFICATION_EXECUTOR =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "bucc-windows-notifications").apply { isDaemon = true }
    }
