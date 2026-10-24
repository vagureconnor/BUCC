package app.bucc.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private var notificationContext: Context? = null
private var notificationPermissionLauncher: (() -> Unit)? = null
private var notificationPermissionContinuation:
    kotlinx.coroutines.CancellableContinuation<NotificationPermissionState>? =
    null

fun installNotificationAndroidContext(context: Context) {
  notificationContext = context.applicationContext
}

fun installNotificationPermissionLauncher(launch: () -> Unit) {
  notificationPermissionLauncher = launch
}

fun completeAndroidNotificationPermissionRequest(granted: Boolean) {
  val context = notificationContext
  val allowed = granted && context?.let(::notificationsAllowed) == true
  notificationPermissionContinuation?.let { continuation ->
    if (continuation.isActive)
        continuation.resume(
            if (allowed) NotificationPermissionState.GRANTED else NotificationPermissionState.DENIED
        )
  }
  notificationPermissionContinuation = null
}

fun notificationPermissionSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= 33)
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    else
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

fun exactAlarmPermissionSettingsIntent(context: Context): Intent? =
    if (Build.VERSION.SDK_INT >= 31)
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"),
        )
    else null

actual suspend fun scheduleBuccNotifications(
    events: List<NotificationEvent>,
    courseEnabled: Boolean,
    dailyTodoEnabled: Boolean,
): String {
  val context = notificationContext ?: return "unavailable_context"
  return NotificationAlarmScheduler.schedule(context, events, courseEnabled, dailyTodoEnabled)
}

actual suspend fun cancelBuccNotifications() {
  notificationContext?.let(NotificationAlarmScheduler::cancel)
}

actual suspend fun buccNotificationPermissionState(): NotificationPermissionState {
  val context = notificationContext ?: return NotificationPermissionState.UNSUPPORTED
  if (notificationsAllowed(context)) return NotificationPermissionState.GRANTED
  if (Build.VERSION.SDK_INT < 33) return NotificationPermissionState.DENIED
  val wasRequested =
      context
          .getSharedPreferences("bucc_notifications", Context.MODE_PRIVATE)
          .getBoolean("post_permission_requested", false)
  return if (wasRequested) NotificationPermissionState.DENIED
  else NotificationPermissionState.NOT_DETERMINED
}

actual suspend fun requestBuccNotificationPermission(): NotificationPermissionState {
  val state = buccNotificationPermissionState()
  if (state != NotificationPermissionState.NOT_DETERMINED) return state
  val context = notificationContext ?: return NotificationPermissionState.UNSUPPORTED
  val launch = notificationPermissionLauncher ?: return NotificationPermissionState.UNSUPPORTED
  context
      .getSharedPreferences("bucc_notifications", Context.MODE_PRIVATE)
      .edit()
      .putBoolean("post_permission_requested", true)
      .apply()
  return suspendCancellableCoroutine { continuation ->
    notificationPermissionContinuation = continuation
    continuation.invokeOnCancellation {
      if (notificationPermissionContinuation === continuation)
          notificationPermissionContinuation = null
    }
    launch()
  }
}

actual fun openBuccNotificationSettings() {
  val context = notificationContext ?: return
  val manager = context.getSystemService(NotificationManager::class.java)
  val hasPostPermission =
      Build.VERSION.SDK_INT < 33 ||
          context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
              PackageManager.PERMISSION_GRANTED
  val notificationsAllowed = manager?.areNotificationsEnabled() == true
  val intent =
      when {
        !hasPostPermission || !notificationsAllowed -> notificationPermissionSettingsIntent(context)
        Build.VERSION.SDK_INT >= 31 &&
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != true ->
            exactAlarmPermissionSettingsIntent(context)
        else -> notificationPermissionSettingsIntent(context)
      } ?: return
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun notificationsAllowed(context: Context): Boolean {
  val manager = context.getSystemService(NotificationManager::class.java)
  val hasPostPermission =
      Build.VERSION.SDK_INT < 33 ||
          context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
              PackageManager.PERMISSION_GRANTED
  return hasPostPermission && manager?.areNotificationsEnabled() == true
}

fun showAndroidNewHomeworkNotification() {
  notificationContext?.let(NotificationAlarmScheduler::showNewHomework)
}
