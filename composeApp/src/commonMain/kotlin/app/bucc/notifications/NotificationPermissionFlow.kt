package app.bucc.notifications

internal enum class NotificationPermissionAction {
  GRANTED,
  SHOW_SETTINGS_BUTTON,
}

internal suspend fun notificationPermissionAction(
    current: NotificationPermissionState,
    request: suspend () -> NotificationPermissionState,
): NotificationPermissionAction {
  val resolved =
      when (current) {
        NotificationPermissionState.GRANTED -> current
        NotificationPermissionState.DENIED -> current
        NotificationPermissionState.NOT_DETERMINED,
        NotificationPermissionState.UNSUPPORTED -> request()
      }
  return when (resolved) {
    NotificationPermissionState.GRANTED -> NotificationPermissionAction.GRANTED
    NotificationPermissionState.DENIED,
    NotificationPermissionState.UNSUPPORTED -> NotificationPermissionAction.SHOW_SETTINGS_BUTTON
    NotificationPermissionState.NOT_DETERMINED -> NotificationPermissionAction.SHOW_SETTINGS_BUTTON
  }
}
