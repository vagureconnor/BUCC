package app.bucc.ui.screens.schedule

import app.bucc.model.dto.Week
import app.bucc.model.dto.WeeklySchedule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CalendarCourseEvent(
    val id: String,
    val title: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
)

data class CalendarExportPreview(val events: List<CalendarCourseEvent>, val skipped: Int)

data class CalendarImportResult(val eventCount: Int, val notificationStatus: String)

fun calendarCourseEvents(weeks: List<Pair<Week, WeeklySchedule>>): CalendarExportPreview {
  val events = mutableListOf<CalendarCourseEvent>()
  var skipped = 0
  // The campus uses a fixed UTC+08:00 offset. Avoid region-zone lookup here so
  // calendar export also works in JS/Wasm runtimes with limited TZDB support.
  val zone = TimeZone.of("+08:00")
  weeks.forEach { (week, schedule) ->
    val monday = LocalDate.parse(week.startDate.substringBefore('T').substringBefore(' '))
    schedule.arrangedList.forEach { course ->
      val day = course.dayOfWeek
      fun time(raw: String?): LocalTime? =
          raw?.let {
            runCatching {
                  LocalTime.parse(
                      it.trim().let { value -> if (value.length == 4) "0$value" else value }
                  )
                }
                .getOrNull()
          }
      val start =
          time(
              course.beginTime
                  ?: schedule.sectionTimes.firstOrNull { it.section == course.beginSection }?.start
          )
      val end =
          time(
              course.endTime
                  ?: schedule.sectionTimes.firstOrNull { it.section == course.endSection }?.end
          )
      if (day == null || day !in 1..7 || start == null || end == null || end <= start) {
        skipped++
      } else {
        val date = monday.plus(day - 1, DateTimeUnit.DAY)
        if (date > LocalDate.parse(week.endDate.substringBefore('T').substringBefore(' ')))
            return@forEach
        events +=
            CalendarCourseEvent(
                id =
                    "${week.term}/${week.serialNumber}/${course.courseCode}/${course.courseSerialNo}/$date/$start/$end",
                title = course.courseName,
                location = course.placeName.orEmpty(),
                startMillis = LocalDateTime(date, start).toInstant(zone).toEpochMilliseconds(),
                endMillis = LocalDateTime(date, end).toInstant(zone).toEpochMilliseconds(),
            )
      }
    }
  }
  return CalendarExportPreview(events.distinctBy { it.id }.sortedBy { it.startMillis }, skipped)
}

expect fun calendarImportAvailable(): Boolean

expect suspend fun importCourseCalendar(
    accountId: String,
    termName: String,
    events: List<CalendarCourseEvent>,
    reminderMinutes: Int?,
): Result<CalendarImportResult>

internal fun calendarEventsJson(events: List<CalendarCourseEvent>): String =
    JsonArray(
            events.map { event ->
              buildJsonObject {
                put("id", event.id)
                put("title", event.title)
                put("location", event.location)
                put("startMillis", event.startMillis)
                put("endMillis", event.endMillis)
              }
            }
        )
        .toString()
