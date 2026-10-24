package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.localUpstreamUrl
import app.bucc.security.CampusCredential
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class ZfwSessionCoordinatorTest {
  private val login =
      """<input id="public" value="mock-public-key"><form method="post" action="/">
    <input name="_csrf-mock" value="mock-csrf">
    <input name="LoginForm[username]"><input name="LoginForm[password]" type="password">
    <input name="LoginForm[smsCode]" value="mock-sms-code"><input name="LoginForm[verifyCode]">
    <img src="/site/captcha?v=mock-login-image"></form>"""
  private val home = "已用流量 免费流量剩余 计费流量剩余"

  @Test
  fun `forced captcha leaves only the explicitly declared ZFW session before reading login`() =
      runTest {
        var loggedIn = true
        val requests = mutableListOf<String>()
        var logoutFields: Parameters? = null
        val loggedInPage =
            """<meta name="csrf-param" content="_csrf-zfw"><meta name="csrf-token" content="mock-home-csrf">
      <main>$home</main><a data-method="post" href="/site/logout">退出</a>"""
        val client =
            HttpClient(
                MockEngine { request ->
                  requests +=
                      "${request.method.value} ${request.url.encodedPath}${request.url.encodedQuery.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()}"
                  when {
                    request.url.encodedPath == "/" && loggedIn ->
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/home"))
                    request.url.encodedPath == "/home" && loggedIn -> respond(loggedInPage)
                    request.url.encodedPath == "/site/logout" &&
                        request.method == HttpMethod.Post -> {
                      logoutFields = (request.body as FormDataContent).formData
                      loggedIn = false
                      respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
                    }
                    request.url.encodedPath == "/" -> respond(login)
                    request.url.encodedPath == "/site/captcha" &&
                        request.url.parameters["refresh"] == "1" ->
                        respond("{\"url\":\"/site/captcha?v=mock-after-logout\"}")
                    request.url.encodedPath == "/site/captcha" ->
                        respond(
                            byteArrayOf(1),
                            headers = headersOf(HttpHeaders.ContentType, "image/png"),
                        )
                    else -> respond("not found", HttpStatusCode.NotFound)
                  }
                }
            ) {
              followRedirects = false
            }
        val coordinator =
            ZfwSessionCoordinator(
                { client },
                { CampusCredential("mock-user", "mock-password") },
                { _, _ -> "mock-encrypted" },
            )
        try {
          assertIs<ZfwSessionState.LoginCaptcha>(
              coordinator.ensureSession(forceVerification = true)
          )
          assertEquals(Parameters.build { append("_csrf-zfw", "mock-home-csrf") }, logoutFields)
          val logoutIndex = requests.indexOfFirst { it == "POST /site/logout" }
          val refreshIndex =
              requests.indexOfFirst {
                it.startsWith("GET /site/captcha?") && it.contains("refresh=1")
              }
          assertEquals(1, requests.count { it == "POST /site/logout" })
          assertTrue(logoutIndex >= 0 && refreshIndex > logoutIndex)
        } finally {
          coordinator.close()
        }
      }

  @Test
  fun `forced captcha refuses unsafe or ambiguous logout declarations without posting`() = runTest {
    val invalidDeclarations =
        listOf(
            """<a href="https://evil.example/logout" data-method="post">Logout</a>""",
            """<form method="post" action="/site/logout"><input type="hidden" name="_csrf-zfw" value=""></form>""",
            """<meta name="csrf-param" content="_csrf-zfw"><meta name="csrf-token" content="mock-token">
          <a href="/site/logout">Logout</a><form method="post" action="/logout"><input type="hidden" name="_csrf-zfw" value="mock-token"></form>""",
        )
    for (declaration in invalidDeclarations) {
      val requests = mutableListOf<String>()
      val client =
          HttpClient(
              MockEngine { request ->
                requests +=
                    "${request.method.value} ${request.url.encodedPath}${request.url.encodedQuery.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()}"
                when (request.url.encodedPath) {
                  "/" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/home"))
                  "/home" -> respond("$home $declaration")
                  else -> respond("unexpected logout request", HttpStatusCode.InternalServerError)
                }
              }
          ) {
            followRedirects = false
          }
      val coordinator =
          ZfwSessionCoordinator({ client }, { CampusCredential("mock-user", "mock-password") })
      try {
        val failed =
            assertIs<ZfwSessionState.Failed>(coordinator.ensureSession(forceVerification = true))
        assertTrue(failed.reason.contains("校园网仍处于旧登录状态，无法开始本次验证码验证"))
        assertFalse(
            requests.any {
              it.startsWith("POST ") ||
                  it.startsWith("GET /site/logout") ||
                  it.startsWith("GET /logout")
            }
        )
        assertFalse(coordinator.state.value is ZfwSessionState.Ready)
      } finally {
        coordinator.close()
      }
    }
  }

  @Test
  fun `finance cookie restoration resolves remembered login alias by its verified account`() =
      runTest {
        val previousMode = ConnectionModeStore.get()
        val previousCredentials = app.bucc.api.storage.CredentialStore.settings
        val previousSessions = app.bucc.api.local.LocalAuthSessionStore.settings
        app.bucc.api.storage.CredentialStore.settings = MapSettings()
        app.bucc.api.local.LocalAuthSessionStore.settings = MapSettings()
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        ConnectionRuntime.resolveSelectedMode()
        app.bucc.td.TdCampusCredentials.clearSession()
        app.bucc.api.local.LocalAuthSessionStore.save(
            app.bucc.api.local.LocalAuthSession(
                "fixture-school",
                app.bucc.model.dto.UserData("Fixture", "fixture-school"),
                "now",
                "now",
            )
        )
        app.bucc.api.storage.CredentialStore.setRememberUsername(true)
        app.bucc.api.storage.CredentialStore.saveUsername("fixture-alias", "fixture-school")
        val loaded = mutableListOf<String>()
        val storage =
            object : app.bucc.security.CampusCredentialStorage {
              override suspend fun save(credential: CampusCredential) = Unit

              override suspend fun load(username: String): CampusCredential? {
                loaded += username
                return CampusCredential("fixture-alias", "synthetic").takeIf {
                  username == "fixture-alias"
                }
              }

              override suspend fun delete(username: String) = Unit
            }
        fun newClient() =
            HttpClient(
                MockEngine { request ->
                  when (request.url.encodedPath) {
                    "/home" ->
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
                    "/site/captcha" ->
                        if (request.url.parameters["refresh"] == "1")
                            respond("{\"url\":\"/site/captcha?v=fresh\"}")
                        else
                            respond(
                                byteArrayOf(1),
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                    else -> respond(login)
                  }
                }
            ) {
              followRedirects = false
            }
        app.bucc.api.local.LocalUpstreamClientProvider.isolatedClientFactory = { _, _ ->
          newClient()
        }
        try {
          app.bucc.security.CampusSecureCredentials.withStorageForTests(storage) {
            assertIs<ZfwSessionState.LoginCaptcha>(CampusFinanceSession.current().ensureSession())
            assertEquals(listOf("fixture-alias"), loaded)
            CampusFinanceSession.reset()
            app.bucc.api.local.LocalAuthSessionStore.save(
                app.bucc.api.local.LocalAuthSession(
                    "fixture-other",
                    app.bucc.model.dto.UserData("Other", "fixture-other"),
                    "now",
                    "now",
                )
            )
            assertIs<ZfwSessionState.Failed>(CampusFinanceSession.current().ensureSession())
            assertEquals(listOf("fixture-alias"), loaded)
          }
        } finally {
          CampusFinanceSession.reset()
          app.bucc.api.local.LocalUpstreamClientProvider.reset()
          app.bucc.api.storage.CredentialStore.settings = previousCredentials
          app.bucc.api.local.LocalAuthSessionStore.settings = previousSessions
          if (previousMode == null) ConnectionModeStore.clear()
          else ConnectionModeStore.save(previousMode)
          ConnectionRuntime.resolveSelectedMode()
        }
      }

  @Test
  fun `existing CAS cookies restore ZFW through read redirects without loading a password`() =
      runTest {
        val cookies = io.ktor.client.plugins.cookies.AcceptAllCookiesStorage()
        cookies.addCookie(
            Url("https://sso.buaa.edu.cn/"),
            Cookie("CASTGC", "synthetic-ticket", path = "/", secure = true),
        )
        var credentialReads = 0
        var casReads = 0
        val client =
            HttpClient(
                MockEngine { request ->
                  assertEquals(HttpMethod.Get, request.method)
                  when {
                    request.url.host == "sso.buaa.edu.cn" -> {
                      casReads++
                      assertTrue(
                          request.headers[HttpHeaders.Cookie]
                              .orEmpty()
                              .contains("CASTGC=synthetic-ticket")
                      )
                      respond(
                          "",
                          HttpStatusCode.Found,
                          headersOf(
                              HttpHeaders.Location,
                              "https://zfw.buaa.edu.cn/home?verified=1",
                          ),
                      )
                    }
                    request.url.parameters["verified"] == "1" -> respond(home)
                    else ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(
                                HttpHeaders.Location,
                                "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fzfw.buaa.edu.cn%2Fhome",
                            ),
                        )
                  }
                }
            ) {
              followRedirects = false
              install(io.ktor.client.plugins.cookies.HttpCookies) { storage = cookies }
            }
        val coordinator =
            ZfwSessionCoordinator(
                { client },
                {
                  credentialReads++
                  null
                },
            )
        try {
          assertIs<ZfwSessionState.Ready>(coordinator.ensureSession())
          assertEquals(1, casReads)
          assertEquals(0, credentialReads)
        } finally {
          coordinator.close()
        }
      }

  @Test
  fun `read redirects reject loops and stop after eight hops without loading credentials`() =
      runTest {
        for (loop in listOf(true, false)) {
          var requests = 0
          var credentialReads = 0
          val client =
              HttpClient(
                  MockEngine { request ->
                    requests++
                    val next =
                        if (loop) "/"
                        else
                            "/home?hop=" + ((request.url.parameters["hop"]?.toIntOrNull() ?: 0) + 1)
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, next))
                  }
              ) {
                followRedirects = false
              }
          val coordinator =
              ZfwSessionCoordinator(
                  { client },
                  {
                    credentialReads++
                    null
                  },
              )
          try {
            val result = assertIs<ZfwSessionState.Failed>(coordinator.ensureSession())
            assertTrue(result.reason.contains(if (loop) "循环" else "次数过多"))
            assertEquals(if (loop) 2 else 9, requests)
            assertEquals(0, credentialReads)
          } finally {
            coordinator.close()
          }
        }
      }

  @Test
  fun `direct login challenge follows safe read redirects before parsing`() = runTest {
    val reads = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              assertEquals(HttpMethod.Get, request.method)
              reads += request.url.encodedPath
              when (request.url.encodedPath) {
                "/home",
                "/" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "/site/login"),
                    )
                "/site/login" -> respond(login)
                "/site/captcha" ->
                    if (request.url.parameters["refresh"] == "1")
                        respond("{\"url\":\"/site/captcha?v=fresh\"}")
                    else
                        respond(
                            byteArrayOf(1),
                            headers = headersOf(HttpHeaders.ContentType, "image/png"),
                        )
                else -> error("Unexpected mock path")
              }
            }
        ) {
          followRedirects = false
        }
    val coordinator = ZfwSessionCoordinator({ client }, { CampusCredential("fixture", "fixture") })
    try {
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession())
      assertTrue("/site/login" in reads)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `read redirect to an untrusted host is rejected before visiting it`() = runTest {
    var reads = 0
    val client =
        HttpClient(
            MockEngine { request ->
              reads++
              assertEquals("zfw.buaa.edu.cn", request.url.host)
              respond(
                  "",
                  HttpStatusCode.Found,
                  headersOf(HttpHeaders.Location, "https://untrusted.example/site/login"),
              )
            }
        ) {
          followRedirects = false
        }
    val coordinator = ZfwSessionCoordinator({ client }, { CampusCredential("fixture", "fixture") })
    try {
      val result = assertIs<ZfwSessionState.Failed>(coordinator.ensureSession())
      assertTrue(result.reason.contains("跳转"))
      assertEquals(1, reads)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `expired session presents native login captcha and submits captured fields`() = runTest {
    var authenticated = false
    var loginPosts = 0
    val client =
        HttpClient(
            MockEngine { request ->
              when {
                request.url.encodedPath == "/site/validate-user" ->
                    respond("{\"success\":true,\"inputSms\":false}")
                request.url.encodedPath == "/site/captcha" &&
                    request.url.parameters["refresh"] == "1" ->
                    respond(
                        "{\"url\":\"/site/captcha?v=mock-fresh\"}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                request.url.encodedPath == "/home" ->
                    if (authenticated) respond(home)
                    else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
                request.url.encodedPath == "/site/captcha" ->
                    respond(
                        byteArrayOf(1, 2),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "image/png"),
                    )
                request.method == HttpMethod.Post -> {
                  loginPosts++
                  val fields = (request.body as FormDataContent).formData
                  assertEquals("mock-csrf", fields["_csrf-mock"])
                  assertEquals("mock-user", fields["LoginForm[username]"])
                  assertEquals("mock-encrypted", fields["LoginForm[password]"])
                  assertEquals("mock-sms-code", fields["LoginForm[smsCode]"])
                  assertEquals("mock-login-code", fields["LoginForm[verifyCode]"])
                  assertNull(fields["OneCardForm[verifyCode]"])
                  authenticated = true
                  respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location, "/home"))
                }
                else -> respond(login)
              }
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator(
            { client },
            { CampusCredential("mock-user", "mock-password") },
            { _, _ -> "mock-encrypted" },
        )
    try {
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession())
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession())
      assertEquals(0, loginPosts)
      assertIs<ZfwSessionState.Ready>(coordinator.submitLoginCaptcha("mock-login-code"))
      assertIs<ZfwSessionState.Ready>(coordinator.ensureSession())
      assertEquals(1, loginPosts)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `existing official home never requests stored password or posts login`() = runTest {
    var credentialReads = 0
    val client =
        HttpClient(
            MockEngine {
              assertEquals(HttpMethod.Get, it.method)
              assertEquals("/home", it.url.encodedPath)
              respond(home)
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator(
            { client },
            {
              credentialReads++
              null
            },
        )
    try {
      assertIs<ZfwSessionState.Ready>(coordinator.ensureSession())
      assertEquals(0, credentialReads)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `login form can never be accepted as a successful traffic session`() = runTest {
    var loginPosts = 0
    val client =
        HttpClient(
            MockEngine { request ->
              if (request.url.encodedPath == "/site/validate-user") {
                loginPosts++
                respond("{\"success\":false,\"inputSms\":false}")
              } else if (
                  request.url.encodedPath == "/site/captcha" &&
                      request.url.parameters["refresh"] == "1"
              )
                  respond("{\"url\":\"/site/captcha?v=mock-fresh\"}")
              else if (request.url.encodedPath == "/site/captcha")
                  respond(
                      byteArrayOf(1),
                      HttpStatusCode.OK,
                      headersOf(HttpHeaders.ContentType, "image/png"),
                  )
              else if (request.method == HttpMethod.Post) {
                loginPosts++
                respond(login)
              } else respond(login)
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator(
            { client },
            { CampusCredential("mock-user", "mock-password") },
            { _, _ -> "mock-encrypted" },
        )
    try {
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession())
      val result = coordinator.submitLoginCaptcha("incorrect-mock-code")
      assertIs<ZfwSessionState.LoginCaptcha>(result)
      assertEquals(1, loginPosts)
      assertFalse(result.toString().contains("mock-password"))
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `concurrent session checks share one captcha challenge`() = runTest {
    var homeRequests = 0
    var captchaRequests = 0
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/home" -> {
                  homeRequests++
                  respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
                }
                "/site/captcha" ->
                    if (request.url.parameters["refresh"] == "1")
                        respond("{\"url\":\"/site/captcha?v=mock-fresh\"}")
                    else {
                      captchaRequests++
                      respond(
                          byteArrayOf(1),
                          HttpStatusCode.OK,
                          headersOf(HttpHeaders.ContentType, "image/png"),
                      )
                    }
                else -> respond(login)
              }
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator(
            { client },
            { CampusCredential("mock-user", "mock-password") },
            { _, _ -> "mock-encrypted" },
        )
    try {
      val results =
          listOf(async { coordinator.ensureSession() }, async { coordinator.ensureSession() })
              .awaitAll()
      assertTrue(results.all { it is ZfwSessionState.LoginCaptcha })
      // One /home probe before the portal CAS bridge and one after it falls back to the login page.
      assertEquals(2, homeRequests)
      assertEquals(1, captchaRequests)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `refresh invokes official generation endpoint before loading returned image`() = runTest {
    val requests = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              requests += request.url.encodedPath + "?" + request.url.encodedQuery
              when {
                request.url.encodedPath == "/home" -> respond("", HttpStatusCode.Found)
                request.url.parameters["refresh"] == "1" ->
                    respond("{\"url\":\"/site/captcha?v=mock-generated\"}")
                request.url.encodedPath == "/site/captcha" ->
                    respond(
                        byteArrayOf(1),
                        headers = headersOf(HttpHeaders.ContentType, "image/png"),
                    )
                else -> respond(login)
              }
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator(
            { client },
            { CampusCredential("mock-user", "mock-password") },
            { _, _ -> "mock-encrypted" },
        )
    try {
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession(forceVerification = true))
      assertTrue(requests.any { it.startsWith("/site/captcha?refresh=1") })
      assertTrue(requests.last().contains("mock-generated"))
      assertIs<ZfwSessionState.LoginCaptcha>(
          coordinator.ensureSession(refreshCaptcha = true, forceVerification = true)
      )
      assertEquals(2, requests.count { it.startsWith("/site/captcha?refresh=1") })
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `WebVPN login form and refreshed captcha URLs are decoded to official targets`() = runTest {
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val action = LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/site/login")
    val captcha =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/site/captcha?v=mock-image")
    val refreshedCaptcha =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/site/captcha?v=mock-refreshed")
    val webVpnLogin =
        login
            .replace("action=\"/\"", "action=\"$action\"")
            .replace("src=\"/site/captcha?v=mock-login-image\"", "src=\"$captcha\"")
    val requests = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              val target = LocalWebVpnSupport.fromWebVpnUrl(request.url.toString())
              requests += target
              when {
                target == "https://zfw.buaa.edu.cn/home" -> respond("", HttpStatusCode.Found)
                target.startsWith("https://zfw.buaa.edu.cn/site/captcha") &&
                    request.url.parameters["refresh"] == "1" ->
                    respond("{\"url\":\"$refreshedCaptcha\"}")
                target.startsWith("https://zfw.buaa.edu.cn/site/captcha") ->
                    respond(
                        byteArrayOf(1, 2),
                        headers = headersOf(HttpHeaders.ContentType, "image/png"),
                    )
                else -> respond(webVpnLogin)
              }
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator({ client }, { CampusCredential("mock-user", "mock-password") })
    try {
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession(forceVerification = true))
      assertTrue(requests.all { it.startsWith("https://zfw.buaa.edu.cn/") })
      assertTrue(
          requests.any { it.startsWith("https://zfw.buaa.edu.cn/site/captcha?v=mock-refreshed") }
      )
      assertTrue(
          localUpstreamUrl("https://zfw.buaa.edu.cn/site/login")
              .startsWith("https://d.buaa.edu.cn/https/")
      )
    } finally {
      coordinator.close()
      client.close()
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `login parser rejects wrapped URLs with wrong gateway target origin scheme port or path`() {
    val wrappedHttpAction = LocalWebVpnSupport.toWebVpnUrl("http://zfw.buaa.edu.cn/site/login")
    val wrappedWrongPortAction =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn:8443/site/login")
    val unsafeActions =
        listOf(
            wrappedHttpAction,
            wrappedWrongPortAction,
            "https://d.buaa.edu.cn/https/not-an-encrypted-host/site/login",
            wrappedHttpAction.replace("https://d.buaa.edu.cn", "https://d.buaa.edu.cn:444"),
            wrappedHttpAction.replace(
                "https://d.buaa.edu.cn",
                "https://d.buaa.edu.cn.evil.example",
            ),
            "https://zfw.buaa.edu.cn:444/site/login",
            "https://evil.example/site/login",
        )
    unsafeActions.forEach { action ->
      assertNull(parseLogin(login.replace("action=\"/\"", "action=\"$action\"")))
    }
    val wrappedHttpCaptcha =
        LocalWebVpnSupport.toWebVpnUrl("http://zfw.buaa.edu.cn/site/captcha?v=x")
    val wrappedWrongPortCaptcha =
        LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn:8443/site/captcha?v=x")
    val unsafeCaptcha =
        listOf(
            "http://zfw.buaa.edu.cn/site/captcha?v=x",
            "https://zfw.buaa.edu.cn:444/site/captcha?v=x",
            "https://evil.example/site/captcha?v=x",
            wrappedHttpCaptcha,
            wrappedWrongPortCaptcha,
        )
    unsafeCaptcha.forEach { image ->
      assertNull(
          parseLogin(login.replace("src=\"/site/captcha?v=mock-login-image\"", "src=\"$image\""))
      )
    }
  }

  @Test
  fun `WebVPN verification expires only cookies scoped below the ZFW gateway prefix`() = runTest {
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    LocalCookieStore.settings = MapSettings()
    val storage = LocalCookieStore.storage(ConnectionMode.WEBVPN)
    val gatewayRoot = Url("https://d.buaa.edu.cn/")
    val zfwRoot = Url(LocalWebVpnSupport.toWebVpnUrl("https://zfw.buaa.edu.cn/"))
    val casPath = Url(LocalWebVpnSupport.toWebVpnUrl("https://sso.buaa.edu.cn/")).encodedPath
    val otherPath = Url(LocalWebVpnSupport.toWebVpnUrl("https://uc.buaa.edu.cn/")).encodedPath
    val zfwPath = zfwRoot.encodedPath.trimEnd('/')
    storage.addCookie(
        gatewayRoot,
        Cookie("VPN_TICKET", "mock-ticket", domain = "d.buaa.edu.cn", path = "/", secure = true),
    )
    storage.addCookie(
        gatewayRoot,
        Cookie("CAS_LOGIN", "mock-cas", domain = "d.buaa.edu.cn", path = casPath, secure = true),
    )
    storage.addCookie(
        gatewayRoot,
        Cookie(
            "OTHER_UPSTREAM",
            "mock-other",
            domain = "d.buaa.edu.cn",
            path = otherPath,
            secure = true,
        ),
    )
    storage.addCookie(
        gatewayRoot,
        Cookie("ZFW_IDENTITY", "mock-zfw", domain = "d.buaa.edu.cn", path = zfwPath, secure = true),
    )

    try {
      clearZfwIdentityCookies(storage, ConnectionMode.WEBVPN)
      assertEquals(listOf("VPN_TICKET"), storage.get(gatewayRoot).map { it.name })
      assertTrue(storage.get(Url("https://d.buaa.edu.cn$casPath")).any { it.name == "CAS_LOGIN" })
      assertTrue(
          storage.get(Url("https://d.buaa.edu.cn$otherPath")).any { it.name == "OTHER_UPSTREAM" }
      )
      assertFalse(storage.get(zfwRoot).any { it.name == "ZFW_IDENTITY" })
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `direct verification still expires only ZFW domain cookies`() = runTest {
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    LocalCookieStore.settings = MapSettings()
    val storage = LocalCookieStore.storage(ConnectionMode.DIRECT)
    val zfwRoot = Url("https://zfw.buaa.edu.cn/")
    val otherRoot = Url("https://d.buaa.edu.cn/")
    storage.addCookie(
        zfwRoot,
        Cookie("ZFW_IDENTITY", "mock-zfw", domain = "zfw.buaa.edu.cn", path = "/", secure = true),
    )
    storage.addCookie(
        otherRoot,
        Cookie("VPN_TICKET", "mock-ticket", domain = "d.buaa.edu.cn", path = "/", secure = true),
    )

    try {
      clearZfwIdentityCookies(storage, ConnectionMode.DIRECT)
      assertFalse(storage.get(zfwRoot).any { it.name == "ZFW_IDENTITY" })
      assertTrue(storage.get(otherRoot).any { it.name == "VPN_TICKET" })
    } finally {
      ConnectionRuntime.clearSelectedMode()
    }
  }

  @Test
  fun `official sms challenge cannot complete login before user enters sms code`() = runTest {
    var authenticated = false
    var loginPosts = 0
    var validationPosts = 0
    val client =
        HttpClient(
            MockEngine { request ->
              when {
                request.url.encodedPath == "/home" ->
                    if (authenticated) respond(home)
                    else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
                request.url.encodedPath == "/site/captcha" &&
                    request.url.parameters["refresh"] == "1" ->
                    respond("{\"url\":\"/site/captcha?v=mock-fresh\"}")
                request.url.encodedPath == "/site/captcha" ->
                    respond(
                        byteArrayOf(1),
                        headers = headersOf(HttpHeaders.ContentType, "image/png"),
                    )
                request.url.encodedPath == "/site/validate-user" -> {
                  validationPosts++
                  val fields = (request.body as FormDataContent).formData
                  assertEquals("mock-encrypted", fields["LoginForm[password]"])
                  respond("{\"success\":true,\"inputSms\":true}")
                }
                request.url.encodedPath == "/site/validate-smscode" -> {
                  assertEquals("mock-sms", (request.body as FormDataContent).formData["code"])
                  respond("{\"success\":true}")
                }
                request.method == HttpMethod.Post -> {
                  loginPosts++
                  assertEquals(
                      "mock-sms",
                      (request.body as FormDataContent).formData["LoginForm[smsCode]"],
                  )
                  authenticated = true
                  respond("", HttpStatusCode.SeeOther)
                }
                else -> respond(login)
              }
            }
        ) {
          followRedirects = false
        }
    val coordinator =
        ZfwSessionCoordinator(
            { client },
            { CampusCredential("mock-user", "mock-password") },
            { _, _ -> "mock-encrypted" },
        )
    try {
      assertIs<ZfwSessionState.LoginCaptcha>(coordinator.ensureSession())
      assertIs<ZfwSessionState.SmsChallenge>(coordinator.submitLoginCaptcha("mock-code"))
      assertEquals(0, loginPosts)
      assertIs<ZfwSessionState.Ready>(coordinator.submitSmsCode("mock-sms"))
      assertEquals(1, validationPosts)
      assertEquals(1, loginPosts)
    } finally {
      coordinator.close()
    }
  }
}
