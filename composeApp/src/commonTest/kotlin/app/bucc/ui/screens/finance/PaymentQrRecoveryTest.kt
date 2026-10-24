package app.bucc.ui.screens.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PaymentQrRecoveryTest {
  @Test
  fun `gateway rewritten QR payload opens original official target`() {
    val previous = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    try {
      val target = "https://cashier.cc-pay.cn/cashier?order=synthetic"
      val wrapped =
          "https://d.buaa.edu.cn/https/77726476706e69737468656265737421f3f652942e357a1e7d0bc4bc994c6d36af/cashier?order=synthetic"
      val result =
          PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_PAY_CARD).decide(10, listOf(wrapped))
      assertTrue(result is PaymentQrRecoveryDecision.OpenOfficialPayment)
      assertEquals(target, result.url)
    } finally {
      app.bucc.api.ConnectionModeStore.settings = previous
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `back navigation skips QR responses as well as one shot POST responses`() {
    assertFalse(canReturnToPaymentHistory("https://zfw.buaa.edu.cn/qrcode", allowZfw = true))
    assertFalse(canReturnToPaymentHistory("https://zfw.buaa.edu.cn/pay/card", allowZfw = true))
    assertTrue(canReturnToPaymentHistory("https://cashier.cc-pay.cn/cashier"))
  }

  @Test
  fun `only a verified loaded payment destination is ready to reveal`() {
    assertEquals(PaymentRecoveryPhase.PROCESSING, paymentRecoveryPhase(null))
    assertEquals(
        PaymentRecoveryPhase.PROCESSING,
        paymentRecoveryPhase("https://zfw.buaa.edu.cn/qrcode?url=fixture"),
    )
    assertEquals(
        PaymentRecoveryPhase.OPENING,
        paymentRecoveryPhase("https://cashier.cc-pay.cn/cashier", navigating = true),
    )
    assertEquals(
        PaymentRecoveryPhase.READY,
        paymentRecoveryPhase("https://cashier.cc-pay.cn/cashier"),
    )
    assertEquals(
        PaymentRecoveryPhase.READY,
        paymentRecoveryPhase("https://mapi.alipay.com/new-channel/entry"),
    )
    assertEquals(
        PaymentRecoveryPhase.READY,
        paymentRecoveryPhase("https://cashier.cc-pay.cn/payment/menu"),
    )
    assertEquals(
        PaymentRecoveryPhase.FAILED,
        paymentRecoveryPhase("https://cashier.cc-pay.cn/cashier", failed = true),
    )
    assertEquals(
        PaymentRecoveryPhase.PROCESSING,
        paymentRecoveryPhase("https://cashier.cc-pay.cn.evil.test/cashier"),
    )
  }

  @Test
  fun `provider payload has the same bounded recovery rules on all platforms`() {
    val attempt = PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_QRCODE)
    assertIs<PaymentQrRecoveryDecision.OpenOfficialPayment>(
        attempt.decide(1, listOf("https://qr.alipay.com/synthetic-fixture"))
    )
    assertEquals(
        PaymentQrRecoveryDecision.AlreadyNavigated,
        attempt.decide(2, listOf("https://qr.alipay.com/synthetic-fixture")),
    )
  }

  @Test
  fun `a shorter page lifetime expires and retries cannot extend it`() {
    val attempt = PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_QRCODE)
    assertEquals(
        PaymentQrRecoveryDecision.Invalid,
        attempt.decide(100, emptyList(), pageRemainingMillis = 200),
    )
    assertEquals(
        PaymentQrRecoveryDecision.Expired,
        attempt.decide(
            301,
            listOf("https://cashier.cc-pay.cn/cashier?order=fixture"),
            pageRemainingMillis = 900_000,
        ),
    )
  }

  @Test
  fun `loads the pinned decoder and pixel scanner from local compose resources`() = runTest {
    val script = paymentQrDecodeScript()

    assertTrue(script.contains("webpackUniversalModuleDefinition"))
    assertTrue(script.contains("querySelectorAll(\"img, canvas\")"))
    assertTrue(script.contains("__BUCC_QR_CROSS_ORIGIN__"))
    assertTrue(script.contains("4 * 1024 * 1024"))
  }

  @Test
  fun `source helper accepts only current mode ZFW payment and QR pages`() {
    val previousSettings = app.bucc.api.ConnectionModeStore.settings
    val previousAuth = app.bucc.api.local.LocalAuthSessionStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.local.LocalAuthSessionStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    try {
      assertEquals(
          PaymentQrSource.ZFW_PAY_CARD,
          paymentQrSourceForCurrentUrl("https://zfw.buaa.edu.cn/pay/card"),
      )
      assertEquals(
          PaymentQrSource.ZFW_QRCODE,
          paymentQrSourceForCurrentUrl("https://zfw.buaa.edu.cn/qrcode"),
      )
      assertEquals(null, paymentQrSourceForCurrentUrl("https://zfw.buaa.edu.cn/home"))
      assertEquals(null, paymentQrSourceForCurrentUrl("https://evil.example/qrcode"))
      assertEquals(
          null,
          paymentQrSourceForCurrentUrl(
              "https://d.buaa.edu.cn/https/example/https/zfw.buaa.edu.cn/qrcode"
          ),
      )
      app.bucc.api.ConnectionModeStore.save(ConnectionMode.WEBVPN)
      ConnectionRuntime.resolveSelectedMode()
      assertEquals(
          PaymentQrSource.ZFW_QRCODE,
          paymentQrSourceForCurrentUrl("https://zfw.buaa.edu.cn/qrcode"),
      )
      val wrapped =
          "https://d.buaa.edu.cn/https/77726476706e69737468656265737421eaf156d225256951300d8db9d6562d/qrcode"
      assertEquals(PaymentQrSource.ZFW_QRCODE, paymentQrSourceForCurrentUrl(wrapped))
    } finally {
      app.bucc.api.ConnectionModeStore.settings = previousSettings
      app.bucc.api.local.LocalAuthSessionStore.settings = previousAuth
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `opens one supported official payload once within the submission window`() {
    val attempt =
        PaymentQrRecoveryAttempt(
            startedAtMonotonicMillis = 1_000L,
            source = PaymentQrSource.ZFW_PAY_CARD,
        )
    val decision =
        attempt.decide(
            nowMonotonicMillis = 2_000L,
            payloads = listOf("https://cashier.cc-pay.cn/cashier?order=fixture"),
        )

    assertIs<PaymentQrRecoveryDecision.OpenOfficialPayment>(decision)
    assertEquals("https://cashier.cc-pay.cn/cashier?order=fixture", decision.url)
    assertEquals(
        PaymentQrRecoveryDecision.AlreadyNavigated,
        attempt.decide(
            nowMonotonicMillis = 2_001L,
            payloads = listOf("https://cashier.cc-pay.cn/cashier?order=fixture"),
        ),
    )
  }

  @Test
  fun `expires after fifteen minutes from the submission start`() {
    val attempt =
        PaymentQrRecoveryAttempt(
            startedAtMonotonicMillis = 10_000L,
            source = PaymentQrSource.ZFW_QRCODE,
        )

    assertEquals(
        PaymentQrRecoveryDecision.Expired,
        attempt.decide(
            nowMonotonicMillis = 10_000L + PaymentQrRecoveryAttempt.MAX_AGE_MILLIS,
            payloads = listOf("https://cashier.cc-pay.cn/cashier?order=fixture"),
        ),
    )
  }

  @Test
  fun `distinguishes empty invalid unsupported and ambiguous results`() {
    val attempt =
        PaymentQrRecoveryAttempt(
            startedAtMonotonicMillis = 0L,
            source = PaymentQrSource.ZFW_PAY_CARD,
        )
    assertEquals(PaymentQrRecoveryDecision.Invalid, attempt.decide(1L, emptyList()))
    assertEquals(PaymentQrRecoveryDecision.Invalid, attempt.decide(1L, listOf("not a URL")))
    assertEquals(
        PaymentQrRecoveryDecision.Unsupported,
        attempt.decide(1L, listOf("https://evil.example/pay")),
    )
    assertEquals(
        PaymentQrRecoveryDecision.Ambiguous,
        attempt.decide(
            1L,
            listOf(
                "https://cashier.cc-pay.cn/cashier?order=a",
                "https://cashier.cc-pay.cn/cashier?order=b",
            ),
        ),
    )
  }

  @Test
  fun `deduplicates the same payload but never exposes payload in decision string`() {
    val payload = "https://cashier.cc-pay.cn/cashier?secret=fixture-token"
    val attempt =
        PaymentQrRecoveryAttempt(
            startedAtMonotonicMillis = 0L,
            source = PaymentQrSource.ZFW_PAY_CARD,
        )
    val decision = attempt.decide(1L, listOf(payload, payload))

    assertIs<PaymentQrRecoveryDecision.OpenOfficialPayment>(decision)
    assertTrue(decision.toString().contains("[redacted]"))
    assertFalse(decision.toString().contains("fixture-token"))
    assertFalse(attempt.toString().contains("fixture-token"))
  }

  @Test
  fun `invalid scheme and fragment are rejected as payment sources`() {
    assertEquals(null, paymentQrSourceForCurrentUrl("http://zfw.buaa.edu.cn/qrcode"))
    assertEquals(null, paymentQrSourceForCurrentUrl("https://zfw.buaa.edu.cn/qrcode#external"))
  }
}
