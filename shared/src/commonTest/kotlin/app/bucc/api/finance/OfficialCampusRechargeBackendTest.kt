package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalWebVpnSupport
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class OfficialCampusRechargeBackendTest {
  private val form =
      """
      <form action="/site/logout" method="post"><input name="ignored" value="mock"></form>
      <form method="post" action="/pay/card">
        <input value="mock-csrf" type="hidden" name="_csrf-mock">
        <input name="OneCardForm[cardNo]" value="mock-card">
        <select name="OneCardForm[productId]"><option value="mock-product" selected>模拟产品</option></select>
        <input name="OneCardForm[amount]"><input name="OneCardForm[verifyCode]">
        <img src="/pay/captcha?v=mock-image">
      </form>
      <script>jQuery('#captcha').yiiCaptcha({"refreshUrl":"/pay/captcha?refresh=1"});</script>
      """
          .trimIndent()

  @Test
  fun `official cashier URL is not tied to an invented id query parameter`() {
    val page = "https://cashier.cc-pay.cn/cashier?order=mock-order"
    assertEquals(page, officialCashierFromResponse("", page))
    assertEquals(
        page,
        officialCashierFromResponse(
            "<img src='/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dmock-order'>"
        ),
    )
  }

  @Test
  fun `documented QR wrapper resolves from relative and absolute Location without losing encoded query`() {
    val page = "https://cashier.cc-pay.cn/cashier?order=mock-order&signature=mock%2Bvalue"
    val wrapper = "/qrcode?url=" + page.encodeURLParameter()
    assertEquals(page, officialCashierFromResponse("", wrapper))
    assertEquals(page, officialCashierFromResponse("", "https://zfw.buaa.edu.cn$wrapper"))
    assertEquals(
        page,
        officialCashierFromResponse(
            "<a target='_blank' href='https://zfw.buaa.edu.cn$wrapper'>官方入口</a>"
        ),
    )
    assertEquals(
        "https://cashier.cc-pay.cn/cashier?order=mock&signature=mock",
        officialCashierFromResponse(
            "<iframe src='https://cashier.cc-pay.cn/cashier?order=mock&#38;signature=mock'></iframe>"
        ),
    )
  }

  @Test
  fun `wrapper rejects untrusted origin duplicate targets and malicious destinations`() {
    assertNull(
        officialCashierFromResponse(
            "",
            "https://evil.test/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier",
        )
    )
    assertNull(
        officialCashierFromResponse(
            "",
            "/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier&url=https%3A%2F%2Fevil.test",
        )
    )
    assertNull(
        officialCashierFromResponse(
            "",
            "/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn.evil.test%2Fcashier",
        )
    )
    assertNull(officialCashierFromResponse("", "/qrcode?url=javascript%3Aalert%281%29"))
    assertNull(
        officialCashierFromResponse("", "https://synthetic:secret@cashier.cc-pay.cn/cashier")
    )
  }

  @Test
  fun `internet order returns a one time encoded browser form without posting an order`() =
      runTest {
        var posts = 0
        fun newClient() =
            HttpClient(
                MockEngine { request ->
                  when (request.url.encodedPath) {
                    "/pay/card" ->
                        if (request.method == HttpMethod.Get) respond(form)
                        else {
                          posts++
                          respond(
                              "<img src=\"/pay/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dmock-zfw\">"
                          )
                        }
                    "/pay/captcha" ->
                        if (request.url.parameters["refresh"] == "1")
                            respond("{\"url\":\"/pay/captcha?v=mock\"}")
                        else
                            respond(
                                byteArrayOf(1),
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                    else -> error("Unexpected mock path: ${request.url}")
                  }
                }
            ) {
              followRedirects = false
            }
        val backend = OfficialCampusRechargeBackend({ newClient() }, { true })
        try {
          assertTrue(backend.prepare(RechargeKind.Internet).available)
          val page = backend.createOrder(RechargeKind.Internet, "10", "synthetic code+&")
          assertFalse(page.isFormSubmission)
          assertEquals("https://cashier.cc-pay.cn/cashier?order=mock-zfw", page.url)
          assertEquals(1, posts)
          assertFails { backend.createOrder(RechargeKind.Internet, "10", "synthetic-code") }
          assertEquals(1, posts)
        } finally {
          backend.close()
        }
      }

  @Test
  fun `fresh internet preparation allows a new manual form submission`() = runTest {
    var formReads = 0
    var posts = 0
    var captchaRefreshes = 0
    var captchaImages = 0
    fun newClient() =
        HttpClient(
            MockEngine { request ->
              when {
                request.url.encodedPath == "/pay/card" && request.method == HttpMethod.Get -> {
                  formReads++
                  respond(form.replace("mock-csrf", "mock-csrf-$formReads"))
                }
                request.url.encodedPath == "/pay/card" -> {
                  posts++
                  respond(
                      "<img src=\"/pay/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dmock-zfw\">"
                  )
                }
                request.url.encodedPath == "/pay/captcha" ->
                    if (request.url.parameters["refresh"] == "1") {
                      captchaRefreshes++
                      respond("{\"url\":\"/pay/captcha?v=mock-$captchaRefreshes\"}")
                    } else {
                      captchaImages++
                      respond(
                          byteArrayOf(captchaImages.toByte()),
                          headers = headersOf(HttpHeaders.ContentType, "image/png"),
                      )
                    }
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    val backend = OfficialCampusRechargeBackend({ newClient() }, { true })
    try {
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      val firstPage = backend.createOrder(RechargeKind.Internet, "1", "synthetic-code")
      // This explicit prepare represents the user's choice to start a fresh attempt after an
      // unknown result.
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      assertEquals(2, formReads)
      assertEquals(0, captchaRefreshes)
      assertEquals(2, captchaImages)
      val secondPage = backend.createOrder(RechargeKind.Internet, "2", "new-synthetic-code")
      assertFalse(firstPage.isFormSubmission)
      assertFalse(secondPage.isFormSubmission)
      assertEquals("https://cashier.cc-pay.cn/cashier?order=mock-zfw", firstPage.url)
      assertEquals("https://cashier.cc-pay.cn/cashier?order=mock-zfw", secondPage.url)
      assertEquals(2, posts)
      assertFails { backend.createOrder(RechargeKind.Internet, "3", "third-code") }
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      assertEquals(3, formReads)
      assertEquals(0, captchaRefreshes)
      assertEquals(3, captchaImages)
    } finally {
      backend.close()
    }
  }

  @Test
  fun `WebVPN preparation reads ZFW on its direct origin without the gateway`() = runTest {
    val previous = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val events = mutableListOf<String>()
    val hosts = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              hosts += request.url.host
              val upstream = Url(request.url.toString())
              when (upstream.encodedPath) {
                "/pay/card" -> respond(form)
                "/pay/captcha" ->
                    if (upstream.parameters["refresh"] == "1")
                        respond("{\"url\":\"/pay/captcha?v=mock\"}")
                    else
                        respond(
                            byteArrayOf(1),
                            headers = headersOf(HttpHeaders.ContentType, "image/png"),
                        )
                else -> error("Unexpected ZFW fixture path")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      val backend =
          OfficialCampusRechargeBackend(
              { client },
              { true },
              ensureSession = {
                events += "zfw"
                true
              },
              zfwClientFactory = { client },
          )
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      assertEquals(listOf("zfw"), events)
      assertTrue(hosts.all { it == "zfw.buaa.edu.cn" })
      backend.close()
    } finally {
      ConnectionModeStore.settings = previous
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `direct internet preparation verifies ZFW first and reads only direct endpoints`() = runTest {
    val previous = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    val events = mutableListOf<String>()
    var orderPosts = 0
    val client =
        HttpClient(
            MockEngine { request ->
              assertEquals("zfw.buaa.edu.cn", request.url.host)
              when {
                request.url.encodedPath == "/pay/card" && request.method == HttpMethod.Get -> {
                  events += "form"
                  respond(form)
                }
                request.url.encodedPath == "/pay/captcha" &&
                    request.url.parameters["refresh"] == "1" -> {
                  events += "captcha-refresh"
                  respond("{\"url\":\"/pay/captcha?v=mock-image\"}")
                }
                request.url.encodedPath == "/pay/captcha" -> {
                  events += "captcha-image"
                  respond(byteArrayOf(1), headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
                request.method == HttpMethod.Post -> {
                  orderPosts++
                  error("Preparation must not create an order")
                }
                else -> error("Unexpected direct preparation request: ${request.url}")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      val backend =
          OfficialCampusRechargeBackend(
              { client },
              { true },
              ensureSession = {
                events += "zfw-verification"
                true
              },
          )
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      assertEquals(listOf("zfw-verification", "form", "captcha-image"), events)
      assertEquals(0, orderPosts)
      backend.close()
    } finally {
      client.close()
      ConnectionModeStore.settings = previous
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `recharge preparation follows official form and captcha read redirects without posting`() =
      runTest {
        val previous = ConnectionModeStore.settings
        ConnectionModeStore.settings = MapSettings()
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        ConnectionRuntime.resolveSelectedMode()
        val paths = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                  assertEquals(HttpMethod.Get, request.method)
                  paths += request.url.toString()
                  when {
                    request.url.encodedPath == "/pay/card" &&
                        request.url.parameters["ready"] == null ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "/pay/card?ready=1"),
                        )
                    request.url.encodedPath == "/pay/card" -> respond(form)
                    request.url.parameters["refresh"] == "1" ->
                        respond("{\"url\":\"/pay/captcha?v=mock\"}")
                    request.url.parameters["ready"] == null ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "/pay/captcha?ready=1"),
                        )
                    else ->
                        respond(
                            byteArrayOf(1),
                            headers = headersOf(HttpHeaders.ContentType, "image/png"),
                        )
                  }
                }
            ) {
              followRedirects = false
            }
        val backend = OfficialCampusRechargeBackend({ client }, { true })
        try {
          assertTrue(backend.prepare(RechargeKind.Internet).available)
          assertTrue(paths.any { it.endsWith("/pay/card?ready=1") })
          assertTrue(paths.any { it.endsWith("/pay/captcha?ready=1") })
        } finally {
          backend.close()
          ConnectionModeStore.settings = previous
          ConnectionRuntime.resolveSelectedMode()
        }
      }

  @Test
  fun `WebVPN preparation never depends on a gateway ticket for ZFW`() = runTest {
    val previous = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    var requests = 0
    var zfwSessions = 0
    val client =
        HttpClient(
            MockEngine { request ->
              requests++
              val upstream = Url(request.url.toString())
              when (upstream.encodedPath) {
                "/pay/card" -> respond(form)
                "/pay/captcha" ->
                    if (upstream.parameters["refresh"] == "1")
                        respond("{\"url\":\"/pay/captcha?v=mock\"}")
                    else
                        respond(
                            byteArrayOf(1),
                            headers = headersOf(HttpHeaders.ContentType, "image/png"),
                        )
                else -> error("Unexpected ZFW fixture path")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      val backend =
          OfficialCampusRechargeBackend(
              { client },
              { true },
              ensureSession = {
                zfwSessions++
                true
              },
              zfwClientFactory = { client },
          )
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      assertEquals(1, zfwSessions)
      assertTrue(requests > 0)
      backend.close()
    } finally {
      ConnectionModeStore.settings = previous
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `internet submission does not contact the order endpoint`() = runTest {
    var posts = 0
    fun newClient() =
        HttpClient(
            MockEngine { request ->
              when {
                request.url.encodedPath == "/pay/card" && request.method == HttpMethod.Get ->
                    respond(form)
                request.url.encodedPath == "/pay/card" -> {
                  posts++
                  respond(
                      "<img src=\"/pay/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dmock-zfw\">"
                  )
                }
                request.url.encodedPath == "/pay/captcha" ->
                    if (request.url.parameters["refresh"] == "1")
                        respond("{\"url\":\"/pay/captcha?v=mock\"}")
                    else
                        respond(
                            byteArrayOf(1),
                            headers = headersOf(HttpHeaders.ContentType, "image/png"),
                        )
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    val backend = OfficialCampusRechargeBackend({ newClient() }, { true })
    try {
      assertTrue(backend.prepare(RechargeKind.Internet).available)
      val page = backend.createOrder(RechargeKind.Internet, "10", "synthetic-code")
      assertFalse(page.isFormSubmission)
      assertEquals("https://cashier.cc-pay.cn/cashier?order=mock-zfw", page.url)
      assertEquals(1, posts)
      assertFails { backend.createOrder(RechargeKind.Internet, "10", "synthetic-code") }
      assertEquals(1, posts)
    } finally {
      backend.close()
    }
  }

  @Test
  fun `official closed payment window is explicit and never creates an order`() = runTest {
    var posts = 0
    val client =
        HttpClient(
            MockEngine { request ->
              if (request.method == HttpMethod.Post) posts++
              respond("<html><p>不在缴费时间</p></html>")
            }
        ) {
          followRedirects = false
        }
    val backend = OfficialCampusRechargeBackend({ client }, { true })
    val prepared = backend.prepare(RechargeKind.Internet)
    assertFalse(prepared.available)
    assertEquals("当前不在官方缴费时间，请在开放时段重试。未创建订单。", prepared.reason)
    assertEquals(0, posts)
  }

  @Test
  fun `form preparation traces official fields instead of inventing identifiers`() {
    val parsed = assertNotNull(parseZfwRechargeForm(form))
    assertEquals("mock-csrf", parsed.fields["_csrf-mock"])
    assertEquals("mock-card", parsed.fields["OneCardForm[cardNo]"])
    assertEquals("mock-product", parsed.fields["OneCardForm[productId]"])
    assertEquals("https://zfw.buaa.edu.cn/pay/captcha?v=mock-image", parsed.captchaUrl)
    assertEquals("https://zfw.buaa.edu.cn/pay/captcha?refresh=1", parsed.captchaRefreshUrl)
    assertFalse(parsed.toString().contains("mock-card"))
    assertNull(parseZfwRechargeForm(form.replace("mock-card", "")))
    assertNull(parseZfwRechargeForm("<form action='/login'>登录</form>"))
  }

  @Test
  fun `recharge parser decodes relative WebVPN action and captcha paths`() {
    val wrappedAction =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/pay/card")
            .removePrefix("https://d.buaa.edu.cn")
    val wrappedCaptcha =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/pay/captcha?v=mock-vpn-image")
            .removePrefix("https://d.buaa.edu.cn")
    val wrappedRefresh =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/pay/captcha?refresh=1")
    val vpnForm =
        form
            .replace("action=\"/pay/card\"", "action=\"$wrappedAction\"")
            .replace("src=\"/pay/captcha?v=mock-image\"", "src=\"$wrappedCaptcha\"")
            .replace("/pay/captcha?refresh=1", wrappedRefresh)
    val parsed = assertNotNull(parseZfwRechargeForm(vpnForm))
    assertEquals("https://zfw.buaa.edu.cn/pay/captcha?v=mock-vpn-image", parsed.captchaUrl)
    assertEquals("https://zfw.buaa.edu.cn/pay/captcha?refresh=1", parsed.captchaRefreshUrl)
  }

  @Test
  fun `recharge parser rejects unsafe wrapped and direct action and captcha targets`() {
    val wrappedHttpAction = LocalWebVpnSupport.toWebVpnUrl("http://zfw.buaa.edu.cn/pay/card")
    val wrappedWrongPortAction =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn:8443/pay/card")
    val unsafeActions =
        listOf(
            "http://zfw.buaa.edu.cn/pay/card",
            "https://zfw.buaa.edu.cn:444/pay/card",
            "https://zfw.buaa.edu.cn.evil.example/pay/card",
            wrappedHttpAction,
            wrappedWrongPortAction,
            wrappedHttpAction.replace("https://d.buaa.edu.cn", "https://d.buaa.edu.cn:444"),
            "https://d.buaa.edu.cn/https/not-an-encrypted-host/pay/card",
            wrappedHttpAction.replace(
                "https://d.buaa.edu.cn",
                "https://d.buaa.edu.cn.evil.example",
            ),
        )
    unsafeActions.forEach { action ->
      assertNull(parseZfwRechargeForm(form.replace("action=\"/pay/card\"", "action=\"$action\"")))
    }
    val wrappedHttpCaptcha =
        LocalWebVpnSupport.toWebVpnUrl("http://zfw.buaa.edu.cn/pay/captcha?v=x")
    val wrappedWrongPortCaptcha =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn:8443/pay/captcha?v=x")
    val unsafeCaptchas =
        listOf(
            "http://zfw.buaa.edu.cn/pay/captcha?v=x",
            "https://zfw.buaa.edu.cn:444/pay/captcha?v=x",
            "https://evil.example/pay/captcha?v=x",
            wrappedHttpCaptcha,
            wrappedWrongPortCaptcha,
        )
    unsafeCaptchas.forEach { captcha ->
      assertNull(
          parseZfwRechargeForm(
              form.replace("src=\"/pay/captcha?v=mock-image\"", "src=\"$captcha\"")
          )
      )
    }
  }

  @Test
  fun `cashier gateway leads to official page rather than an entry QR image`() {
    assertEquals(
        "https://cashier.cc-pay.cn/cashier?id=mock-order",
        officialCashierFromResponse(
            "<img src='/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Fid%3Dmock-order'>"
        ),
    )
    assertNull(officialCashierFromResponse("<a href='https://example.test/cashier?id=mock'>付款</a>"))
    assertNull(officialCashierFromResponse("", "https://pass.cc-pay.cn/login"))
  }

  @Test
  fun `missing session and campus card exchange never create an order`() = runTest {
    var requests = 0
    val client =
        HttpClient(
            MockEngine {
              requests++
              error("No request expected")
            }
        )
    try {
      val backend = OfficialCampusRechargeBackend({ client }, { false })
      assertFalse(backend.prepare(RechargeKind.Internet).available)
      assertFalse(backend.prepare(RechargeKind.Card).available)
      assertEquals(0, requests)
    } finally {
      client.close()
    }
  }
}
