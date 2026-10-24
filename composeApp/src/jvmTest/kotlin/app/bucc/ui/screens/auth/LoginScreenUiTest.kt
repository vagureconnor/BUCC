package app.bucc.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.bucc.api.ConnectionMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginScreenUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `saved connection mode allows login immediately and after remount`() {
    var calls = 0
    var visible by mutableStateOf(true)
    compose.setContent {
      MaterialTheme {
        if (visible) testLogin(ConnectionMode.WEBVPN, onLogin = { calls++ })
        else androidx.compose.material3.Text("登录页已卸载")
      }
    }
    compose.onNodeWithText("登录").assertIsEnabled().performClick()
    assertEquals(1, calls)
    visible = false
    compose.waitForIdle()
    visible = true
    compose.waitForIdle()
    compose.onNodeWithText("登录").assertIsEnabled().performClick()
    assertEquals(2, calls)
    app.bucc.ui.saveRenderedEvidence("login-webvpn-direct", compose.onRoot().captureToImage())
  }

  @Test
  fun `login page has no saved account continuation entry`() {
    compose.setContent { MaterialTheme { testLogin(ConnectionMode.DIRECT) } }
    compose.onAllNodesWithText("使用已保存的账号继续登录").assertCountEquals(0)
  }

  @Test
  fun `connection mode introduction includes TD limitations`() {
    compose.setContent { MaterialTheme { testLogin(ConnectionMode.WEBVPN) } }
    compose.onNodeWithText("模式").performClick()
    compose.onNodeWithText("连接模式说明").performClick()
    compose.onNodeWithText(ConnectionMode.introduction).assertIsDisplayed()
    compose.onNodeWithText(ConnectionMode.WEBVPN.description).assertIsDisplayed()
  }

  @Test
  fun `login failure offers mode switch without clearing form`() {
    var selected: ConnectionMode? = null
    compose.setContent {
      MaterialTheme {
        testLogin(ConnectionMode.DIRECT, error = "无法连接统一认证服务", onMode = { selected = it })
      }
    }
    compose.onNodeWithText("切换连接模式").performClick()
    compose.onNodeWithText("WebVPN模式").performClick()
    assertEquals(ConnectionMode.WEBVPN, selected)
    compose.onNodeWithText("student").assertIsDisplayed()
    compose.onNodeWithText("登录").assertIsEnabled()
  }

  @androidx.compose.runtime.Composable
  private fun testLogin(
      mode: ConnectionMode,
      error: String? = null,
      onLogin: () -> Unit = {},
      onMode: (ConnectionMode) -> Unit = {},
  ) {
    LoginScreen(
        loginFormState = LoginFormState(username = "student", password = "synthetic-secret"),
        currentConnectionMode = mode,
        availableConnectionModes = ConnectionMode.entries,
        onUsernameChange = {},
        onPasswordChange = {},
        onCaptchaChange = {},
        onRememberUsernameChange = {},
        onConnectionModeSelected = onMode,
        onLoginClick = onLogin,
        onRefreshCaptcha = {},
        isLoading = false,
        isRefreshingCaptcha = false,
        captchaRequired = false,
        captchaInfo = null,
        error = error,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
    )
  }

  @Test
  fun `password is hidden by default and eye control toggles visible text`() {
    compose.setContent {
      var form by remember { mutableStateOf(LoginFormState()) }
      MaterialTheme {
        LoginScreen(
            loginFormState = form,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            currentConnectionMode = ConnectionMode.DIRECT,
            availableConnectionModes = ConnectionMode.entries,
            onUsernameChange = { form = form.copy(username = it) },
            onPasswordChange = { form = form.copy(password = it) },
            onCaptchaChange = { form = form.copy(captcha = it) },
            onRememberUsernameChange = { form = form.copy(rememberUsername = it) },
            onConnectionModeSelected = {},
            onLoginClick = {},
            onRefreshCaptcha = {},
            isLoading = false,
            isRefreshingCaptcha = false,
            captchaRequired = false,
            captchaInfo = null,
            error = null,
        )
      }
    }

    val syntheticPassword = "synthetic-password"
    compose.onNodeWithText("密码").performTextInput(syntheticPassword)
    assertPasswordIsHidden(syntheticPassword)
    app.bucc.ui.saveRenderedEvidence("login-password-hidden", compose.onRoot().captureToImage())
    compose.onNodeWithContentDescription("显示密码").assertIsDisplayed().performClick()
    assertPasswordIsVisible(syntheticPassword)
    app.bucc.ui.saveRenderedEvidence("login-password-visible", compose.onRoot().captureToImage())
    compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed().performClick()
    compose.onNodeWithContentDescription("显示密码").assertIsDisplayed()
    assertPasswordIsHidden(syntheticPassword)
  }

  private fun assertPasswordIsHidden(password: String) {
    val config = compose.onNodeWithText("密码").fetchSemanticsNode().config
    assertTrue(config.contains(SemanticsProperties.Password))
    assertEquals(
        "•".repeat(password.length),
        config.getOrNull(SemanticsProperties.EditableText)?.text,
    )
    assertEquals(password, config.getOrNull(SemanticsProperties.InputText)?.text)
    val textValues = config.getOrNull(SemanticsProperties.Text).orEmpty()
    assertFalse(textValues.any { password in it.text })
  }

  private fun assertPasswordIsVisible(password: String) {
    val config = compose.onNodeWithText("密码").fetchSemanticsNode().config
    assertTrue(config.contains(SemanticsProperties.Password))
    assertEquals(password, config.getOrNull(SemanticsProperties.EditableText)?.text)
    assertEquals(password, config.getOrNull(SemanticsProperties.InputText)?.text)
  }
}
