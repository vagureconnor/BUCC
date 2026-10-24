package app.bucc.ui

import app.bucc.model.dto.CourseClass
import app.bucc.repository.UpcomingClass
import app.bucc.ui.screens.menu.homeScheduleProgressLabel
import app.bucc.ui.screens.menu.homeScheduleProgressOccurrences
import app.bucc.ui.screens.menu.todayCourseProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class TodayCourseProgressTest {
  private fun occurrence(name: String, date: String, start: String?, end: String?): UpcomingClass {
    val localDate = LocalDate.parse(date)
    val course =
        CourseClass(
            name,
            name,
            null,
            null,
            start,
            end,
            1,
            2,
            null,
            null,
            null,
            null,
            localDate.dayOfWeek.ordinal + 1,
        )
    return UpcomingClass(
        name,
        localDate,
        course,
        start?.let { LocalDateTime.parse("$date" + "T$it") },
        end?.let { LocalDateTime.parse("$date" + "T$it") },
    )
  }

  @Test
  fun `visible today classes keep progress label in sync when dedicated list is empty`() {
    val now = LocalDateTime.parse("2026-10-13T08:30")
    val visible = listOf(occurrence("today", "2026-10-13", "08:00", "09:00"))
    val occurrences = homeScheduleProgressOccurrences(emptyList(), visible, now.date)

    val progress = todayCourseProgress(occurrences, now)
    assertEquals(0.5f, progress?.fraction)
    assertEquals("今日课程进度 50%", homeScheduleProgressLabel(progress, now))
  }

  @Test
  fun `today progress counts all of today's dated courses`() {
    val now = LocalDateTime.parse("2026-10-13T08:30")
    val today =
        listOf(
            occurrence("ended", "2026-10-13", "08:00", "08:30"),
            occurrence("ongoing", "2026-10-13", "08:30", "09:00"),
            occurrence("later", "2026-10-13", "10:00", "11:00"),
        )

    val progress = todayCourseProgress(today, now)
    assertEquals(0.25f, progress?.fraction)
    assertEquals("今日课程进度 25%", homeScheduleProgressLabel(progress, now))
  }

  @Test
  fun `all ended courses remain at one hundred percent`() {
    val now = LocalDateTime.parse("2026-10-13T11:00")
    val progress =
        todayCourseProgress(
            listOf(
                occurrence("morning", "2026-10-13", "08:00", "09:00"),
                occurrence("later", "2026-10-13", "10:00", "11:00"),
            ),
            now,
        )

    assertEquals(1f, progress?.fraction)
    assertEquals("今日课程进度 100%", homeScheduleProgressLabel(progress, now))
  }

  @Test
  fun `unknown end time reports today's class without false no-class status`() {
    val now = LocalDateTime.parse("2026-10-13T12:00")
    val progress =
        todayCourseProgress(listOf(occurrence("unknown", "2026-10-13", "08:00", null)), now)

    assertNull(progress?.fraction)
    assertEquals("今日有课 · 进度未知", homeScheduleProgressLabel(progress, now))
  }

  @Test
  fun `future date classes do not become today's progress`() {
    val now = LocalDateTime.parse("2026-10-13T12:00")
    val progress =
        todayCourseProgress(listOf(occurrence("tomorrow", "2026-10-14", "08:00", "09:00")), now)

    assertNull(progress)
    assertEquals("今日无课", homeScheduleProgressLabel(progress, now, scheduleHasLoaded = true))
  }

  @Test
  fun `missing progress data is not reported as no classes before first load or after failure`() {
    val now = LocalDateTime.parse("2026-10-13T12:00")

    assertEquals("正在读取今日课表", homeScheduleProgressLabel(null, now, scheduleHasLoaded = false))
    assertEquals("正在读取今日课表", homeScheduleProgressLabel(null, now, isLoading = true))
    assertEquals("今日课程数据暂不可用", homeScheduleProgressLabel(null, now, error = "offline"))
    assertEquals("今日课程数据暂不可用", homeScheduleProgressLabel(null, now, error = ""))
    assertEquals("今日无课", homeScheduleProgressLabel(null, now, scheduleHasLoaded = true))
  }
}
