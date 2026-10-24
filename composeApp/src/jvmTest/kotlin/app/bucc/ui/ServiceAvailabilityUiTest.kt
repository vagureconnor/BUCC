package app.bucc.ui.screens.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test

class ServiceAvailabilityUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `disabled TD card is unavailable and does not invoke its callback`() {
    var clickCount = 0
    var enabled by mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        WorkbenchFeatureCard(
            feature = feature(enabled = enabled),
            onClick = { clickCount += 1 },
            modifier = Modifier.testTag("td-feature-card"),
        )
      }
    }

    val card = compose.onNodeWithTag("td-feature-card")
    card.assertIsNotEnabled().performTouchInput { click() }
    val disabledImage = card.captureToImage()
    app.bucc.ui.saveRenderedEvidence("td-webvpn-disabled", disabledImage)
    enabled = true
    compose.waitForIdle()
    val enabledImage = card.captureToImage()
    app.bucc.ui.saveRenderedEvidence("td-direct-enabled", enabledImage)
    val sampleX = disabledImage.width / 4
    val sampleY = disabledImage.height / 2

    assertEquals(0, clickCount)
    assertNotEquals(
        disabledImage.toPixelMap()[sampleX, sampleY],
        enabledImage.toPixelMap()[sampleX, sampleY],
        "disabled card rendering should be visibly faded",
    )
  }

  @Test
  fun `enabled TD card remains clickable in ordinary network mode`() {
    var clickCount = 0
    compose.setContent {
      MaterialTheme {
        WorkbenchFeatureCard(
            feature = feature(enabled = true),
            onClick = { clickCount += 1 },
            modifier = Modifier.testTag("td-feature-card"),
        )
      }
    }

    compose.onNodeWithTag("td-feature-card").performClick()

    assertEquals(1, clickCount)
  }

  private fun feature(enabled: Boolean) =
      WorkbenchFeature(
          id = "td",
          title = "TD",
          description = "教学系统",
          icon = Icons.Default.School,
          enabled = enabled,
      )
}
