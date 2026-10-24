package app.bucc.api.finance

import app.bucc.api.ConnectionModeStore
import app.bucc.api.local.LocalCookieStore
import com.russhwolf.settings.MapSettings
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class CampusRechargeTest {
  @Test
  fun `internet browser receives only BUCC scoped ZFW and payment cookies`() = runTest {
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.clear()
    LocalCookieStore.settings = MapSettings()
    val storage = LocalCookieStore.storage()
    storage.addCookie(
        Url("https://zfw.buaa.edu.cn/"),
        Cookie("ZFW_SYNTHETIC", "private-value", path = "/", secure = true),
    )
    storage.addCookie(
        Url("https://evil.test/"),
        Cookie("UNRELATED", "private-value", path = "/", secure = true),
    )
    val cookies = paymentSessionCookies("https://zfw.buaa.edu.cn/pay/card", includeZfw = true)
    assertEquals(listOf("ZFW_SYNTHETIC"), cookies.map { it.name })
    assertEquals("https://zfw.buaa.edu.cn/", cookies.single().originUrl)
    assertFalse(cookies.toString().contains("private-value"))
    assertFailsWith<IllegalArgumentException> {
      paymentSessionCookies("https://evil.test/", includeZfw = true)
    }
  }

  @Test
  fun `payment cookies retain the official origin they were read from and redact values`() =
      runTest {
        ConnectionModeStore.settings = MapSettings()
        ConnectionModeStore.clear()
        LocalCookieStore.settings = MapSettings()
        LocalCookieStore.clearAllScopes()
        val storage = LocalCookieStore.storage()
        storage.addCookie(
            Url("https://mall.cc-pay.cn/"),
            Cookie(
                name = "MALL_ONLY",
                value = "mall-secret-synthetic",
                path = "/",
                secure = true,
                httpOnly = true,
            ),
        )
        storage.addCookie(
            Url("https://cashier.cc-pay.cn/"),
            Cookie(
                name = "CASHIER_ONLY",
                value = "cashier-secret-synthetic",
                path = "/",
                secure = true,
                httpOnly = true,
            ),
        )
        storage.addCookie(
            Url("https://pass.cc-pay.cn/"),
            Cookie(
                name = "PASS_ONLY",
                value = "pass-secret-synthetic",
                path = "/",
                secure = true,
                httpOnly = true,
            ),
        )
        storage.addCookie(
            Url("https://mall.cc-pay.cn/"),
            Cookie(
                name = "SHARED",
                value = "shared-secret-synthetic",
                domain = "cc-pay.cn",
                path = "/",
                secure = true,
            ),
        )

        val cookies = paymentSessionCookies("https://cashier.cc-pay.cn/cashier?order=synthetic-id")

        assertEquals(1, cookies.count { it.name == "SHARED" })
        assertEquals("https://mall.cc-pay.cn/", cookies.single { it.name == "MALL_ONLY" }.originUrl)
        assertEquals(
            "https://cashier.cc-pay.cn/",
            cookies.single { it.name == "CASHIER_ONLY" }.originUrl,
        )
        assertEquals("https://pass.cc-pay.cn/", cookies.single { it.name == "PASS_ONLY" }.originUrl)
        assertEquals("https://cashier.cc-pay.cn/", cookies.single { it.name == "SHARED" }.originUrl)
        cookies.forEach { cookie ->
          assertFalse(cookie.toString().contains("secret-synthetic"))
          assertFalse(cookie.toString().contains("synthetic-id"))
        }
      }

  @Test
  fun `payment cookie import rejects untrusted page URLs without reading storage`() = runTest {
    assertFailsWith<IllegalArgumentException> {
      paymentSessionCookies("https://evil.example/cashier")
    }
  }

  @Test
  fun `prepares captcha and opens validated official page`() = runTest {
    val backend = FakeRechargeBackend().apply { captchaImage = byteArrayOf(1, 2, 3) }
    val controller = CampusRechargeController(backend)

    controller.prepare(RechargeKind.Internet)
    val ready = controller.state as CampusRechargeState.Ready
    assertEquals(RechargeKind.Internet, ready.kind)
    assertTrue(ready.captchaImage!!.contentEquals(byteArrayOf(1, 2, 3)))

    controller.create("1,234.5", "mock-code")

    val page = (controller.state as CampusRechargeState.OfficialPage).page
    assertEquals("https://cashier.cc-pay.cn/cashier?order=synthetic-order-id", page.url)
    assertEquals(
        listOf("prepare:Internet", "create:1234.50:mock-code"),
        backend.calls,
    )
    assertFalse(page.toString().contains("synthetic-order-id"))
  }

  @Test
  fun `serializes concurrent create calls and creates only one order`() = runTest {
    val backend =
        FakeRechargeBackend().apply {
          createStarted = CompletableDeferred()
          createGate = CompletableDeferred()
        }
    val controller = CampusRechargeController(backend)
    controller.prepare(RechargeKind.Card)

    val first = async { controller.create("10") }
    backend.createStarted!!.await()
    assertFalse(controller.canPrepare)
    val second = async { controller.create("10") }
    backend.createGate!!.complete(Unit)
    first.await()
    second.await()

    assertEquals(1, backend.calls.count { it.startsWith("create:") })
    assertTrue(controller.state is CampusRechargeState.OfficialPage)
  }

  @Test
  fun `ambiguous result stays blocked until a manual fresh prepare`() = runTest {
    val backend = FakeRechargeBackend().apply { createFailure = IllegalStateException("timeout") }
    val controller = CampusRechargeController(backend)
    controller.prepare(RechargeKind.Internet)

    controller.create("10")
    controller.create("10")

    assertEquals(1, backend.calls.count { it.startsWith("create:") })
    assertTrue(controller.state is CampusRechargeState.Failed)
    assertTrue(controller.canPrepare)

    backend.createFailure = null
    controller.prepare(RechargeKind.Internet)
    assertEquals(
        1,
        backend.calls.count { it.startsWith("create:") },
        "Preparing must not send another order",
    )
    controller.create("10")
    assertEquals(2, backend.calls.count { it.startsWith("create:") })
    assertFalse(controller.canSubmit)
  }

  @Test
  fun `maps typed recharge stages to safe messages and blocks another create`() = runTest {
    val cases =
        listOf(
            RechargeFlowStage.REJECTED to RechargeFlowFailureReason.REJECTED,
            RechargeFlowStage.CREATE_ORDER to "建单结果暂不明确，请先核实官方订单或余额，确认后再决定是否重新提交。",
            RechargeFlowStage.RESOLVE_PAYMENT_PAGE to "充值已提交，但官方支付入口暂未识别，请先核实官方订单，避免重复充值。",
            RechargeFlowStage.CONNECT_PAYMENT_SESSION to "充值已提交，支付会话暂未接续，请先核实官方订单，避免重复充值。",
        )

    cases.forEach { (stage, expectedReason) ->
      val backend = FakeRechargeBackend().apply { createFailure = RechargeFlowException(stage) }
      val controller = CampusRechargeController(backend)
      controller.prepare(RechargeKind.Internet)

      controller.create("10")
      val failed = controller.state as CampusRechargeState.Failed
      assertEquals(expectedReason, failed.reason)
      assertFalse(controller.canSubmit)
      assertTrue(controller.canPrepare)

      controller.create("10")
      assertEquals(1, backend.calls.count { it.startsWith("create:") })
      backend.createFailure = null
      controller.prepare(RechargeKind.Internet)
      assertEquals(1, backend.calls.count { it.startsWith("create:") })
      controller.create("10")
      assertEquals(2, backend.calls.count { it.startsWith("create:") })
      assertFalse(failed.reason.contains("synthetic-order-id"))
    }
  }

  @Test
  fun `payment continuation is retained only for a validated non form payment page`() = runTest {
    val valid = OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic-order-id")
    val invalid = OfficialPaymentPage("https://example.test/cashier?order=synthetic-order-id")
    val form =
        OfficialPaymentPage.zfwFormSubmission(
            mapOf(
                "OneCardForm[cardNo]" to "synthetic-card",
                "OneCardForm[productId]" to "synthetic-product",
                "OneCardForm[amount]" to "10.00",
                "OneCardForm[verifyCode]" to "synthetic-code",
                "_csrf-synthetic" to "synthetic-token",
            ),
        )

    for ((page, expected) in listOf(valid to valid, invalid to null, form to null)) {
      val backend =
          FakeRechargeBackend().apply {
            createFailure =
                RechargeFlowException(
                    RechargeFlowStage.CONNECT_PAYMENT_SESSION,
                    paymentPage = page,
                )
          }
      val controller = CampusRechargeController(backend)
      controller.prepare(RechargeKind.Internet)
      controller.create("10")

      val failed = controller.state as CampusRechargeState.Failed
      assertEquals(expected, failed.continuation)
      assertFalse(failed.toString().contains("synthetic-order-id"))
      assertFalse(controller.canSubmit)
    }
  }

  @Test
  fun `resuming a retained payment page never creates another order`() = runTest {
    val entry = OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic-order-id")
    val resumed = OfficialPaymentPage("https://mall.cc-pay.cn/entry/card/synthetic-payment")
    val backend =
        FakeRechargeBackend().apply {
          createFailure =
              RechargeFlowException(RechargeFlowStage.CONNECT_PAYMENT_SESSION, paymentPage = entry)
        }
    val resumeTargets = mutableListOf<String>()
    val controller =
        CampusRechargeController(backend) { target ->
          resumeTargets += target
          resumed
        }
    controller.prepare(RechargeKind.Internet)
    controller.create("10")

    controller.resumePayment()
    controller.resumePayment()

    assertEquals(listOf(entry.url), resumeTargets)
    assertEquals(1, backend.calls.count { it.startsWith("create:") })
    assertTrue(controller.state is CampusRechargeState.OfficialPage)
    assertFalse(controller.canSubmit)
  }

  @Test
  fun `failed or cancelled continuation preserves the verified entry without exposing errors`() =
      runTest {
        val entry = OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=private-order-id")
        val backend =
            FakeRechargeBackend().apply {
              createFailure =
                  RechargeFlowException(
                      RechargeFlowStage.CONNECT_PAYMENT_SESSION,
                      paymentPage = entry,
                  )
            }
        val controller =
            CampusRechargeController(backend) {
              throw IllegalStateException("private exception details")
            }
        controller.prepare(RechargeKind.Internet)
        controller.create("10")

        controller.resumePayment()
        val failed = controller.state as CampusRechargeState.Failed
        assertEquals(entry, failed.continuation)
        assertEquals("支付页面暂未接续，可重试接续或返回。", failed.reason)
        assertFalse(failed.toString().contains("private-order-id"))
        assertFalse(failed.toString().contains("private exception details"))

        val cancelled =
            CampusRechargeController(backend) { throw CancellationException("cancelled") }
        cancelled.prepare(RechargeKind.Internet)
        cancelled.create("10")
        assertFailsWith<CancellationException> { cancelled.resumePayment() }
        assertEquals(entry, (cancelled.state as CampusRechargeState.Failed).continuation)
        assertEquals(2, backend.calls.count { it.startsWith("create:") })
      }

  @Test
  fun `rejects invalid amounts before order creation and permits correction`() = runTest {
    val backend = FakeRechargeBackend()
    val controller = CampusRechargeController(backend)
    controller.prepare(RechargeKind.Internet)

    controller.create("0.004")
    assertTrue(controller.state is CampusRechargeState.Failed)
    assertEquals(0, backend.calls.count { it.startsWith("create:") })

    controller.create("10.001")
    assertTrue(controller.state is CampusRechargeState.Failed)
    assertEquals(0, backend.calls.count { it.startsWith("create:") })

    controller.create("10e2")
    assertEquals(0, backend.calls.count { it.startsWith("create:") })
    controller.create("0.01")
    assertEquals("create:0.01:null", backend.calls.last())
    assertTrue(controller.state is CampusRechargeState.OfficialPage)
  }

  @Test
  fun `campus card rejects amounts over official limit before consuming submission`() = runTest {
    val backend = FakeRechargeBackend()
    val controller = CampusRechargeController(backend)
    controller.prepare(RechargeKind.Card)
    controller.create("1000.01")
    assertTrue(controller.state is CampusRechargeState.Failed)
    assertTrue(controller.canSubmit)
    assertEquals(0, backend.calls.count { it.startsWith("create:") })
  }

  @Test
  fun `accepts only secure official cashier URLs and identifies login pages`() {
    assertTrue(isOfficialPaymentUrl("https://cashier.cc-pay.cn/cashier?order=synthetic-id"))
    assertTrue(isOfficialPaymentUrl("https://mall.cc-pay.cn/cashier/"))
    assertFalse(isOfficialPaymentUrl("http://cashier.cc-pay.cn/cashier"))
    assertFalse(isOfficialPaymentUrl("https://cashier.cc-pay.cn:8443/cashier"))
    assertFalse(isOfficialPaymentUrl("https://evil.example/cashier"))
    assertFalse(isOfficialPaymentUrl("https://cashier.cc-pay.cn/login"))
    assertFalse(isOfficialPaymentUrl("https://cashier.cc-pay.cn/cashier/extra"))
    assertTrue(isPaymentLoginUrl("https://sso.example.edu/cas/login?service=synthetic"))
    assertTrue(isPaymentLoginUrl("https://mall.cc-pay.cn/account/sign-in"))
    assertFalse(isPaymentLoginUrl("https://cashier.cc-pay.cn/cashier?order=synthetic-id"))
  }

  @Test
  fun `rejects untrusted and login URLs returned as payment pages`() = runTest {
    for (url in
        listOf(
            "http://cashier.cc-pay.cn/cashier?order=synthetic-id",
            "https://evil.example/cashier?order=synthetic-id",
            "https://sso.example.edu/cas/login?service=synthetic",
        )) {
      val controller = CampusRechargeController(FakeRechargeBackend().apply { pageUrl = url })
      controller.prepare(RechargeKind.Internet)
      controller.create("10")
      assertTrue(controller.state is CampusRechargeState.Failed)
    }
  }

  @Test
  fun `default backend stays unavailable without an order attempt`() = runTest {
    val controller = CampusRechargeController()

    controller.prepare(RechargeKind.Internet)

    val unavailable = controller.state as CampusRechargeState.Unavailable
    assertTrue(unavailable.reason.isNotBlank())
    controller.create("10")
    assertTrue(controller.state is CampusRechargeState.Unavailable)
  }

  @Test
  fun `redacts official payment page URL from string representation`() {
    val page = OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic-order-id")

    assertFalse(page.toString().contains("synthetic-order-id"))
  }

  @Test
  fun `zfw form prefers the fresh direct cookie over a stale gateway cookie`() = runTest {
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.clear()
    LocalCookieStore.settings = MapSettings()
    LocalCookieStore.clearAllScopes()
    LocalCookieStore.storage()
        .addCookie(
            Url("https://zfw.buaa.edu.cn/"),
            Cookie("PHPSESSID_8800", "stale", path = "/", secure = true, httpOnly = true),
        )
    LocalCookieStore.storage(app.bucc.api.ConnectionMode.DIRECT)
        .addCookie(
            Url("https://zfw.buaa.edu.cn/"),
            Cookie("PHPSESSID_8800", "fresh", path = "/", secure = true, httpOnly = true),
        )
    val cookies = paymentSessionCookies("https://zfw.buaa.edu.cn/pay/card", includeZfw = true)
    assertEquals(listOf("fresh"), cookies.map { it.value })
  }
}

private class FakeRechargeBackend : CampusRechargeBackend {
  val calls = mutableListOf<String>()
  var pageUrl = "https://cashier.cc-pay.cn/cashier?order=synthetic-order-id"
  var captchaImage: ByteArray? = null
  var createFailure: Throwable? = null
  var createStarted: CompletableDeferred<Unit>? = null
  var createGate: CompletableDeferred<Unit>? = null

  override suspend fun prepare(kind: RechargeKind): RechargePreparation {
    calls += "prepare:$kind"
    return RechargePreparation(available = true, captchaImage = captchaImage)
  }

  override suspend fun createOrder(
      kind: RechargeKind,
      amount: String,
      verificationCode: String?,
  ): OfficialPaymentPage {
    calls += "create:$amount:$verificationCode"
    createStarted?.complete(Unit)
    createGate?.await()
    createFailure?.let { throw it }
    return OfficialPaymentPage(pageUrl)
  }
}
