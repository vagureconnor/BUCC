@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.bucc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnitType
import app.bucc.api.ConnectionMode
import app.bucc.ui.common.components.buccSlogan
import app.bucc.ui.screens.auth.LoginFormState
import app.bucc.ui.screens.auth.LoginScreen
import app.bucc.ui.theme.BUCCTheme
import app.bucc.ui.theme.ThemeMode
import kotlin.test.*
import org.junit.Test

class SloganUiTest {
  @Test
  fun `login slogan fits real desktop and mobile viewports in both themes`() {
    for ((width, height) in listOf(1100 to 760, 640 to 600, 360 to 780)) {
      for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
        runDesktopComposeUiTest(width = width, height = height) {
          setContent {
            BUCCTheme(mode) {
              LoginScreen(
                  LoginFormState(),
                  ConnectionMode.DIRECT,
                  ConnectionMode.entries,
                  {},
                  {},
                  {},
                  {},
                  {},
                  {},
                  {},
                  false,
                  false,
                  false,
                  null,
                  null,
                  modifier =
                      Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
              )
            }
          }
          val node = onNodeWithText("Beyond Utility Comes Creativity.")
          node.assertIsDisplayed()
          val bounds = node.fetchSemanticsNode().boundsInRoot
          assertTrue(bounds.left >= 0 && bounds.right <= width)
          val layouts = mutableListOf<TextLayoutResult>()
          node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
          assertFalse(layouts.single().hasVisualOverflow)
          val screenshot = onRoot().captureToImage()
          assertEquals(width, screenshot.width)
          assertEquals(height, screenshot.height)
          saveRenderedEvidence("login-${width}x${height}-${mode.name.lowercase()}", screenshot)
        }
      }
    }
  }

  @Test
  fun `all four initials resolve to the same absolute font size`() = runDesktopComposeUiTest {
    var slogan: AnnotatedString? = null
    setContent { MaterialTheme { slogan = buccSlogan() } }
    waitForIdle()
    val actual = assertNotNull(slogan)
    val spans = actual.spanStyles
    assertEquals(4, spans.size)
    assertEquals(listOf("B", "U", "C", "C"), spans.map { actual.text.substring(it.start, it.end) })
    assertEquals(1, spans.map { it.item.fontSize }.distinct().size)
    assertEquals(TextUnitType.Sp, spans.first().item.fontSize.type)
  }
}
