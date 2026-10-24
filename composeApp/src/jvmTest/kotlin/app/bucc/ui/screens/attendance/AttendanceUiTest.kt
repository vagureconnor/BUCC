package app.bucc.ui.screens.attendance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.bucc.attendance.AttendanceApi
import app.bucc.attendance.AttendanceCourse
import app.bucc.attendance.AttendanceRecord
import app.bucc.attendance.AttendanceSemester
import app.bucc.model.dto.Term
import app.bucc.ui.saveRenderedEvidence
import app.bucc.ui.theme.BUCCTheme
import app.bucc.ui.theme.ThemeMode
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class AttendanceUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `selected date survives refresh and week boundaries are inclusive`() {
    val viewModel = AttendanceViewModel(AttendanceDateFixtureApi())
    compose.setContent {
      BUCCTheme(ThemeMode.LIGHT) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Surface(Modifier.width(390.dp).fillMaxHeight()) { AttendanceScreen(viewModel) }
        }
      }
    }

    compose.waitUntil(5_000) {
      viewModel.state.value.selectedTerm?.itemCode == "T-2026-FALL" &&
          !viewModel.state.value.isLoadingSemester &&
          viewModel.state.value.records.size == 4
    }
    compose.onNodeWithText("日期：2026-09-21").assertIsDisplayed()

    compose.onNodeWithText("日期：2026-09-21").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag("attendance-date-picker").performTouchInput { click(Offset(84f, 344f)) }
    compose.onNodeWithText("确定").performClick()
    compose.onNodeWithText("日期：2026-09-15").assertIsDisplayed()
    compose.onNodeWithText("缺勤").assertIsDisplayed()

    compose.runOnIdle { viewModel.refresh() }
    compose.waitUntil(5_000) { !viewModel.state.value.isLoadingSemester }
    compose.onNodeWithText("日期：2026-09-15").assertIsDisplayed()

    // Wednesday exercises the inclusive Monday/Sunday bounds and excludes next Monday.
    compose.onNodeWithText("日期：2026-09-15").performClick()
    compose.onNodeWithTag("attendance-date-picker").performTouchInput { click(Offset(132f, 344f)) }
    compose.onNodeWithText("确定").performClick()
    compose.onNodeWithText("周视图").performClick()
    compose.onNodeWithText("2026-09-14 至 2026-09-20（周一至周日）").assertIsDisplayed()
    compose.onNodeWithText("出勤").assertIsDisplayed()
    compose.onNodeWithText("缺勤").assertIsDisplayed()
    compose.onNodeWithText("迟到").assertIsDisplayed()
    compose.onAllNodesWithText("边界外").assertCountEquals(0)

    compose.onNodeWithText("日视图").performClick()
    compose.onNodeWithText("日期：2026-09-16").performClick()
    compose.onNodeWithTag("attendance-date-picker").performTouchInput { click(Offset(132f, 344f)) }
    compose.onNodeWithText("确定").performClick()
    compose.onNodeWithText("该日期暂无出勤记录").assertIsDisplayed()

    compose.onNodeWithText("2026 秋季学期").performClick()
    compose.onNodeWithText("2026 春季学期").performClick()
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedTerm?.itemCode == "T-2026-SPRING" &&
          !viewModel.state.value.isLoadingSemester &&
          viewModel.state.value.records.size == 1
    }
    compose.onNodeWithText("日期：2026-03-26").assertIsDisplayed()
    compose.onNodeWithText("出勤").assertIsDisplayed()
  }

  @Test
  fun `attendance history renders by date and course and supports selecting a course`() {
    val viewModel = AttendanceViewModel(AttendanceFixtureApi())
    compose.setContent {
      BUCCTheme(ThemeMode.LIGHT) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Surface(Modifier.width(390.dp).fillMaxHeight().testTag("attendance-preview")) {
            AttendanceScreen(viewModel)
          }
        }
      }
    }

    compose.waitUntil(5_000) {
      viewModel.state.value.courses.size == 2 && viewModel.state.value.records.size == 3
    }
    compose.onNodeWithText("按日期").assertIsDisplayed()
    compose.onNodeWithText("日期：2026-09-17").assertIsDisplayed()
    compose.onNodeWithText("日视图").assertIsDisplayed()
    compose.onNodeWithText("周视图").assertIsDisplayed()
    compose.onAllNodesWithText("打开 iClass 个人中心").assertCountEquals(0)
    compose.onNodeWithText("未知状态（3）").assertIsDisplayed()
    compose.onAllNodesWithText("出勤").assertCountEquals(0)
    compose.onAllNodesWithText("缺勤").assertCountEquals(0)
    saveRenderedEvidence(
        "attendance-date",
        compose.onNodeWithTag("attendance-preview").captureToImage(),
    )

    compose.onNodeWithText("周视图").performClick()
    compose.onNodeWithText("2026-09-14 至 2026-09-20（周一至周日）").assertIsDisplayed()
    compose.onNodeWithText("出勤").assertIsDisplayed()
    compose.onNodeWithText("缺勤").assertIsDisplayed()
    compose.onNodeWithText("未知状态（3）").assertIsDisplayed()
    saveRenderedEvidence(
        "attendance-week",
        compose.onNodeWithTag("attendance-preview").captureToImage(),
    )
    compose.onNodeWithText("日期：2026-09-17").performClick()
    compose.onNodeWithText("确定").assertIsDisplayed()
    compose.onNodeWithText("取消").performClick()

    compose.onNodeWithText("按课程").performClick()
    compose.onAllNodesWithText("实验力学").assertCountEquals(1)
    compose.onAllNodesWithText("高等数学").assertCountEquals(1)
    compose.onNodeWithText("暂无出勤记录").assertIsDisplayed()
    saveRenderedEvidence(
        "attendance-course",
        compose.onNodeWithTag("attendance-preview").captureToImage(),
    )

    compose.onNodeWithText("全部课程").performClick()
    compose.onAllNodesWithText("高等数学").onLast().performClick()
    compose.runOnIdle { assertEquals("course-2", viewModel.state.value.selectedCourseId) }
    compose.onAllNodesWithText("高等数学").assertCountEquals(1)
    compose.onNodeWithText("该课程暂无出勤记录").assertIsDisplayed()

    compose.onNodeWithText("按日期").performClick()
    compose.onAllNodesWithText("课程筛选").assertCountEquals(0)
    compose.onNodeWithText("未知状态（3）").assertIsDisplayed()

    compose.onNodeWithText("按课程").performClick()
    compose.onNodeWithText("课程筛选").assertIsDisplayed()
    compose.onNodeWithText("高等数学").assertIsDisplayed()
    compose.onNodeWithText("该课程暂无出勤记录").assertIsDisplayed()
  }
}

private class AttendanceFixtureApi : AttendanceApi {
  private val term = Term("T-2026", "2026 秋季学期", selected = true, itemIndex = 0)
  private val courses =
      listOf(
          AttendanceCourse("course-1", "实验力学"),
          AttendanceCourse("course-2", "高等数学"),
      )

  override suspend fun getTerms(): Result<List<Term>> = Result.success(listOf(term))

  override suspend fun getSemester(termCode: String): Result<AttendanceSemester> =
      Result.success(
          AttendanceSemester(
              courses = courses,
              records =
                  listOf(
                      AttendanceRecord("r-1", "course-1", "2026-09-14", "08:00–09:00", "出勤"),
                      AttendanceRecord("r-2", "course-1", "2026-09-15", "08:00–09:00", "缺勤"),
                      AttendanceRecord("r-3", "course-1", "2026-09-17", "08:00–09:00", "未知状态（3）"),
                  ),
          )
      )
}

private class AttendanceDateFixtureApi : AttendanceApi {
  private val fall = Term("T-2026-FALL", "2026 秋季学期", selected = true, itemIndex = 0)
  private val spring = Term("T-2026-SPRING", "2026 春季学期", selected = false, itemIndex = 1)
  private val course = AttendanceCourse("course-1", "离散数学")

  override suspend fun getTerms(): Result<List<Term>> = Result.success(listOf(fall, spring))

  override suspend fun getSemester(termCode: String): Result<AttendanceSemester> =
      Result.success(
          AttendanceSemester(
              courses = listOf(course),
              records =
                  if (termCode == fall.itemCode) {
                    listOf(
                        AttendanceRecord("monday", course.id, "2026-09-14", "08:00–09:00", "出勤"),
                        AttendanceRecord("tuesday", course.id, "2026-09-15", "08:00–09:00", "缺勤"),
                        AttendanceRecord("sunday", course.id, "2026-09-20", "08:00–09:00", "迟到"),
                        AttendanceRecord(
                            "next-monday",
                            course.id,
                            "2026-09-21",
                            "08:00–09:00",
                            "边界外",
                        ),
                    )
                  } else {
                    listOf(
                        AttendanceRecord(
                            "spring-latest",
                            course.id,
                            "2026-03-26",
                            "08:00–09:00",
                            "出勤",
                        )
                    )
                  },
          )
      )
}
