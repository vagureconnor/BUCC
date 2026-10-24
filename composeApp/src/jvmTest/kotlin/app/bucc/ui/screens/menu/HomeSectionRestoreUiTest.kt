package app.bucc.ui.screens.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import app.bucc.ui.screens.grade.GradeScoreUpdateNotice
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import org.junit.Rule
import org.junit.Test

class HomeSectionRestoreUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `returning from a service feature restores the parent section menu`() {
    val financeScreen = androidx.compose.runtime.mutableStateOf(false)
    var openedFeature: String? = null
    val openSectionId = androidx.compose.runtime.mutableStateOf<String?>(null)
    val services =
        WorkbenchFeatureSection(
            id = "services",
            title = "服务",
            features =
                (1..6).map { index ->
                  WorkbenchFeature(
                      id = if (index == 1) "finance" else "service-$index",
                      title = if (index == 1) "校园财务" else "服务入口 $index",
                      description = if (index == 6) "用于验证列表末尾卡片能完整滚动显示" else "合成服务项",
                      icon = Icons.Default.AccountBalanceWallet,
                  )
                },
        )

    compose.setContent {
      MaterialTheme {
        Box(Modifier.width(390.dp).height(640.dp)) {
          if (financeScreen.value) {
            Column {
              Text("校园财务页面")
              Button(onClick = { financeScreen.value = false }) { Text("返回菜单") }
            }
          } else {
            HomeScreen(
                upcomingClasses = emptyList(),
                signinClasses = emptyList(),
                now = LocalDateTime.parse("2026-10-14T08:00:00"),
                updatedAt = null,
                isLoading = false,
                isRefreshing = false,
                error = null,
                todayProgressOccurrences = emptyList(),
                scheduleHasLoaded = false,
                todoItems = emptyList(),
                todoLoading = false,
                todoLoadingSources = emptyList(),
                todoFailedSources = emptyList(),
                todoSourceErrors = emptyMap(),
                signingTodoId = null,
                scoreUpdateNotice = null as GradeScoreUpdateNotice?,
                onRetrySchedule = {},
                onRefresh = {},
                onOpenScoresClick = {},
                onDismissScoreNotice = {},
                onTodoClick = {},
                onSigninTodoClick = {},
                featureSections = listOf(services),
                openSectionId = openSectionId.value,
                onOpenSectionChange = { openSectionId.value = it },
                onFeatureClick = { featureId ->
                  openedFeature = featureId
                  financeScreen.value = true
                },
                onOpenSchedule = {},
                onOpenTodos = {},
                onFeatureOrderChange = { _, _ -> },
                onCourseClick = {},
            )
          }
        }
      }
    }

    compose.onNodeWithText("服务").performClick()
    compose.onNodeWithText("校园财务").performClick()
    assertEquals("finance", openedFeature)
    compose.onNodeWithText("校园财务页面").assertIsDisplayed()
    compose.onNodeWithText("返回菜单").performClick()

    compose.onNodeWithText("调整顺序").assertIsDisplayed()
    compose.onNodeWithText("校园财务").assertIsDisplayed()
    compose.onNodeWithTag("home-section-feature-list").performScrollToIndex(5)
    compose.onNodeWithText("用于验证列表末尾卡片能完整滚动显示").assertIsDisplayed()
  }
}
