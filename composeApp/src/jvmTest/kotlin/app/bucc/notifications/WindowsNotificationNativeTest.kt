package app.bucc.notifications

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue

/**
 * Opt-in Windows integration test; it uses an isolated fixture AUMID and never displays a toast.
 */
class WindowsNotificationNativeTest {
  @Test
  fun `scheduling and cancellation update only the fixture Windows notification state`() =
      runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeTrue(System.getenv("BUCC_WINDOWS_NOTIFICATION_NATIVE_TEST") == "1")
        val launcherValue = System.getenv("BUCC_WINDOWS_TEST_LAUNCHER")
        assumeNotNull(launcherValue)
        val launcher = File(launcherValue!!).canonicalFile
        assumeTrue(launcher.isFile && launcher.name.equals("BUCC.exe", ignoreCase = true))

        val fixtureId = UUID.randomUUID().toString().replace("-", "")
        val appId = "fixture.bucc.$fixtureId"
        val scheme = "bucc-f-$fixtureId"
        val sid =
            invokePowerShell("identity", appId, scheme, "")
                .jsonObject["sid"]
                ?.jsonPrimitive
                ?.content ?: error("Windows identity query did not return a SID")
        val taskName = windowsNotificationTaskName(appId, sid)
        val initial = inspectFixture(appId, scheme, taskName)
        assumeFalse(initial.registryKeysExist)
        assumeFalse(initial.taskPresent)
        assumeTrue(initial.scheduledCount == 0)

        val bridge = WindowsNotificationBridge(launcher, appId, scheme)
        try {
          val result =
              bridge.schedule(
                  plan =
                      listOf(
                          CourseReminderPlanItem(
                              "native-smoke-fixture",
                              System.currentTimeMillis() + FUTURE_TRIGGER_MILLIS,
                          )
                      ),
                  dailyTodo = true,
              )
          assertEquals("scheduled", result, "Native fixture scheduling did not complete")

          val scheduled = inspectFixture(appId, scheme, taskName)
          assertEquals(1, scheduled.scheduledCount, "Expected one scheduled fixture notification")
          assertTrue(scheduled.taskPresent, "Expected the fixture daily task")

          bridge.cancel()
          val cancelled = inspectFixture(appId, scheme, taskName)
          assertEquals(
              0,
              cancelled.scheduledCount,
              "Fixture notifications remained after cancellation",
          )
          assertFalse(cancelled.taskPresent, "Fixture daily task remained after cancellation")
        } finally {
          runCatching { bridge.cancel() }
          runCatching { invokePowerShell("cleanup", appId, scheme, taskName) }
        }
      }

  private fun inspectFixture(appId: String, scheme: String, taskName: String): FixtureState {
    val result = invokePowerShell("inspect", appId, scheme, taskName).jsonObject
    return FixtureState(
        scheduledCount = result.getValue("scheduledCount").jsonPrimitive.content.toInt(),
        taskPresent = result.getValue("taskPresent").jsonPrimitive.content.toBooleanStrict(),
        registryKeysExist =
            result.getValue("appKeyExists").jsonPrimitive.content.toBooleanStrict() ||
                result.getValue("protocolKeyExists").jsonPrimitive.content.toBooleanStrict(),
    )
  }

  private fun invokePowerShell(
      operation: String,
      appId: String,
      scheme: String,
      taskName: String,
  ): kotlinx.serialization.json.JsonElement {
    val payload =
        """{"operation":"$operation","appId":"$appId","scheme":"$scheme","taskName":"$taskName"}"""
    val payloadBase64 =
        Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    val command =
        Base64.getEncoder()
            .encodeToString(
                POWERSHELL_SCRIPT.replace("__PAYLOAD__", payloadBase64)
                    .toByteArray(StandardCharsets.UTF_16LE),
            )
    val process =
        ProcessBuilder(
                windowsPowerShellExecutable()!!.path,
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                command,
            )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    if (!process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("Windows notification fixture query timed out")
    }
    val output =
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText().trim() }
    assertEquals(0, process.exitValue(), "Windows notification fixture query failed")
    return Json.parseToJsonElement(output)
  }

  private data class FixtureState(
      val scheduledCount: Int,
      val taskPresent: Boolean,
      val registryKeysExist: Boolean,
  )

  private companion object {
    const val FUTURE_TRIGGER_MILLIS = 30L * 60L * 1000L
    const val POWERSHELL_TIMEOUT_SECONDS = 30L

    // All variable values arrive as JSON encoded in Base64; no fixture value is executable
    // PowerShell.
    val POWERSHELL_SCRIPT =
        """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}ProgressPreference = 'SilentlyContinue'
        try {
          ${'$'}data = ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__PAYLOAD__')))
          ${'$'}sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
          ${'$'}appKey = Join-Path 'HKCU:\Software\Classes\AppUserModelId' ([string]${'$'}data.appId)
          ${'$'}protocolKey = Join-Path 'HKCU:\Software\Classes' ([string]${'$'}data.scheme)
          ${'$'}taskName = [string]${'$'}data.taskName
          switch ([string]${'$'}data.operation) {
            'identity' {
              @{ sid = ${'$'}sid } | ConvertTo-Json -Compress
            }
            'inspect' {
              [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
              ${'$'}scheduledCount = 0
              if (Test-Path ${'$'}appKey) {
                ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier([string]${'$'}data.appId)
                ${'$'}scheduledCount = @(${'$'}notifier.GetScheduledToastNotifications()).Count
              }
              ${'$'}taskPresent = [bool](Get-ScheduledTask -TaskName ${'$'}taskName -ErrorAction SilentlyContinue)
              @{
                scheduledCount = ${'$'}scheduledCount
                taskPresent = ${'$'}taskPresent
                appKeyExists = [bool](Test-Path ${'$'}appKey)
                protocolKeyExists = [bool](Test-Path ${'$'}protocolKey)
              } | ConvertTo-Json -Compress
            }
            'cleanup' {
              Remove-Item -LiteralPath ${'$'}appKey -Recurse -Force -ErrorAction SilentlyContinue
              Remove-Item -LiteralPath ${'$'}protocolKey -Recurse -Force -ErrorAction SilentlyContinue
              @{ cleaned = ${'$'}true } | ConvertTo-Json -Compress
            }
            default { throw 'unsupported fixture operation' }
          }
        } catch { throw 'fixture query failed' }
        """
            .trimIndent()
  }
}
