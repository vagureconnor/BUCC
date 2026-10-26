@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.ui.screens.schedule

import androidx.compose.ui.napi.asInt
import androidx.compose.ui.napi.asString
import app.bucc.platform.HarmonyHost
import app.bucc.platform.status
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual fun calendarImportAvailable(): Boolean = false

actual suspend fun importCourseCalendar(
    accountId: String,
    termName: String,
    events: List<CalendarCourseEvent>,
    reminderMinutes: Int?,
): Result<CalendarImportResult> {
  return try {
    require(
        events.size <= 2000 && events.all { it.startMillis > 0 && it.endMillis > it.startMillis }
    ) {
      "课程时间无效，请刷新课表后重试"
    }
    val result =
        HarmonyHost.request(
            "importCourseCalendar",
            buildJsonObject {
              put("accountId", accountId)
              put("termName", termName)
              put("events", Json.parseToJsonElement(calendarEventsJson(events)))
              reminderMinutes?.let { put("reminderMinutes", it) }
            },
        )
    val message = result["notificationStatus"].asString() ?: "日历导入未完成，请检查系统权限后重试"
    if (result.status == "success" || result.status == "partial") {
      val count = result["eventCount"].asInt() ?: 0
      check(count in 0..events.size)
      Result.success(CalendarImportResult(count, message))
    } else Result.failure(IllegalStateException(message))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Throwable) {
    Result.failure(IllegalStateException("日历导入未完成，请检查系统权限后重试"))
  }
}
