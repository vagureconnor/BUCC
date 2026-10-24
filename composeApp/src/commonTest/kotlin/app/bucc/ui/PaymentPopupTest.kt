package app.bucc.ui

import app.bucc.ui.screens.finance.officialPaymentSessionTarget
import app.bucc.ui.screens.finance.routePaymentPopup
import kotlin.test.*

class PaymentPopupTest {
  @Test
  fun `browser DOM candidates decode once or twice and reject unbounded content`() {
    val raw = "[\"https://zfw.buaa.edu.cn/qrcode?url=synthetic\"]"
    assertEquals(
        listOf("https://zfw.buaa.edu.cn/qrcode?url=synthetic"),
        app.bucc.ui.screens.finance.paymentResponseCandidates(raw),
    )
    val quoted =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(raw),
        )
    assertEquals(
        app.bucc.ui.screens.finance.paymentResponseCandidates(raw),
        app.bucc.ui.screens.finance.paymentResponseCandidates(quoted),
    )
    listOf("null", "{}", "[1]", "synthetic".repeat(20000)).forEach {
      assertTrue(app.bucc.ui.screens.finance.paymentResponseCandidates(it).isEmpty())
    }
  }

  @Test
  fun `VPN new window keeps the direct ZFW origin inside the internet flow`() {
    val settings = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    try {
      app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      val page =
          app.bucc.api.finance.OfficialPaymentPage.zfwFormSubmission(
              mapOf(
                  "_csrf-synthetic" to "synthetic",
                  "OneCardForm[cardNo]" to "synthetic",
                  "OneCardForm[productId]" to "synthetic",
                  "OneCardForm[amount]" to "1",
                  "OneCardForm[verifyCode]" to "synthetic",
              )
          )
      var loads = 0
      assertTrue(routePaymentPopup(page.url, allowZfw = true) { loads++ })
      assertFalse(routePaymentPopup(page.url) { loads++ })
      assertFalse(
          routePaymentPopup(page.url.replace("zfw.buaa.edu.cn", "evil.test"), allowZfw = true) {
            loads++
          }
      )
      assertFalse(
          routePaymentPopup(
              page.url.replace("zfw.buaa.edu.cn", "zfw.buaa.edu.cn:8443"),
              allowZfw = true,
          ) {
            loads++
          }
      )
      assertEquals(1, loads)
    } finally {
      app.bucc.api.ConnectionModeStore.settings = settings
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `official new window loads exactly once inside the existing browser`() {
    var loads = 0
    assertTrue(routePaymentPopup("https://cashier.cc-pay.cn/cashier?order=synthetic") { loads++ })
    assertEquals(1, loads)
  }

  @Test
  fun `untrusted and login popup destinations never load`() {
    var loads = 0
    listOf(
            "https://evil.test/cashier",
            "https://cashier.cc-pay.cn.evil.test/",
            "javascript:alert(1)",
            "https://pass.cc-pay.cn/login",
            "https://user:secret@cashier.cc-pay.cn/cashier",
        )
        .forEach { assertFalse(routePaymentPopup(it) { loads++ }) }
    assertEquals(0, loads)
  }

  @Test
  fun `internet flow admits official ZFW windows without widening card navigation`() {
    var loads = 0
    assertTrue(
        routePaymentPopup("https://zfw.buaa.edu.cn/qrcode?url=synthetic", allowZfw = true) {
          loads++
        }
    )
    assertEquals(1, loads)
    assertFalse(routePaymentPopup("https://zfw.buaa.edu.cn/qrcode?url=synthetic") { loads++ })
    assertFalse(
        routePaymentPopup("https://zfw.buaa.edu.cn.evil.test/qrcode", allowZfw = true) { loads++ }
    )
    assertFalse(routePaymentPopup("https://zfw.buaa.edu.cn/login", allowZfw = true) { loads++ })
    assertEquals(1, loads)
  }

  @Test
  fun `native session continuation accepts only one official backUrl from the payment login`() {
    val target = "https://cashier.cc-pay.cn/cashier?order=synthetic"
    assertEquals(
        target,
        officialPaymentSessionTarget(
            "https://pass.cc-pay.cn/login?backUrl=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dsynthetic"
        ),
    )
    assertEquals(
        target,
        officialPaymentSessionTarget(
            "https://pass.cc-pay.cn/signIn?backUrl=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dsynthetic"
        ),
    )
    assertNull(
        officialPaymentSessionTarget(
            "https://pass.cc-pay.cn/signIn?backUrl=https%3A%2F%2Fevil.test%2F"
        )
    )
    listOf(
            "https://evil.test/login?backUrl=$target",
            "https://pass.cc-pay.cn/login",
            "https://pass.cc-pay.cn/login?backUrl=https://evil.test/",
            "https://pass.cc-pay.cn/login?backUrl=$target&backUrl=$target",
            "http://pass.cc-pay.cn/login?backUrl=$target",
        )
        .forEach { assertNull(officialPaymentSessionTarget(it)) }
  }
}
