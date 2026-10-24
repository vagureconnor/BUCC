package app.bucc.api.finance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentServiceNavigationTest {
  @Test
  fun `only official payment and constrained pass login services are accepted`() {
    listOf(
            "https://cashier.cc-pay.cn/cashier?order=mock-order",
            "https://mall.cc-pay.cn/entry/card/mock-product",
            "https://pass.cc-pay.cn/login",
            "https://pass.cc-pay.cn/login?backUrl=https%3A%2F%2Fmall.cc-pay.cn%2Fentry%2Fcard%2Fmock-product&channel=BUAASSO",
        )
        .forEach { assertTrue(isOfficialPaymentCasService(it), it) }

    listOf(
            "http://cashier.cc-pay.cn/cashier",
            "https://cashier.cc-pay.cn:444/cashier",
            "https://user@cashier.cc-pay.cn/cashier",
            "https://evil.example/login",
            "https://pass.cc-pay.cn/account",
            "https://pass.cc-pay.cn/login?backUrl=https%3A%2F%2Fevil.example%2F",
            "https://pass.cc-pay.cn/login?backUrl=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier&backUrl=https%3A%2F%2Fmall.cc-pay.cn%2Fcashier",
            "https://pass.cc-pay.cn/login?channel=OTHER",
            "https://pass.cc-pay.cn/login?channel=BUAASSO&channel=BUAASSO",
            "https://pass.cc-pay.cn/login?unexpected=value",
        )
        .forEach { assertFalse(isOfficialPaymentCasService(it), it) }
  }
}
