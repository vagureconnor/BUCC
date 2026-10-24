package app.bucc.ui.screens.td

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.bucc.td.TdCounts
import org.junit.Rule
import org.junit.Test

class TdCountsCardUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun refreshAnimationKeepsCachedCountsVisibleAndStopsWhenFinished() {
    val refreshing = mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        TdCountsCard(
            TdCounts(today = 2, semester = 7),
            enabled = true,
            refreshing = refreshing.value,
            onRefresh = {},
        )
      }
    }
    compose.onNodeWithTag("td-counts-refresh-progress").assertDoesNotExist()
    compose.onNodeWithText("刷新").assertIsEnabled()
    compose.runOnIdle { refreshing.value = true }
    compose.onNodeWithTag("td-counts-refresh-progress", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithText("正在刷新").assertIsNotEnabled()
    compose.onNodeWithText("2").assertIsDisplayed()
    compose.onNodeWithText("7").assertIsDisplayed()
    compose.runOnIdle { refreshing.value = false }
    compose.onNodeWithTag("td-counts-refresh-progress").assertDoesNotExist()
    compose.onNodeWithText("刷新").assertIsEnabled()
  }
}
