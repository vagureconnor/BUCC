package app.bucc.api.finance

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CampusCardRechargeBackendTest {
  @Test
  fun `official signIn redirect continues through the canonical BUAASSO login`() = runTest {
    val target = "https://cashier.cc-pay.cn/cashier?order=fixture"
    val signIn =
        "https://pass.cc-pay.cn/signIn?" +
            Parameters.build { append("backUrl", target) }.formUrlEncode()
    var authenticated = false
    var unifiedReads = 0
    val client =
        HttpClient(
            MockEngine { request ->
              assertEquals(HttpMethod.Get, request.method)
              when (request.url.host) {
                "cashier.cc-pay.cn" ->
                    if (authenticated) respond("<title>Fixture cashier</title>")
                    else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, signIn))
                "pass.cc-pay.cn" -> {
                  assertEquals("/login", request.url.encodedPath)
                  assertEquals(target, request.url.parameters["backUrl"])
                  if (request.url.parameters["channel"] == "BUAASSO") {
                    unifiedReads++
                    authenticated = true
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target))
                  } else respond("<a>统一认证登录</a>")
                }
                else -> error("Unexpected fixture request")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(target, connectOfficialPaymentPage(client, target).url)
      assertEquals(1, unifiedReads)
    } finally {
      client.close()
    }
  }

  @Test
  fun `cashier query-only redirect retains the official cashier path`() = runTest {
    val reads = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              reads += request.url.toString()
              if (request.url.parameters["step"] == null) {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "?step=ready"))
              } else respond("<title>Fixture cashier</title>")
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(
          "https://cashier.cc-pay.cn/cashier?step=ready",
          connectOfficialPaymentPage(client, "https://cashier.cc-pay.cn/cashier?order=fixture").url,
      )
      assertEquals(2, reads.size)
    } finally {
      client.close()
    }
  }

  private lateinit var previousModeSettings: com.russhwolf.settings.Settings
  private lateinit var previousSessionSettings: com.russhwolf.settings.Settings

  @BeforeTest
  fun isolateMode() {
    previousSessionSettings = app.bucc.api.local.LocalAuthSessionStore.settings
    app.bucc.api.local.LocalAuthSessionStore.settings = com.russhwolf.settings.MapSettings()
    previousModeSettings = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.DIRECT)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
  }

  @AfterTest
  fun restoreMode() {
    app.bucc.api.local.LocalAuthSessionStore.settings = previousSessionSettings
    app.bucc.api.ConnectionModeStore.settings = previousModeSettings
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
  }

  @Test
  fun `an already usable official cashier is opened without forcing a separate pass login`() =
      runTest {
        val target = "https://cashier.cc-pay.cn/cashier?order=fixture"
        val reads = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                  reads += request.url.toString()
                  if (request.url.host == "cashier.cc-pay.cn")
                      respond("<title>Fixture cashier</title>")
                  else respond("", HttpStatusCode.Forbidden)
                }
            ) {
              followRedirects = false
            }
        try {
          assertEquals(target, connectOfficialPaymentPage(client, target).url)
          assertEquals(listOf(target), reads)
        } finally {
          client.close()
        }
      }

  @Test
  fun `VPN keeps CAS and Hct API on the official gateway`() = runTest {
    val previous = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    var casReads = 0
    val apiPaths = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              val original =
                  Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
              when (original.encodedPath) {
                "/login" ->
                    if (original.host == "sso.buaa.edu.cn") {
                      casReads++
                      val identity =
                          original.parameters["service"]?.contains("m.icw.buaa.edu.cn") == true
                      assertEquals(
                          if (identity) "d.buaa.edu.cn" else "sso.buaa.edu.cn",
                          request.url.host,
                      )
                      respond(
                          "",
                          HttpStatusCode.Found,
                          headersOf(HttpHeaders.Location, identityUrl),
                      )
                    } else
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, paymentUrl),
                        )
                "/card/home/openHomePageApp" -> {
                  assertEquals("m.icw.buaa.edu.cn", request.url.host)
                  apiPaths += original.encodedPath
                  respond(
                      """{"success":true,"data":{"funcList":[{"id":"synthetic","functionname":"校园卡充值","linkurl":"/pages_other/recharge/cardrecharge/cardrecharge?id=synthetic"}]}}"""
                  )
                }
                "/card/queryPayInfoList" -> {
                  assertEquals("m.icw.buaa.edu.cn", request.url.host)
                  apiPaths += original.encodedPath
                  respond("""{"success":true,"data":{"data":[]}}""")
                }
                "/card/cardpay/openCardPay" -> {
                  assertEquals("m.icw.buaa.edu.cn", request.url.host)
                  apiPaths += original.encodedPath
                  respond("""{"success":true,"data":{"cardPay":{"idserial":"synthetic"}}}""")
                }
                "/card/recharge/rechargeByHct" -> {
                  assertEquals("m.icw.buaa.edu.cn", request.url.host)
                  apiPaths += original.encodedPath
                  respond("""{"success":true,"resultData":{"mweb_url":"$paymentUrl"}}""")
                }
                "/entry/card/mock-product" -> respond("<title>Official synthetic cashier</title>")
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    val backend =
        OfficialCampusCardRechargeBackend({ client }, { true }, prepareGatewaySession = {})
    try {
      assertTrue(backend.prepare(RechargeKind.Card).available)
      assertEquals(paymentUrl, backend.createOrder(RechargeKind.Card, "1", null).url)
      assertEquals(1, casReads)
      assertEquals(4, apiPaths.size)
    } finally {
      backend.close()
      app.bucc.api.ConnectionModeStore.settings = previous
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  private val identityUrl =
      "https://m.icw.buaa.edu.cn/card#/pages/homepage/index/index?openid=mock-identity&usertype=1"
  private val paymentUrl = "https://mall.cc-pay.cn/entry/card/mock-product?context=mock-context"

  @Test
  fun `campus card uses the authenticated business route in both modes`() = runTest {
    for ((mode, gatewaySession) in
        listOf(
            app.bucc.api.ConnectionMode.WEBVPN to true,
            app.bucc.api.ConnectionMode.DIRECT to false,
        )) {
      app.bucc.api.ConnectionModeStore.save(mode)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      app.bucc.api.local.LocalAuthSessionStore.save(
          app.bucc.api.local.LocalAuthSession(
              username = "mock-account",
              user = app.bucc.model.dto.UserData("Mock", "mock-account"),
              authenticatedAt = "2026-10-22T00:00:00Z",
              lastActivity = "2026-10-22T00:00:00Z",
          )
      )
      val cardHosts = mutableListOf<String>()
      var gatewayPreparationCount = 0
      var directFactoryCount = 0
      fun newClient() =
          HttpClient(
              MockEngine { request ->
                val original =
                    Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
                when (original.encodedPath) {
                  "/login" ->
                      respond(
                          "",
                          HttpStatusCode.Found,
                          headersOf(HttpHeaders.Location, identityUrl),
                      )
                  "/card/home/openHomePageApp" -> {
                    cardHosts += request.url.host
                    respond(
                        """{"success":true,"data":{"funcList":[{"id":"mock-menu","functionname":"校园卡充值","linkurl":"/pages_other/recharge/cardrecharge/cardrecharge?id=mock-menu"}]}}"""
                    )
                  }
                  "/card/queryPayInfoList" -> {
                    cardHosts += request.url.host
                    respond("""{"success":true,"data":{"data":[]}}""")
                  }
                  "/card/cardpay/openCardPay" -> {
                    cardHosts += request.url.host
                    respond("""{"success":true,"data":{"cardPay":{"idserial":"mock-card"}}}""")
                  }
                  else -> error("Unexpected synthetic request: ${request.url.encodedPath}")
                }
              }
          ) {
            followRedirects = false
          }

      val backend =
          OfficialCampusCardRechargeBackend(
              clientFactory = { newClient() },
              hasAppSession = { true },
              directClientFactory = {
                directFactoryCount++
                newClient()
              },
              prepareGatewaySession = { gatewayPreparationCount++ },
          )
      try {
        assertTrue(backend.prepare(RechargeKind.Card).available, "mode=$mode")
        assertEquals(
            listOf("m.icw.buaa.edu.cn", "m.icw.buaa.edu.cn", "m.icw.buaa.edu.cn"),
            cardHosts,
            "mode=$mode",
        )
        assertEquals(
            if (gatewaySession) 1 else 0,
            gatewayPreparationCount,
            "mode=$mode gateway=$gatewaySession",
        )
        assertEquals(1, directFactoryCount, "mode=$mode")
      } finally {
        backend.close()
      }
    }
  }

  @Test
  fun `malformed gateway catalog falls back once before any POST and keeps payment CAS on gateway`() =
      runTest {
        app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
        app.bucc.api.ConnectionRuntime.resolveSelectedMode()
        var gatewayCatalogReads = 0
        var gatewayCasReads = 0
        var paymentReads = 0
        var directCatalogReads = 0
        var directInitializationPosts = 0
        var directOrderPosts = 0
        var directFactories = 0
        fun gatewayClient() =
            HttpClient(
                MockEngine { request ->
                  val original =
                      Url(
                          app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(
                              request.url.toString()
                          )
                      )
                  when (original.host) {
                    "sso.buaa.edu.cn" -> {
                      gatewayCasReads++
                      val identity =
                          original.parameters["service"]?.contains("m.icw.buaa.edu.cn") == true
                      assertEquals(
                          if (identity) "d.buaa.edu.cn" else "sso.buaa.edu.cn",
                          request.url.host,
                      )
                      respond(
                          "",
                          HttpStatusCode.Found,
                          headersOf(
                              HttpHeaders.Location,
                              if (identity) identityUrl
                              else "https://pass.cc-pay.cn/login?ticket=mock-ticket",
                          ),
                      )
                    }
                    "m.icw.buaa.edu.cn" -> {
                      assertEquals(HttpMethod.Get, request.method)
                      assertEquals("/card/home/openHomePageApp", original.encodedPath)
                      gatewayCatalogReads++
                      respond(
                          "Synthetic upstream unavailable",
                          HttpStatusCode.OK,
                          headersOf(HttpHeaders.ContentType, "application/json"),
                      )
                    }
                    "pass.cc-pay.cn" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(
                                HttpHeaders.Location,
                                if (request.url.parameters["ticket"] != null) paymentUrl
                                else
                                    "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fpass.cc-pay.cn%2Flogin",
                            ),
                        )
                    "mall.cc-pay.cn" -> {
                      paymentReads++
                      if (paymentReads == 1)
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(
                                  HttpHeaders.Location,
                                  "https://pass.cc-pay.cn/login?backUrl=" +
                                      paymentUrl.encodeURLParameter(),
                              ),
                          )
                      else respond("<title>Synthetic official cashier</title>")
                    }
                    else -> error("Unexpected synthetic destination")
                  }
                }
            ) {
              followRedirects = false
            }
        val direct =
            HttpClient(
                MockEngine { request ->
                  assertEquals("m.icw.buaa.edu.cn", request.url.host)
                  when (request.url.encodedPath) {
                    "/card/home/openHomePageApp" -> {
                      directCatalogReads++
                      respond(syntheticCardMenus)
                    }
                    "/card/queryPayInfoList" -> respond("""{"success":true,"data":{"data":[]}}""")
                    "/card/cardpay/openCardPay" -> {
                      assertEquals(HttpMethod.Post, request.method)
                      directInitializationPosts++
                      respond("""{"success":true,"data":{"cardPay":{"idserial":"synthetic"}}}""")
                    }
                    "/card/recharge/rechargeByHct" -> {
                      assertEquals(HttpMethod.Post, request.method)
                      directOrderPosts++
                      respond("""{"success":true,"resultData":{"mweb_url":"$paymentUrl"}}""")
                    }
                    else -> error("Unexpected synthetic direct request")
                  }
                }
            ) {
              followRedirects = false
            }
        val backend =
            OfficialCampusCardRechargeBackend(
                { gatewayClient() },
                { true },
                {
                  directFactories++
                  direct
                },
                prepareGatewaySession = {},
                financeClientFactory = { gatewayClient() },
            )
        try {
          assertTrue(backend.prepare(RechargeKind.Card).available)
          assertEquals(0, directOrderPosts)
          assertEquals(paymentUrl, backend.createOrder(RechargeKind.Card, "1", null).url)
          assertEquals(1, gatewayCatalogReads)
          assertEquals(1, directFactories)
          assertEquals(1, directCatalogReads)
          assertEquals(1, directInitializationPosts)
          assertEquals(1, directOrderPosts)
          assertEquals(2, gatewayCasReads)
        } finally {
          backend.close()
        }
      }

  @Test
  fun `gateway order POST failure never switches route or replays the order`() = runTest {
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    var orderPosts = 0
    var directFactories = 0
    fun gatewayClient() =
        HttpClient(
            MockEngine { request ->
              val original =
                  Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
              when (original.encodedPath) {
                "/login" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, identityUrl))
                "/card/home/openHomePageApp" -> respond(syntheticCardMenus)
                "/card/queryPayInfoList" -> respond("""{"success":true,"data":{"data":[]}}""")
                "/card/cardpay/openCardPay" ->
                    respond("""{"success":true,"data":{"cardPay":{"idserial":"synthetic"}}}""")
                "/card/recharge/rechargeByHct" -> {
                  orderPosts++
                  throw io.ktor.client.network.sockets.ConnectTimeoutException(
                      "Synthetic uncertain order"
                  )
                }
                else -> error("Unexpected synthetic gateway request")
              }
            }
        ) {
          followRedirects = false
        }
    val backend =
        OfficialCampusCardRechargeBackend(
            { gatewayClient() },
            { true },
            {
              directFactories++
              error("No order fallback permitted")
            },
            prepareGatewaySession = {},
            financeClientFactory = { gatewayClient() },
        )
    try {
      assertTrue(backend.prepare(RechargeKind.Card).available)
      assertFails { backend.createOrder(RechargeKind.Card, "1", null) }
      assertFails { backend.createOrder(RechargeKind.Card, "1", null) }
      assertEquals(1, orderPosts)
      assertEquals(0, directFactories)
    } finally {
      backend.close()
    }
  }

  @Test
  fun `explicit official rejection is classified and never opens a payment page`() = runTest {
    val counts = mutableMapOf("orders" to 0, "paymentPages" to 0)
    val backend =
        backendForOrderResponse(
            """{"success":false,"resultData":{"mweb_url":"$paymentUrl"}}""",
            counts,
        )
    try {
      assertTrue(backend.prepare(RechargeKind.Card).available)
      val failure =
          assertFailsWith<RechargeFlowException> {
            backend.createOrder(RechargeKind.Card, "1", null)
          }
      assertEquals(RechargeFlowStage.REJECTED, failure.stage)
      assertEquals(1, counts.getValue("orders"))
      assertEquals(0, counts.getValue("paymentPages"))
    } finally {
      backend.close()
    }
  }

  @Test
  fun `successful order without an official payment address is classified`() = runTest {
    val counts = mutableMapOf("orders" to 0, "paymentPages" to 0)
    val backend = backendForOrderResponse("""{"success":true,"resultData":{}}""", counts)
    try {
      assertTrue(backend.prepare(RechargeKind.Card).available)
      val failure =
          assertFailsWith<RechargeFlowException> {
            backend.createOrder(RechargeKind.Card, "1", null)
          }
      assertEquals(RechargeFlowStage.RESOLVE_PAYMENT_PAGE, failure.stage)
      assertEquals(1, counts.getValue("orders"))
      assertEquals(0, counts.getValue("paymentPages"))
    } finally {
      backend.close()
    }
  }

  private fun backendForOrderResponse(
      orderResponse: String,
      counts: MutableMap<String, Int>,
  ) =
      OfficialCampusCardRechargeBackend(
          {
            HttpClient(
                MockEngine { request ->
                  when (request.url.encodedPath) {
                    "/login" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, identityUrl),
                        )
                    "/card/home/openHomePageApp" -> respond(syntheticCardMenus)
                    "/card/queryPayInfoList" -> respond("""{"success":true,"data":{"data":[]}}""")
                    "/card/cardpay/openCardPay" ->
                        respond("""{"success":true,"data":{"cardPay":{"idserial":"synthetic"}}}""")
                    "/card/recharge/rechargeByHct" -> {
                      counts["orders"] = counts.getValue("orders") + 1
                      respond(orderResponse)
                    }
                    "/entry/card/mock-product" -> {
                      counts["paymentPages"] = counts.getValue("paymentPages") + 1
                      respond("<title>Official synthetic cashier</title>")
                    }
                    else -> error("Unexpected synthetic request: ${request.url.encodedPath}")
                  }
                }
            ) {
              followRedirects = false
            }
          },
          { true },
      )

  @Test
  fun `gateway payment method GET failure does not trigger catalog fallback`() = runTest {
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    var directFactories = 0
    var initializationPosts = 0
    fun gatewayClient() =
        HttpClient(
            MockEngine { request ->
              val original =
                  Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
              when (original.encodedPath) {
                "/login" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, identityUrl))
                "/card/home/openHomePageApp" -> respond(syntheticCardMenus)
                "/card/queryPayInfoList" -> respond("Synthetic outage", HttpStatusCode.BadGateway)
                else -> {
                  initializationPosts++
                  error("No initialization permitted")
                }
              }
            }
        ) {
          followRedirects = false
        }
    val backend =
        OfficialCampusCardRechargeBackend(
            { gatewayClient() },
            { true },
            {
              directFactories++
              error("No late fallback permitted")
            },
            prepareGatewaySession = {},
            financeClientFactory = { gatewayClient() },
        )
    try {
      assertFalse(backend.prepare(RechargeKind.Card).available)
      assertEquals(0, directFactories)
      assertEquals(0, initializationPosts)
    } finally {
      backend.close()
    }
  }

  private val syntheticCardMenus =
      """{"success":true,"data":{"funcList":[{"id":"synthetic","functionname":"校园卡充值","linkurl":"/pages_other/recharge/cardrecharge/cardrecharge?id=synthetic"}]}}"""

  @Test
  fun `card catalog uses dedicated client and network failure stops without card initialization`() =
      runTest {
        var catalogReads = 0
        var cardPosts = 0
        val cas =
            HttpClient(
                MockEngine { request ->
                  assertEquals("sso.buaa.edu.cn", request.url.host)
                  respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, identityUrl))
                }
            ) {
              followRedirects = false
            }
        val direct =
            HttpClient(
                MockEngine { request ->
                  assertEquals("m.icw.buaa.edu.cn", request.url.host)
                  if (request.method == HttpMethod.Post) cardPosts++ else catalogReads++
                  throw io.ktor.client.network.sockets.ConnectTimeoutException(
                      "synthetic catalog outage"
                  )
                }
            ) {
              followRedirects = false
            }
        val backend = OfficialCampusCardRechargeBackend({ cas }, { true }, { direct })
        try {
          val prepared = backend.prepare(RechargeKind.Card)
          assertFalse(prepared.available)
          assertTrue(prepared.reason.orEmpty().contains("校园卡服务连接失败"))
          assertEquals(2, catalogReads)
          assertEquals(0, cardPosts)
          assertFails { backend.createOrder(RechargeKind.Card, "1", null) }
        } finally {
          backend.close()
        }
      }

  @Ignore(
      "Legacy split-client route; production now keeps one direct card client and is covered by live probes."
  )
  @Test
  fun `verified campus card page allows fresh context and a second order`() = runTest {
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.DIRECT)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    var orderCount = 0
    var initCount = 0
    fun newClient() =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/login" -> {
                  if (request.url.host == "pass.cc-pay.cn")
                      return@MockEngine respond(
                          "",
                          HttpStatusCode.Found,
                          headersOf(HttpHeaders.Location, paymentUrl),
                      )
                  assertEquals(HttpMethod.Get, request.method)
                  assertEquals(
                      "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth",
                      request.url.parameters["service"],
                  )
                  respond(
                      "",
                      HttpStatusCode.Found,
                      headersOf(
                          HttpHeaders.Location,
                          "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth?ticket=mock-ticket",
                      ),
                  )
                }
                "/card/home/sisUstbMiniReturnPage/112233/1/auth" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, identityUrl))
                "/card/home/openHomePageApp" -> {
                  val payload =
                      CampusCardCodec.decode(
                          buildJsonObject { put("datajson", request.url.parameters["datajson"]!!) }
                              .toString()
                      )
                  assertEquals("mock-identity", payload["openid"]?.jsonPrimitive?.content)
                  assertEquals("uniapp", request.headers["session-type"])
                  respond(
                      """{"success":true,"data":{"funcList":[{"id":"mock-home-menu","functionname":"充值","linkurl":"/pages_other/recharge/cardrecharge/cardrecharge?id=mock-home-menu"},{"id":"mock-menu","functionname":"校园卡充值","linkurl":"/pages_other/recharge/cardrecharge/cardrecharge?id=mock-menu"}]}}"""
                  )
                }
                "/card/queryPayInfoList" -> {
                  val payload =
                      CampusCardCodec.decode(
                          buildJsonObject { put("datajson", request.url.parameters["datajson"]!!) }
                              .toString()
                      )
                  assertEquals("mock-menu", payload["menuid"]?.jsonPrimitive?.content)
                  respond("""{"success":true,"data":{"data":[]},"message":""}""")
                }
                "/card/cardpay/openCardPay" -> {
                  initCount++
                  assertEquals(HttpMethod.Post, request.method)
                  val payload =
                      CampusCardCodec.decode(
                          (request.body as OutgoingContent.ByteArrayContent)
                              .bytes()
                              .decodeToString()
                      )
                  assertEquals(setOf("openid"), payload.keys)
                  respond("""{"success":true,"data":{"cardPay":{"idserial":"mock-card"}}}""")
                }
                "/card/recharge/rechargeByHct" -> {
                  orderCount++
                  val payload =
                      CampusCardCodec.decode(
                          (request.body as OutgoingContent.ByteArrayContent)
                              .bytes()
                              .decodeToString()
                      )
                  assertEquals("mock-identity", request.url.parameters["openid"])
                  assertEquals("mock-card", payload["idserial"]?.jsonPrimitive?.content)
                  assertEquals("1230", payload["txamt"]?.jsonPrimitive?.content)
                  assertEquals("10", payload["payWay"]?.jsonPrimitive?.content)
                  assertEquals("10", payload["payway"]?.jsonPrimitive?.content)
                  assertEquals("WAP", payload["tradetype"]?.jsonPrimitive?.content)
                  assertEquals("1", payload["usertype"]?.jsonPrimitive?.content)
                  assertEquals("2", payload["orgid"]?.jsonPrimitive?.content)
                  respond("""{"success":true,"resultData":{"mweb_url":"$paymentUrl"}}""")
                }
                "/entry/card/mock-product" -> respond("<title>官方充值收银台</title>")
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    val backend =
        OfficialCampusRechargeBackend(
            clientFactory = { newClient() },
            hasAppSession = { true },
            ensureSession = { true },
            cardClientFactory = { newClient() },
            zfwClientFactory = { newClient() },
            refreshCasOnPrepare = false,
            directCardOnPrepare = false,
        )
    try {
      val prepared = backend.prepare(RechargeKind.Card)
      assertTrue(prepared.available, prepared.reason)
      assertEquals(0, orderCount)
      assertEquals(1, initCount)
      val firstPage = backend.createOrder(RechargeKind.Card, "12.30", null)
      assertEquals(paymentUrl, firstPage.url)

      // A new preparation is an explicit user action after the previous result became unknown.
      assertTrue(backend.prepare(RechargeKind.Card).available)
      assertEquals(2, initCount)
      assertEquals(paymentUrl, backend.createOrder(RechargeKind.Card, "12.30", null).url)
      assertEquals(2, orderCount)
      assertFails { backend.createOrder(RechargeKind.Card, "12.30", null) }
    } finally {
      backend.close()
    }
  }

  @Test
  fun `campus card expired CAS never initializes card or creates order`() = runTest {
    var requests = 0
    val client =
        HttpClient(
            MockEngine {
              requests++
              respond("<input type='password'>")
            }
        ) {
          followRedirects = false
        }
    val backend =
        OfficialCampusRechargeBackend(
            clientFactory = { client },
            hasAppSession = { true },
            ensureSession = { true },
            cardClientFactory = { client },
            zfwClientFactory = { client },
            refreshCasOnPrepare = false,
            directCardOnPrepare = false,
        )
    try {
      val result = backend.prepare(RechargeKind.Card)
      assertFalse(result.available)
      assertTrue(result.reason.orEmpty().contains("主账号"))
      assertEquals(1, requests)
    } finally {
      backend.close()
    }
  }

  @Test
  fun `closed period missing methods and explicitly unsupported methods stop before card initialization`() =
      runTest {
        for (methods in
            listOf(
                """{"success":true,"data":{"data":[]},"message":"当前不在缴费期间"}""",
                """{"success":true,"data":{}}""",
                """{"success":true,"data":{"data":[{"payway":"6"}]}}""",
            )) {
          var initialized = false
          val client =
              HttpClient(
                  MockEngine { request ->
                    when (request.url.encodedPath) {
                      "/login" ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(HttpHeaders.Location, identityUrl),
                          )
                      "/card/home/openHomePageApp" ->
                          respond(
                              """{"success":true,"data":{"funcList":[{"id":"mock-menu","linkurl":"/pages_other/recharge/cardrecharge/cardrecharge?id=mock-menu"}]}}"""
                          )
                      "/card/queryPayInfoList" -> respond(methods)
                      else -> {
                        initialized = true
                        error("Unexpected initialization or mutation")
                      }
                    }
                  }
              ) {
                followRedirects = false
              }
          val backend = OfficialCampusRechargeBackend({ client }, { true })
          try {
            assertFalse(backend.prepare(RechargeKind.Card).available)
            assertFalse(initialized)
          } finally {
            backend.close()
          }
        }
      }

  @Test
  fun `official product entry is allowed but unrelated mall routes are rejected`() {
    assertTrue(isOfficialPaymentUrl(paymentUrl))
    assertFalse(isOfficialPaymentUrl("https://mall.cc-pay.cn/entry/card/"))
    assertFalse(isOfficialPaymentUrl("https://mall.cc-pay.cn/account/profile"))
    assertFalse(isOfficialPaymentUrl("https://mall.cc-pay.cn/entry/card/../login"))
    assertFalse(isOfficialPaymentUrl("https://mall.cc-pay.cn/entry/card/mock-product/extra"))
  }

  @Test
  fun `official entry follows its redirect and rejects a separate login destination`() = runTest {
    var requests = 0
    var separateLogin = false
    val requestedHosts = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              requests++
              requestedHosts += request.url.host
              if (request.url.host == "mall.cc-pay.cn")
                  respond(
                      "",
                      HttpStatusCode.Found,
                      headersOf(
                          HttpHeaders.Location,
                          if (separateLogin) "https://pass.cc-pay.cn/login"
                          else "https://cashier.cc-pay.cn/cashier?id=mock-order",
                      ),
                  )
              else if (request.url.host == "cashier.cc-pay.cn") respond("<title>官方收银台</title>")
              else error("Separate pass login must be rejected without a request")
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(
          "https://cashier.cc-pay.cn/cashier?id=mock-order",
          connectOfficialPaymentPage(client, paymentUrl).url,
      )
      assertEquals(2, requests)
      assertEquals(listOf("mall.cc-pay.cn", "cashier.cc-pay.cn"), requestedHosts)
      separateLogin = true
      val failure = runCatching { connectOfficialPaymentPage(client, paymentUrl) }.exceptionOrNull()
      assertEquals(
          RechargeFlowStage.CONNECT_PAYMENT_SESSION,
          (failure as RechargeFlowException).stage,
      )
      assertEquals(3, requests)
      assertEquals(listOf("mall.cc-pay.cn", "cashier.cc-pay.cn", "mall.cc-pay.cn"), requestedHosts)
    } finally {
      client.close()
    }
  }

  @Test
  fun `official payment session follows BUAASSO without entering separate credentials`() = runTest {
    val login =
        "https://pass.cc-pay.cn/login?" +
            Parameters.build { append("backUrl", paymentUrl) }.formUrlEncode()
    var ordinaryLoginReads = 0
    var unifiedLoginReads = 0
    var casReads = 0
    var callbackReads = 0
    var firstHost: String? = null
    val requestedHosts = mutableListOf<String>()
    val cashier = "https://cashier.cc-pay.cn/cashier?id=mock-order"
    val client =
        HttpClient(
            MockEngine { request ->
              if (firstHost == null) firstHost = request.url.host
              requestedHosts += request.url.host
              assertEquals(HttpMethod.Get, request.method)
              when (request.url.host) {
                "mall.cc-pay.cn" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, login))
                "pass.cc-pay.cn" ->
                    when {
                      request.url.parameters["ticket"] != null -> {
                        callbackReads++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, cashier))
                      }
                      request.url.parameters["channel"] == "BUAASSO" -> {
                        unifiedLoginReads++
                        assertEquals(paymentUrl, request.url.parameters["backUrl"])
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(
                                HttpHeaders.Location,
                                "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fpass.cc-pay.cn%2Flogin",
                            ),
                        )
                      }
                      else -> {
                        ordinaryLoginReads++
                        respond("<input type='password'><a>统一认证登录</a>")
                      }
                    }
                "sso.buaa.edu.cn" -> {
                  casReads++
                  respond(
                      "",
                      HttpStatusCode.Found,
                      headersOf(
                          HttpHeaders.Location,
                          "https://pass.cc-pay.cn/login?ticket=mock-ticket",
                      ),
                  )
                }
                "cashier.cc-pay.cn" -> respond("<h1>该订单已关闭。</h1>")
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(cashier, connectOfficialPaymentPage(client, paymentUrl).url)
      assertEquals("mall.cc-pay.cn", firstHost, "Start from the official payment URL")
      assertEquals(
          listOf(1, 1, 1, 1),
          listOf(ordinaryLoginReads, unifiedLoginReads, casReads, callbackReads),
      )
      assertEquals(
          listOf(
              "mall.cc-pay.cn",
              "pass.cc-pay.cn",
              "pass.cc-pay.cn",
              "sso.buaa.edu.cn",
              "pass.cc-pay.cn",
              "cashier.cc-pay.cn",
          ),
          requestedHosts,
      )
    } finally {
      client.close()
    }
  }

  @Test
  fun `official CAS service can return directly to an official payment page`() = runTest {
    val requestedHosts = mutableListOf<String>()
    val login =
        "https://pass.cc-pay.cn/login?" +
            Parameters.build { append("backUrl", paymentUrl) }.formUrlEncode()
    val client =
        HttpClient(
            MockEngine { request ->
              requestedHosts += request.url.host
              when (request.url.host) {
                "mall.cc-pay.cn" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, login))
                "pass.cc-pay.cn" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://sso.buaa.edu.cn/login?service=${paymentUrl.encodeURLParameter()}",
                        ),
                    )
                "sso.buaa.edu.cn" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://cashier.cc-pay.cn/cashier?id=mock-order",
                        ),
                    )
                "cashier.cc-pay.cn" -> respond("<h1>合成官方收银台</h1>")
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(
          "https://cashier.cc-pay.cn/cashier?id=mock-order",
          connectOfficialPaymentPage(client, paymentUrl).url,
      )
      assertEquals(
          listOf("mall.cc-pay.cn", "pass.cc-pay.cn", "sso.buaa.edu.cn", "cashier.cc-pay.cn"),
          requestedHosts,
      )
    } finally {
      client.close()
    }
  }

  @Test
  fun `official CAS service can complete the BUAASSO ticket callback`() = runTest {
    val login =
        "https://pass.cc-pay.cn/login?" +
            Parameters.build { append("backUrl", paymentUrl) }.formUrlEncode()
    val requestedHosts = mutableListOf<String>()
    var callbackReads = 0
    val client =
        HttpClient(
            MockEngine { request ->
              requestedHosts += request.url.host
              when (request.url.host) {
                "mall.cc-pay.cn" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, login))
                "pass.cc-pay.cn" ->
                    when {
                      request.url.parameters["ticket"] != null -> {
                        callbackReads++
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(
                                HttpHeaders.Location,
                                "https://cashier.cc-pay.cn/cashier?id=mock-order",
                            ),
                        )
                      }
                      request.url.parameters["channel"] == "BUAASSO" ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(
                                  HttpHeaders.Location,
                                  "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fpass.cc-pay.cn%2Flogin%3FbackUrl%3Dhttps%253A%252F%252Fmall.cc-pay.cn%252Fentry%252Fcard%252Fmock-product%26channel%3DBUAASSO",
                              ),
                          )
                      else -> respond("<p>统一认证</p>")
                    }
                "sso.buaa.edu.cn" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://pass.cc-pay.cn/login?ticket=mock-ticket",
                        ),
                    )
                "cashier.cc-pay.cn" -> respond("<h1>合成官方收银台</h1>")
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(
          "https://cashier.cc-pay.cn/cashier?id=mock-order",
          connectOfficialPaymentPage(client, paymentUrl).url,
      )
      assertEquals(1, callbackReads)
      assertEquals(
          listOf(
              "mall.cc-pay.cn",
              "pass.cc-pay.cn",
              "pass.cc-pay.cn",
              "sso.buaa.edu.cn",
              "pass.cc-pay.cn",
              "cashier.cc-pay.cn",
          ),
          requestedHosts,
      )
    } finally {
      client.close()
    }
  }

  @Test
  fun `untrusted CAS service is rejected before any request to its host`() = runTest {
    var evilRequests = 0
    val requestedHosts = mutableListOf<String>()
    val login =
        "https://pass.cc-pay.cn/login?" +
            Parameters.build { append("backUrl", paymentUrl) }.formUrlEncode()
    val client =
        HttpClient(
            MockEngine { request ->
              requestedHosts += request.url.host
              when (request.url.host) {
                "pass.cc-pay.cn" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fevil.example%2Flogin",
                        ),
                    )
                "mall.cc-pay.cn" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, login))
                "sso.buaa.edu.cn" ->
                    error("Untrusted CAS service must be rejected before SSO is requested")
                "evil.example" -> {
                  evilRequests++
                  error("Untrusted host must never be requested")
                }
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertTrue(runCatching { connectOfficialPaymentPage(client, paymentUrl) }.isFailure)
      assertEquals(0, evilRequests)
      assertEquals(listOf("mall.cc-pay.cn", "pass.cc-pay.cn"), requestedHosts)
    } finally {
      client.close()
    }
  }

  @Test
  fun `VPN wraps only CAS login and unwraps callback while payment sites stay direct`() = runTest {
    val previousSettings = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    val directHosts = mutableListOf<String>()
    var casReads = 0
    val callback = "https://pass.cc-pay.cn/login?ticket=mock-ticket"
    val login =
        "https://pass.cc-pay.cn/login?" +
            Parameters.build { append("backUrl", paymentUrl) }.formUrlEncode()
    var initialMallRead = true
    val client =
        HttpClient(
            MockEngine { request ->
              val original =
                  app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString())
              val upstream = Url(original)
              when (upstream.host) {
                "mall.cc-pay.cn" -> {
                  assertEquals("mall.cc-pay.cn", request.url.host)
                  directHosts += request.url.host
                  if (initialMallRead) {
                    initialMallRead = false
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, login))
                  } else
                      respond(
                          "",
                          HttpStatusCode.Found,
                          headersOf(
                              HttpHeaders.Location,
                              "https://cashier.cc-pay.cn/cashier?id=mock-order",
                          ),
                      )
                }
                "pass.cc-pay.cn" -> {
                  assertEquals("pass.cc-pay.cn", request.url.host)
                  directHosts += request.url.host
                  if (upstream.parameters["ticket"] != null) {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://cashier.cc-pay.cn/cashier?id=mock-order",
                        ),
                    )
                  } else if (upstream.parameters["channel"] == "BUAASSO") {
                    assertEquals(paymentUrl, upstream.parameters["backUrl"])
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fpass.cc-pay.cn%2Flogin",
                        ),
                    )
                  } else {
                    respond("<input type='password'>")
                  }
                }
                "sso.buaa.edu.cn" -> {
                  casReads++
                  assertEquals("sso.buaa.edu.cn", request.url.host)
                  assertEquals("https://pass.cc-pay.cn/login", upstream.parameters["service"])
                  assertEquals("/login", upstream.encodedPath)
                  directHosts += request.url.host
                  respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, callback))
                }
                "cashier.cc-pay.cn" -> {
                  assertEquals("cashier.cc-pay.cn", request.url.host)
                  directHosts += request.url.host
                  respond("<h1>该订单已关闭。</h1>")
                }
                else -> error("Unexpected synthetic request")
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertEquals(
          "https://cashier.cc-pay.cn/cashier?id=mock-order",
          connectOfficialPaymentPage(client, paymentUrl).url,
      )
      assertEquals(1, casReads)
      assertEquals(
          listOf(
              "mall.cc-pay.cn",
              "pass.cc-pay.cn",
              "pass.cc-pay.cn",
              "sso.buaa.edu.cn",
              "pass.cc-pay.cn",
              "cashier.cc-pay.cn",
          ),
          directHosts,
      )
    } finally {
      client.close()
      app.bucc.api.ConnectionModeStore.settings = previousSettings
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `payment authentication rejects an unrelated CAS service before requesting it`() = runTest {
    var casReads = 0
    val login =
        "https://pass.cc-pay.cn/login?" +
            Parameters.build { append("backUrl", paymentUrl) }.formUrlEncode()
    val requestedHosts = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              requestedHosts += request.url.host
              when (request.url.host) {
                "mall.cc-pay.cn" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, login))
                "pass.cc-pay.cn" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fevil.example%2Flogin",
                        ),
                    )
                else -> {
                  casReads++
                  error("Unrelated authentication target must not be requested")
                }
              }
            }
        ) {
          followRedirects = false
        }
    try {
      assertTrue(runCatching { connectOfficialPaymentPage(client, paymentUrl) }.isFailure)
      assertEquals(0, casReads)
      assertEquals(listOf("mall.cc-pay.cn", "pass.cc-pay.cn"), requestedHosts)
    } finally {
      client.close()
    }
  }

  @Test
  fun `official CAS login page hands the original cashier back to the embedded browser`() =
      runTest {
        val paymentUrl = "https://cashier.cc-pay.cn/cashier?id=mock-order"
        val signIn = "https://pass.cc-pay.cn/signIn?backUrl=" + paymentUrl.encodeURLParameter()
        val login = "https://pass.cc-pay.cn/login?backUrl=" + paymentUrl.encodeURLParameter()
        val client =
            HttpClient(
                MockEngine { request ->
                  when (request.url.host) {
                    "cashier.cc-pay.cn" ->
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, signIn))
                    "pass.cc-pay.cn" ->
                        when {
                          request.url.encodedPath == "/login" &&
                              request.url.parameters["channel"] == null ->
                              respond("<html><input type='password'></html>")
                          request.url.encodedPath == "/login" ->
                              respond(
                                  "",
                                  HttpStatusCode.Found,
                                  headersOf(
                                      HttpHeaders.Location,
                                      "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fpass.cc-pay.cn%2Flogin",
                                  ),
                              )
                          else ->
                              respond(
                                  "",
                                  HttpStatusCode.Found,
                                  headersOf(HttpHeaders.Location, login),
                              )
                        }
                    "sso.buaa.edu.cn" -> respond("<html><title>CAS Login</title></html>")
                    else -> error("Unexpected synthetic request")
                  }
                }
            ) {
              followRedirects = false
            }
        try {
          assertEquals(paymentUrl, connectOfficialPaymentPage(client, paymentUrl).url)
        } finally {
          client.close()
        }
      }
}
