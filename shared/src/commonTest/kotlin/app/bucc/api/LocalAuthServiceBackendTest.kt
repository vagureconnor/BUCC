package app.bucc.api

import app.bucc.api.auth.CaptchaRequiredClientException
import app.bucc.api.local.LocalAuthServiceBackend
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalAuthServiceBackendTest {

  @BeforeTest
  fun setup() {
    runTest { localConnectionTestMutex.lock() }
    ConnectionModeStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    LocalAuthSessionStore.clearAllScopes()
    LocalCookieStore.clearAllScopes()
    LocalUpstreamClientProvider.reset()
  }

  @AfterTest
  fun tearDown() {
    LocalUpstreamClientProvider.reset()
    LocalAuthSessionStore.clearAllScopes()
    LocalCookieStore.clearAllScopes()
    ConnectionRuntime.clearSelectedMode()
    localConnectionTestMutex.unlock()
  }

  @Test
  fun `direct UC transport failure is actionable and never fabricates login`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    var requests = 0
    useMockUpstream(
        MockEngine { request ->
          requests++
          val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
          if (upstream.host == "sso.buaa.edu.cn") {
            respond(
                ByteReadChannel.Empty,
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "/cas/success"),
            )
          } else {
            throw io.ktor.client.network.sockets.ConnectTimeoutException("synthetic offline")
          }
        }
    )
    val result = LocalAuthServiceBackend().preloadLoginState()
    val error = assertIs<app.bucc.api.auth.ApiCallException>(result.exceptionOrNull())
    assertEquals("auth_upstream_unreachable", error.code)
    assertTrue(error.message.orEmpty().contains("uc.buaa.edu.cn"))
    assertTrue(error.message.orEmpty().contains("切换连接模式"))
    assertNull(LocalAuthSessionStore.get())
    assertEquals(2, requests)
  }

  @Test
  fun `UC connectivity failure preserves stored session instead of treating it as expired`() =
      runTest {
        val session =
            LocalAuthSession(
                "22373333",
                UserData("Test User", "22373333"),
                "2026-10-17T00:00:00Z",
                "2026-10-17T00:00:00Z",
            )
        LocalAuthSessionStore.save(session)
        useMockUpstream(
            MockEngine {
              throw io.ktor.client.network.sockets.ConnectTimeoutException("synthetic offline")
            }
        )
        val result = LocalAuthServiceBackend().getAuthStatus()
        val error = assertIs<app.bucc.api.auth.ApiCallException>(result.exceptionOrNull())
        assertEquals("auth_upstream_unreachable", error.code)
        assertEquals(session, LocalAuthSessionStore.get())
      }

  @Test
  fun `preloadLoginState restores direct session from upstream cookies`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location to listOf("/cas/success"),
                        HttpHeaders.SetCookie to
                            listOf("CASTGC=sso-ticket; Domain=sso.buaa.edu.cn; Path=/; Secure"),
                    ),
            )
        "https://uc.buaa.edu.cn/api/login?target=https%3A%2F%2Fuc.buaa.edu.cn%2F%23%2Fuser%2Flogin" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
                headers =
                    headersOf(
                        HttpHeaders.SetCookie,
                        "JSESSIONID=uc-session; Domain=uc.buaa.edu.cn; Path=/; Secure",
                    ),
            )
        "https://uc.buaa.edu.cn/api/uc/status" -> {
          assertTrue(
              request.headers[HttpHeaders.Cookie].orEmpty().contains("JSESSIONID=uc-session")
          )
          respond(
              content =
                  ByteReadChannel(
                      """{"code":0,"data":{"name":"Test User","schoolid":"22373333","username":"22373333"}}"""
                  ),
              status = HttpStatusCode.OK,
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
        else -> error("Unexpected url: ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = LocalAuthServiceBackend().preloadLoginState()

    assertTrue(result.isSuccess)
    assertEquals("22373333", result.getOrNull()?.userData?.schoolid)
    assertNull(result.getOrNull()?.accessToken)
    assertEquals("22373333", LocalAuthSessionStore.get()?.username)
    assertNotNull(LocalCookieStore.load(ConnectionMode.DIRECT))
  }

  @Test
  fun `fresh alias login persists direct cookies restores by mode and uses current CAS challenge`() =
      runTest {
        val credentialPosts = mutableListOf<Pair<String, String>>()
        val modeTransitions = mutableListOf<ConnectionMode>()
        var challengeNumber = 0
        var latestExecution = ""
        val engine = MockEngine { request ->
          val webVpn = request.url.host == "d.buaa.edu.cn"
          val mode = if (webVpn) ConnectionMode.WEBVPN else ConnectionMode.DIRECT
          if (modeTransitions.lastOrNull() != mode) modeTransitions += mode
          if (webVpn) {
            val cookieHeader = request.headers[HttpHeaders.Cookie].orEmpty()
            assertTrue(!cookieHeader.contains("CASTGC=direct-ticket"))
            assertTrue(!cookieHeader.contains("JSESSIONID=direct-session"))
          }
          val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
          when {
            upstream.host == "sso.buaa.edu.cn" && upstream.encodedPath == "/login" -> {
              if (
                  request.headers[HttpHeaders.Cookie].orEmpty().contains("CASTGC=direct-ticket") &&
                      request.method == HttpMethod.Get &&
                      upstream.parameters["service"].isNullOrEmpty()
              ) {
                respond(
                    ByteReadChannel.Empty,
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "/cas/success"),
                )
              } else {
                challengeNumber++
                latestExecution = "synthetic-execution-$challengeNumber"
                val service = upstream.parameters["service"]
                val action =
                    if (service.isNullOrEmpty()) "/cas/initial-submit"
                    else "/cas/direct-submit?service=${service.encodeURLParameter()}"
                respond(
                    ByteReadChannel(
                        """
                    <html><form id="fm1" action="$action" method="post">
                      <input type="hidden" name="execution" value="$latestExecution" />
                    </form></html>
                    """
                            .trimIndent()
                    ),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
              }
            }
            upstream.host == "uc.buaa.edu.cn" && upstream.encodedPath == "/api/login" -> {
              if (credentialPosts.isEmpty() || mode == ConnectionMode.WEBVPN) {
                val challengeUrl =
                    "https://sso.buaa.edu.cn/login?service=" +
                        "https://uc.buaa.edu.cn/#/user/login".encodeURLParameter()
                respond(
                    ByteReadChannel.Empty,
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, challengeUrl),
                )
              } else {
                respond(ByteReadChannel.Empty, HttpStatusCode.OK)
              }
            }
            upstream.host == "sso.buaa.edu.cn" && upstream.encodedPath == "/cas/direct-submit" -> {
              val body = request.bodyText()
              val fields = parseQueryString(body)
              assertEquals("alias-user", fields["username"])
              assertEquals("synthetic-password", fields["password"])
              assertEquals(latestExecution, fields["execution"])
              assertEquals("https://uc.buaa.edu.cn/#/user/login", upstream.parameters["service"])
              credentialPosts += upstream.toString() to fields["execution"].orEmpty()
              respond(
                  ByteReadChannel.Empty,
                  HttpStatusCode.Found,
                  headersOf(
                      HttpHeaders.Location to listOf("https://uc.buaa.edu.cn/landing"),
                      HttpHeaders.SetCookie to
                          listOf("CASTGC=direct-ticket; Domain=sso.buaa.edu.cn; Path=/; Secure"),
                  ),
              )
            }
            upstream.host == "uc.buaa.edu.cn" && upstream.encodedPath == "/landing" ->
                respond(
                    ByteReadChannel.Empty,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.SetCookie,
                        "JSESSIONID=direct-session; Domain=uc.buaa.edu.cn; Path=/; Secure",
                    ),
                )
            upstream.host == "uc.buaa.edu.cn" && upstream.encodedPath == "/api/uc/status" -> {
              if (mode == ConnectionMode.WEBVPN) {
                assertTrue(
                    request.headers[HttpHeaders.Cookie].orEmpty().isEmpty(),
                    "WebVPN must not receive direct-mode cookies",
                )
                respond(ByteReadChannel.Empty, HttpStatusCode.Unauthorized)
              } else {
                assertTrue(
                    request.headers[HttpHeaders.Cookie]
                        .orEmpty()
                        .contains("JSESSIONID=direct-session")
                )
                respond(
                    ByteReadChannel(
                        """{"code":0,"data":{"name":"Synthetic User","schoolid":"22379999","username":"alias-user"}}"""
                    ),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
              }
            }
            else -> error("Unexpected synthetic ${mode.name} request: ${request.url}")
          }
        }
        useMockUpstream(engine)

        val firstBackend = LocalAuthServiceBackend()
        val preload = firstBackend.preloadLoginState()
        assertTrue(preload.isSuccess)
        val preloadedExecution = assertNotNull(preload.getOrNull()?.execution)
        assertTrue(preloadedExecution.startsWith("synthetic-execution-"))

        val login =
            firstBackend.login("alias-user", "synthetic-password", captcha = null, execution = null)
        assertTrue(login.isSuccess, login.exceptionOrNull()?.message.orEmpty())
        assertEquals("22379999", login.getOrNull()?.user?.schoolid)
        assertEquals("22379999", LocalAuthSessionStore.get()?.username)
        val (postUrl, postedExecution) = credentialPosts.single()
        assertTrue(
            postUrl.startsWith("https://sso.buaa.edu.cn/cas/direct-submit?service="),
            postUrl,
        )
        assertEquals(latestExecution, postedExecution)
        assertTrue(
            postedExecution != preloadedExecution,
            "Login must use its current service challenge",
        )
        assertEquals(1, credentialPosts.size)
        assertNotNull(LocalCookieStore.load(ConnectionMode.DIRECT))

        // Exercise cookie restoration on a new backend with no in-memory persisted identity.
        LocalAuthSessionStore.clear()
        ConnectionModeStore.save(ConnectionMode.WEBVPN)
        ConnectionRuntime.resolveSelectedMode()
        val vpnPreload = LocalAuthServiceBackend().preloadLoginState()
        assertTrue(vpnPreload.isSuccess)
        assertNull(vpnPreload.getOrNull()?.userData)
        assertNull(LocalAuthSessionStore.get())
        assertTrue(LocalCookieStore.load(ConnectionMode.WEBVPN).isNullOrEmpty())

        ConnectionModeStore.save(ConnectionMode.DIRECT)
        ConnectionRuntime.resolveSelectedMode()
        val restored = LocalAuthServiceBackend().preloadLoginState()
        assertTrue(restored.isSuccess)
        assertEquals("22379999", restored.getOrNull()?.userData?.schoolid)
        assertEquals("22379999", LocalAuthSessionStore.get()?.username)
        assertEquals(1, credentialPosts.size, "Cookie restoration must not replay CAS credentials")
        assertEquals(
            listOf(ConnectionMode.DIRECT, ConnectionMode.WEBVPN, ConnectionMode.DIRECT),
            modeTransitions,
        )
      }

  @Test
  fun `preloadLoginState uses webvpn wrapped upstream urls when current mode is webvpn`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.WEBVPN)
        ConnectionRuntime.resolveSelectedMode()
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine { request ->
          requestedUrls += request.url.toString()
          when {
            request.url.host == "d.buaa.edu.cn" && request.url.encodedPath.endsWith("/api/login") ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(
                            HttpHeaders.SetCookie,
                            "JSESSIONID=uc-session; Domain=d.buaa.edu.cn; Path=/; Secure",
                        ),
                )
            request.url.host == "d.buaa.edu.cn" && request.url.encodedPath.endsWith("/login") ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location to listOf("/cas/success"),
                            HttpHeaders.SetCookie to
                                listOf("CASTGC=sso-ticket; Domain=d.buaa.edu.cn; Path=/; Secure"),
                        ),
                )
            request.url.host == "d.buaa.edu.cn" &&
                request.url.encodedPath.endsWith("/api/uc/status") -> {
              assertTrue(request.url.encodedPath.startsWith("/https/"))
              assertTrue(
                  request.headers[HttpHeaders.Cookie].orEmpty().contains("JSESSIONID=uc-session")
              )
              respond(
                  content =
                      ByteReadChannel(
                          """{"code":0,"data":{"name":"WebVPN User","schoolid":"22374444","username":"22374444"}}"""
                      ),
                  status = HttpStatusCode.OK,
                  headers = headersOf(HttpHeaders.ContentType, "application/json"),
              )
            }
            else -> error("Unexpected url: ${request.url}")
          }
        }
        useMockUpstream(engine)

        val result = LocalAuthServiceBackend().preloadLoginState()

        assertTrue(result.isSuccess)
        assertEquals("22374444", result.getOrNull()?.userData?.schoolid)
        assertTrue(requestedUrls.all { it.startsWith("https://d.buaa.edu.cn/") })
        assertNotNull(LocalCookieStore.load(ConnectionMode.WEBVPN))
      }

  @Test
  fun `login reports direct login stats after successful authentication`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login" ->
            when (request.method.value) {
              "GET" ->
                  respond(
                      content =
                          ByteReadChannel(
                              """
                              <html>
                                <body>
                                  <form id="fm1">
                                    <input type="hidden" name="execution" value="e1s1" />
                                  </form>
                                </body>
                              </html>
                              """
                                  .trimIndent()
                          ),
                      status = HttpStatusCode.OK,
                      headers = headersOf(HttpHeaders.ContentType, "text/html"),
                  )
              "POST" ->
                  respond(
                      content = ByteReadChannel.Empty,
                      status = HttpStatusCode.Found,
                      headers = headersOf(HttpHeaders.Location, "https://uc.buaa.edu.cn/landing"),
                  )
              else -> error("Unexpected method: ${request.method}")
            }
        "https://uc.buaa.edu.cn/landing" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
            )
        "https://uc.buaa.edu.cn/api/login?target=https%3A%2F%2Fuc.buaa.edu.cn%2F%23%2Fuser%2Flogin" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
            )
        "https://uc.buaa.edu.cn/api/uc/status" ->
            respond(
                content =
                    ByteReadChannel(
                        """{"code":0,"data":{"name":"Direct User","schoolid":"22375555","username":"22375555"}}"""
                    ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        else -> error("Unexpected url: ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result =
        LocalAuthServiceBackend().login("22375555", "secret", captcha = null, execution = null)

    assertTrue(result.isSuccess)
    assertEquals("22375555", result.getOrNull()?.user?.schoolid)
  }

  @Test
  fun `login ignores sso password expiry warning and records direct login stats`() = runTest {
    var loginPostCount = 0
    var ignoreBody = ""
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login" ->
            when (request.method.value) {
              "GET" ->
                  respond(
                      content =
                          ByteReadChannel(
                              """
                              <html>
                                <body>
                                  <form id="fm1">
                                    <input type="hidden" name="execution" value="e1s1" />
                                  </form>
                                </body>
                              </html>
                              """
                                  .trimIndent()
                          ),
                      status = HttpStatusCode.OK,
                      headers = headersOf(HttpHeaders.ContentType, "text/html"),
                  )
              "POST" -> {
                loginPostCount += 1
                if (loginPostCount == 1) {
                  respond(
                      content = ByteReadChannel(passwordExpiryWarningHtml()),
                      status = HttpStatusCode.OK,
                      headers = headersOf(HttpHeaders.ContentType, "text/html"),
                  )
                } else {
                  ignoreBody = request.bodyText()
                  respond(
                      content = ByteReadChannel.Empty,
                      status = HttpStatusCode.Found,
                      headers = headersOf(HttpHeaders.Location, "https://uc.buaa.edu.cn/landing"),
                  )
                }
              }
              else -> error("Unexpected method: ${request.method}")
            }
        "https://uc.buaa.edu.cn/landing" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
            )
        "https://uc.buaa.edu.cn/api/login?target=https%3A%2F%2Fuc.buaa.edu.cn%2F%23%2Fuser%2Flogin" ->
            if (loginPostCount == 0)
                respond(
                    ByteReadChannel.Empty,
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://sso.buaa.edu.cn/login"),
                )
            else respond(ByteReadChannel.Empty, HttpStatusCode.OK)
        "https://uc.buaa.edu.cn/api/uc/status" ->
            respond(
                content =
                    ByteReadChannel(
                        """{"code":0,"data":{"name":"Direct User","schoolid":"22375555","username":"22375555"}}"""
                    ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        else -> error("Unexpected url: ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result =
        LocalAuthServiceBackend().login("22375555", "secret", captcha = null, execution = null)

    assertTrue(result.isSuccess)
    assertEquals(2, loginPostCount)
    assertTrue(ignoreBody.contains("execution=e2s2"), "Unexpected ignore body: $ignoreBody")
    assertTrue(
        ignoreBody.contains("_eventId=ignoreAndContinue"),
        "Unexpected ignore body: $ignoreBody",
    )
    assertEquals("22375555", result.getOrNull()?.user?.schoolid)
  }

  @Test
  fun `login returns captcha requirement when direct mode upstream asks for captcha`() = runTest {
    val loginHtml =
        """
        <html>
          <body>
            <form id="fm1">
              <input type="hidden" name="execution" value="e1s1" />
            </form>
            <script>
              config.captcha = { type: 'image', id: 'captcha-1' }
            </script>
          </body>
        </html>
        """
            .trimIndent()
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login" ->
            respond(
                content = ByteReadChannel(loginHtml),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        "https://uc.buaa.edu.cn/api/login?target=https%3A%2F%2Fuc.buaa.edu.cn%2F%23%2Fuser%2Flogin" ->
            respond(
                ByteReadChannel.Empty,
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://sso.buaa.edu.cn/login"),
            )
        "https://sso.buaa.edu.cn/captcha?captchaId=captcha-1" ->
            respond(
                content = ByteReadChannel(byteArrayOf(1, 2, 3, 4)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
            )
        else -> error("Unexpected url: ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result =
        LocalAuthServiceBackend().login("22373333", "secret", captcha = null, execution = null)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<CaptchaRequiredClientException>(exception)
    assertEquals("captcha-1", exception.captcha.id)
    assertTrue(exception.captcha.base64Image.orEmpty().startsWith("data:image/jpeg;base64,"))
  }

  @Test
  fun `getAuthStatus clears persisted local session when uc session is invalid`() = runTest {
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
        )
    )
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://uc.buaa.edu.cn/api/uc/status" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Unauthorized,
            )
        else -> error("Unexpected url: ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = LocalAuthServiceBackend().getAuthStatus()

    assertTrue(result.isFailure)
    assertNull(LocalAuthSessionStore.get())
  }

  private fun useMockUpstream(engine: MockEngine) {
    LocalUpstreamClientProvider.clientFactory = { followRedirects ->
      HttpClient(engine) {
        this.followRedirects = followRedirects
        install(HttpCookies) {
          storage =
              LocalCookieStore.storage(ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT)
        }
      }
    }
  }

  private fun passwordExpiryWarningHtml(): String =
      """
      <html>
        <body>
          <form id="continueForm" action="/login" method="post">
            <div>账号存在安全风险，请修改密码</div>
            <input type="hidden" name="execution" value="e2s2" />
            <button type="submit" name="_eventId" value="ignoreAndContinue">忽略提示</button>
          </form>
        </body>
      </html>
      """
          .trimIndent()

  private fun io.ktor.client.request.HttpRequestData.bodyText(): String =
      when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("Unsupported request body: ${content::class.simpleName}")
      }
}
