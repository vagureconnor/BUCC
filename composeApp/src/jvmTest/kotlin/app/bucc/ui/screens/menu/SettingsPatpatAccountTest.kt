package app.bucc.ui.screens.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.bucc.api.ConnectionMode
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsPatpatAccountTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `patpat account status is visible and opens its dedicated login flow`() {
    var opened = false
    compose.setContent {
      MaterialTheme {
        SettingsScreen(
            currentMode = ConnectionMode.DIRECT,
            availableModes = listOf(ConnectionMode.DIRECT),
            onModeSelected = {},
            onAccodingAccount = {},
            onPatpatAccount = { opened = true },
            onTdAccountSave = { _, _ -> },
            patpatAccountName = "saved-patpat",
        )
      }
    }

    compose.onNodeWithText("PatPat").assertIsDisplayed().performClick()
    compose.onNodeWithText("saved-patpat").assertIsDisplayed()
    assertTrue(opened)
  }
}
