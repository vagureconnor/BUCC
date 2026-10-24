package app.bucc.ui

import app.bucc.attendance.AttendanceApi
import app.bucc.attendance.AttendanceCourse
import app.bucc.attendance.AttendanceRecord
import app.bucc.attendance.AttendanceSemester
import app.bucc.model.dto.Term
import app.bucc.ui.screens.attendance.AttendanceViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceViewModelTest {
  private val term1 = Term("2025-2026-1", "2025-2026 学年第一学期", true, 1)
  private val term2 = Term("2025-2026-2", "2025-2026 学年第二学期", false, 2)
  private val courses =
      listOf(
          AttendanceCourse("c-1", "大学物理实验报告"),
          AttendanceCourse("c-2", "大学物理实验报告"),
          AttendanceCourse("c-3", "高等数学"),
      )
  private val records =
      listOf(
          AttendanceRecord("r-1", "c-1", "2025-09-01", "08:00", "已签到"),
          AttendanceRecord("r-2", "c-2", "2025-09-01", "10:00", "缺勤"),
          AttendanceRecord("r-3", "c-3", "2025-09-03", "14:00", "迟到"),
      )

  @AfterTest
  fun resetDispatcher() {
    Dispatchers.resetMain()
  }

  private class FakeApi : AttendanceApi {
    var termsResult: Result<List<Term>> = Result.success(emptyList())
    val semesters = mutableMapOf<String, Result<AttendanceSemester>>()
    val requestedTerms = mutableListOf<String>()

    override suspend fun getTerms() = termsResult

    override suspend fun getSemester(termCode: String): Result<AttendanceSemester> {
      requestedTerms += termCode
      return semesters[termCode] ?: Result.success(AttendanceSemester(emptyList(), emptyList()))
    }
  }

  @Test
  fun `initial load selects current term and keeps same-named courses distinct`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api =
        FakeApi().apply {
          termsResult = Result.success(listOf(term2, term1))
          semesters[term1.itemCode] = Result.success(AttendanceSemester(courses, records))
        }
    val viewModel = AttendanceViewModel(api)

    viewModel.ensureLoaded()
    advanceUntilIdle()

    assertEquals(term1.itemCode, viewModel.state.value.selectedTerm?.itemCode)
    assertEquals(listOf("c-1", "c-2", "c-3"), viewModel.state.value.courses.map { it.id })
    assertEquals(3, viewModel.state.value.records.size)
    assertEquals(listOf(term1.itemCode), api.requestedTerms)
  }

  @Test
  fun `term switch clears old semester data before loading new one`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api =
        FakeApi().apply {
          termsResult = Result.success(listOf(term1, term2))
          semesters[term1.itemCode] = Result.success(AttendanceSemester(courses, records))
          semesters[term2.itemCode] =
              Result.success(AttendanceSemester(listOf(courses.last()), emptyList()))
        }
    val viewModel = AttendanceViewModel(api)
    viewModel.ensureLoaded()
    advanceUntilIdle()
    viewModel.selectCourse("c-1")

    viewModel.selectTerm(term2)
    assertTrue(viewModel.state.value.courses.isEmpty())
    assertTrue(viewModel.state.value.records.isEmpty())
    assertNull(viewModel.state.value.selectedCourseId)
    advanceUntilIdle()

    assertEquals(listOf(term2.itemCode), api.requestedTerms.drop(1))
    assertEquals(listOf("c-3"), viewModel.state.value.courses.map { it.id })
    assertTrue(viewModel.state.value.records.isEmpty())
  }

  @Test
  fun `semester request failure leaves no prior term records visible`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api =
        FakeApi().apply {
          termsResult = Result.success(listOf(term1, term2))
          semesters[term1.itemCode] = Result.success(AttendanceSemester(courses, records))
          semesters[term2.itemCode] = Result.failure(IllegalStateException("网络不可用"))
        }
    val viewModel = AttendanceViewModel(api)
    viewModel.ensureLoaded()
    advanceUntilIdle()

    viewModel.selectTerm(term2)
    advanceUntilIdle()

    assertEquals(term2.itemCode, viewModel.state.value.selectedTerm?.itemCode)
    assertTrue(viewModel.state.value.records.isEmpty())
    assertTrue(viewModel.state.value.courses.isEmpty())
    assertEquals("网络不可用", viewModel.state.value.error)
    assertFalse(viewModel.state.value.isLoadingSemester)
  }

  @Test
  fun `late old term response cannot replace the newly selected term`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val oldResponse = CompletableDeferred<AttendanceSemester>()
    val api =
        object : AttendanceApi {
          override suspend fun getTerms() = Result.success(listOf(term1, term2))

          override suspend fun getSemester(termCode: String): Result<AttendanceSemester> =
              if (termCode == term1.itemCode)
                  withContext(NonCancellable) { Result.success(oldResponse.await()) }
              else Result.success(AttendanceSemester(listOf(courses.last()), emptyList()))
        }
    val viewModel = AttendanceViewModel(api)
    viewModel.ensureLoaded()
    advanceUntilIdle()
    viewModel.selectTerm(term2)
    advanceUntilIdle()
    oldResponse.complete(AttendanceSemester(courses, records))
    advanceUntilIdle()
    assertEquals(term2.itemCode, viewModel.state.value.selectedTerm?.itemCode)
    assertEquals(listOf("c-3"), viewModel.state.value.courses.map { it.id })
    assertTrue(viewModel.state.value.records.isEmpty())
  }

  @Test
  fun `course filter uses stable course id rather than title`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api =
        FakeApi().apply {
          termsResult = Result.success(listOf(term1))
          semesters[term1.itemCode] = Result.success(AttendanceSemester(courses, records))
        }
    val viewModel = AttendanceViewModel(api)
    viewModel.ensureLoaded()
    advanceUntilIdle()

    viewModel.selectCourse("c-2")

    assertEquals("c-2", viewModel.state.value.selectedCourseId)
    assertEquals(
        "缺勤",
        viewModel.state.value.records
            .single { it.courseId == viewModel.state.value.selectedCourseId }
            .status,
    )
    viewModel.selectCourse("missing")
    assertEquals("c-2", viewModel.state.value.selectedCourseId)
    viewModel.selectCourse(null)
    assertNull(viewModel.state.value.selectedCourseId)
  }
}
