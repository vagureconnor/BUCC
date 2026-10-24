@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.bucc.notifications

import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter

interface BuccNotificationPlatform {
  fun schedule(
      coursePlan: List<CourseReminderPlanItem>,
      dailyTodoEnabled: Boolean,
      completion: (String) -> Unit,
  )

  fun cancel(completion: () -> Unit)
}

object NotificationSchedulerBridge {
  private var platform: BuccNotificationPlatform? = null

  fun install(platform: BuccNotificationPlatform) {
    this.platform = platform
  }

  fun schedule(
      coursePlan: List<CourseReminderPlanItem>,
      dailyTodoEnabled: Boolean,
      completion: (String) -> Unit,
  ) {
    platform?.schedule(coursePlan, dailyTodoEnabled, completion) ?: completion("unavailable")
  }

  fun cancel(completion: () -> Unit) {
    platform?.cancel(completion) ?: completion()
  }
}

actual suspend fun scheduleBuccNotifications(
    events: List<NotificationEvent>,
    courseEnabled: Boolean,
    dailyTodoEnabled: Boolean,
): String {
  val plan =
      if (courseEnabled) buildCourseReminderPlan(events, currentTimeMillis()) else emptyList()
  return withTimeout(15_000) {
    suspendCancellableCoroutine { continuation ->
      NotificationSchedulerBridge.schedule(plan, dailyTodoEnabled) { status ->
        if (continuation.isActive) continuation.resume(status)
      }
    }
  }
}

actual suspend fun cancelBuccNotifications() {
  withTimeout(15_000) {
    suspendCancellableCoroutine { continuation ->
      NotificationSchedulerBridge.cancel { if (continuation.isActive) continuation.resume(Unit) }
    }
  }
}

actual suspend fun buccNotificationPermissionState(): NotificationPermissionState =
    suspendCancellableCoroutine { continuation ->
      UNUserNotificationCenter.currentNotificationCenter()
          .getNotificationSettingsWithCompletionHandler { settings ->
            if (!continuation.isActive) return@getNotificationSettingsWithCompletionHandler
            continuation.resume(
                when (settings?.authorizationStatus) {
                  UNAuthorizationStatusAuthorized,
                  UNAuthorizationStatusProvisional -> NotificationPermissionState.GRANTED
                  UNAuthorizationStatusNotDetermined -> NotificationPermissionState.NOT_DETERMINED
                  UNAuthorizationStatusDenied -> NotificationPermissionState.DENIED
                  else -> NotificationPermissionState.UNSUPPORTED
                }
            )
          }
    }

actual suspend fun requestBuccNotificationPermission(): NotificationPermissionState =
    suspendCancellableCoroutine { continuation ->
      UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
          UNAuthorizationOptionAlert or UNAuthorizationOptionSound
      ) { granted, error ->
        if (!continuation.isActive) return@requestAuthorizationWithOptions
        continuation.resume(
            if (granted && error == null) NotificationPermissionState.GRANTED
            else NotificationPermissionState.DENIED
        )
      }
    }

actual fun openBuccNotificationSettings() {
  runCatching {
    NSURL.URLWithString("app-settings:")?.let { UIApplication.sharedApplication.openURL(it) }
  }
}

private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
