package app.bucc.ui.screens.grade

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.bucc.gpa.GpaCollegeProfile
import app.bucc.gpa.GpaCollegeSettings
import app.bucc.gpa.GpaCollegeStore
import app.bucc.gpa.cumulativeGpa
import app.bucc.gpa.gpaCourses
import app.bucc.model.dto.Grade
import app.bucc.model.dto.GradeData
import app.bucc.model.dto.Term
import app.bucc.repository.ScheduleStore
import java.io.File
import kotlin.test.assertEquals
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GpaDashboardTest {
  @get:Rule val compose = createComposeRule()

  @Before
  fun useFakeAccount() {
    ScheduleStore.useAccount("test-gpa-dashboard-${System.nanoTime()}")
  }

  @After
  fun clearFakeAccount() {
    ScheduleStore.forgetAccount()
  }

  @Test
  fun dashboardFiltersTermsAndOpensCourseSelection() {
    val current = Term("2025-2026-1", "当前学期", true, 0)
    val historical = Term("2024-2025-2", "历史学期", false, 1)
    val olderHistorical = Term("2024-2025-1", "更早学期", false, 2)
    val viewModel =
        GradeViewModel(
            gradeSource =
                FakeDashboardGradeSource(
                    mapOf(
                        current.itemCode to GradeData(current.itemCode),
                        historical.itemCode to
                            GradeData(
                                historical.itemCode,
                                listOf(
                                    Grade(
                                        courseName = "程序设计基础",
                                        courseCode = "B370012005",
                                        termCode = historical.itemCode,
                                        termName = historical.itemName,
                                        credit = 2.0,
                                        score = "91",
                                    ),
                                    Grade(
                                        courseName = "虚构通识课程",
                                        courseCode = "TEST-GENERAL-001",
                                        termCode = historical.itemCode,
                                        termName = historical.itemName,
                                        credit = 1.0,
                                        score = "87",
                                    ),
                                ),
                            ),
                        olderHistorical.itemCode to
                            GradeData(
                                olderHistorical.itemCode,
                                listOf(
                                    Grade(
                                        courseName = "虚构离散数学",
                                        courseCode = "TEST-MATH-002",
                                        termCode = olderHistorical.itemCode,
                                        termName = olderHistorical.itemName,
                                        credit = 3.0,
                                        score = "84",
                                    ),
                                ),
                            ),
                    ),
                ),
            termsSource = FakeDashboardTermsSource(listOf(current, historical, olderHistorical)),
        )
    viewModel.ensureLoaded()

    compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.termGrades.size == 3 }
    compose.setContent { MaterialTheme { GpaDashboard(viewModel) } }

    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("累计成绩趋势"))
    val trendRows = viewModel.uiState.value.termGrades.values.flatMap { it.grades }
    assertEquals(2, cumulativeGpa(gpaCourses(trendRows)).size)
    captureScreenshotIfRequested()

    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("程序设计基础"))
    compose.onNodeWithText("程序设计基础").assertIsDisplayed()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("虚构通识课程"))
    compose.onNodeWithText("虚构通识课程").assertIsDisplayed()

    compose.onNodeWithText("全部学期").performClick()
    compose.onNodeWithText("当前学期").performClick()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("课程成绩 · 0 门"))
    compose.onNodeWithText("课程成绩 · 0 门").assertIsDisplayed()
    compose.onNodeWithText("所选范围暂无已发布成绩，可切换学期查看。").assertIsDisplayed()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("当前学期"))
    compose.onNodeWithText("当前学期").performClick()
    compose.onNodeWithText("全部学期").performClick()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("虚构通识课程"))
    compose.onNodeWithText("虚构通识课程").assertIsDisplayed()

    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("推免课程"))
    compose.onNodeWithText("推免课程").performClick()
    compose.onNodeWithText("软件学院 · 推免统计").assertIsDisplayed()
    compose.onNodeWithText("基础课程 A 方案").assertIsDisplayed()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("程序设计基础"))
    compose.onNodeWithText("程序设计基础").assertIsDisplayed()
    compose.onAllNodesWithText("虚构通识课程").assertCountEquals(0)

    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("所有课程"))
    compose.onNodeWithText("所有课程").performClick()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("虚构通识课程"))
    compose.onNodeWithText("虚构通识课程").assertIsDisplayed()
    compose.onNodeWithTag("gpa-dashboard").performScrollToNode(hasText("推免课程"))
    compose.onNodeWithText("推免课程").performClick()

    compose.onNodeWithText("选择推免课程").performClick()
    compose.onNodeWithText("软件学院 · 推免课程").assertIsDisplayed()
    compose.onNodeWithText("取消").performClick()
    compose.onAllNodesWithText("软件学院 · 推免课程").assertCountEquals(0)
  }

  @Test
  fun deletingCustomCollegeRequiresConfirmationAndCancelKeepsProfile() {
    val account = checkNotNull(ScheduleStore.account())
    val store = GpaCollegeStore(account)
    store.write(
        GpaCollegeSettings(
            selected = "虚构学院",
            profiles = listOf(GpaCollegeProfile("软件学院"), GpaCollegeProfile("虚构学院")),
        ),
    )
    val viewModel =
        GradeViewModel(
            gradeSource = FakeDashboardGradeSource(emptyMap()),
            termsSource = FakeDashboardTermsSource(emptyList()),
        )
    compose.setContent { MaterialTheme { GpaDashboard(viewModel) } }

    compose.onNodeWithText("推免课程").performClick()
    compose.onNodeWithText("虚构学院").performClick()
    compose.onNodeWithText("删除 虚构学院").performClick()
    compose.onNodeWithText("删除学院方案").assertIsDisplayed()
    compose.onNodeWithText("确定删除“虚构学院”方案吗？此操作只会删除本账号保存的方案。").assertIsDisplayed()
    compose.onNodeWithText("取消").performClick()

    compose.onNodeWithText("虚构学院").assertIsDisplayed()
    assertEquals(listOf("软件学院", "虚构学院"), store.read().profiles.map { it.name })

    compose.onNodeWithText("虚构学院").performClick()
    compose.onNodeWithText("删除 虚构学院").performClick()
    compose.onNodeWithText("删除学院方案").assertIsDisplayed()
    compose.onNodeWithText("删除", substring = false).performClick()

    compose.onNodeWithText("软件学院").assertIsDisplayed()
    assertEquals(listOf("软件学院"), store.read().profiles.map { it.name })
  }

  private fun captureScreenshotIfRequested() {
    val path = System.getProperty("bucc.gpa.screenshot")?.takeIf { it.isNotBlank() } ?: return
    val image = Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap())
    val pngData = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
    File(path).apply {
      parentFile?.mkdirs()
      writeBytes(pngData.bytes)
    }
  }
}

private class FakeDashboardGradeSource(
    private val grades: Map<String, GradeData>,
) : GradeDataSource {
  override suspend fun getGrades(termCode: String): Result<GradeData> =
      Result.success(grades.getValue(termCode))
}

private class FakeDashboardTermsSource(
    private val terms: List<Term>,
) : GradeTermsSource {
  override suspend fun getTerms(forceRefresh: Boolean): Result<List<Term>> = Result.success(terms)
}
