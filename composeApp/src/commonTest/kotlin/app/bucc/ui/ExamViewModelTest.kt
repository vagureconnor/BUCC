package app.bucc.ui

import app.bucc.api.feature.ScheduleApi
import app.bucc.api.feature.ScheduleApiBackend
import app.bucc.model.dto.*
import app.bucc.repository.TermRepository
import app.bucc.ui.screens.exam.ExamViewModel
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModelTest {
  @AfterTest
  fun clean() {
    Dispatchers.resetMain()
  }

  @Test
  fun refreshClearsOldResultsUntilTheLatestExamDataArrives() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val refreshedData = CompletableDeferred<Result<ExamArrangementData>>()
    var examRequests = 0
    val backend =
        object : ScheduleApiBackend {
          override suspend fun getTerms() =
              Result.success(listOf(Term("2026-1", "2026-2027学年第一学期", true, 1)))

          override suspend fun getWeeks(termCode: String) = Result.success(emptyList<Week>())

          override suspend fun getWeeklySchedule(termCode: String, week: Int) =
              Result.success(WeeklySchedule(emptyList(), "", ""))

          override suspend fun getTodaySchedule() = Result.success(emptyList<TodayClass>())

          override suspend fun getExamArrangement(termCode: String): Result<ExamArrangementData> {
            examRequests++
            return if (examRequests == 1) Result.success(ExamArrangementData())
            else refreshedData.await()
          }
        }
    val api = ScheduleApi { backend }
    val viewModel = ExamViewModel(api, TermRepository(api))

    viewModel.ensureLoaded()
    advanceUntilIdle()
    assertNotNull(viewModel.uiState.value.examData)

    viewModel.ensureLoaded(forceRefresh = true)
    runCurrent()
    assertTrue(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.examData)

    refreshedData.complete(
        Result.success(ExamArrangementData(arranged = listOf(Exam(courseName = "新结果"))))
    )
    advanceUntilIdle()
    assertEquals("新结果", viewModel.uiState.value.examData?.arranged?.single()?.courseName)
  }
}
