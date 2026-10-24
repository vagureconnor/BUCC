package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class TdHealthCloudClientTest {
  private lateinit var previousModeSettings: Settings

  @BeforeTest
  fun useIsolatedDirectMode() {
    previousModeSettings = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
  }

  @AfterTest
  fun restoreModeSettings() {
    ConnectionModeStore.settings = previousModeSettings
    ConnectionRuntime.resolveSelectedMode()
  }

  @Test
  fun semesterDaysUseBoundedParallelReadsAndKeepRecentCacheFresh() = runTest {
    val inFlight = MutableStateFlow(0)
    val maximum = MutableStateFlow(0)
    val days = MutableStateFlow<List<String>>(emptyList())
    val engine = MockEngine { request ->
      val body =
          when {
            request.url.encodedPath.endsWith("index.php") -> loginForm()
            request.url.encodedPath.endsWith("login") -> "登录完成"
            request.url.encodedPath.endsWith("main.php") ->
                """<input id="xnxq" value="2026-2027-1">"""
            else -> {
              val day = (request.body as FormDataContent).formData["detailDate"]!!
              days.update { it + day }
              val active = inFlight.updateAndGet { it + 1 }
              maximum.update { maxOf(it, active) }
              try {
                delay(100)
              } finally {
                inFlight.update { it - 1 }
              }
              """{"status":1,"count":1,"infoProperty":1,"infoList":[{"mark":"1","location":"TD出口","swaptime":"$day 12:00:00"}]}"""
            }
          }
      respond(body, HttpStatusCode.OK, htmlHeaders())
    }
    val client = healthCloudClient(HttpClient(engine), baseUrl = "http://healthcloud.test/")
    val initial = client.queryCounts("student-fixture", "password-fixture", LocalDate(2026, 8, 10))
    assertEquals(10, initial.semesterCount)
    assertEquals(10, initial.dayCounts.size)
    assertTrue(maximum.value in 2..4, "Expected bounded parallel day queries, got ${maximum.value}")
    assertEquals(10, days.value.distinct().size)
    days.value = emptyList()
    val cached =
        client.queryCounts(
            "student-fixture",
            "password-fixture",
            LocalDate(2026, 8, 10),
            cachedTerm = initial.term,
            cachedDayCounts = initial.dayCounts,
        )
    assertEquals(setOf("2026-08-09", "2026-08-10"), days.value.toSet())
    assertEquals(10, cached.semesterCount)
  }

  @Test
  fun loginAndPaginationCountOnlyValidExitRows() = runTest {
    val pages = mutableListOf<String>()
    val engine = MockEngine { request ->
      val content =
          when {
            request.url.encodedPath.endsWith("index.php") ->
                """<form action="login"><input name="username"><input name="password"><input name="execution" value="csrf"></form>"""
            request.url.encodedPath.endsWith("login") -> "登录完成"
            request.url.encodedPath.endsWith("main.php") ->
                """<select id="xnxq"><option selected value="2026-2027-1">当前学期</option></select>"""
            else -> {
              val page = (request.body as FormDataContent).formData["trgtPage"].orEmpty()
              pages += page
              if (page == "1") {
                """{"status":1,"count":21,"infoProperty":1,"infoList":[{"mark":"1","location":"TD出口1","swaptime":"2026-09-28 12:00:00"},{"mark":"1","location":"TD入口1","swaptime":"2026-09-28 12:01:00"}]}"""
              } else {
                """{"status":1,"count":21,"infoProperty":1,"infoList":[{"mark":"1","location":"TD出口2","swaptime":"2026-09-28 13:00:00"},{"mark":"0","location":"TD出口3","swaptime":"2026-09-28 14:00:00"}]}"""
              }
            }
          }
      respond(
          content = content,
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
      )
    }
    val client = healthCloudClient(HttpClient(engine), baseUrl = "http://healthcloud.test/")

    val count =
        client.queryTodayValidExitCount(
            studentId = "20370001",
            password = "secret",
            day = LocalDate(2026, 9, 28),
        )

    assertEquals(2, count)
    assertEquals(listOf("1", "2"), pages)
  }

  @Test
  fun postLoginRedirectUsesGetWithoutForwardingCredentials() = runTest {
    listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther).forEach { redirectStatus ->
      val requests = mutableListOf<Pair<HttpMethod, String>>()
      val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += request.method to path
        when {
          path.endsWith("index.php") ->
              respond(
                  content = loginForm(),
                  status = HttpStatusCode.OK,
                  headers = htmlHeaders(),
              )
          path.endsWith("login") -> {
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("20370001", (request.body as FormDataContent).formData["username"])
            assertEquals("secret", (request.body as FormDataContent).formData["password"])
            respond(
                content = "",
                status = redirectStatus,
                headers = headersOf(HttpHeaders.Location, "/signed-in"),
            )
          }
          path.endsWith("signed-in") -> {
            assertEquals(HttpMethod.Get, request.method)
            assertFalse(request.body is FormDataContent)
            assertEquals(null, request.headers[HttpHeaders.ContentType])
            assertEquals(null, request.url.parameters["username"])
            assertEquals(null, request.url.parameters["password"])
            respond("已登录", HttpStatusCode.OK, htmlHeaders())
          }
          path.endsWith("main.php") ->
              respond(
                  """<input id="xnxq" value="2026-2027-1">""",
                  HttpStatusCode.OK,
                  htmlHeaders(),
              )
          else ->
              respond(
                  """{"status":1,"count":0,"infoProperty":1,"infoList":[]}""",
                  HttpStatusCode.OK,
                  headersOf(HttpHeaders.ContentType, "application/json"),
              )
        }
      }
      val client = healthCloudClient(HttpClient(engine), baseUrl = "http://healthcloud.test/")

      assertEquals(
          0,
          client.queryTodayValidExitCount(
              studentId = "20370001",
              password = "secret",
              day = LocalDate(2026, 10, 12),
          ),
      )
      assertEquals(
          listOf(
              HttpMethod.Get to "/index.php",
              HttpMethod.Post to "/login",
              HttpMethod.Get to "/signed-in",
              HttpMethod.Get to "/main.php",
              HttpMethod.Post to "/api.php",
          ),
          requests,
      )
    }
  }

  @Test
  fun loginRedirectThatStillShowsLoginFormIsRejected() = runTest {
    listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther).forEach { redirectStatus ->
      val requests = mutableListOf<String>()
      val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += path
        when {
          path.endsWith("index.php") -> respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
          path.endsWith("login") ->
              respond(
                  content = "",
                  status = redirectStatus,
                  headers = headersOf(HttpHeaders.Location, "/signed-in"),
              )
          path.endsWith("signed-in") -> respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
          else -> error("登录仍显示表单时不应继续查询：$path")
        }
      }
      val client = healthCloudClient(HttpClient(engine), baseUrl = "http://healthcloud.test/")

      val failure =
          assertFailsWith<IllegalStateException> {
            client.queryTodayValidExitCount(
                studentId = "20370001",
                password = "secret",
                day = LocalDate(2026, 10, 12),
            )
          }

      assertContains(failure.message.orEmpty(), "健康云登录失败")
      assertEquals(listOf("/index.php", "/login", "/signed-in"), requests)
    }
  }

  @Test
  fun readOnlyModesUseOfficialVpnWithoutAttemptingTheUnreachableDirectEntry() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val hosts = mutableListOf<String>()
    var posts = 0
    val engine = MockEngine { request ->
      hosts += request.url.host
      assertEquals("d.buaa.edu.cn", request.url.host)
      assertEquals(443, request.url.port)
      assertEquals("https", request.url.protocol.name)
      if (request.method == HttpMethod.Post) posts++
      when (
          Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
              .encodedPath
      ) {
        "/index.php" ->
            respond(
                loginForm().replace("action=\"login\"", "action=\"http://10.212.28.38/login\""),
                HttpStatusCode.OK,
                htmlHeaders(),
            )
        "/login" -> respond("已登录", HttpStatusCode.OK, htmlHeaders())
        "/main.php" ->
            respond("""<input id="xnxq" value="2026-2027-1">""", HttpStatusCode.OK, htmlHeaders())
        else ->
            respond("""{"status":1,"count":0,"infoList":[]}""", HttpStatusCode.OK, htmlHeaders())
      }
    }
    assertEquals(
        0,
        healthCloudClient(HttpClient(engine))
            .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18)),
    )
    assertEquals(List(4) { "d.buaa.edu.cn" }, hosts)
    assertEquals(2, posts)
  }

  @Test
  fun existingGatewayHealthCloudSessionDoesNotRequireAnotherPasswordPost() = runTest {
    for (mode in listOf(ConnectionMode.WEBVPN)) {
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      var credentialPosts = 0
      val client =
          healthCloudClient(
              HttpClient(
                  MockEngine { request ->
                    val target =
                        Url(
                            app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(
                                request.url.toString()
                            )
                        )
                    when (target.encodedPath) {
                      "/index.php" ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(HttpHeaders.Location, "main.php"),
                          )
                      "/main.php" ->
                          respond(
                              if (target.parameters["module"] == "stu")
                                  "<input id='xnxq' value='2026-2027-1'>"
                              else "<html>authenticated navigation</html>",
                              HttpStatusCode.OK,
                              htmlHeaders(),
                          )
                      "/api.php" ->
                          respond(
                              """{"status":1,"count":0,"infoList":[]}""",
                              HttpStatusCode.OK,
                              htmlHeaders(),
                          )
                      else -> {
                        credentialPosts++
                        error("An authenticated health-cloud session must not submit credentials")
                      }
                    }
                  }
              ) {
                followRedirects = false
              }
          )
      assertEquals(
          0,
          client.queryTodayValidExitCount("fixture", "must-not-be-sent", LocalDate(2026, 10, 20)),
      )
      assertEquals(0, credentialPosts)
    }
  }

  @Test
  fun coldGatewaySessionFollowsRootRelativeGatewayCasCallbacks() = runTest {
    for (mode in listOf(ConnectionMode.WEBVPN)) {
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      val callback =
          Url(
              app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl(
                  "http://10.212.28.38/index.php?ticket=fixture-only"
              )
          )
      val main =
          Url(app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl("http://10.212.28.38/main.php"))
      val client =
          healthCloudClient(
              HttpClient(
                  MockEngine { request ->
                    val target =
                        Url(
                            app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(
                                request.url.toString()
                            )
                        )
                    when {
                      target.host == "sso.buaa.edu.cn" ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(
                                  HttpHeaders.Location,
                                  callback.encodedPath + "?ticket=fixture-only",
                              ),
                          )
                      target.encodedPath == "/index.php" && target.parameters["ticket"] != null ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(HttpHeaders.Location, main.encodedPath),
                          )
                      target.encodedPath == "/index.php" ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf(HttpHeaders.Location, "https://sso.buaa.edu.cn/login"),
                          )
                      target.encodedPath == "/main.php" ->
                          respond(
                              "<input id='xnxq' value='2026-2027-1'>",
                              HttpStatusCode.OK,
                              htmlHeaders(),
                          )
                      target.encodedPath == "/api.php" ->
                          respond(
                              """{"status":1,"count":0,"infoList":[]}""",
                              HttpStatusCode.OK,
                              htmlHeaders(),
                          )
                      else ->
                          error(
                              "Gateway prefix was incorrectly forwarded into the health-cloud path"
                          )
                    }
                  }
              ) {
                followRedirects = false
              }
          )
      assertEquals(0, client.queryTodayValidExitCount("fixture", "", LocalDate(2026, 10, 20)))
    }
  }

  @Test
  fun missingLoginFormWithInvalidTermCannotBecomeAnAuthenticatedSession() = runTest {
    var recordRequests = 0
    val client =
        healthCloudClient(
            HttpClient(
                MockEngine { request ->
                  when (request.url.encodedPath) {
                    "/index.php" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "main.php"),
                        )
                    "/main.php" ->
                        respond(
                            "<input id='xnxq' value='invalid-term'>",
                            HttpStatusCode.OK,
                            htmlHeaders(),
                        )
                    else -> {
                      recordRequests++
                      respond(
                          """{"status":1,"count":0,"infoList":[]}""",
                          HttpStatusCode.OK,
                          htmlHeaders(),
                      )
                    }
                  }
                }
            ) {
              followRedirects = false
            }
        )
    assertFailsWith<IllegalStateException> {
      client.queryTodayValidExitCount("fixture", "", LocalDate(2026, 10, 20))
    }
    assertEquals(0, recordRequests)
  }

  @Test
  fun webVpnUsesTheOfficialGatewayForThePrivateHealthCloudOrigin() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val upstreams = mutableListOf<String>()
    val engine = MockEngine { request ->
      assertEquals("d.buaa.edu.cn", request.url.host)
      val upstream = app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString())
      upstreams += upstream
      assertEquals("10.212.28.38", Url(upstream).host)
      when (Url(upstream).encodedPath) {
        "/index.php" -> respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
        "/login" -> respond("已登录", HttpStatusCode.OK, htmlHeaders())
        "/main.php" ->
            respond("<input id=\"xnxq\" value=\"2026-2027-1\">", HttpStatusCode.OK, htmlHeaders())
        else ->
            respond("{\"status\":1,\"count\":0,\"infoList\":[]}", HttpStatusCode.OK, htmlHeaders())
      }
    }
    assertEquals(
        0,
        healthCloudClient(HttpClient(engine))
            .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18)),
    )
    assertEquals(
        listOf(
            "http://10.212.28.38/index.php",
            "http://10.212.28.38/login",
            "http://10.212.28.38/main.php?module=stu&title=stu_sun_score",
            "http://10.212.28.38/api.php?module=stu&file=ajax_stu&title=stu_sun_score&action=getRecordDetail&isAjax=1",
        ),
        upstreams,
    )
  }

  @Test
  fun loginFormCannotSendCredentialsToAnUnrelatedOrigin() = runTest {
    val requests = mutableListOf<String>()
    val engine = MockEngine { request ->
      requests += request.url.host
      respond(
          loginForm().replace("action=\"login\"", "action=\"https://unrelated.invalid/login\""),
          HttpStatusCode.OK,
          htmlHeaders(),
      )
    }
    assertFailsWith<IllegalStateException> {
      healthCloudClient(HttpClient(engine), baseUrl = "http://healthcloud.test/")
          .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18))
    }
    assertEquals(listOf("healthcloud.test"), requests)
  }

  @Test
  fun postFailureDoesNotRetryLoginOrChangeEntry() = runTest {
    var gets = 0
    var posts = 0
    val engine = MockEngine { request ->
      assertEquals("10.212.28.38", request.url.host)
      if (request.method == HttpMethod.Post) {
        posts++
        throw fixtureConnectionFailure()
      }
      gets++
      respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
    }
    assertFailsWith<IllegalStateException> {
      healthCloudClient(HttpClient(engine))
          .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18))
    }
    assertEquals(1, posts)
    assertEquals(1, gets)
  }

  @Test
  fun vpnPortalFormIsNotTreatedAsHealthCloudLogin() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val requests = mutableListOf<String>()
    val engine = MockEngine { request ->
      requests += request.url.host
      assertEquals("d.buaa.edu.cn", request.url.host)
      respond(
          "",
          HttpStatusCode.Found,
          headersOf(HttpHeaders.Location, "https://vpn.buaa.edu.cn/portal/#!/login"),
      )
    }
    val error =
        assertFailsWith<IllegalStateException> {
          healthCloudClient(HttpClient(engine) { followRedirects = false })
              .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18))
        }
    assertContains(error.message.orEmpty(), "VPN")
    assertEquals(listOf("d.buaa.edu.cn"), requests)
  }

  @Test
  fun healthyDirectEntryNeverUsesVpn() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    val hosts = mutableListOf<String>()
    val engine = MockEngine { request ->
      hosts += request.url.host
      when (request.url.encodedPath) {
        "/index.php" -> respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
        "/login" -> respond("已登录", HttpStatusCode.OK, htmlHeaders())
        "/main.php" ->
            respond("""<input id="xnxq" value="2026-2027-1">""", HttpStatusCode.OK, htmlHeaders())
        else ->
            respond("""{"status":1,"count":0,"infoList":[]}""", HttpStatusCode.OK, htmlHeaders())
      }
    }
    assertEquals(
        0,
        healthCloudClient(HttpClient(engine))
            .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18)),
    )
    assertEquals(List(4) { "10.212.28.38" }, hosts)
  }

  @Test
  fun externalRedirectIsRejectedBeforeItsTargetIsRequested() = runTest {
    var requests = 0
    val engine = MockEngine {
      requests++
      respond(
          "",
          HttpStatusCode.Found,
          headersOf(HttpHeaders.Location, "https://unrelated.invalid/login"),
      )
    }
    assertFailsWith<IllegalStateException> {
      healthCloudClient(
              HttpClient(engine) { followRedirects = false },
              baseUrl = "http://healthcloud.test/",
          )
          .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18))
    }
    assertEquals(1, requests)
  }

  @Test
  fun officialSsoCanReturnToDirectOrVpnHealthCloudWithoutReplayingPassword() = runTest {
    for (mode in listOf(ConnectionMode.DIRECT, ConnectionMode.WEBVPN)) {
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      var passwordPosts = 0
      val engine = MockEngine { request ->
        val upstream =
            Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
        if (upstream.encodedPath == "/index.php") {
          respond(
              "",
              HttpStatusCode.Found,
              headersOf(HttpHeaders.Location, "https://sso.buaa.edu.cn/login"),
          )
        } else if (upstream.host == "sso.buaa.edu.cn") {
          if (request.method == HttpMethod.Post) {
            passwordPosts++
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "http://10.212.28.38/main.php"),
            )
          } else respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
        } else {
          assertEquals("10.212.28.38", upstream.host)
          when (upstream.encodedPath) {
            "/main.php" ->
                respond(
                    """<input id="xnxq" value="2026-2027-1">""",
                    HttpStatusCode.OK,
                    htmlHeaders(),
                )
            else ->
                respond(
                    """{"status":1,"count":0,"infoList":[]}""",
                    HttpStatusCode.OK,
                    htmlHeaders(),
                )
          }
        }
      }
      assertEquals(
          0,
          healthCloudClient(HttpClient(engine) { followRedirects = false })
              .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18)),
      )
      assertEquals(1, passwordPosts)
    }
  }

  @Test
  fun directAndUnknownUseDirectEntryWhileWebVpnUsesTheGatewayRoute() = runTest {
    for (mode in listOf(ConnectionMode.DIRECT, ConnectionMode.WEBVPN, null)) {
      if (mode == null) ConnectionModeStore.clear() else ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      val hosts = mutableListOf<String>()
      val expectedHost = if (mode == ConnectionMode.WEBVPN) "d.buaa.edu.cn" else "10.212.28.38"
      val engine = MockEngine { request ->
        hosts += request.url.host
        throw fixtureConnectionFailure()
      }
      assertFailsWith<IllegalStateException> {
        healthCloudClient(HttpClient(engine))
            .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18))
      }
      assertEquals(listOf(expectedHost), hosts, "Health Cloud route must match $mode mode")
    }
  }

  @Test
  fun changingAwayFromWebVpnStopsFurtherVpnRequests() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    val hosts = mutableListOf<String>()
    val engine = MockEngine { request ->
      hosts += request.url.host
      ConnectionModeStore.save(ConnectionMode.DIRECT)
      ConnectionRuntime.resolveSelectedMode()
      respond(loginForm(), HttpStatusCode.OK, htmlHeaders())
    }
    assertFailsWith<IllegalStateException> {
      healthCloudClient(HttpClient(engine))
          .queryTodayValidExitCount("synthetic", "fixture-only", LocalDate(2026, 10, 18))
    }
    assertEquals(listOf("d.buaa.edu.cn"), hosts)
  }

  // Authentication handoff is covered by LocalLibBookApiBackendTest; these fixtures test
  // health-cloud transport and parsing.
  private fun healthCloudClient(
      client: HttpClient,
      baseUrl: String = TdDefaults.healthCloudBaseUrl,
  ) = TdHealthCloudClient(client, baseUrl, prepareGateway = {})

  private fun fixtureConnectionFailure() = IllegalStateException("synthetic connection failure")

  private fun loginForm(): String =
      """<form action="login"><input name="username"><input name="password"><input name="execution" value="csrf"></form>"""

  private fun htmlHeaders() = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
}
