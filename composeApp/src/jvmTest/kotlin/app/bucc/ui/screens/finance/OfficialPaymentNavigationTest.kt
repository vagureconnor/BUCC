package app.bucc.ui.screens.finance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficialPaymentNavigationTest {
  @Test
  fun `confirmed channel gateway pages can open but lookalikes and unrelated paths cannot`() {
    listOf(
            "https://openapi.alipay.com/gateway.do?synthetic=1",
            "https://mapi.alipay.com/gateway.do?synthetic=1",
            "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?synthetic=1",
            "https://gateway.95516.com/gateway/api/frontTransReq.do?synthetic=1",
        )
        .forEach { assertTrue(isAllowedPaymentNavigation(it), it) }
    listOf(
            "https://openapi.alipay.com.evil.test/gateway.do",
            "https://wx.tenpay.com/unrelated",
            "https://gateway.95516.com/unrelated",
            "http://mapi.alipay.com/gateway.do",
        )
        .forEach { assertFalse(isAllowedPaymentNavigation(it), it) }
  }

  @Test
  fun `allows HTTPS official payment hosts`() {
    assertTrue(isAllowedPaymentNavigation("https://cashier.cc-pay.cn/cashier?order=synthetic-id"))
    assertTrue(isAllowedPaymentNavigation("https://mall.cc-pay.cn/cashier"))
    assertTrue(isAllowedPaymentNavigation("https://cashier.cc-pay.cn:443/cashier"))
  }

  @Test
  fun `rejects non official hosts credentials and non HTTPS ports`() {
    assertFalse(isAllowedPaymentNavigation("https://evil.example/cashier"))
    assertFalse(isAllowedPaymentNavigation("https://cashier.cc-pay.cn@evil.example/cashier"))
    assertFalse(isAllowedPaymentNavigation("https://user@cashier.cc-pay.cn/cashier"))
    assertFalse(isAllowedPaymentNavigation("https://cashier.cc-pay.cn:8443/cashier"))
  }

  @Test
  fun `rejects sign in SSO and auth paths on official hosts`() {
    assertFalse(isAllowedPaymentNavigation("https://cashier.cc-pay.cn/signin"))
    assertFalse(isAllowedPaymentNavigation("https://mall.cc-pay.cn/sso/continue"))
    assertFalse(isAllowedPaymentNavigation("https://cashier.cc-pay.cn/auth/authorize"))
  }
}
