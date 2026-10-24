package app.bucc.ui

import app.bucc.ui.screens.finance.canReturnToPaymentHistory
import kotlin.test.*

class PaymentBackPolicyTest {
  @Test
  fun `return preserves GET navigation but never revisits an order submission`() {
    assertTrue(canReturnToPaymentHistory("https://cashier.cc-pay.cn/cashier?order=synthetic"))
    assertTrue(canReturnToPaymentHistory("https://mall.cc-pay.cn/entry/card/synthetic"))
    assertFalse(canReturnToPaymentHistory("https://zfw.buaa.edu.cn/pay/card", allowZfw = true))
    assertFalse(canReturnToPaymentHistory("https://evil.test/"))
    assertFalse(canReturnToPaymentHistory(null))
  }
}
