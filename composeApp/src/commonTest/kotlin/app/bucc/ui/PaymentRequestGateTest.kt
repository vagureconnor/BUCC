package app.bucc.ui

import app.bucc.ui.screens.finance.PaymentRequestGate
import kotlin.test.*

class PaymentRequestGateTest {
  @Test
  fun `valid native payment app schemes are routed externally`() {
    val gate = PaymentRequestGate(false)
    assertEquals(2, gate.decision("alipays://platformapi/startapp?appId=synthetic", "GET", false))
    assertEquals(2, gate.decision("weixin://wap/pay?synthetic=1", "GET", false))
    assertEquals(0, gate.decision("unknown-app://run", "GET", false))
  }

  @Test
  fun `official provider subpages and POSTs remain in the existing payment context`() {
    val gate = PaymentRequestGate(false)
    assertTrue(gate.allows("https://qr.alipay.com/synthetic", "GET", false))
    assertTrue(gate.allows("https://mapi.alipay.com/new-channel/entry", "POST", false))
    assertTrue(gate.allows("https://wx.tenpay.com/pay/continue", "GET", false))
    assertFalse(gate.allows("https://mapi.alipay.com.evil.test/new-channel/entry", "POST", false))
    assertFalse(gate.allows("http://mapi.alipay.com/new-channel/entry", "GET", false))
  }

  @Test
  fun `only the initial root order POST is allowed and children cannot consume it`() {
    val gate = PaymentRequestGate(allowZfw = true)
    val target = "https://zfw.buaa.edu.cn/pay/card"
    assertFalse(gate.allows(target, "POST", isRoot = false))
    assertTrue(gate.allows(target, "GET", isRoot = true))
    assertTrue(gate.allows(target, "POST", isRoot = true))
    assertFalse(gate.allows(target, "POST", isRoot = true))
    assertFalse(gate.allows(target, "POST", isRoot = false))
    assertTrue(
        gate.allows("https://cashier.cc-pay.cn/cashier?order=synthetic", "GET", isRoot = false)
    )
    assertFalse(gate.allows("https://unrelated.example/", "POST", isRoot = true))
  }
}
