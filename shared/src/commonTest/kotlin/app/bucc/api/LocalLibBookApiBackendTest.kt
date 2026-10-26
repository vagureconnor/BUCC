package app.bucc.api

import app.bucc.api.auth.ApiCallException
import app.bucc.api.core.DefaultApiFactory
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalLibBookApiBackend
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.model.dto.LibBookReserveRequest
import app.bucc.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalLibBookApiBackendTest {
  @BeforeTest
  fun setup() {
    runTest { localConnectionTestMutex.lock() }
    ConnectionModeStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    ConnectionRuntime.apiFactoryProvider = { DefaultApiFactory }
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-05-08T08:00:00Z",
            lastActivity = "2026-05-08T08:30:00Z",
        )
    )
    LocalUpstreamClientProvider.reset()
  }

  @AfterTest
  fun tearDown() {
    LocalUpstreamClientProvider.reset()
    LocalAuthSessionStore.clearAllScopes()
    LocalCookieStore.clearAllScopes()
    ConnectionRuntime.clearSelectedMode()
    ConnectionRuntime.apiFactoryProvider = { DefaultApiFactory }
    localConnectionTestMutex.unlock()
  }

  @Test
  fun `WebVPN authorizes gateway before library CAS`() = runTest {
    val session = requireNotNull(LocalAuthSessionStore.get())
    for (mode in listOf(ConnectionMode.WEBVPN)) {
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      LocalAuthSessionStore.save(session)
      LocalUpstreamClientProvider.reset()
      var authorized = false
      var callbacks = 0
      val routes = mutableListOf<String>()
      val gatewayService = "https://d.buaa.edu.cn/login?cas_login=true"
      installMockClient(
          MockEngine { request ->
            val url = request.url
            val upstream =
                io.ktor.http.Url(
                    app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(url.toString())
                )
            routes += "${url.host}${upstream.encodedPath}:${url.parameters.names().sorted()}"
            when {
              url.host == "d.buaa.edu.cn" && url.encodedPath == "/" ->
                  if (authorized) respond("official portal", HttpStatusCode.OK)
                  else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/login"))
              url.host == "d.buaa.edu.cn" && url.encodedPath == "/login" -> {
                if (url.parameters["ticket"] != null) {
                  callbacks++
                  authorized = true
                  respond(
                      "",
                      HttpStatusCode.Found,
                      io.ktor.http.Headers.build {
                        append(HttpHeaders.Location, "/")
                        append(HttpHeaders.SetCookie, "gateway-auth=fixture; Path=/; Secure")
                      },
                  )
                } else
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl(
                                "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fd.buaa.edu.cn%2Flogin%3Fcas_login%3Dtrue"
                            ),
                        ),
                    )
              }
              upstream.host == "sso.buaa.edu.cn" &&
                  upstream.parameters["service"] == gatewayService -> {
                assertEquals("d.buaa.edu.cn", url.host)
                assertEquals("GET", request.method.value)
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "$gatewayService&ticket=ST-fixture"),
                )
              }
              upstream.host == "sso.buaa.edu.cn" -> {
                assertTrue(authorized, "the main SSO session does not authorize the WebVPN gateway")
                assertEquals("d.buaa.edu.cn", url.host)
                assertContains(
                    request.headers[HttpHeaders.Cookie].orEmpty(),
                    "gateway-auth=fixture",
                )
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(
                        HttpHeaders.Location,
                        "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=fixture",
                    ),
                )
              }
              upstream.encodedPath == "/v4/login/user" ->
                  respondJson("""{"code":0,"data":{"member":{"token":"fixture"}}}""")
              upstream.encodedPath == "/v4/seminar/siftdate" ->
                  respondJson("""{"code":0,"data":["2026-10-20"]}""")
              else -> error("Unexpected fixture gateway route")
            }
          }
      )
      val result =
          runCatching {
                LocalLibBookApiBackend()
                    .requestOfficial(
                        "seminar_siftdate",
                        "seminar/siftdate",
                        kotlinx.serialization.json.buildJsonObject {},
                        true,
                    )
              }
              .getOrElse {
                throw IllegalStateException("Gateway fixture failed for $mode after $routes", it)
              }
      assertTrue(result.toString().contains("2026-10-20"))
      assertEquals(1, callbacks)
    }
  }

  @Test
  fun `foreign CAS redirect cannot inject a library token`() = runTest {
    var requests = 0
    installMockClient(
        MockEngine {
          requests++
          respond(
              "",
              HttpStatusCode.Found,
              headersOf(HttpHeaders.Location, "https://unrelated.invalid/?cas=fixture"),
          )
        }
    )
    assertTrue(LocalLibBookApiBackend().getLibraries("2026-10-20").isFailure)
    assertEquals(1, requests)
  }

  @Test
  fun `WebVPN preserves gateway prefixes and browser origins across relative CAS redirects`() =
      runTest {
        val session = requireNotNull(LocalAuthSessionStore.get())
        for (mode in listOf(ConnectionMode.WEBVPN)) {
          ConnectionModeStore.save(mode)
          ConnectionRuntime.resolveSelectedMode()
          LocalAuthSessionStore.save(session)
          LocalUpstreamClientProvider.reset()
          val paths = mutableListOf<String>()
          val engine = MockEngine { request ->
            assertEquals("d.buaa.edu.cn", request.url.host)
            if (request.url.encodedPath == "/")
                return@MockEngine respond("official portal", HttpStatusCode.OK)
            assertEquals("https://d.buaa.edu.cn", request.headers[HttpHeaders.Origin])
            val original =
                io.ktor.http.Url(
                    app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString())
                )
            paths += original.encodedPath
            when (original.encodedPath) {
              "/login" ->
                  respond(
                      "",
                      HttpStatusCode.Found,
                      headersOf(HttpHeaders.Location, "/login/continue"),
                  )
              "/login/continue" ->
                  respond(
                      "",
                      HttpStatusCode.Found,
                      headersOf(
                          HttpHeaders.Location,
                          "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=fixture",
                      ),
                  )
              "/v4/login/user" ->
                  respondJson("""{"code":0,"data":{"member":{"token":"fixture"}}}""")
              "/v4/seminar/siftdate" -> respondJson("""{"code":0,"data":["2026-10-20"]}""")
              else -> error("Unexpected library fixture route")
            }
          }
          installMockClient(engine)
          val result =
              LocalLibBookApiBackend()
                  .requestOfficial(
                      "seminar_siftdate",
                      "seminar/siftdate",
                      kotlinx.serialization.json.buildJsonObject {},
                      true,
                  )
          assertTrue(result.toString().contains("2026-10-20"))
          assertEquals(
              listOf("/login", "/login/continue", "/v4/login/user", "/v4/seminar/siftdate"),
              paths,
          )
        }
      }

  @Test
  fun `snapshot keeps percentage geometry state maps and per day slots`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=fixture",
                ),
            )
        "/v4/login/user" -> respondJson("""{"code":0,"data":{"member":{"token":"synthetic"}}}""")
        "/v4/Space/map" ->
            respondJson(
                """{"code":0,"data":{"name":"fixture area","date":{"list":[{"day":"2026-10-17","times":[{"id":1,"start":"08:00","end":"10:00"}]},{"day":"2026-10-18","times":[{"id":2,"start":"18:00","end":"20:00"}]}]}}}"""
            )
        "/v4/Space/seat" ->
            respondJson(
                """{"code":0,"data":{"list":[{"id":101,"no":"001","status":1,"in_label":1,"point_x":"12.25","point_y":18,"width":2,"height":3}],"seat_info":{"map":{"free":"/map/free.jpg","book":"/map/book.jpg"}}}}"""
            )
        else -> error("Unexpected fixture endpoint")
      }
    }
    installMockClient(engine)
    val backend = LocalLibBookApiBackend()
    val detail = backend.getAreaDetail("8").getOrThrow()
    val result = backend.getSeatSnapshot("8", "2026-10-17", "08:00", "10:00").getOrThrow()
    assertEquals("18:00", detail.timeSlotsByDay.getValue("2026-10-18").single().start)
    assertEquals(12.25, result.seats.single().pointX)
    assertEquals(18.0, result.seats.single().pointY)
    assertEquals("/map/book.jpg", result.mapImages.book)
  }

  @Test
  fun `expired mutation is never replayed automatically`() = runTest {
    var submits = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=fixture",
                ),
            )
        "/v4/login/user" -> respondJson("""{"code":0,"data":{"member":{"token":"synthetic"}}}""")
        "/v4/space/confirm" -> {
          submits++
          respondJson("""{"code":-1,"message":"登录失效"}""")
        }
        else -> respondJson("""{"code":-1,"message":"未登录"}""")
      }
    }
    installMockClient(engine)
    val result =
        LocalLibBookApiBackend().reserve(LibBookReserveRequest("8", "101", "2026-10-17", "morning"))
    assertTrue(result.isFailure)
    assertEquals(1, submits)
  }

  @Test
  fun `seat property restrictions prevent selecting a nominally free seat`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=fixture",
                ),
            )
        "/v4/login/user" -> respondJson("""{"code":0,"data":{"member":{"token":"synthetic"}}}""")
        "/v4/Space/seat" ->
            respondJson(
                """{"code":0,"data":{"list":[{"id":101,"no":"001","status":1,"in_label":0},{"id":102,"no":"002","status":1,"in_label":1}]}}"""
            )
        else -> error("Unexpected fixture endpoint")
      }
    }
    installMockClient(engine)
    val seats = LocalLibBookApiBackend().getSeats("8", "2026-10-17", "08:00", "09:00").getOrThrow()
    assertEquals(listOf(false, true), seats.map { it.isAvailable })
  }

  @Test
  fun `libbook backend exchanges cas token and calls v4 APIs`() = runTest {
    var casLoginCalls = 0
    var loginUserCalls = 0
    var reserveBody = ""
    var cancelBody = ""
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" -> {
          casLoginCalls++
          respond(
              content = ByteReadChannel.Empty,
              status = HttpStatusCode.Found,
              headers =
                  headersOf(
                      HttpHeaders.Location,
                      "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=cas-1",
                  ),
          )
        }
        "/v4/login/user" -> {
          loginUserCalls++
          assertContains(request.bodyText(), "\"cas\":\"cas-1\"")
          respondJson(
              """
              {
                "code":0,
                "data":{
                  "member":{
                    "token":"jwt-1",
                    "name":"测试用户"
                  }
                }
              }
              """
                  .trimIndent()
          )
        }
        "/v4/space/pcTopFor" -> {
          assertEquals("bearerjwt-1", request.headers[HttpHeaders.Authorization])
          respondJson(
              """
              {
                "code":1,
                "data":{
                  "list":[
                    {
                      "id":"9",
                      "name":"学院路校区图书馆",
                      "free_num":12,
                      "total_num":100,
                      "children":[
                        {"id":"10","name":"一层","free_num":5,"total_num":30}
                      ]
                    }
                  ]
                }
              }
              """
                  .trimIndent()
          )
        }
        "/v4/space/pick" ->
            respondJson(
                """
                {
                  "code":1,
                  "data":{
                    "area":[
                      {"id":"8","name":"一层西阅学空间","area":"学院路","free_num":2,"total_num":10}
                    ]
                  }
                }
                """
                    .trimIndent()
            )
        "/v4/Space/map" ->
            respondJson(
                """
                {
                  "code":1,
                  "data":{
                    "area":{"id":"8","name":"一层西阅学空间"},
                    "date":{
                      "list":[
                        {
                          "day":"2026-05-08",
                          "times":[{"id":"seg-1","start":"08:00","end":"23:00"}]
                        }
                      ]
                    }
                  }
                }
                """
                    .trimIndent()
            )
        "/v4/Space/seat" ->
            respondJson(
                """
                {
                  "code":1,
                  "data":{
                    "list":[
                      {"id":"101","name":"座位101","no":"101","status":"1","status_name":"空闲"},
                      {"id":"102","name":"座位102","no":"102","status":"2","status_name":"已占用"}
                    ]
                  }
                }
                """
                    .trimIndent()
            )
        "/v4/space/confirm" -> {
          reserveBody = request.bodyText()
          respondJson(
              """{"code":1,"message":"操作成功","data":{"bookInfo":{"id":"b1","nameMerge":"一层 / 101","no":"101"}}}"""
          )
        }
        "/v4/member/seat" ->
            respondJson(
                """
                {
                  "code":1,
                  "data":{
                    "data":[{"id":"b1","nameMerge":"一层 / 101","no":"101","beginTime":"08:00","endTime":"23:00","status_name":"已预约"}],
                    "total":1
                  }
                }
                """
                    .trimIndent()
            )
        "/v4/space/cancel" -> {
          cancelBody = request.bodyText()
          respondJson("""{"code":1,"message":"取消成功"}""")
        }
        else -> error("Unexpected request: ${request.url}")
      }
    }
    installMockClient(engine)

    val backend = LocalLibBookApiBackend()
    val libraries = backend.getLibraries("2026-05-08").getOrThrow()
    val areas = backend.getAreas("9", "10", "2026-05-08").getOrThrow()
    val detail = backend.getAreaDetail("8").getOrThrow()
    val seats = backend.getSeats("8", "2026-05-08", "08:00", "23:00").getOrThrow()
    val reserve =
        backend
            .reserve(
                LibBookReserveRequest(
                    areaId = "8",
                    seatId = "101",
                    day = "2026-05-08",
                    segment = "seg-1",
                )
            )
            .getOrThrow()
    val bookings = backend.getBookings().getOrThrow()
    val cancel = backend.cancelBooking("b1").getOrThrow()

    assertEquals(1, casLoginCalls)
    assertEquals(1, loginUserCalls)
    assertEquals("学院路校区图书馆", libraries.single().name)
    assertEquals("一层", libraries.single().storeys.single().name)
    assertEquals("一层西阅学空间", areas.single().name)
    assertEquals("seg-1", detail.timeSlots.single().id)
    assertEquals(listOf(true, false), seats.map { it.isAvailable })
    assertEquals("操作成功", reserve.message)
    assertContains(
        reserveBody,
        "lGWxL9YCYE0sXIQzPsUCs3jfaFPunT/NyR93uF2nVP1OQPYYihpMRBvm7jxYdUZNTMCyIRtdY8d3DgCNz8G3lmeWmPjvy6jV2KeuJXR8nrOmk26JK+ATZB1VXBNOFebA",
    )
    assertEquals(1, bookings.bookings.size)
    assertTrue(cancel.success)
    assertContains(cancelBody, "\"id\":\"b1\"")
  }

  @Test
  fun `libbook backend extracts cas token from h5 fragment redirect`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://booking.lib.buaa.edu.cn/v4/login/cas?ticket=ST-1",
                    ),
            )
        "/v4/login/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://booking.lib.buaa.edu.cn/h5/index.html#/cas/?cas=cas-fragment",
                    ),
            )
        "/v4/login/user" -> {
          assertContains(request.bodyText(), "\"cas\":\"cas-fragment\"")
          respondJson(
              """
              {
                "code":0,
                "data":{
                  "member":{"token":"jwt-fragment","name":"测试用户"}
                }
              }
              """
                  .trimIndent()
          )
        }
        "/v4/space/pcTopFor" -> {
          assertEquals("bearerjwt-fragment", request.headers[HttpHeaders.Authorization])
          respondJson(
              """
              {
                "code":1,
                "data":{
                  "list":[
                    {"id":"9","name":"学院路校区图书馆","free_num":12,"total_num":100}
                  ]
                }
              }
              """
                  .trimIndent()
          )
        }
        else -> error("Unexpected request: ${request.url}")
      }
    }
    installMockClient(engine)

    val libraries = LocalLibBookApiBackend().getLibraries("2026-05-08").getOrThrow()

    assertEquals("学院路校区图书馆", libraries.single().name)
  }

  @Test
  fun `cancel ended booking is distinct from a missing record without breaking later requests`() =
      runTest {
        var loginUserCalls = 0
        var cancelCalls = 0
        var librariesCalls = 0
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/login" ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location,
                            "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=cas-ended",
                        ),
                )
            "/v4/login/user" -> {
              loginUserCalls++
              respondJson(
                  """
                  {
                    "code":0,
                    "data":{"member":{"token":"jwt-ended","name":"测试用户"}}
                  }
                  """
                      .trimIndent()
              )
            }
            "/v4/space/cancel" -> {
              cancelCalls++
              respondJson("""{"code":2,"message":"预约已结束，不能取消"}""")
            }
            "/v4/space/pcTopFor" -> {
              librariesCalls++
              assertEquals("bearerjwt-ended", request.headers[HttpHeaders.Authorization])
              respondJson(
                  """
                  {
                    "code":1,
                    "data":{"list":[{"id":"9","name":"学院路校区图书馆"}]}
                  }
                  """
                      .trimIndent()
              )
            }
            else -> error("Unexpected request: ${request.url}")
          }
        }
        installMockClient(engine)
        val backend = LocalLibBookApiBackend()

        val error = backend.cancelBooking("b-ended").exceptionOrNull()
        val libraries = backend.getLibraries("2026-05-08").getOrThrow()

        assertTrue(error is ApiCallException)
        assertEquals("libbook_booking_ended", error.code)
        assertEquals("学院路校区图书馆", libraries.single().name)
        assertEquals(1, loginUserCalls)
        assertEquals(1, cancelCalls)
        assertEquals(1, librariesCalls)
      }

  @Test
  fun `mutations reject empty unknown and negative success responses`() = runTest {
    var loginUserCalls = 0
    var cancelCalls = 0
    var librariesCalls = 0
    var mutationResponse = "{}"
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/login" ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location,
                            "https://booking.lib.buaa.edu.cn/v4/login/cas?cas=cas-ended",
                        ),
                )
            "/v4/login/user" -> {
              loginUserCalls++
              respondJson(
                  """
                  {
                    "code":0,
                    "data":{"member":{"token":"jwt-ended","name":"测试用户"}}
                  }
                  """
                      .trimIndent()
              )
            }
            "/v4/space/cancel" -> {
              cancelCalls++
              respondJson(mutationResponse)
            }
            "/v4/space/pcTopFor" -> {
              librariesCalls++
              assertEquals("bearerjwt-ended", request.headers[HttpHeaders.Authorization])
              respondJson(
                  """
                  {
                    "code":1,
                    "data":{"list":[{"id":"9","name":"学院路校区图书馆"}]}
                  }
                  """
                      .trimIndent()
              )
            }
            else -> error("Unexpected request: ${request.url}")
          }
        }
    installMockClient(engine)
    val backend = LocalLibBookApiBackend()
    for (response in listOf("{}", """{"message":"操作成功"}""", """{"code":7,"message":"操作成功"}""", """{"code":0,"message":"预约未成功"}""")) {
      mutationResponse = response
      assertTrue(backend.cancelBooking("test-record").isFailure, response)
    }
    mutationResponse = """{"code":0,"message":"取消成功"}"""
    assertTrue(backend.cancelBooking("test-record").getOrThrow().success)
  }

  private fun installMockClient(engine: MockEngine) {
    val factory = { followRedirects: Boolean ->
      HttpClient(engine) {
        this.followRedirects = followRedirects
        install(ContentNegotiation) { json() }
        install(HttpCookies) { storage = LocalCookieStore.storage() }
      }
    }
    LocalUpstreamClientProvider.clientFactory = factory
    LocalUpstreamClientProvider.libBookClientFactory = factory
  }

  private fun io.ktor.client.request.HttpRequestData.bodyText(): String =
      when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("Unsupported request body: ${content::class.simpleName}")
      }
}

private fun MockRequestHandleScope.respondJson(body: String) =
    respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
