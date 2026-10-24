package app.bucc.ui.screens.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.bucc.api.finance.OfficialPaymentPage
import org.junit.Rule
import org.junit.Test

class OfficialPaymentBrowserUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `payment content does not duplicate the parent toolbar back button`() {
    compose.setContent {
      MaterialTheme { OfficialPaymentBrowser(OfficialPaymentPage("https://untrusted.invalid/")) }
    }
    compose.onAllNodesWithText("返回", useUnmergedTree = true).assertCountEquals(0)
  }
}
