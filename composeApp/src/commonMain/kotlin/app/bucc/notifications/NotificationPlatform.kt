package app.bucc.notifications

enum class NotificationPermissionState {
  GRANTED,
  NOT_DETERMINED,
  DENIED,
  UNSUPPORTED,
}

expect suspend fun scheduleBuccNotifications(
    events: List<NotificationEvent>,
    courseEnabled: Boolean,
    dailyTodoEnabled: Boolean,
): String

expect suspend fun cancelBuccNotifications()

expect suspend fun buccNotificationPermissionState(): NotificationPermissionState

expect suspend fun requestBuccNotificationPermission(): NotificationPermissionState

expect fun openBuccNotificationSettings()
