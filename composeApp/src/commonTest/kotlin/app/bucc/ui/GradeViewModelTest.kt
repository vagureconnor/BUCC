package app.bucc.ui

import app.bucc.model.dto.Grade
import app.bucc.model.dto.GradeData
import app.bucc.model.dto.Term
import app.bucc.ui.screens.grade.GradeDataSource
import app.bucc.ui.screens.grade.GradeTermsSource
import app.bucc.ui.screens.grade.GradeViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class GradeViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `force refresh keeps existing grades visible and exposes refresh state`() = runTest {
    setMainDispatcher(testScheduler)
    val termsSource = FakeGradeTermsSource(listOf(sampleTerm()))
    val gradeSource =
        FakeGradeDataSource(
            mutableMapOf(
                "2025-2026-1" to
                    mutableListOf(
                        GradeData(
                            termCode = "2025-2026-1",
                            grades = listOf(Grade(courseName = "高等数学", score = "90")),
                        ),
                        GradeData(
                            termCode = "2025-2026-1",
                            grades = listOf(Grade(courseName = "高等数学", score = "95")),
                        ),
                    )
            )
        )
    val viewModel = GradeViewModel(gradeSource = gradeSource, termsSource = termsSource)

    viewModel.ensureLoaded()
    advanceUntilIdle()

    assertEquals("90", viewModel.uiState.value.gradeData?.grades?.singleOrNull()?.score)

    viewModel.ensureLoaded(forceRefresh = true)
    runCurrent()

    assertFalse(viewModel.uiState.value.isLoading)
    assertTrue(viewModel.uiState.value.isRefreshing)
    assertEquals("90", viewModel.uiState.value.gradeData?.grades?.singleOrNull()?.score)

    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isRefreshing)
    assertEquals("95", viewModel.uiState.value.gradeData?.grades?.singleOrNull()?.score)
    assertEquals(listOf(false, true), termsSource.forceRefreshRequests)
  }

  @Test
  fun `loads all term grades concurrently`() = runTest {
    setMainDispatcher(testScheduler)
    val terms =
        listOf(
            sampleTerm("2025-2026-1", selected = true),
            sampleTerm("2024-2025-2"),
            sampleTerm("2024-2025-1"),
        )
    val gradeSource = ConcurrentTrackingGradeDataSource(terms.map { it.itemCode }.toSet())
    val viewModel =
        GradeViewModel(gradeSource = gradeSource, termsSource = FakeGradeTermsSource(terms))

    viewModel.ensureLoaded()
    runCurrent()

    assertEquals(3, gradeSource.maxConcurrentRequests)

    advanceUntilIdle()

    assertEquals(terms.map { it.itemCode }.toSet(), viewModel.uiState.value.termGrades.keys)
    assertFalse(viewModel.uiState.value.isSummaryLoading)
  }

  @Test
  fun `historical grades appear while selected term request is pending`() = runTest {
    setMainDispatcher(testScheduler)
    val current = sampleTerm("2025-2026-1", selected = true)
    val historical = sampleTerm("2024-2025-2", selected = false)
    val pendingCurrentResult = CompletableDeferred<Result<GradeData>>()
    val gradeSource =
        object : GradeDataSource {
          override suspend fun getGrades(termCode: String): Result<GradeData> =
              if (termCode == current.itemCode) {
                pendingCurrentResult.await()
              } else {
                Result.success(sampleGradeData(historical.itemCode, "88"))
              }
        }
    val viewModel = GradeViewModel(gradeSource, FakeGradeTermsSource(listOf(current, historical)))

    viewModel.ensureLoaded()
    runCurrent()

    assertEquals(setOf(historical.itemCode), viewModel.uiState.value.termGrades.keys)
    assertEquals(
        "88",
        viewModel.uiState.value.termGrades[historical.itemCode]?.grades?.single()?.score,
    )
    assertTrue(viewModel.uiState.value.isSummaryLoading)

    pendingCurrentResult.complete(Result.success(sampleGradeData(current.itemCode, "92")))
    advanceUntilIdle()

    assertEquals(
        setOf(current.itemCode, historical.itemCode),
        viewModel.uiState.value.termGrades.keys,
    )
    assertEquals(
        "92",
        viewModel.uiState.value.termGrades[current.itemCode]?.grades?.single()?.score,
    )
    assertEquals(
        "88",
        viewModel.uiState.value.termGrades[historical.itemCode]?.grades?.single()?.score,
    )
    assertFalse(viewModel.uiState.value.isSummaryLoading)
  }

  @Test
  fun `selected term failure does not prevent historical grades from loading`() = runTest {
    setMainDispatcher(testScheduler)
    val current = sampleTerm("2025-2026-1", selected = true)
    val historical = sampleTerm("2024-2025-2", selected = false)
    val gradeSource =
        QueuedGradeDataSource(
            mapOf(
                current.itemCode to listOf(Result.failure(IllegalStateException("当前学期不可用"))),
                historical.itemCode to
                    listOf(Result.success(sampleGradeData(historical.itemCode, "88"))),
            )
        )
    val viewModel = GradeViewModel(gradeSource, FakeGradeTermsSource(listOf(current, historical)))

    viewModel.ensureLoaded()
    advanceUntilIdle()

    assertEquals(current, viewModel.uiState.value.selectedTerm)
    assertEquals(setOf(historical.itemCode), viewModel.uiState.value.termGrades.keys)
    assertEquals(
        "88",
        viewModel.uiState.value.termGrades[historical.itemCode]?.grades?.single()?.score,
    )
    assertTrue(viewModel.uiState.value.error.orEmpty().contains(current.itemName))
    assertFalse(viewModel.uiState.value.isLoading)
    assertFalse(viewModel.uiState.value.isSummaryLoading)
  }

  @Test
  fun `historical failure does not prevent later terms from loading`() = runTest {
    setMainDispatcher(testScheduler)
    val current = sampleTerm("2025-2026-1", selected = true)
    val failedTerm = sampleTerm("2024-2025-2", selected = false)
    val laterTerm = sampleTerm("2024-2025-1", selected = false)
    val gradeSource =
        QueuedGradeDataSource(
            mapOf(
                current.itemCode to listOf(Result.success(sampleGradeData(current.itemCode, "91"))),
                failedTerm.itemCode to listOf(Result.failure(IllegalStateException("历史成绩超时"))),
                laterTerm.itemCode to
                    listOf(Result.success(sampleGradeData(laterTerm.itemCode, "82"))),
            )
        )
    val viewModel =
        GradeViewModel(
            gradeSource,
            FakeGradeTermsSource(listOf(current, failedTerm, laterTerm)),
        )

    viewModel.ensureLoaded()
    advanceUntilIdle()

    assertEquals(
        setOf(current.itemCode, laterTerm.itemCode),
        viewModel.uiState.value.termGrades.keys,
    )
    assertEquals("91", viewModel.uiState.value.gradeData?.grades?.single()?.score)
    assertEquals(
        "82",
        viewModel.uiState.value.termGrades[laterTerm.itemCode]?.grades?.single()?.score,
    )
    assertTrue(viewModel.uiState.value.error.orEmpty().contains(failedTerm.itemName))
    assertTrue(viewModel.uiState.value.error.orEmpty().contains("历史成绩超时"))
    assertFalse(viewModel.uiState.value.isSummaryLoading)
  }

  @Test
  fun `refresh keeps cached grades for terms whose requests fail`() = runTest {
    setMainDispatcher(testScheduler)
    val current = sampleTerm("2025-2026-1", selected = true)
    val historical = sampleTerm("2024-2025-2", selected = false)
    val gradeSource =
        QueuedGradeDataSource(
            mapOf(
                current.itemCode to
                    listOf(
                        Result.success(sampleGradeData(current.itemCode, "90")),
                        Result.failure(IllegalStateException("刷新失败")),
                    ),
                historical.itemCode to
                    listOf(
                        Result.success(sampleGradeData(historical.itemCode, "85")),
                        Result.failure(IllegalStateException("历史刷新失败")),
                    ),
            )
        )
    val viewModel = GradeViewModel(gradeSource, FakeGradeTermsSource(listOf(current, historical)))

    viewModel.ensureLoaded()
    advanceUntilIdle()
    viewModel.ensureLoaded(forceRefresh = true)
    advanceUntilIdle()

    assertEquals("90", viewModel.uiState.value.gradeData?.grades?.single()?.score)
    assertEquals(
        "85",
        viewModel.uiState.value.termGrades[historical.itemCode]?.grades?.single()?.score,
    )
    assertEquals(
        setOf(current.itemCode, historical.itemCode),
        viewModel.uiState.value.termGrades.keys,
    )
    assertTrue(viewModel.uiState.value.error.orEmpty().contains("刷新失败"))
    assertTrue(viewModel.uiState.value.error.orEmpty().contains("历史刷新失败"))
    assertFalse(viewModel.uiState.value.isRefreshing)
  }

  private fun setMainDispatcher(testScheduler: TestCoroutineScheduler) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  }
}

private class FakeGradeTermsSource(private val terms: List<Term>) : GradeTermsSource {
  val forceRefreshRequests = mutableListOf<Boolean>()

  override suspend fun getTerms(forceRefresh: Boolean): Result<List<Term>> {
    forceRefreshRequests += forceRefresh
    return Result.success(terms)
  }
}

private class FakeGradeDataSource(
    private val gradeDataByTerm: MutableMap<String, MutableList<GradeData>>
) : GradeDataSource {
  override suspend fun getGrades(termCode: String): Result<GradeData> {
    delay(100)
    val queue = gradeDataByTerm.getValue(termCode)
    return Result.success(queue.removeAt(0))
  }
}

private class ConcurrentTrackingGradeDataSource(private val expectedTermCodes: Set<String>) :
    GradeDataSource {
  private var activeRequests = 0
  var maxConcurrentRequests = 0
    private set

  override suspend fun getGrades(termCode: String): Result<GradeData> {
    require(termCode in expectedTermCodes)
    activeRequests += 1
    maxConcurrentRequests = maxOf(maxConcurrentRequests, activeRequests)
    delay(100)
    activeRequests -= 1
    return Result.success(GradeData(termCode = termCode, grades = emptyList()))
  }
}

private class QueuedGradeDataSource(
    private val gradeDataByTerm: Map<String, List<Result<GradeData>>>,
) : GradeDataSource {
  private val requestIndexes = mutableMapOf<String, Int>()

  override suspend fun getGrades(termCode: String): Result<GradeData> {
    val responses = gradeDataByTerm.getValue(termCode)
    val index = requestIndexes[termCode] ?: 0
    requestIndexes[termCode] = index + 1
    return responses[index]
  }
}

private fun sampleGradeData(termCode: String, score: String) =
    GradeData(termCode = termCode, grades = listOf(Grade(courseName = "课程", score = score)))

private fun sampleTerm(
    itemCode: String = "2025-2026-1",
    selected: Boolean = true,
): Term =
    Term(
        itemCode = itemCode,
        itemName = itemCode,
        selected = selected,
        itemIndex = 0,
    )
