package app.bucc.homework

import app.bucc.api.feature.JudgeApi
import app.bucc.api.feature.JudgeApiBackend
import app.bucc.model.dto.JudgeAssignmentDetailDto
import app.bucc.model.dto.JudgeAssignmentDetailKeyDto
import app.bucc.model.dto.JudgeAssignmentDetailsResponse
import app.bucc.model.dto.JudgeAssignmentSummaryDto
import app.bucc.model.dto.JudgeAssignmentsResponse
import app.bucc.model.dto.JudgeSubmissionStatus
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class HomeworkSemesterTest {
  @Test
  fun judgeUsesTeachingCalendarWithDeadlineFallbackOnlyWhenStartIsUnknown() {
    val term = HomeworkSemester("2026-2027-1", LocalDate(2026, 9, 7), LocalDate(2027, 1, 24))
    assertTrue(term.contains(LocalDateTime(2026, 9, 7, 0, 0), null))
    assertTrue(term.contains(LocalDateTime(2027, 1, 24, 23, 59), null))
    assertFalse(term.contains(LocalDateTime(2026, 5, 1, 0, 0), LocalDateTime(2026, 10, 1, 0, 0)))
    assertFalse(term.contains(LocalDateTime(2027, 1, 25, 0, 0), null))
    assertTrue(term.contains(null, LocalDateTime(2026, 10, 1, 0, 0)))
    assertFalse(term.contains(null, null))
  }

  @Test
  fun judgeHomeworkKeepsCurrentSemesterAssignmentsAndDeduplicatesOnlyStableCourseIds() = runTest {
    val summaries =
        listOf(
            judgeSummary("course-a", "101", "物理实验报告"),
            judgeSummary("course-a", "102", "物理实验报告"),
            judgeSummary("course-b", "101", "物理实验报告"),
            judgeSummary("course-a", "101", "重复列表记录"),
            judgeSummary("course-a", "old", "上学期作业", start = "2026-05-01 00:00:00"),
        )
    val backend =
        object : JudgeApiBackend {
          override suspend fun getAssignments(includeExpired: Boolean, userKey: String?) =
              Result.success(JudgeAssignmentsResponse(summaries))

          override suspend fun getAssignmentDetail(
              courseId: String,
              assignmentId: String,
          ): Result<JudgeAssignmentDetailDto> =
              error("List loading must not require a second detail request")

          override suspend fun getAssignmentDetails(
              keys: List<JudgeAssignmentDetailKeyDto>
          ): Result<JudgeAssignmentDetailsResponse> =
              Result.failure(IllegalStateException("synthetic detail endpoint failure"))
        }
    val adapter =
        JudgeHomeworkAdapter(
            account = "fixture-account",
            api = JudgeApi(backend),
            semester = {
              HomeworkSemester("fixture-term", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
            },
        )

    val items = adapter.load()

    assertEquals(
        listOf("judge:course-a:101", "judge:course-a:102", "judge:course-b:101"),
        items.map { it.id },
    )
    assertEquals(listOf("物理实验报告", "物理实验报告", "物理实验报告"), items.map { it.title })
    assertTrue(items.all { it.source == HomeworkSource.JUDGE })
    assertEquals("Judge（希冀）", items.first().source.label)
  }

  @Test
  fun judgeHomeworkRejectsAssignmentsWithMissingDatesInsteadOfReturningAnEmptySemester() = runTest {
    val backend =
        object : JudgeApiBackend {
          override suspend fun getAssignments(includeExpired: Boolean, userKey: String?) =
              Result.success(
                  JudgeAssignmentsResponse(
                      listOf(
                          judgeSummary("course-a", "101", "无日期作业", start = "").copy(dueTime = "")
                      )
                  )
              )

          override suspend fun getAssignmentDetail(
              courseId: String,
              assignmentId: String,
          ): Result<JudgeAssignmentDetailDto> =
              error("List loading must not require a second detail request")

          override suspend fun getAssignmentDetails(
              keys: List<JudgeAssignmentDetailKeyDto>
          ): Result<JudgeAssignmentDetailsResponse> =
              error("List loading must not require a second detail request")
        }
    val adapter =
        JudgeHomeworkAdapter(
            account = "fixture-account",
            api = JudgeApi(backend),
            semester = {
              HomeworkSemester("fixture-term", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
            },
        )

    val failure = assertFailsWith<IllegalStateException> { adapter.load() }

    assertTrue(failure.message.orEmpty().contains("日期"))
    assertTrue(failure.message.orEmpty().contains("101"))
  }

  @Test
  fun judgeMissingDatesReportRefreshErrorAndKeepThePreviousSnapshot() = runTest {
    var response = JudgeAssignmentsResponse(listOf(judgeSummary("course-a", "101", "当前作业")))
    val backend =
        object : JudgeApiBackend {
          override suspend fun getAssignments(includeExpired: Boolean, userKey: String?) =
              Result.success(response)

          override suspend fun getAssignmentDetail(
              courseId: String,
              assignmentId: String,
          ): Result<JudgeAssignmentDetailDto> =
              error("List loading must not require a second detail request")

          override suspend fun getAssignmentDetails(
              keys: List<JudgeAssignmentDetailKeyDto>
          ): Result<JudgeAssignmentDetailsResponse> =
              error("List loading must not require a second detail request")
        }
    val settings = MapSettings()
    val adapter =
        JudgeHomeworkAdapter(
            account = "fixture-account",
            api = JudgeApi(backend),
            semester = {
              HomeworkSemester("fixture-term", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
            },
        )
    val repository = HomeworkRepository("fixture-account", listOf(adapter), settings) { true }

    repository.refresh(HomeworkSource.JUDGE)
    response =
        JudgeAssignmentsResponse(
            listOf(judgeSummary("course-a", "101", "当前作业", start = "").copy(dueTime = ""))
        )
    repository.refresh(HomeworkSource.JUDGE)

    val state = repository.state.value.sources.getValue(HomeworkSource.JUDGE)
    assertEquals(listOf("judge:course-a:101"), state.items.map { it.id })
    assertTrue(state.error.orEmpty().contains("日期"))
    assertEquals(
        state.items,
        HomeworkRepository("fixture-account", listOf(adapter), settings) { true }
            .state
            .value
            .sources
            .getValue(HomeworkSource.JUDGE)
            .items,
    )
  }

  @Test
  fun partialJudgeRefreshRetainsOnlyFailedCourseCacheAndUpdatesHealthyCourses() = runTest {
    var response =
        JudgeAssignmentsResponse(
            listOf(
                judgeSummary("course-a", "101", "旧甲"),
                judgeSummary("course-b", "101", "旧乙"),
            )
        )
    val backend =
        object : JudgeApiBackend {
          override suspend fun getAssignments(includeExpired: Boolean, userKey: String?) =
              Result.success(response)

          override suspend fun getAssignmentDetail(
              courseId: String,
              assignmentId: String,
          ): Result<JudgeAssignmentDetailDto> = error("unused")

          override suspend fun getAssignmentDetails(
              keys: List<JudgeAssignmentDetailKeyDto>
          ): Result<JudgeAssignmentDetailsResponse> = error("unused")
        }
    val adapter =
        JudgeHomeworkAdapter(
            "fixture",
            JudgeApi(backend),
            semester = {
              HomeworkSemester("fixture-term", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
            },
        )
    val settings = MapSettings()
    val repository = HomeworkRepository("fixture", listOf(adapter), settings) { true }
    repository.refresh()
    response =
        JudgeAssignmentsResponse(
            listOf(judgeSummary("course-b", "102", "新乙")),
            failedCourseIds = listOf("course-a"),
        )
    repository.refresh()
    val state = repository.state.value.sources.getValue(HomeworkSource.JUDGE)
    assertEquals(
        setOf("judge:course-a:101", "judge:course-b:102"),
        state.items.map { it.id }.toSet(),
    )
    assertTrue(state.error?.contains("1") == true)
    assertTrue(
        state.items.first { it.courseId == "course-a" }.submissionHint?.contains("上次") == true
    )
    val restored =
        HomeworkRepository("fixture", listOf(adapter), settings) { true }.state.value.items
    assertEquals(state.items, restored)
  }

  private fun judgeSummary(
      courseId: String,
      assignmentId: String,
      title: String,
      start: String = "2026-10-01 00:00:00",
  ) =
      JudgeAssignmentSummaryDto(
          courseId = courseId,
          courseName = "课程 $courseId",
          assignmentId = assignmentId,
          title = title,
          startTime = start,
          dueTime = "2026-10-20 23:59:00",
          totalProblems = 2,
          submittedCount = 1,
          submissionStatus = JudgeSubmissionStatus.PARTIAL,
          submissionStatusText = "部分完成",
      )
}
