package app.bucc.ui.screens.finance

import app.bucc.api.finance.OfficialPaymentPage
import app.bucc.api.finance.PaymentSessionCookie
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MacPaymentSessionRecoveryTest {
  @Test
  fun `decoded campus target connects before copying cookies and opening the cashier`() = runTest {
    val initial = "https://mall.cc-pay.cn/entry/card/fixture"
    val connected = "https://cashier.cc-pay.cn/cashier?order=fixture"
    val calls = mutableListOf<String>()
    val cookie = PaymentSessionCookie("fixture", "fixture", "cashier.cc-pay.cn", "/", true, true)
    repeat(2) {
      val result =
          resolveMacPaymentTarget(
              initial,
              {
                assertEquals(initial, it)
                calls += "connect"
                OfficialPaymentPage(connected)
              },
              {
                assertEquals(connected, it)
                calls += "cookies"
                listOf(cookie)
              },
          )
      assertEquals(connected, result.first)
      assertEquals(listOf(cookie), result.second)
    }
    assertEquals(listOf("connect", "cookies", "connect", "cookies"), calls)
  }

  @Test
  fun `provider QR target never receives campus cookies or campus authentication`() = runTest {
    val target = "https://qr.alipay.com/fixture"
    val result =
        resolveMacPaymentTarget(
            target,
            { error("Provider must not request campus authentication") },
            { error("Provider must not receive campus cookies") },
        )
    assertEquals(target, result.first)
    assertTrue(result.second.isEmpty())
  }

  @Test
  fun `untrusted decoded target and invalid continuation stop before cookie transfer`() = runTest {
    var connected = false
    try {
      resolveMacPaymentTarget(
          "https://evil.example/cashier",
          {
            connected = true
            OfficialPaymentPage(it)
          },
          { error("No cookie transfer") },
      )
      fail("Untrusted target was accepted")
    } catch (_: IllegalStateException) {}
    assertFalse(connected)
    try {
      resolveMacPaymentTarget(
          "https://cashier.cc-pay.cn/cashier",
          { OfficialPaymentPage("https://evil.example/cashier") },
          { error("No cookie transfer") },
      )
      fail("Untrusted continuation was accepted")
    } catch (_: IllegalStateException) {}
  }
}
