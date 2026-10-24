package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCampusFinanceApi
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.parseZfwHomeTraffic
import app.bucc.api.storage.AuthTokensStore
import app.bucc.api.storage.GradeScoreCacheStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalCampusFinanceApiTest {
  @Test
  fun `portal failure does not hide an independently authenticated network traffic result`() =
      runTest {
        val client =
            financeClient(
                MockEngine { request ->
                  if (request.url.host == "zfw.buaa.edu.cn") respond(zfwHomePage())
                  else respond("", HttpStatusCode.Forbidden)
                }
            )
        val balances =
            LocalCampusFinanceApi(clientFactory = { client })
                .loadBalances("mock-user", includeCard = false)
        assertNotNull(balances.internetFee.error)
        assertEquals("1.23 GB", balances.internetTraffic.used)
        assertNull(balances.internetTraffic.error)
      }

  @Test
  fun `campus card only never queries network fee or traffic`() = runTest {
    val paths = mutableListOf<String>()
    val client =
        financeClient(
            MockEngine { request ->
              paths += request.url.encodedPath
              when (request.url.encodedPath) {
                "/manage/common/cas_login/30001" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/home/"))
                "/home/" -> respond("<title>portal</title>")
                "/buaa/frontend/card/balance" -> respond("{\"e\":0,\"d\":{\"value\":\"12.30\"}}")
                else -> error("Unexpected mock endpoint")
              }
            }
        )
    val balances =
        LocalCampusFinanceApi(clientFactory = { client })
            .loadBalances("mock-user", includeInternet = false)
    assertEquals("12.30", balances.campusCard.value)
    assertEquals(3, paths.size)
  }

  @Test
  fun `reads used free and charged traffic from ZFW home table`() = runTest {
    val requests = mutableListOf<String>()
    val engine = MockEngine { request ->
      requests += "${request.url.host}${request.url.encodedPath}"
      when (request.url.encodedPath) {
        "/manage/common/cas_login/30001" ->
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/home/"))
        "/home/" -> respond("<!doctype html><title>i北航融合门户</title>", HttpStatusCode.OK)
        "/buaa/frontend/card/balance" ->
            respond("""{"e":0,"d":{"value":"32.50","unit":"元"}}""", HttpStatusCode.OK)
        "/personal/frontend/data/items" ->
            respond("""{"e":0,"d":{"data":[{"key":"net.netFee","id":42}]}}""", HttpStatusCode.OK)
        "/personal/frontend/data/detail" ->
            respond("""{"e":0,"d":{"data":{"value":"18.5"}}}""", HttpStatusCode.OK)
        "/home" ->
            respond(
                zfwHomePage(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        else -> error("Unexpected request: ${request.url}")
      }
    }
    val client = financeClient(engine)

    val balances =
        LocalCampusFinanceApi(
                clientFactory = { client },
                zfwBaseUrl = "https://zfw.buaa.edu.cn",
            )
            .loadBalances("test-user")

    assertEquals("1.23 GB", balances.internetTraffic.used)
    assertEquals("2.34 GB", balances.internetTraffic.freeRemaining)
    assertEquals("3.45 GB", balances.internetTraffic.chargedRemaining)
    assertNull(balances.internetTraffic.error)
    assertTrue(requests.contains("zfw.buaa.edu.cn/home"))
  }

  @Test
  fun `rejects missing ZFW traffic values instead of treating them as zero`() {
    assertFailsWith<Exception> { parseZfwHomeTraffic("<html><body>请先登录</body></html>") }
  }

  @Test
  fun `uses selected WebVPN route for portal session and balance endpoints`() = runTest {
    ConnectionModeStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    AuthTokensStore.settings = MapSettings()
    GradeScoreCacheStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val gatewayHost = "d.buaa.edu.cn"
    val requests = mutableListOf<String>()
    val engine = MockEngine { request ->
      requests += request.url.host
      val body =
          when {
            request.url.encodedPath.endsWith("/manage/common/cas_login/30001") -> {
              assertEquals(
                  LocalWebVpnSupport.toWebVpnUrl("https://it.buaa.edu.cn/home/"),
                  request.url.parameters["redirect"],
              )
              respond(
                  "",
                  HttpStatusCode.Found,
                  headersOf(
                      HttpHeaders.Location,
                      LocalWebVpnSupport.toWebVpnUrl("https://it.buaa.edu.cn/home/"),
                  ),
              )
            }
            request.url.encodedPath.endsWith("/home/") ->
                respond("<!doctype html><title>i北航融合门户</title>", HttpStatusCode.OK)
            request.url.encodedPath.endsWith("/home") ->
                respond(
                    zfwHomePage(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            request.url.encodedPath.endsWith("/buaa/frontend/card/balance") ->
                respond("""{"e":0,"d":{"value":"32.50","unit":"元"}}""", HttpStatusCode.OK)
            request.url.encodedPath.endsWith("/personal/frontend/data/items") ->
                respond(
                    """{"e":0,"d":{"data":[{"key":"net.netFee","id":42}]}}""",
                    HttpStatusCode.OK,
                )
            request.url.encodedPath.endsWith("/personal/frontend/data/detail") ->
                respond("""{"e":0,"d":{"data":{"value":"18.5"}}}""", HttpStatusCode.OK)
            else -> error("Unexpected request: ${request.url}")
          }
      body
    }
    try {
      val client = financeClient(engine)

      val balances =
          LocalCampusFinanceApi(
                  clientFactory = { client },
                  portalBaseUrl = "https://it.buaa.edu.cn",
              )
              .loadBalances("test-user")

      assertEquals("32.50", balances.campusCard.value)
      assertEquals("18.50", balances.internetFee.value)
      assertEquals(6, requests.size)
      assertTrue(requests.dropLast(1).all { it == gatewayHost })
      assertEquals("zfw.buaa.edu.cn", requests.last())
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `uses official read only endpoints and parses both balances`() = runTest {
    val requests = mutableListOf<Pair<HttpMethod, String>>()
    val engine = MockEngine { request ->
      requests += request.method to request.url.toString()
      val body =
          when (request.url.encodedPath) {
            "/manage/common/cas_login/30001" -> {
              assertEquals("https://it.buaa.edu.cn/home/", request.url.parameters["redirect"])
              respond(
                  "",
                  HttpStatusCode.Found,
                  headersOf(
                      HttpHeaders.Location to listOf("/home/"),
                      HttpHeaders.SetCookie to listOf("portal_session=ready; Path=/; Secure"),
                  ),
              )
            }
            "/home/" -> {
              assertTrue(
                  request.headers[HttpHeaders.Cookie].orEmpty().contains("portal_session=ready")
              )
              respond(
                  "<!doctype html><title>i北航融合门户</title>",
                  HttpStatusCode.OK,
                  headersOf(HttpHeaders.ContentType, "text/html"),
              )
            }
            "/home" ->
                respond(
                    zfwHomePage(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            "/buaa/frontend/card/balance" ->
                respond(
                    """{"e":0,"d":{"value":"32.50","unit":"元"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            "/personal/frontend/data/items" ->
                respond(
                    """{"e":0,"d":{"data":[{"key":"other","id":7},{"key":"net.netFee","id":42}]}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            "/personal/frontend/data/detail" ->
                respond(
                    """{"e":0,"d":{"data":{"value":"18.5"}}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            else -> error("Unexpected request: ${request.url}")
          }
      body
    }
    val client = financeClient(engine)

    val balances = LocalCampusFinanceApi(clientFactory = { client }).loadBalances("test-user")

    assertEquals("32.50", balances.campusCard.value)
    assertEquals("元", balances.campusCard.unit)
    assertEquals("18.50", balances.internetFee.value)
    assertEquals("元", balances.internetFee.unit)
    assertNull(balances.campusCard.error)
    assertNull(balances.internetFee.error)
    assertEquals(6, requests.size)
    assertTrue(requests.all { it.first == HttpMethod.Get })
    assertTrue(
        requests
            .first()
            .second
            .contains(
                "/manage/common/cas_login/30001?redirect=https%3A%2F%2Fit.buaa.edu.cn%2Fhome%2F"
            )
    )
    assertTrue(
        requests.any { it.second.endsWith("/personal/frontend/data/items?type=personal_data") }
    )
    assertTrue(requests.any { it.second.endsWith("/personal/frontend/data/detail?id=42") })
  }

  @Test
  fun `does not request portal when local login is missing`() = runTest {
    var clientCreated = false
    val api =
        LocalCampusFinanceApi(
            clientFactory = {
              clientCreated = true
              error("A client should not be created without a session")
            }
        )

    val balances = api.loadBalances(null)

    assertNull(balances.campusCard.value)
    assertNotNull(balances.campusCard.error)
    assertNotNull(balances.internetFee.error)
    assertEquals(false, clientCreated)
  }

  @Test
  fun `reports invalid balance response and keeps available balance`() = runTest {
    val engine = MockEngine { request ->
      val body =
          when (request.url.encodedPath) {
            "/manage/common/cas_login/30001" ->
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "/home/"),
                )
            "/home/" -> respond("<!doctype html><title>i北航融合门户</title>", HttpStatusCode.OK)
            "/home" -> respond(zfwHomePage(), HttpStatusCode.OK)
            "/buaa/frontend/card/balance" ->
                respond("""{"e":0,"d":{"value":"1e3"}}""", HttpStatusCode.OK)
            "/personal/frontend/data/items" ->
                respond(
                    """{"e":0,"d":{"data":[{"key":"net.netFee","id":42}]}}""",
                    HttpStatusCode.OK,
                )
            "/personal/frontend/data/detail" ->
                respond("""{"e":0,"d":{"data":{"value":"18.5"}}}""", HttpStatusCode.OK)
            else -> error("Unexpected request: ${request.url}")
          }
      body
    }
    val client = financeClient(engine)

    val balances = LocalCampusFinanceApi(clientFactory = { client }).loadBalances("test-user")

    assertNull(balances.campusCard.value)
    assertNotNull(balances.campusCard.error)
    assertEquals("18.50", balances.internetFee.value)
    assertNull(balances.internetFee.error)
  }

  @Test
  fun `marks portal login errors for both balances`() = runTest {
    val engine = MockEngine { request ->
      val body =
          when (request.url.encodedPath) {
            "/manage/common/cas_login/30001" ->
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "/home/"),
                )
            "/home/" -> respond("<!doctype html><title>i北航融合门户</title>", HttpStatusCode.OK)
            "/home" -> respond(zfwHomePage(), HttpStatusCode.OK)
            "/buaa/frontend/card/balance" -> respond("""{"e":10013,"d":null}""", HttpStatusCode.OK)
            "/personal/frontend/data/items" ->
                respond(
                    """{"e":0,"d":{"data":[{"key":"net.netFee","id":42}]}}""",
                    HttpStatusCode.OK,
                )
            "/personal/frontend/data/detail" ->
                respond("""{"e":10013,"d":null}""", HttpStatusCode.OK)
            else -> error("Unexpected request: ${request.url}")
          }
      body
    }
    val client = financeClient(engine)

    val balances = LocalCampusFinanceApi(clientFactory = { client }).loadBalances("test-user")

    assertTrue(balances.campusCard.error.orEmpty().contains("登录"))
    assertTrue(balances.internetFee.error.orEmpty().contains("登录"))
  }

  @Test
  fun `low balance reminders are deduplicated until recovered`() {
    FinanceBalanceReminderStore.settings = MapSettings()
    val atThresholds =
        CampusFinanceBalances(
            internetFee = FinanceBalance(value = "5"),
            campusCard = FinanceBalance(value = "50"),
        )
    assertTrue(FinanceBalanceReminderStore.update("threshold-account", atThresholds).isEmpty())

    val lowBalances =
        CampusFinanceBalances(
            internetFee = FinanceBalance(value = "4.99"),
            campusCard = FinanceBalance(value = "49.99"),
        )

    val first = FinanceBalanceReminderStore.update("account-a", lowBalances)
    val duplicate = FinanceBalanceReminderStore.update("account-a", lowBalances)
    val otherAccount = FinanceBalanceReminderStore.update("account-b", lowBalances)

    assertEquals(2, first.size)
    assertTrue(first.any { it.contains("网费") && it.contains("5") })
    assertTrue(first.any { it.contains("校园卡") && it.contains("50") })
    assertTrue(duplicate.isEmpty())
    assertEquals(2, otherAccount.size)

    FinanceBalanceReminderStore.update(
        "account-a",
        CampusFinanceBalances(
            internetFee = FinanceBalance(value = "5"),
            campusCard = FinanceBalance(value = "50"),
        ),
    )
    val afterRecovery = FinanceBalanceReminderStore.update("account-a", lowBalances)
    assertEquals(2, afterRecovery.size)
  }

  @Test
  fun `formats decimal amounts to cents using half up rounding`() {
    assertEquals("1234.50", formatFinanceAmount("1,234.5"))
    assertEquals("-0.01", formatFinanceAmount("-0.005"))
    assertEquals("1.24", formatFinanceAmount("1.235"))
  }

  @Test
  fun `rejects malformed decimal amounts and grouping`() {
    assertNull(formatFinanceAmount("12abc"))
    assertNull(formatFinanceAmount("1,23.45"))
    assertNull(formatFinanceAmount("1,,234.00"))
  }

  @Test
  fun `compares low balance thresholds without floating point rounding`() {
    FinanceBalanceReminderStore.settings = MapSettings()
    val reminders =
        FinanceBalanceReminderStore.update(
            "precision-account",
            CampusFinanceBalances(
                internetFee = FinanceBalance(value = "4.999999999999999999"),
                campusCard = FinanceBalance(value = "50.000000000000000001"),
            ),
        )

    assertEquals(1, reminders.size)
    assertTrue(reminders.single().contains("网费"))
  }

  @Test
  fun `rejects CAS exchange that ends on an SSO login page`() = runTest {
    val requests = mutableListOf<String>()
    val client =
        financeClient(
            MockEngine { request ->
              requests += request.url.toString()
              when (request.url.encodedPath) {
                "/manage/common/cas_login/30001" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://sso.buaa.edu.cn/login"),
                    )
                "/login" -> respond("<html>login</html>", HttpStatusCode.OK)
                "/home" -> respond(zfwHomePage())
                else -> error("Unexpected request: ${request.url}")
              }
            }
        )

    val balances = LocalCampusFinanceApi(clientFactory = { client }).loadBalances("test-user")

    assertTrue(balances.campusCard.error.orEmpty().contains("登录状态无效"))
    assertTrue(balances.internetFee.error.orEmpty().contains("登录状态无效"))
    assertEquals(3, requests.size)
    assertEquals("1.23 GB", balances.internetTraffic.used)
    assertTrue(requests.none { it.contains("/buaa/frontend/card/balance") })
  }
}

private fun financeClient(engine: MockEngine): HttpClient =
    HttpClient(engine) { install(HttpCookies) { storage = AcceptAllCookiesStorage() } }

private fun zfwHomePage(): String =
    """
    <div id="w3-container">
      <table class="kv-grid-table">
        <thead><tr>
          <th>产品名称</th><th>计费策略</th><th>已用流量</th><th>已用时长</th>
          <th>免费流量剩余(不含套餐)</th><th>计费流量剩余(不含套餐)</th>
          <th>结算日期</th><th>套餐详情</th>
        </tr></thead>
        <tbody><tr>
          <td>测试套餐</td><td>测试策略</td><td>1.23 GB</td><td>2 小时</td>
          <td>2.34 GB</td><td>3.45 GB</td><td>测试日期</td><td></td>
        </tr></tbody>
      </table>
    </div>
    """
        .trimIndent()
