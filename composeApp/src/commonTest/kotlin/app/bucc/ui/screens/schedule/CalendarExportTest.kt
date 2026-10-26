package app.bucc.ui.screens.schedule

import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.SectionTime
import app.bucc.model.dto.Week
import app.bucc.model.dto.WeeklySchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class CalendarExportTest {
  @Test
  fun bridgeJsonPreservesUnicodeQuotesAndLongTimestampsWithoutGeneratedSerializer() {
    val event =
        CalendarCourseEvent("term/course", "课程\"名称", "教室\n二层", 1791859200000L, 1791862800000L)
    val encoded =
        Json.parseToJsonElement(calendarEventsJson(listOf(event))).jsonArray.single().jsonObject
    assertEquals(event.title, encoded["title"]!!.jsonPrimitive.content)
    assertEquals(event.location, encoded["location"]!!.jsonPrimitive.content)
    assertEquals(event.startMillis, encoded["startMillis"]!!.jsonPrimitive.long)
    assertEquals(event.endMillis, encoded["endMillis"]!!.jsonPrimitive.long)
  }

  @Test
  fun usesMondayAndAsiaShanghaiForEventDateAndEpoch() {
    val preview =
        calendarCourseEvents(
            listOf(
                week() to
                    schedule(
                        course(
                            name = "测试课程",
                            day = 3,
                            start = "09:00",
                            end = "10:30",
                            location = "虚构教室A",
                        ),
                    ),
            ),
        )

    assertEquals(0, preview.skipped)
    assertEquals(1, preview.events.size)
    val event = preview.events.single()
    assertEquals("测试课程", event.title)
    assertEquals("虚构教室A", event.location)
    // Wednesday, 2026-04-15 at 09:00 Asia/Shanghai is 01:00 UTC.
    assertEquals(1_776_214_800_000L, event.startMillis)
    assertEquals(1_776_220_200_000L, event.endMillis)
  }

  @Test
  fun retainsOverlappingCoursesAndCoursesTouchingAtBoundary() {
    val preview =
        calendarCourseEvents(
            listOf(
                week() to
                    schedule(
                        course("earlier", day = 1, start = "08:00", end = "10:00"),
                        course("later", day = 1, start = "09:00", end = "11:00"),
                        course("touching", day = 1, start = "11:00", end = "12:00"),
                    ),
            ),
        )

    assertEquals(0, preview.skipped)
    assertEquals(listOf("earlier", "later", "touching"), preview.events.map { it.title })
  }

  @Test
  fun usesSectionTimesWhenCourseTimesAreMissing() {
    val preview =
        calendarCourseEvents(
            listOf(
                week() to
                    WeeklySchedule(
                        arrangedList =
                            listOf(
                                course(
                                    name = "作息表课程",
                                    day = 1,
                                    start = null,
                                    end = null,
                                    beginSection = 1,
                                    endSection = 2,
                                ),
                            ),
                        code = "fixture",
                        name = "fixture",
                        sectionTimes =
                            listOf(
                                SectionTime(section = 1, start = "08:00", end = "08:45"),
                                SectionTime(section = 2, start = "08:50", end = "09:35"),
                            ),
                    ),
            ),
        )

    assertEquals(0, preview.skipped)
    assertEquals(1, preview.events.size)
    val event = preview.events.single()
    // Monday, 2026-04-13 08:00–09:35 Asia/Shanghai.
    assertEquals(1_776_038_400_000L, event.startMillis)
    assertEquals(1_776_044_100_000L, event.endMillis)
  }

  @Test
  fun skipsMissingInvalidAndNonPositiveRangesAndDeduplicatesSameCourse() {
    val repeated = course("valid", day = 2, start = "10:00", end = "11:00")
    val preview =
        calendarCourseEvents(
            listOf(
                week() to
                    schedule(
                        course("missing", day = 2, start = null, end = "10:00"),
                        course("invalid", day = 2, start = "bad-time", end = "11:00"),
                        course("reversed", day = 2, start = "12:00", end = "11:00"),
                        repeated,
                        repeated,
                    ),
            ),
        )

    assertEquals(3, preview.skipped)
    assertEquals(listOf("valid"), preview.events.map { it.title })
  }

  @Test
  fun reschedulingKeepsOccurrenceIdentityAndDeletingCourseRemovesItFromSnapshot() {
    val before =
        calendarCourseEvents(listOf(week() to schedule(course("course", 1, "08:00", "09:00"))))
    val after =
        calendarCourseEvents(listOf(week() to schedule(course("course", 3, "10:00", "11:00"))))
    assertEquals(before.events.single().id, after.events.single().id)
    assertEquals(emptyList(), calendarCourseEvents(listOf(week() to schedule())).events)
  }

  private fun week() = Week("2026-04-13", "2026-04-19", "fixture-term", false, 1, "第1周")

  private fun schedule(vararg courses: CourseClass) =
      WeeklySchedule(arrangedList = courses.toList(), code = "fixture", name = "fixture")

  private fun course(
      name: String,
      day: Int,
      start: String?,
      end: String?,
      location: String? = null,
      beginSection: Int? = null,
      endSection: Int? = null,
  ) =
      CourseClass(
          courseCode = name,
          courseName = name,
          courseSerialNo = "fixture",
          credit = null,
          beginTime = start,
          endTime = end,
          beginSection = beginSection,
          endSection = endSection,
          placeName = location,
          weeksAndTeachers = null,
          teachingTarget = null,
          color = null,
          dayOfWeek = day,
      )
}
