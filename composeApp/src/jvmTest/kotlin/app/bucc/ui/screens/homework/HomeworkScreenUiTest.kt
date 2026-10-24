package app.bucc.ui.screens.homework

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.bucc.homework.HomeworkAdapter
import app.bucc.homework.HomeworkItem
import app.bucc.homework.HomeworkRepository
import app.bucc.homework.HomeworkSource
import app.bucc.homework.HomeworkSubmission
import app.bucc.ui.saveRenderedEvidence
import app.bucc.ui.theme.BUCCTheme
import app.bucc.ui.theme.ThemeMode
import com.russhwolf.settings.MapSettings
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import org.junit.Rule
import org.junit.Test

class HomeworkScreenUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `empty source error is visible instead of the no assignments message and login remains available`() {
    val adapter = FakeHomeworkAdapter(requiresSeparateLogin = false) { error("HTTP 400") }
    val viewModel = createViewModel(adapter)
    try {
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(viewModel) } }
      compose.waitUntil(5_000) {
        viewModel.state.value.sources.getValue(HomeworkSource.PATPAT).error != null
      }

      compose.onNodeWithText("Patpat").performClick()
      assertFalse(viewModel.needsSeparateLogin(HomeworkSource.PATPAT))
      compose.onNodeWithText("Patpat 查询失败：HTTP 400").assertIsDisplayed()
      compose.onAllNodesWithText("暂无符合条件的作业").assertCountEquals(0)
      compose.onNodeWithText("登录/修改 Patpat 账号").assertIsDisplayed()
      compose.onNodeWithText("登录/修改 Patpat 账号").performClick()
      compose.onNodeWithText("登录 Patpat").assertIsDisplayed()
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `completion is toggled from the detail dialog instead of the list`() {
    val adapters =
        HomeworkSource.entries.map { source ->
          object : HomeworkAdapter {
            override val source = source

            override suspend fun load() =
                listOf(
                    HomeworkItem(
                        "${source.name}:synthetic",
                        source,
                        "synthetic",
                        "${source.label} fixture",
                        submission = HomeworkSubmission.UNSUBMITTED,
                        canMarkComplete = true,
                    ),
                )
          }
        }
    val model =
        HomeworkViewModel(
            HomeworkRepository("homework-detail-toggle-ui-test", adapters, MapSettings()) { true }
        )
    try {
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(model) } }
      compose.waitUntil(5_000) { model.state.value.items.size == HomeworkSource.entries.size }
      compose.onNodeWithText("全部").performClick()
      compose.onAllNodesWithContentDescription("标记", substring = true).assertCountEquals(0)
      compose.onAllNodesWithText("标记完成").assertCountEquals(0)

      compose.onNodeWithText("SPOC fixture").performClick()
      compose.waitUntil(5_000) { model.detail.value.item?.title == "SPOC fixture" }
      saveRenderedEvidence("homework-detail-toggle", compose.onNode(isDialog()).captureToImage())
      compose.onNodeWithText("标记完成").performClick()
      assertTrue(model.state.value.items.first { it.title == "SPOC fixture" }.locallyCompleted)
      compose.onNodeWithText("撤销标记完成").performClick()
      assertFalse(model.state.value.items.first { it.title == "SPOC fixture" }.locallyCompleted)
    } finally {
      model.close()
    }
  }

  @Test
  fun `status icons are display only and regrouping follows local completion`() {
    val source = HomeworkSource.SPOC
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.SPOC

          override suspend fun load() =
              listOf(
                  HomeworkItem(
                      "done",
                      source,
                      "done",
                      "Completed overdue",
                      dueAt = LocalDateTime(2020, 1, 1, 0, 0),
                      submission = HomeworkSubmission.SUBMITTED,
                  ),
                  HomeworkItem(
                      "late",
                      source,
                      "late",
                      "Overdue incomplete",
                      dueAt = LocalDateTime(2020, 1, 1, 0, 0),
                      submission = HomeworkSubmission.UNSUBMITTED,
                      canMarkComplete = true,
                  ),
              )
        }
    val model =
        HomeworkViewModel(
            HomeworkRepository("homework-status-icons", listOf(adapter), MapSettings()) { true }
        )
    try {
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(model) } }
      compose.waitUntil(5_000) { model.state.value.items.size == 2 }
      compose.onNodeWithText("全部").performClick()
      compose.onNodeWithContentDescription("作业已完成").assertIsDisplayed()
      compose.onNodeWithContentDescription("作业已过期").assertIsDisplayed()
      compose.onAllNodesWithContentDescription("标记", substring = true).assertCountEquals(0)
      saveRenderedEvidence("homework-right-status-toggle", compose.onRoot().captureToImage())

      // Mark an expired item complete: it moves to the completed group.
      compose.onNodeWithText("Overdue incomplete").performClick()
      compose.waitUntil(5_000) { model.detail.value.item?.id == "late" }
      compose.onNodeWithText("标记完成").performClick()
      assertTrue(model.state.value.items.first { it.id == "late" }.completed)
      compose.onNodeWithText("撤销标记完成").performClick()
      assertFalse(model.state.value.items.first { it.id == "late" }.completed)

      // Unmarking an old item keeps it in the expired group.
      compose
          .onNode(
              SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                  hasText("过期")
          )
          .performClick()
      compose.onNodeWithText("Overdue incomplete").assertIsDisplayed()
      // Marking the same expired item complete moves it out of expired into completed.
      compose.onNodeWithText("Overdue incomplete").performClick()
      compose.waitUntil(5_000) { model.detail.value.item?.id == "late" }
      compose.onNodeWithText("标记完成").performClick()
      compose
          .onNode(
              SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                  hasText("已完成")
          )
          .performClick()
      compose.onNodeWithText("Overdue incomplete").assertIsDisplayed()
    } finally {
      model.close()
    }
  }

  @Test
  fun `unmarking a future completed item returns it to unfinished`() {
    val source = HomeworkSource.SPOC
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.SPOC

          override suspend fun load() =
              listOf(
                  HomeworkItem(
                      "future",
                      source,
                      "future",
                      "Future assignment",
                      dueAt = LocalDateTime(2030, 1, 1, 0, 0),
                      submission = HomeworkSubmission.UNSUBMITTED,
                      canMarkComplete = true,
                  ),
              )
        }
    val model =
        HomeworkViewModel(
            HomeworkRepository("homework-future-unmark", listOf(adapter), MapSettings()) { true }
        )
    try {
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(model) } }
      compose.waitUntil(5_000) { model.state.value.items.size == 1 }
      compose.onNodeWithText("Future assignment").performClick()
      compose.waitUntil(5_000) { model.detail.value.item?.id == "future" }
      compose.onNodeWithText("标记完成").performClick()
      compose.onNodeWithText("撤销标记完成").performClick()
      compose
          .onNode(
              SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                  hasText("未完成")
          )
          .performClick()
      compose.onNodeWithText("Future assignment").assertIsDisplayed()
    } finally {
      model.close()
    }
  }

  @Test
  fun `source refresh error identifies retained prior data`() {
    var calls = 0
    val adapter =
        FakeHomeworkAdapter(requiresSeparateLogin = false) {
          calls++
          if (calls == 1) listOf(patpatItem()) else error("HTTP 400")
        }
    val viewModel = createViewModel(adapter)
    try {
      viewModel.refresh(HomeworkSource.PATPAT)
      compose.waitUntil(5_000) {
        viewModel.state.value.sources.getValue(HomeworkSource.PATPAT).items.isNotEmpty()
      }
      viewModel.refresh(HomeworkSource.PATPAT)
      compose.waitUntil(5_000) {
        viewModel.state.value.sources.getValue(HomeworkSource.PATPAT).error != null
      }
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(viewModel) } }

      compose.onNodeWithText("Patpat").performClick()
      compose.onNodeWithText("Patpat 查询失败，已保留上次成功的 1 条作业：HTTP 400").assertIsDisplayed()
      compose.onNodeWithText("Synthetic Patpat assignment").assertIsDisplayed()
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `experiment source orders by title numbers instead of deadline or text prefix`() {
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.BYXT

          override suspend fun load() =
              listOf(
                  HomeworkItem(
                      "byxt:10",
                      source,
                      "10",
                      "稳态法 · 第10轮",
                      submission = HomeworkSubmission.UNSUBMITTED,
                  ),
                  HomeworkItem(
                      "byxt:2",
                      source,
                      "2",
                      "杨氏模量 · 第2轮",
                      submission = HomeworkSubmission.UNSUBMITTED,
                  ),
              )
        }
    val model = createViewModel(adapter)
    try {
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(model) } }
      compose.waitUntil(5000) {
        model.state.value.sources.getValue(HomeworkSource.BYXT).items.size == 2
      }
      compose.onNodeWithText("实验报告").performClick()
      val second = compose.onNodeWithText("杨氏模量 · 第2轮").fetchSemanticsNode().boundsInRoot.top
      val tenth = compose.onNodeWithText("稳态法 · 第10轮").fetchSemanticsNode().boundsInRoot.top
      assertTrue(second < tenth)
      saveRenderedEvidence("homework-experiment-number-order", compose.onRoot().captureToImage())
    } finally {
      model.close()
    }
  }

  @Test
  fun `Accoding assignments can be locally marked completed`() {
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.ACCODING

          override suspend fun load() =
              listOf(
                  HomeworkItem(
                      "accoding:synthetic",
                      source,
                      "synthetic",
                      "Synthetic Accoding assignment",
                      submission = HomeworkSubmission.UNKNOWN,
                      submissionHint = "提交状态读取失败，请刷新重试",
                      canMarkComplete = true,
                  )
              )
        }
    val model = createViewModel(adapter)
    try {
      compose.setContent { BUCCTheme(ThemeMode.LIGHT) { HomeworkScreen(model) } }
      compose.waitUntil(5000) {
        model.state.value.sources.getValue(HomeworkSource.ACCODING).items.size == 1
      }
      compose.onNodeWithText("Accoding").performClick()
      compose.onNodeWithText("提交状态读取失败，请刷新重试").assertIsDisplayed()
      saveRenderedEvidence("homework-accoding-grade-warning", compose.onRoot().captureToImage())
      compose.onNodeWithText("Synthetic Accoding assignment").performClick()
      compose.onNodeWithText("标记完成").assertIsDisplayed()
    } finally {
      model.close()
    }
  }

  private fun createViewModel(adapter: HomeworkAdapter) =
      HomeworkViewModel(
          HomeworkRepository("homework-screen-ui-test", listOf(adapter), MapSettings()) { true },
      )

  private fun patpatItem() =
      HomeworkItem(
          id = "patpat:synthetic",
          source = HomeworkSource.PATPAT,
          upstreamId = "synthetic",
          title = "Synthetic Patpat assignment",
          submission = HomeworkSubmission.UNSUBMITTED,
      )

  private class FakeHomeworkAdapter(
      override val requiresSeparateLogin: Boolean,
      private val loadItems: suspend () -> List<HomeworkItem>,
  ) : HomeworkAdapter {
    override val source = HomeworkSource.PATPAT

    override suspend fun load(): List<HomeworkItem> = loadItems()
  }
}
