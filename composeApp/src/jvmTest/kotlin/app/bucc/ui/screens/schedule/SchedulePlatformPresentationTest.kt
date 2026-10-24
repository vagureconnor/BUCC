package app.bucc.ui.screens.schedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.Term
import app.bucc.model.dto.Week
import app.bucc.model.dto.WeeklySchedule
import app.bucc.repository.UpcomingClass
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

class SchedulePlatformPresentationTest {
  @get:Rule val compose = createComposeRule()

  private val term = Term("20261", "测试学期", true, 1)
  private val otherTerm = Term("20262", "另一学期", false, 2)
  private val week = Week("2026-10-12", "2026-10-18", "20261", true, 1, "第1周")
  private val nextWeek = Week("2026-10-19", "2026-10-25", "20261", false, 2, "第2周")
  private val weeklyCourse = course("周课表课程")
  private val nextDayCourse = course("下一有课日课程")

  @Test
  fun iosUsesFullWeekViewInScheduleScreen() {
    var selectedTerm: Term? = null
    var selectedWeek: Week? = null
    compose.setContent {
      MaterialTheme {
        ScheduleScreen(
            terms = listOf(term, otherTerm),
            weeks = listOf(week, nextWeek),
            weeklySchedule = WeeklySchedule(listOf(weeklyCourse), "20261", "测试学期"),
            weekSchedules =
                mapOf(
                    1 to WeeklySchedule(listOf(weeklyCourse), "20261", "测试学期"),
                    2 to WeeklySchedule(listOf(course("第二周课程")), "20261", "测试学期"),
                ),
            selectedTerm = term,
            selectedWeek = week,
            isLoading = false,
            error = null,
            onTermSelected = { selectedTerm = it },
            onWeekSelected = { selectedWeek = it },
            onNavigateBack = {},
            onCourseClick = {},
            upcomingClasses =
                listOf(
                    UpcomingClass(
                        id = "next-day-course",
                        date = LocalDate(2026, 10, 14),
                        course = nextDayCourse,
                        start = null,
                        end = null,
                    )
                ),
            isMobile = true,
            isIOS = true,
        )
      }
    }

    compose.onNodeWithText("测试学期").assertIsDisplayed()
    compose.onNodeWithText("第1周").assertIsDisplayed()
    compose.onNodeWithText("周课表课程").assertIsDisplayed()
    compose.onAllNodesWithText("下一有课日课程").assertCountEquals(0)
    compose.onNodeWithText("测试学期").performClick()
    compose.onNodeWithText("另一学期").performClick()
    assertEquals(otherTerm, selectedTerm)
    compose.onNodeWithContentDescription("下一周").performClick()
    assertEquals(nextWeek, selectedWeek)
  }

  @Test
  fun androidUsesFullWeekViewInScheduleScreen() {
    var selectedTerm: Term? = null
    var selectedWeek: Week? = null
    compose.setContent {
      MaterialTheme {
        ScheduleScreen(
            terms = listOf(term, otherTerm),
            weeks = listOf(week, nextWeek),
            weeklySchedule = WeeklySchedule(listOf(weeklyCourse), "20261", "测试学期"),
            weekSchedules =
                mapOf(
                    1 to WeeklySchedule(listOf(weeklyCourse), "20261", "测试学期"),
                    2 to WeeklySchedule(listOf(course("第二周课程")), "20261", "测试学期"),
                ),
            selectedTerm = term,
            selectedWeek = week,
            isLoading = false,
            error = null,
            onTermSelected = { selectedTerm = it },
            onWeekSelected = { selectedWeek = it },
            onNavigateBack = {},
            onCourseClick = {},
            upcomingClasses =
                listOf(
                    UpcomingClass(
                        id = "next-day-course",
                        date = LocalDate(2026, 10, 14),
                        course = nextDayCourse,
                        start = null,
                        end = null,
                    )
                ),
            isMobile = true,
            isIOS = false,
        )
      }
    }

    compose.onNodeWithText("测试学期").assertIsDisplayed()
    compose.onNodeWithText("第1周").assertIsDisplayed()
    compose.onNodeWithText("周课表课程").assertIsDisplayed()
    compose.onAllNodesWithText("下一有课日课程").assertCountEquals(0)
    compose.onNodeWithText("测试学期").performClick()
    compose.onNodeWithText("另一学期").performClick()
    assertEquals(otherTerm, selectedTerm)
    compose.onNodeWithContentDescription("下一周").performClick()
    assertEquals(nextWeek, selectedWeek)
  }

  private companion object {
    fun course(name: String) =
        CourseClass(
            courseCode = name,
            courseName = name,
            courseSerialNo = null,
            credit = null,
            beginTime = "08:00",
            endTime = "09:35",
            beginSection = 1,
            endSection = 2,
            placeName = "教室",
            weeksAndTeachers = null,
            teachingTarget = null,
            color = null,
            dayOfWeek = 3,
        )
  }
}
