package app.bucc.ui.screens.schedule

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

interface CalendarImportCallback {
  fun onSuccess(count: Int, notificationStatus: String)

  fun onFailure(message: String)
}

interface CalendarNativePlatform {
  fun importEvents(
      accountId: String,
      termName: String,
      eventsJson: String,
      reminderMinutes: Int,
      callback: CalendarImportCallback,
  )
}

object CalendarBridgeInstaller {
  internal var platform: CalendarNativePlatform? = null

  fun install(platform: CalendarNativePlatform) {
    this.platform = platform
  }
}

actual fun calendarImportAvailable(): Boolean = CalendarBridgeInstaller.platform != null

actual suspend fun importCourseCalendar(
    accountId: String,
    termName: String,
    events: List<CalendarCourseEvent>,
    reminderMinutes: Int?,
): Result<CalendarImportResult> = suspendCancellableCoroutine { continuation ->
  val platform = CalendarBridgeInstaller.platform
  if (platform == null) {
    continuation.resume(Result.failure(IllegalStateException("日历服务未接入")))
  } else {
    platform.importEvents(
        accountId,
        termName,
        calendarEventsJson(events),
        reminderMinutes ?: -1,
        object : CalendarImportCallback {
          override fun onSuccess(count: Int, notificationStatus: String) {
            if (continuation.isActive) {
              continuation.resume(Result.success(CalendarImportResult(count, notificationStatus)))
            }
          }

          override fun onFailure(message: String) {
            if (continuation.isActive)
                continuation.resume(Result.failure(IllegalStateException(message)))
          }
        },
    )
  }
}
