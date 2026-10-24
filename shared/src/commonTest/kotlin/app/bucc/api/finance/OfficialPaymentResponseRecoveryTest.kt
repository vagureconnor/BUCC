package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalWebVpnSupport
import com.russhwolf.settings.MapSettings
import io.ktor.http.encodeURLParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class OfficialPaymentResponseRecoveryTest {
  private val cashier = "https://cashier.cc-pay.cn/cashier?order=synthetic-order"
  private val mall = "https://mall.cc-pay.cn/entry/card/synthetic-product"

  private fun selectMode(mode: ConnectionMode) {
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(mode)
    ConnectionRuntime.resolveSelectedMode()
  }

  @Test
  fun `gateway rewritten QR targets unwrap only the selected official provider`() = runTest {
    selectMode(ConnectionMode.WEBVPN)
    try {
      val source = LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/pay/card")
      val target = LocalWebVpnSupport.toWebVpnUrl(cashier)
      assertEquals(cashier, officialPaymentFromBrowserCandidates(source, listOf(target)))
      assertEquals(
          cashier,
          officialPaymentFromBrowserCandidates(
              source,
              listOf("/qrcode?url=${target.encodeURLParameter()}"),
          ),
      )
      assertNull(
          officialPaymentFromBrowserCandidates(
              source,
              listOf(LocalWebVpnSupport.toWebVpnUrl("https://evil.test/pay")),
          )
      )
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `recovers verified provider payload from encoded QR response without reading pixels`() =
      runTest {
        selectMode(ConnectionMode.DIRECT)
        try {
          val provider = "https://qr.alipay.com/synthetic-fixture"
          val source = "https://zfw.buaa.edu.cn/pay/card"
          assertEquals(
              provider,
              officialPaymentFromBrowserCandidates(
                  source,
                  listOf("/qrcode?url=${provider.encodeURLParameter()}"),
              ),
          )
          assertNull(
              officialPaymentFromBrowserCandidates(
                  source,
                  listOf(
                      "/qrcode?url=${"https://qr.alipay.com.evil.test/fixture".encodeURLParameter()}"
                  ),
              )
          )
        } finally {
          ConnectionRuntime.clearSelectedMode()
        }
      }

  @Test
  fun `direct source recovers only the nested official destination from its qrcode image`() =
      runTest {
        selectMode(ConnectionMode.DIRECT)
        try {
          val image = "https://zfw.buaa.edu.cn/qrcode?url=${cashier.encodeURLParameter()}"
          assertEquals(
              cashier,
              officialPaymentFromBrowserCandidates(
                  "https://zfw.buaa.edu.cn/pay/card",
                  listOf(image),
              ),
          )
        } finally {
          ConnectionRuntime.clearSelectedMode()
        }
      }

  @Test
  fun `WebVPN source recovers destination from wrapped qrcode response candidate`() = runTest {
    selectMode(ConnectionMode.WEBVPN)
    try {
      val source = LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/pay/card")
      val wrappedImage =
          LocalWebVpnSupport.toWebVpnUrl(
              "https://zfw.buaa.edu.cn/qrcode?url=${cashier.encodeURLParameter()}",
          )
      assertEquals(cashier, officialPaymentFromBrowserCandidates(source, listOf(wrappedImage)))
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `encoded entities and repeated candidates resolve only one distinct destination`() = runTest {
    selectMode(ConnectionMode.DIRECT)
    try {
      val qrcode = "/qrcode?url=${cashier.encodeURLParameter()}&amp;view=mock"
      assertEquals(
          cashier,
          officialPaymentFromBrowserCandidates(
              "https://zfw.buaa.edu.cn/pay/card",
              listOf(cashier, qrcode, qrcode),
          ),
      )
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `encoded QR target takes priority over ordinary payment links`() = runTest {
    selectMode(ConnectionMode.DIRECT)
    try {
      assertEquals(
          cashier,
          officialPaymentFromBrowserCandidates(
              "https://zfw.buaa.edu.cn/pay/card",
              listOf(mall, "/qrcode?url=${cashier.encodeURLParameter()}"),
          ),
      )
      assertNull(
          officialPaymentFromBrowserCandidates(
              "https://zfw.buaa.edu.cn/pay/card",
              listOf(mall, "/qrcode?url=${"https://evil.test/pay".encodeURLParameter()}"),
          )
      )
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `multiple distinct allowed destinations remain ambiguous`() = runTest {
    selectMode(ConnectionMode.DIRECT)
    try {
      val cashierImage = "/qrcode?url=${cashier.encodeURLParameter()}"
      val mallImage = "/qrcode?url=${mall.encodeURLParameter()}"
      assertNull(
          officialPaymentFromBrowserCandidates(
              "https://zfw.buaa.edu.cn/pay/card",
              listOf(cashierImage, mallImage),
          ),
      )
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `rejects untrusted candidates and source URLs outside the official submission routes`() =
      runTest {
        selectMode(ConnectionMode.DIRECT)
        try {
          val valid = "/qrcode?url=${cashier.encodeURLParameter()}"
          val unsafe =
              listOf(
                  "https://evil.example/qrcode?url=${cashier.encodeURLParameter()}",
                  "https://user:secret@zfw.buaa.edu.cn/qrcode?url=${cashier.encodeURLParameter()}",
                  "http://zfw.buaa.edu.cn/qrcode?url=${cashier.encodeURLParameter()}",
                  "https://zfw.buaa.edu.cn:8443/qrcode?url=${cashier.encodeURLParameter()}",
                  "https://user:secret@cashier.cc-pay.cn/cashier",
              )
          unsafe.forEach { candidate ->
            assertNull(
                officialPaymentFromBrowserCandidates(
                    "https://zfw.buaa.edu.cn/pay/card",
                    listOf(candidate),
                )
            )
          }
          listOf(
                  "https://zfw.buaa.edu.cn/",
                  "https://zfw.buaa.edu.cn/home",
                  "https://zfw.buaa.edu.cn/pay/captcha",
                  "http://zfw.buaa.edu.cn/pay/card",
                  "https://zfw.buaa.edu.cn:8443/pay/card",
                  "https://user:secret@zfw.buaa.edu.cn/pay/card",
                  "https://evil.example/pay/card",
              )
              .forEach { source ->
                assertNull(officialPaymentFromBrowserCandidates(source, listOf(valid)))
              }
        } finally {
          ConnectionRuntime.clearSelectedMode()
        }
      }

  @Test
  fun `rejects response candidates that exceed count per candidate or aggregate limits`() =
      runTest {
        selectMode(ConnectionMode.DIRECT)
        try {
          val source = "https://zfw.buaa.edu.cn/pay/card"
          val valid = "/qrcode?url=${cashier.encodeURLParameter()}"
          assertNull(officialPaymentFromBrowserCandidates(source, List(65) { valid }))
          assertNull(
              officialPaymentFromBrowserCandidates(
                  source,
                  listOf("/qrcode?url=" + "x".repeat(8192)),
              )
          )
          assertNull(officialPaymentFromBrowserCandidates(source, List(9) { "x".repeat(8192) }))
        } finally {
          ConnectionRuntime.clearSelectedMode()
        }
      }

  @Test
  fun `source URL must match the active direct or WebVPN mode`() = runTest {
    val wrappedSource = LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/pay/card")
    val directSource = "https://zfw.buaa.edu.cn/pay/card"
    val candidate = "/qrcode?url=${cashier.encodeURLParameter()}"
    selectMode(ConnectionMode.DIRECT)
    try {
      assertNull(officialPaymentFromBrowserCandidates(wrappedSource, listOf(candidate)))
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
    selectMode(ConnectionMode.WEBVPN)
    try {
      // ZFW is direct in both modes; the direct source must still be scannable under WebVPN.
      assertNotNull(officialPaymentFromBrowserCandidates(directSource, listOf(candidate)))
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `zfw mobile payment page QR carrier resolves the official cashier`() {
    val cashier = "https://cashier.cc-pay.cn/cashier?id=f04493ce73e94759b33404ca07242654"
    val carrier = "/pay/qrcode?url=${cashier.encodeURLParameter()}"
    val page = "https://zfw.buaa.edu.cn/pay/card"
    assertEquals(
        listOf(cashier),
        officialPaymentTargetsFromBrowserCandidates(page, listOf(carrier)),
    )
    assertEquals(cashier, officialCashierFromResponse("<img src=\"$carrier\">"))
  }
}
