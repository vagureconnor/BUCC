package app.bucc.ui.screens.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.bucc.api.finance.CampusNetworkTraffic
import app.bucc.api.finance.CampusRechargeBackend
import app.bucc.api.finance.FinanceBalance
import app.bucc.api.finance.InternetFinanceBalances
import app.bucc.api.finance.OfficialPaymentPage
import app.bucc.api.finance.RechargeFlowException
import app.bucc.api.finance.RechargeFlowStage
import app.bucc.api.finance.RechargeKind
import app.bucc.api.finance.RechargePreparation
import app.bucc.api.finance.ZfwSessionBackend
import app.bucc.api.finance.ZfwSessionState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class CampusFinanceCaptchaUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `network verification stays visually silent while fee data is loading`() {
    val session = FakeSession(initiallyChecking = true, holdChecking = true)
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 }

    compose.onAllNodesWithText("正在准备校园网验证码…").assertCountEquals(0)
    compose.onAllNodesWithText("正在检查校园网登录状态…").assertCountEquals(0)
    compose.onAllNodesWithText("正在准备支付会话…").assertCountEquals(0)
    compose.onAllNodesWithText("校园网身份验证").assertCountEquals(0)
    compose.onAllNodesWithText("校园财务").assertCountEquals(0)
    compose.onAllNodesWithText("校园卡").assertCountEquals(0)
    compose.onAllNodesWithText("网费").assertCountEquals(0)
    assertEquals(0, loaders.internetCalls)
  }

  @Test
  fun `network recharge preparation waits until the first balance load completes`() {
    val session = FakeSession(initiallyReady = true)
    val balanceGate = CompletableDeferred<InternetFinanceBalances>()
    val loaders = FakeFinanceLoaders(internetLoader = { balanceGate.await() })
    val recharge = FakeRechargeBackend()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { loaders.internetCalls == 1 }

    assertTrue(RechargeKind.Internet !in recharge.preparedKinds)
    compose.onAllNodesWithText("网费余额").assertCountEquals(0)
    compose.onAllNodesWithText("校园财务").assertCountEquals(0)
    compose.onAllNodesWithText("校园卡").assertCountEquals(0)
    compose.onAllNodesWithText("网费").assertCountEquals(0)
    compose.onAllNodesWithText("正在检查校园网登录状态…").assertCountEquals(0)
    compose.onAllNodesWithText("正在准备支付会话…").assertCountEquals(0)

    balanceGate.complete(
        InternetFinanceBalances(
            internetFee = FinanceBalance(value = "100.00"),
            internetTraffic = CampusNetworkTraffic(used = "1 GB", freeRemaining = "2 GB"),
        )
    )
    compose.waitUntil(5_000) { RechargeKind.Internet in recharge.preparedKinds }
    compose.onNodeWithText("网费余额").assertIsDisplayed()
  }

  @Test
  fun `campus card is the default and loads without requesting network verification`() {
    val session = FakeSession()
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("校园卡余额").performScrollTo().assertIsDisplayed()
    compose.waitUntil(5_000) { loaders.cardCalls == 1 }
    compose.onNodeWithText("校园卡充值").performScrollTo().assertIsDisplayed()
    compose.onAllNodesWithText("登录验证码").assertCountEquals(0)
    assertEquals(0, session.forceVerificationCalls)
    assertEquals(0, loaders.internetCalls)
    assertTrue(recharge.preparedKinds.contains(RechargeKind.Card))
    assertEquals(0, recharge.createCalls)
  }

  @Test
  fun `network tab reuses ready session without asking for login captcha`() {
    val session = FakeSession(initiallyReady = true)
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 && loaders.internetCalls == 1 }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("网费余额").fetchSemanticsNodes().size == 1
    }
    compose.onAllNodesWithText("登录验证码").assertCountEquals(0)
    compose.onAllNodesWithText("网费信息尚未完成本次登录验证码验证。").assertCountEquals(0)
    compose.onNodeWithText("网费余额").assertIsDisplayed()
    compose.onNodeWithText("充值验证码").performScrollTo().assertIsDisplayed()
    listOf("账号", "密码", "用户名", "校园网密码").forEach {
      compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
    }
    assertEquals(0, session.forceVerificationCalls)
    assertEquals(0, recharge.createCalls)
  }

  @Test
  fun `failed network loading shows retry without stale verification message`() {
    val session = FakeSession(failEnsure = true)
    setScreen(session, FakeRechargeBackend(), FakeFinanceLoaders())

    compose.onNodeWithText("网费").performClick()
    compose.onNodeWithText("校园网登录状态暂时无法读取，请重试。").assertIsDisplayed()
    compose.onNodeWithText("重新验证校园网").assertIsEnabled()
    compose.onAllNodesWithText("网费信息尚未完成本次登录验证码验证。").assertCountEquals(0)
  }

  @Test
  fun `sms challenge must be completed before loading network balances`() {
    val session = FakeSession(smsChallengeOnLogin = true)
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 }
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) { session.state.value is ZfwSessionState.SmsChallenge }
    compose.onNodeWithText("短信验证码").performTextInput("sms-demo")
    assertEquals(0, loaders.internetCalls)
    compose.onNodeWithText("验证短信验证码").performClick()
    compose.waitUntil(5_000) {
      session.state.value is ZfwSessionState.Ready && loaders.internetCalls == 1
    }
  }

  @Test
  fun `login captcha then recharge submits only mock amount and recharge captcha`() {
    val session = FakeSession()
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 }
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) {
      session.state.value is ZfwSessionState.Ready && loaders.internetCalls == 1
    }

    compose.onNodeWithText("充值金额（元）").performScrollTo().performTextInput("12.34")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("recharge-demo")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()
    compose.waitUntil(5_000) { recharge.createCalls == 1 }

    assertEquals(RechargeKind.Internet, recharge.createdKind)
    assertEquals("12.34", recharge.createdAmount)
    assertEquals("recharge-demo", recharge.createdCode)
    compose
        .onNodeWithText("官方支付页面地址无法验证，请勿继续付款。", substring = true)
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun `failed login captcha keeps authentication retry separate from recharge`() {
    val session = FakeSession(loginFails = true)
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 }
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.onNodeWithText("验证码不正确，请重新输入").assertIsDisplayed()
    compose.onNodeWithText("登录验证码").assertIsDisplayed()
    compose.onAllNodesWithText("充值验证码").assertCountEquals(0)
    listOf("账号", "密码", "用户名", "校园网密码").forEach {
      compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
    }
    assertTrue(recharge.preparedKinds.none { it == RechargeKind.Internet })
    assertEquals(0, recharge.createCalls)
    assertEquals(0, loaders.internetCalls)
  }

  @Test
  fun `refreshing recharge captcha preserves amount and clears old code`() {
    val session = FakeSession()
    val recharge = FakeRechargeBackend()
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 }
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) {
      session.state.value is ZfwSessionState.Ready && loaders.internetCalls == 1
    }
    compose.onNodeWithText("充值金额（元）").performScrollTo().performTextInput("45.60")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("stale-code")

    compose.onNodeWithText("刷新充值准备").performScrollTo().performClick()
    compose.waitUntil(5_000) { recharge.prepareCompletedCalls >= 2 }
    compose.waitForIdle()
    compose.onNodeWithText("充值金额（元）").performScrollTo().assertTextEquals("充值金额（元）", "45.60")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("fresh-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()
    compose.waitUntil(5_000) { recharge.createCalls == 1 }
    assertEquals("45.60", recharge.createdAmount)
    assertEquals("fresh-code", recharge.createdCode)
  }

  @Test
  fun `top back from payment restores internet tab with fresh preparation and no automatic resubmission`() {
    val session = FakeSession()
    val recharge =
        FakeRechargeBackend(
            OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic")
        )
    val loaders = FakeFinanceLoaders()
    var back: (() -> Unit)? = null
    compose.setContent {
      MaterialTheme {
        CampusFinanceScreen(
            sessionOverride = session,
            campusCardBalanceLoaderOverride = { loaders.loadCard() },
            internetBalancesLoaderOverride = { loaders.loadInternet() },
            rechargeBackendOverride = recharge,
            browserInitializer = { true },
            onBackHandlerChanged = { back = it },
            paymentBrowser = { Text("模拟官方页面") },
        )
      }
    }
    compose.onNodeWithText("网费").performClick()
    compose.waitUntil(5_000) { session.ensureCalls == 1 }
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) { loaders.internetCalls == 1 }
    compose.onNodeWithText("充值金额（元）").performScrollTo().performTextInput("1")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("synthetic-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()
    compose.onNodeWithText("模拟官方页面").assertIsDisplayed()
    compose.runOnIdle { checkNotNull(back)() }
    compose.onNodeWithText("网费充值").performScrollTo().assertIsDisplayed()
    compose.waitUntil(5_000) { recharge.prepareCompletedCalls == 2 }
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().assertIsNotEnabled()
    assertEquals(1, recharge.createCalls)
    compose.onNodeWithText("充值金额（元）").performScrollTo().performTextInput("2")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("new-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()
    compose.waitUntil(5_000) { recharge.createCalls == 2 }
    assertEquals("2.00", recharge.createdAmount)
    assertEquals("new-code", recharge.createdCode)
    assertEquals(0, session.forceVerificationCalls)
  }

  @Test
  fun `uncertain submission permits a manually prepared new attempt and never replays automatically`() {
    val session = FakeSession()
    val recharge = FakeRechargeBackend(failFirstSubmission = true)
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)
    compose.onNodeWithText("网费").performClick()
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) { loaders.internetCalls == 1 }
    compose.onNodeWithText("充值金额（元）").performScrollTo().performTextInput("1")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("old-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()
    compose.waitUntil(5_000) { recharge.createCalls == 1 }
    compose.onNodeWithText("刷新充值准备").performScrollTo().assertIsEnabled().performClick()
    compose.waitUntil(5_000) { recharge.prepareCompletedCalls == 2 }
    assertEquals(1, recharge.createCalls)
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("new-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()
    compose.waitUntil(5_000) { recharge.createCalls == 2 }
    assertEquals("1.00", recharge.createdAmount)
    assertEquals("new-code", recharge.createdCode)
  }

  @Test
  fun `payment continuation button reconnects the retained page without another order`() {
    val session = FakeSession()
    val entry = OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic-order-id")
    val recharge =
        FakeRechargeBackend(
            paymentConnectFailure =
                RechargeFlowException(
                    RechargeFlowStage.CONNECT_PAYMENT_SESSION,
                    paymentPage = entry,
                ),
        )
    val loaders = FakeFinanceLoaders()
    val resumedTargets = mutableListOf<String>()
    compose.setContent {
      MaterialTheme {
        CampusFinanceScreen(
            sessionOverride = session,
            campusCardBalanceLoaderOverride = { loaders.loadCard() },
            internetBalancesLoaderOverride = { loaders.loadInternet() },
            rechargeBackendOverride = recharge,
            browserInitializer = { true },
            paymentPageResumer = { target ->
              resumedTargets += target
              OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic-resumed-id")
            },
            paymentBrowser = { Text("模拟官方支付页面") },
        )
      }
    }
    compose.onNodeWithText("网费").performClick()
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) { loaders.internetCalls == 1 }
    compose.onNodeWithText("充值金额（元）").performScrollTo().performTextInput("10")
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("synthetic-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().performClick()

    compose.onNodeWithText("重新接续支付页（不会重新建单）").performScrollTo().assertIsEnabled().performClick()
    compose.onNodeWithText("模拟官方支付页面").assertIsDisplayed()

    assertEquals(listOf(entry.url), resumedTargets)
    assertEquals(1, recharge.createCalls)
  }

  @Test
  fun `timed out payment preparation can be refreshed without creating an order`() {
    val session = FakeSession()
    val recharge = FakeRechargeBackend(hangFirstPrepare = true)
    val loaders = FakeFinanceLoaders()
    setScreen(session, recharge, loaders)

    compose.onNodeWithText("网费").performClick()
    compose.onNodeWithText("登录验证码").performTextInput("login-demo")
    compose.onNodeWithText("验证并继续").performClick()
    compose.waitUntil(5_000) { loaders.internetCalls == 1 }
    compose.waitUntil(25_000) {
      compose.onAllNodesWithText("准备支付会话超时，未创建订单。请点击刷新充值准备重试。").fetchSemanticsNodes().isNotEmpty()
    }

    compose.onNodeWithText("准备支付会话超时，未创建订单。请点击刷新充值准备重试。").performScrollTo().assertIsDisplayed()
    assertEquals(0, recharge.createCalls)

    compose.onNodeWithText("刷新充值准备").performScrollTo().assertIsEnabled().performClick()
    compose.waitUntil(5_000) { recharge.prepareCompletedCalls == 1 }
    compose.onNodeWithText("充值验证码").performScrollTo().performTextInput("retry-code")
    compose.onNodeWithText("提交充值并打开支付页面").performScrollTo().assertIsEnabled()
    assertEquals(0, recharge.createCalls)
  }

  private fun setScreen(
      session: FakeSession,
      recharge: FakeRechargeBackend,
      loaders: FakeFinanceLoaders,
  ) {
    compose.setContent {
      MaterialTheme {
        Surface {
          CampusFinanceScreen(
              sessionOverride = session,
              campusCardBalanceLoaderOverride = { loaders.loadCard() },
              internetBalancesLoaderOverride = { loaders.loadInternet() },
              rechargeBackendOverride = recharge,
              browserInitializer = { true },
          )
        }
      }
    }
  }

  private class FakeSession(
      private val loginFails: Boolean = false,
      private val smsChallengeOnLogin: Boolean = false,
      initiallyReady: Boolean = false,
      initiallyChecking: Boolean = false,
      private val holdChecking: Boolean = false,
      private val failEnsure: Boolean = false,
  ) : ZfwSessionBackend {
    private val mutableState =
        MutableStateFlow<ZfwSessionState>(
            when {
              initiallyChecking -> ZfwSessionState.Checking
              initiallyReady -> ZfwSessionState.Ready
              else -> ZfwSessionState.LoginCaptcha(mockPng, "请完成校园网登录验证码")
            },
        )
    override val state: StateFlow<ZfwSessionState> = mutableState
    var ensureCalls = 0
    var forceVerificationCalls = 0

    override suspend fun ensureSession(
        refreshCaptcha: Boolean,
        forceVerification: Boolean,
    ): ZfwSessionState {
      ensureCalls++
      if (failEnsure) error("fixture network failure")
      if (holdChecking) awaitCancellation()
      if (forceVerification) {
        forceVerificationCalls++
        mutableState.value = ZfwSessionState.LoginCaptcha(mockPng, "请完成校园网登录验证码")
      } else if (refreshCaptcha) mutableState.value = ZfwSessionState.LoginCaptcha(mockPng)
      return mutableState.value
    }

    override suspend fun submitLoginCaptcha(code: String): ZfwSessionState {
      assertEquals("login-demo", code)
      mutableState.value =
          when {
            loginFails -> ZfwSessionState.LoginCaptcha(mockPng, "验证码不正确，请重新输入")
            smsChallengeOnLogin -> ZfwSessionState.SmsChallenge()
            else -> ZfwSessionState.Ready
          }
      return mutableState.value
    }

    override suspend fun submitSmsCode(code: String): ZfwSessionState {
      assertEquals("sms-demo", code)
      mutableState.value = ZfwSessionState.Ready
      return mutableState.value
    }
  }

  private class FakeFinanceLoaders(
      private val internetLoader: suspend () -> InternetFinanceBalances = {
        InternetFinanceBalances(
            internetFee = FinanceBalance(value = "100.00"),
            internetTraffic = CampusNetworkTraffic(used = "1 GB", freeRemaining = "2 GB"),
        )
      },
  ) {
    var cardCalls = 0
    var internetCalls = 0

    suspend fun loadCard(): FinanceBalance {
      cardCalls++
      return FinanceBalance(value = "50.00")
    }

    suspend fun loadInternet(): InternetFinanceBalances {
      internetCalls++
      return internetLoader()
    }
  }

  private class FakeRechargeBackend(
      private val browserPage: OfficialPaymentPage? = null,
      private val failFirstSubmission: Boolean = false,
      private val paymentConnectFailure: RechargeFlowException? = null,
      private val hangFirstPrepare: Boolean = false,
  ) : CampusRechargeBackend {
    var prepareCalls = 0
    var prepareCompletedCalls = 0
    var internetPrepareCalls = 0
    var createCalls = 0
    val preparedKinds = mutableListOf<RechargeKind>()
    var createdKind: RechargeKind? = null
    var createdAmount: String? = null
    var createdCode: String? = null

    override suspend fun prepare(kind: RechargeKind): RechargePreparation {
      prepareCalls++
      preparedKinds += kind
      if (hangFirstPrepare && kind == RechargeKind.Internet && ++internetPrepareCalls == 1)
          awaitCancellation()
      return if (kind == RechargeKind.Card) RechargePreparation(false, "校园卡充值暂不可用，未创建订单。")
      else
          RechargePreparation(available = true, captchaImage = mockPng).also {
            prepareCompletedCalls++
          }
    }

    override suspend fun createOrder(
        kind: RechargeKind,
        amount: String,
        verificationCode: String?,
    ): OfficialPaymentPage {
      createCalls++
      createdKind = kind
      createdAmount = amount
      createdCode = verificationCode
      if (failFirstSubmission && createCalls == 1)
          throw RechargeFlowException(RechargeFlowStage.CREATE_ORDER)
      paymentConnectFailure?.let { throw it }
      return browserPage ?: OfficialPaymentPage("https://example.test/mock-payment")
    }
  }

  private companion object {
    val mockPng =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/tMcAAAAASUVORK5CYII=",
            )
  }
}
