package app.bucc.ui.navigation

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionViewModelStoreTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `account switch and logout clear session view models`() {
    var sessionKey by mutableStateOf("account-a")
    var showSession by mutableStateOf(true)
    var current: TrackingViewModel? = null
    compose.setContent {
      if (showSession) {
        SessionViewModelStore(sessionKey) {
          val viewModel = viewModel(key = "shared-page") { TrackingViewModel() }
          SideEffect { current = viewModel }
        }
      }
    }

    lateinit var accountA: TrackingViewModel
    compose.runOnIdle {
      accountA = current!!
      sessionKey = "account-b"
    }
    compose.waitForIdle()

    lateinit var accountB: TrackingViewModel
    compose.runOnIdle {
      accountB = current!!
      assertNotSame(accountA, accountB)
      assertTrue(accountA.cleared)
      showSession = false
    }
    compose.waitForIdle()

    compose.runOnIdle { assertTrue(accountB.cleared) }
  }

  private class TrackingViewModel : ViewModel() {
    var cleared = false
      private set

    override fun onCleared() {
      cleared = true
    }
  }
}
