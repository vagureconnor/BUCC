package app.bucc.ui

import app.bucc.ui.screens.finance.PaymentWebNavigationPolicy
import app.bucc.ui.screens.finance.paymentAppLaunchTarget
import io.ktor.http.encodeURLParameter
import kotlin.test.*

class PaymentWebNavigationPolicyTest {
  @Test
  fun `card flow accepts official browser authentication and channel subpages`() {
    val policy = PaymentWebNavigationPolicy(false)
    val service = "https://pass.cc-pay.cn/login?backUrl=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier"
    listOf(
            service,
            "https://sso.buaa.edu.cn/login?service=${service.encodeURLParameter()}",
            "https://sso.buaa.edu.cn/login?service=${"https://mall.cc-pay.cn/entry/card/synthetic".encodeURLParameter()}",
            "https://pass.cc-pay.cn/login?ticket=synthetic-ticket",
            "https://pass.cc-pay.cn/signIn?backUrl=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Fid%3Dsynthetic",
            "https://cashier.cc-pay.cn/auth/continue",
            "https://mapi.alipay.com/new-channel/entry",
            "https://wx.tenpay.com/pay/continue",
            "https://gateway.95516.com/gateway/channel/menu",
        )
        .forEach { assertEquals(1, policy.decide(it, "GET", false), it) }
  }

  @Test
  fun `lookalikes unrelated SSO services unsafe origins and ports remain blocked`() {
    val policy = PaymentWebNavigationPolicy(true)
    listOf(
            "https://cashier.cc-pay.cn.evil.test/",
            "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fevil.test%2F",
            "https://pass.cc-pay.cn/login?backUrl=https%3A%2F%2Fevil.test%2F",
            "https://user@cashier.cc-pay.cn/",
            "https://wx.tenpay.com:444/pay",
            "http://cashier.cc-pay.cn/",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://evil.test/",
        )
        .forEach { assertEquals(0, policy.decide(it, "GET", false), it) }
  }

  @Test
  fun `native initial POST is submitted once while children cannot submit orders`() {
    val policy = PaymentWebNavigationPolicy(true)
    val url = "https://zfw.buaa.edu.cn/pay/card"
    assertEquals(0, policy.decide(url, "POST", false))
    assertEquals(1, policy.decide(url, "POST", true))
    assertEquals(0, policy.decide(url, "POST", true))
    assertEquals(0, PaymentWebNavigationPolicy(false).decide(url, "POST", true))
  }

  @Test
  fun `provider QR destinations stay HTTPS and share the same decoder policy`() {
    val target = "https://qr.alipay.com/synthetic"
    val native =
        app.bucc.ui.screens.finance.PaymentQrRecoveryAttempt(
            0,
            app.bucc.ui.screens.finance.PaymentQrSource.ZFW_PAY_CARD,
            { app.bucc.ui.screens.finance.isOfficialProviderPaymentUrl(it) },
        )
    assertIs<app.bucc.ui.screens.finance.PaymentQrRecoveryDecision.OpenOfficialPayment>(
        native.decide(1, listOf(target))
    )
    assertFalse(
        app.bucc.ui.screens.finance.isOfficialProviderPaymentUrl(
            "https://qr.alipay.com.evil.test/pay"
        )
    )
    assertFalse(
        app.bucc.ui.screens.finance.isOfficialProviderPaymentUrl("http://qr.alipay.com/pay")
    )
    assertIs<app.bucc.ui.screens.finance.PaymentQrRecoveryDecision.OpenOfficialPayment>(
        app.bucc.ui.screens.finance
            .PaymentQrRecoveryAttempt(0, app.bucc.ui.screens.finance.PaymentQrSource.ZFW_PAY_CARD)
            .decide(1, listOf(target))
    )
  }

  @Test
  fun `payment app protocols require a separate user confirmation decision`() {
    val policy = PaymentWebNavigationPolicy(false)
    assertEquals(2, policy.decide("alipays://platformapi/startapp?appId=synthetic", "GET", false))
    assertEquals(2, policy.decide("alipay://platformapi/startapp?appId=synthetic", "GET", false))
    assertEquals(2, policy.decide("weixin://wap/pay?synthetic=1", "GET", false))
    assertEquals(2, policy.decide("uppay://pay?synthetic=1", "GET", false))
    assertEquals(0, policy.decide("unknown-app://run", "GET", false))
    assertEquals(1, policy.decide("about:blank", "GET", false))
  }

  @Test
  fun `official Alipay wrappers and Android intent links are routed to the payment app`() {
    val policy = PaymentWebNavigationPolicy(false)
    val alipay =
        "alipays://platformapi/startapp?appId=synthetic&url=https%3A%2F%2Fcashier.example%2F"
    val wrappers =
        listOf(
            "https://ds.alipay.com/?scheme=${alipay.encodeURLParameter()}",
            "https://render.alipay.com/p/s/i/?scheme=${alipay.encodeURLParameter()}",
            "intent://platformapi/startapp?appId=synthetic#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;end",
        )

    wrappers.forEach { assertEquals(2, policy.decide(it, "GET", false), it) }
    assertEquals(2, policy.decide("https://qr.alipay.com/synthetic", "GET", false))
    assertEquals(alipay, paymentAppLaunchTarget(wrappers[0]))
    assertEquals(alipay, paymentAppLaunchTarget(wrappers[1]))
    assertEquals(
        "alipays://platformapi/startapp?appId=synthetic",
        paymentAppLaunchTarget(wrappers[2]),
    )
    assertEquals(
        "https://qr.alipay.com/synthetic",
        paymentAppLaunchTarget("https://qr.alipay.com/synthetic"),
    )
    assertNull(paymentAppLaunchTarget("https://ds.alipay.com/?scheme=https%3A%2F%2Fevil.example"))
    assertNull(
        paymentAppLaunchTarget(
            "https://ds.alipay.com.evil.example/?scheme=${"alipays://platformapi/startapp".encodeURLParameter()}"
        )
    )
    assertEquals(0, policy.decide(wrappers[0], "POST", false))
  }
}
