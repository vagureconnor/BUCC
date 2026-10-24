package app.bucc.api

import app.bucc.api.core.DefaultApiFactory
import app.bucc.api.feature.SpocApi
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalSpocClient
import app.bucc.api.local.LocalSpocCrypto
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.plane.PaperPlaneApi
import app.bucc.model.dto.SpocSubmissionStatus
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LocalSpocApiBackendTest {
  @Test
  fun `paper plane schedule reads independent weeks concurrently and retains every class`() =
      runTest {
        val secondWeekStarted = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" ->
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                    ),
                )
            "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            "/spocnewht/inco/ht/queryOne" -> respondJson("""{"code":200,"content":{}}""")
            "/spocnewht/inco/ht/queryList" -> {
              val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
              val payload =
                  json
                      .parseToJsonElement(
                          LocalSpocCrypto.decryptParam(body["param"]!!.jsonPrimitive.content)
                      )
                      .jsonObject
              when (payload["sqlid"]!!.jsonPrimitive.content) {
                "17139418053476da3da1fa8ba8e717e1aaa5d5682d5be31" ->
                    respondJson("""{"code":200,"content":[{"sfdqxq":"1","xnxq":"mock-term"}]}""")
                "17272305190027285c6d5e1a68532c347c6efc5755da1" ->
                    respondJson(
                        """{"code":200,"content":[{"zksrq":"week-a","zjsrq":"end-a"},{"zksrq":"week-b","zjsrq":"end-b"}]}"""
                    )
                else -> {
                  val first = payload["zksrq"]!!.jsonPrimitive.content == "week-a"
                  if (first)
                      withContext(Dispatchers.Default) {
                        withTimeout(2_000) { secondWeekStarted.await() }
                      }
                  else secondWeekStarted.complete(Unit)
                  respondJson(
                      if (first) """{"code":200,"content":[{"hdktid":"class-a"}]}"""
                      else """{"code":200,"content":[{"hdktid":"class-b"}]}"""
                  )
                }
              }
            }
            else -> error("Unexpected synthetic request")
          }
        }
        useMockUpstream(engine)
        val schedule = LocalSpocClient().getPaperPlaneCurrentTermSchedule()
        assertEquals(listOf("class-a", "class-b"), schedule.sessions.map { it.hdktid })
      }

  @Test
  fun `paper plane read retains credentials while another read refreshes authentication`() =
      runTest {
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var logins = 0
        var courseReads = 0
        val integralRead = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" -> {
              if (++logins > 1) {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
              }
              respond(
                  "",
                  HttpStatusCode.Found,
                  headersOf(
                      HttpHeaders.Location,
                      "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                  ),
              )
            }
            "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            "/spocnewht/inco/ht/queryList" -> {
              listStarted.complete(Unit)
              releaseList.await()
              respondJson("""{"code":200,"content":[]}""")
            }
            "/spocnewht/inco/ht/queryOne" -> {
              integralRead.complete(Unit)
              respondJson("""{"code":200,"content":{"jf":"42"}}""")
            }
            "/spocnewht/jxkj/queryKclb" ->
                if (++courseReads == 1) respond("", HttpStatusCode.Unauthorized)
                else respondJson("""{"code":200,"content":[]}""")
            else -> error("Unexpected synthetic request")
          }
        }
        useMockUpstream(engine)
        val client = LocalSpocClient()
        val tasks = async { client.getPaperPlaneTasks("mock-student") }
        listStarted.await()
        val courses = async { client.getCourses("mock-term") }
        refreshStarted.await()
        releaseList.complete(Unit)
        try {
          withContext(Dispatchers.Default) { withTimeout(2_000) { integralRead.await() } }
        } finally {
          releaseRefresh.complete(Unit)
        }
        tasks.await()
        courses.await()
      }

  @Test
  fun `post throw feed uses the same page size as one click continuation`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/inco/ht/queryListByPage" -> {
          val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
          val payload =
              json
                  .parseToJsonElement(
                      LocalSpocCrypto.decryptParam(body["param"]!!.jsonPrimitive.content)
                  )
                  .jsonObject
          assertEquals("20", payload["pageSize"]!!.jsonPrimitive.content)
          respondJson("""{"code":200,"content":{"list":[]}}""")
        }
        else -> error("Unexpected synthetic request")
      }
    }
    useMockUpstream(engine)
    LocalSpocClient()
        .getPaperPlaneClassFeed(
            app.bucc.api.plane.PaperPlaneCourseSession(kcid = "mock-course", hdktid = "mock-class"),
            "mock-student",
        )
  }

  @Test
  fun `student view records use plane role rather than CAS header role`() = runTest {
    val payloads = mutableListOf<String>()
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/grzx/queryByInfo" ->
            respondJson("""{"code":200,"content":{"yhdm":"mock-student"}}""")
        "/spocnewht/inco/ht/queryOne",
        "/spocnewht/inco/ht/add" -> {
          val encrypted = json.parseToJsonElement((request.body as TextContent).text).jsonObject
          val plain = LocalSpocCrypto.decryptParam(encrypted["param"]!!.jsonPrimitive.content)
          if (plain.contains("17126473942303c5f6d76786468fdeb4af2c1192a3bd")) {
            respondJson("""{"code":200,"content":{"yhlx":"2"}}""")
          } else {
            assertEquals("01", request.headers["RoleCode"])
            payloads += plain
            respondJson(
                if (request.url.encodedPath.endsWith("add")) """{"code":200,"content":null}"""
                else """{"code":200,"content":{"sfyjl":0}}"""
            )
          }
        }
        else -> error("Unexpected synthetic request")
      }
    }
    useMockUpstream(engine)
    val client = LocalSpocClient()
    assertEquals(false, client.hasPaperPlaneViewed("mock-plane", "mock-student"))
    client.markPaperPlaneViewed("mock-plane", "mock-student")
    assertEquals(2, payloads.size)
    payloads.forEach { plain ->
      val payload = json.parseToJsonElement(plain).jsonObject
      assertEquals("1", payload["jllx"]!!.jsonPrimitive.content)
      assertEquals("mock-student", payload["yhdm"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun `unknown student identity stops before submitting view records`() = runTest {
    var writes = 0
    var studentType = "null"
    var userId = "mock-student"
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/grzx/queryByInfo" ->
            respondJson("""{"code":200,"content":{"yhdm":"$userId"}}""")
        "/spocnewht/inco/ht/queryOne" ->
            respondJson("""{"code":200,"content":{"yhlx":$studentType}}""")
        "/spocnewht/inco/ht/add" -> {
          writes++
          respondJson("""{"code":200,"content":null}""")
        }
        else -> error("Unexpected synthetic request")
      }
    }
    useMockUpstream(engine)
    assertTrue(
        runCatching { LocalSpocClient().markPaperPlaneViewed("mock-plane", "mock-student") }
            .isFailure
    )
    studentType = "2"
    userId = "different-user"
    assertTrue(
        runCatching { LocalSpocClient().markPaperPlaneViewed("mock-plane", "mock-student") }
            .isFailure
    )
    assertEquals(0, writes)
  }

  @Test
  fun `view permission accepts numeric flags and rejects missing or unknown state`() = runTest {
    var content = """{"sfyjl":0}"""
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location,
                    "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/grzx/queryByInfo" ->
            respondJson("""{"code":200,"content":{"yhdm":"mock-student"}}""")
        "/spocnewht/inco/ht/queryOne" -> {
          val encrypted = json.parseToJsonElement((request.body as TextContent).text).jsonObject
          val plain = LocalSpocCrypto.decryptParam(encrypted["param"]!!.jsonPrimitive.content)
          respondJson(
              if (plain.contains("17126473942303c5f6d76786468fdeb4af2c1192a3bd"))
                  """{"code":200,"content":{"yhlx":"2"}}"""
              else """{"code":200,"content":$content}"""
          )
        }
        else -> error("Unexpected synthetic request")
      }
    }
    useMockUpstream(engine)
    val client = LocalSpocClient()
    assertEquals(false, client.hasPaperPlaneViewed("mock-plane", "mock-student"))
    content = """{"sfyjl":1}"""
    assertEquals(true, client.hasPaperPlaneViewed("mock-plane", "mock-student"))
    content = "{}"
    assertTrue(runCatching { client.hasPaperPlaneViewed("mock-plane", "mock-student") }.isFailure)
    content = """{"sfyjl":"unknown"}"""
    assertTrue(runCatching { client.hasPaperPlaneViewed("mock-plane", "mock-student") }.isFailure)
  }

  @Test
  fun `view acknowledgement accepts successful empty content without relying on JSON whitespace`() =
      runTest {
        var body = """{"code":200,"content": null}"""
        var writes = 0
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" ->
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=mock-token",
                    ),
                )
            "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            "/spocnewht/grzx/queryByInfo" ->
                respondJson("""{"code":200,"content":{"yhdm":"mock-student"}}""")
            "/spocnewht/inco/ht/queryOne" -> respondJson("""{"code":200,"content":{"yhlx":"2"}}""")
            "/spocnewht/inco/ht/add" -> {
              writes++
              respondJson(body)
            }
            else -> error("Unexpected synthetic request")
          }
        }
        useMockUpstream(engine)
        val client = LocalSpocClient()
        assertEquals(null, client.markPaperPlaneViewed("mock-plane-a", "mock-student"))
        body = """{"code":200}"""
        assertEquals(null, client.markPaperPlaneViewed("mock-plane-b", "mock-student"))
        assertEquals(2, writes)
      }

  private val json = Json { ignoreUnknownKeys = true }

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
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
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
  fun `spoc api uses direct upstream backend to fetch assignments`() = runTest {
    val requestBodies = mutableListOf<String>()
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                    ),
            )
        "/spocnewht/sys/casLogin" -> {
          assertEquals("Inco-test-token", request.headers["Token"])
          respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        }
        "/spocnewht/inco/ht/queryOne" -> {
          assertEquals("Inco-test-token", request.headers["Token"])
          assertEquals("01", request.headers["RoleCode"])
          respondJson("""{"code":200,"content":{"dqxq":"2026年春季学期","mrxq":"2025-20262"}}""")
        }
        "/spocnewht/jxkj/queryKclb" -> {
          assertEquals("2025-20262", request.url.parameters["xnxq"])
          respondJson(
              """{"code":200,"content":[{"kcid":"course-1","kcmc":"操作系统","skjs":"牛虹婷,王良"}]}"""
          )
        }
        "/spocnewht/inco/ht/queryListByPage" -> {
          assertEquals(HttpMethod.Post, request.method)
          val bodyText = (request.body as TextContent).text
          requestBodies += bodyText
          val encryptedParam =
              json.parseToJsonElement(bodyText).jsonObject["param"]!!.jsonPrimitive.content
          val plainText = LocalSpocCrypto.decryptParam(encryptedParam)
          assertTrue("\"xnxq\":\"2025-20262\"" in plainText)
          assertTrue("\"tjzt\":\"1\"" in plainText)
          respondJson(
              when {
                """"pageNum":1""" in plainText ->
                    """{"code":200,"content":{"pageNum":1,"pageSize":15,"pages":2,"hasNextPage":true,"list":[{"zyid":"a1","tjzt":"未做","zyjzsj":"2026-03-31T15:59:59.000+00:00","zymc":"练习题作业1","zykssj":"2026-03-24T08:00:00.000+00:00","sskcid":"course-1","kcmc":"操作系统","mf":"满分:0"}]}}"""
                """"pageNum":2""" in plainText ->
                    """{"code":200,"content":{"pageNum":2,"pageSize":15,"pages":2,"hasNextPage":false,"list":[{"zyid":"a2","tjzt":"已做","zyjzsj":"2025-03-19T16:00:00.000+00:00","zymc":"lab0实验作业","zykssj":"2026-03-16T08:00:00.000+00:00","sskcid":"course-1","kcmc":"操作系统","mf":"满分:100"}]}}"""
                else -> error("Unexpected page payload: $plainText")
              }
          )
        }
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = SpocApi().getAssignments()

    assertTrue(result.isSuccess)
    val response = result.getOrNull()
    assertEquals("2025-20262", response?.termCode)
    assertEquals("2026年春季学期", response?.termName)
    assertEquals(2, response?.assignments?.size)
    assertEquals("lab0实验作业", response?.assignments?.firstOrNull()?.title)
    assertEquals(
        SpocSubmissionStatus.SUBMITTED,
        response?.assignments?.firstOrNull()?.submissionStatus,
    )
    assertEquals("练习题作业1", response?.assignments?.lastOrNull()?.title)
    assertEquals(
        SpocSubmissionStatus.UNSUBMITTED,
        response?.assignments?.lastOrNull()?.submissionStatus,
    )
    assertTrue(requestBodies.all { "\"param\"" in it })
  }

  @Test
  fun `paper plane reports unauthorized SPOC response without hiding the cause`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                    ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/inco/ht/queryList" ->
            respond(content = ByteReadChannel.Empty, status = HttpStatusCode.Unauthorized)
        else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
      }
    }
    useMockUpstream(engine)

    val result = PaperPlaneApi().getTasks()

    assertEquals("SPOC 登录状态异常，请重新登录后重试", result.exceptionOrNull()?.message)
    assertEquals("SPOC API 返回 HTTP 401", result.exceptionOrNull()?.cause?.message)
  }

  @Test
  fun `paper plane accepts the live SPOC task list content array`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                    ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/inco/ht/queryList" ->
            respondJson(
                """{"code":200,"content":[{"bz":"note","gzmc":"task","id":"rule-1","jf":3,"num":2,"sx":"16"}]}"""
            )
        "/spocnewht/inco/ht/queryOne" -> respondJson("""{"code":200,"content":{"jf":"42"}}""")
        else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
      }
    }
    useMockUpstream(engine)

    val result = PaperPlaneApi().getTasks().getOrThrow()

    assertEquals(1, result.list.size)
    assertEquals("rule-1", result.list.single().id)
    assertEquals("note", result.list.single().bz)
    assertEquals("task", result.list.single().gzmc)
    assertEquals("3", result.list.single().jf)
    assertEquals("42", result.info?.jf)
  }

  @Test
  fun `paper plane course reading survives an incompatible send-rule response`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                    ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/inco/ht/queryList" -> {
          val bodyText = (request.body as TextContent).text
          val encryptedParam =
              json.parseToJsonElement(bodyText).jsonObject["param"]!!.jsonPrimitive.content
          val payload = LocalSpocCrypto.decryptParam(encryptedParam)
          when {
            """"sqlid":"17139418053476da3da1fa8ba8e717e1aaa5d5682d5be31""" in payload ->
                respondJson("""{"code":200,"content":[{"xnxq":"2026-2027-1","sfdqxq":"1"}]}""")
            """"sqlid":"17272305190027285c6d5e1a68532c347c6efc5755da1""" in payload ->
                respondJson(
                    """{"code":200,"content":[{"zc":1,"zksrq":"2026-10-12","zjsrq":"2026-10-18"}]}"""
                )
            else ->
                respondJson(
                    """{"code":200,"content":[{"kcid":"course-1","kcmc":"示例课程","hdktid":"class-1","skkssj":"2026-10-14 08:00:00","skjssj":"2026-10-14 09:00:00"}]}"""
                )
          }
        }
        "/spocnewht/inco/ht/queryOne" ->
            respondJson(
                """{"code":200,"content":[{"pjrq":"2026-10-12","pjxsrq":"2026-10-18","zc":"1","zjsrq":"2026-10-18","zksrq":"2026-10-12"}]}"""
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
      }
    }
    useMockUpstream(engine)

    val courses = PaperPlaneApi().getCurrentTermCourses().getOrThrow()

    assertEquals(listOf("class-1"), courses.map { it.hdktid })
  }

  @Test
  fun `paper plane feed query decodes numeric and string fields from official encrypted page response`() =
      runTest {
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location,
                            "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                        ),
                )
            "/spocnewht/sys/casLogin" -> {
              assertEquals(HttpMethod.Post, request.method)
              assertEquals("Inco-test-token", request.headers["Token"])
              respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            }
            "/spocnewht/inco/ht/queryListByPage" -> {
              assertEquals(HttpMethod.Post, request.method)
              assertEquals("Inco-test-token", request.headers["Token"])
              assertEquals("01", request.headers["RoleCode"])
              val bodyText = (request.body as TextContent).text
              val encryptedParam =
                  json.parseToJsonElement(bodyText).jsonObject["param"]!!.jsonPrimitive.content
              val plainText = LocalSpocCrypto.decryptParam(encryptedParam)
              assertTrue(
                  "\"sqlid\":\"17446090892651c8782524f35177e4f76937cee4694d36\"" in plainText
              )
              assertTrue("\"pageSize\":5" in plainText)
              assertTrue("\"pageNum\":2" in plainText)
              assertTrue("\"kcid\":\"course-1\"" in plainText)
              assertTrue("\"pllx\":\"0\"" in plainText)
              assertTrue("\"hdktid\":\"class-1\"" in plainText)
              assertTrue("\"dqxh\":\"22373333\"" in plainText)
              assertTrue("\"sfdt\":\"0\"" in plainText)
              respondJson(
                  """{"code":200,"content":{"list":[{"id":12345,"xh":"student-author","xm":"同学","nr":"课堂留言","sfdz":"0","dzsl":2,"sfyjl":1}]}}"""
              )
            }
            else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
          }
        }
        useMockUpstream(engine)

        val result =
            PaperPlaneApi()
                .getFeed(
                    course =
                        app.bucc.api.plane.PaperPlaneCourseSession(
                            kcid = "course-1",
                            hdktid = "class-1",
                        ),
                    pageSize = 5,
                    pageNum = 2,
                )
                .getOrThrow()
                .list
                .orEmpty()
                .single()

        assertEquals("12345", result.id)
        assertEquals("student-author", result.xh)
        assertEquals("同学", result.xm)
        assertEquals("课堂留言", result.nr)
        assertEquals(0, result.liked)
        assertEquals(2, result.likes)
        assertEquals(1, result.viewed)
      }

  @Test
  fun `paper plane feed reports an incompatible response shape instead of showing a false empty list`() =
      runTest {
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location,
                            "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                        ),
                )
            "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            "/spocnewht/inco/ht/queryListByPage" ->
                respondJson("""{"code":200,"content":{"records":[]}}""")
            else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
          }
        }
        useMockUpstream(engine)

        val error =
            PaperPlaneApi()
                .getFeed(
                    course =
                        app.bucc.api.plane.PaperPlaneCourseSession(
                            kcid = "course-1",
                            hdktid = "class-1",
                        ),
                )
                .exceptionOrNull()

        assertEquals("无法加载纸飞机列表", error?.message)
        assertTrue(error?.cause?.message.orEmpty().contains("响应缺少 list 字段"))
      }

  @Test
  fun `paper plane integral query failure does not discard the successfully loaded point records`() =
      runTest {
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers =
                        headersOf(
                            HttpHeaders.Location,
                            "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                        ),
                )
            "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            "/spocnewht/inco/ht/queryList" ->
                respondJson("""{"code":200,"content":[{"id":"rule-1","gzmc":"task","jf":3}]}""")
            "/spocnewht/inco/ht/queryOne" ->
                respondJson("""{"code":502,"msg":"query unavailable","content":null}""")
            else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
          }
        }
        useMockUpstream(engine)

        val result = PaperPlaneApi().getTasks().getOrThrow()

        assertEquals(1, result.list.size)
        assertEquals("3", result.list.single().jf)
        assertEquals(null, result.info)
        assertEquals("query unavailable", result.integralError)
      }

  @Test
  fun `paper plane writes are not replayed after an authentication failure`() = runTest {
    var loginCount = 0
    var writeCount = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" -> {
          loginCount++
          respond(
              content = ByteReadChannel.Empty,
              status = HttpStatusCode.Found,
              headers =
                  headersOf(
                      HttpHeaders.Location,
                      "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                  ),
          )
        }
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/grzx/queryByInfo" ->
            respondJson("""{"code":200,"content":{"yhdm":"student-1"}}""")
        "/spocnewht/inco/ht/queryOne" -> respondJson("""{"code":200,"content":{"yhlx":"2"}}""")
        "/spocnewht/inco/ht/add" -> {
          writeCount++
          respond(content = ByteReadChannel.Empty, status = HttpStatusCode.Unauthorized)
        }
        else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
      }
    }
    useMockUpstream(engine)
    val client = LocalSpocClient()

    assertTrue(runCatching { client.markPaperPlaneViewed("plane-1", "student-1") }.isFailure)
    assertTrue(runCatching { client.cheerPaperPlane("plane-2", "student-1", "author-1") }.isFailure)
    assertTrue(
        runCatching { client.replyPaperPlane("plane-3", "student-1", "author-1", "reply-1", "测试") }
            .isFailure
    )

    assertEquals(3, writeCount)
    assertEquals(1, loginCount)
  }

  @Test
  fun `spoc login preserves pathless cookies for subsequent api calls`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location to
                            listOf(
                                "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh"
                            ),
                        HttpHeaders.SetCookie to
                            listOf("SPOCSESSION=spoc-session; Domain=spoc.buaa.edu.cn; Secure"),
                    ),
            )
        "/spocnewht/sys/casLogin" -> {
          assertTrue(
              request.headers[HttpHeaders.Cookie].orEmpty().contains("SPOCSESSION=spoc-session")
          )
          respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        }
        "/spocnewht/inco/ht/queryOne" ->
            respondJson("""{"code":200,"content":{"dqxq":"2026年春季学期","mrxq":"2025-20262"}}""")
        "/spocnewht/jxkj/queryKclb" ->
            respondJson(
                """{"code":200,"content":[{"kcid":"course-1","kcmc":"操作系统","skjs":"牛虹婷"}]}"""
            )
        "/spocnewht/inco/ht/queryListByPage" ->
            respondJson(
                """{"code":200,"content":{"pageNum":1,"pageSize":15,"pages":1,"hasNextPage":false,"list":[{"zyid":"a1","tjzt":"未做","zyjzsj":"2026-03-31T15:59:59.000+00:00","zymc":"练习题作业1","zykssj":"2026-03-24T08:00:00.000+00:00","sskcid":"course-1","kcmc":"操作系统","mf":"满分:0"}]}}"""
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = SpocApi().getAssignments()

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals(1, result.getOrNull()?.assignments?.size)
  }

  @Test
  fun `spoc login follows sso redirect chain before extracting token`() = runTest {
    val ssoUrl =
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fspoc.buaa.edu.cn%2Fspocnewht%2FcasLogin"
    val serviceUrl = "https://spoc.buaa.edu.cn/spocnewht/casLogin?ticket=test-ticket"
    val tokenUrl = "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh"
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://spoc.buaa.edu.cn/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, ssoUrl),
            )
        ssoUrl ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, serviceUrl),
            )
        serviceUrl ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, tokenUrl),
            )
        "https://spoc.buaa.edu.cn/spocnewht/sys/casLogin" ->
            respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne" ->
            respondJson("""{"code":200,"content":{"dqxq":"2026年春季学期","mrxq":"2025-20262"}}""")
        "https://spoc.buaa.edu.cn/spocnewht/jxkj/queryKclb?kcmc=&xnxq=2025-20262" ->
            respondJson(
                """{"code":200,"content":[{"kcid":"course-1","kcmc":"操作系统","skjs":"牛虹婷"}]}"""
            )
        "https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryListByPage" ->
            respondJson(
                """{"code":200,"content":{"pageNum":1,"pageSize":15,"pages":1,"hasNextPage":false,"list":[{"zyid":"a1","tjzt":"未做","zyjzsj":"2026-03-31T15:59:59.000+00:00","zymc":"练习题作业1","zykssj":"2026-03-24T08:00:00.000+00:00","sskcid":"course-1","kcmc":"操作系统","mf":"满分:0"}]}}"""
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = SpocApi().getAssignments()

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals(1, result.getOrNull()?.assignments?.size)
  }

  @Test
  fun `spoc api reuses business login across repeated direct calls`() = runTest {
    var casRequests = 0
    var casLoginRequests = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" -> {
          casRequests++
          respond(
              content = ByteReadChannel.Empty,
              status = HttpStatusCode.Found,
              headers =
                  headersOf(
                      HttpHeaders.Location,
                      "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                  ),
          )
        }
        "/spocnewht/sys/casLogin" -> {
          casLoginRequests++
          respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        }
        "/spocnewht/inco/ht/queryOne" ->
            respondJson("""{"code":200,"content":{"dqxq":"2026年春季学期","mrxq":"2025-20262"}}""")
        "/spocnewht/jxkj/queryKclb" ->
            respondJson(
                """{"code":200,"content":[{"kcid":"course-1","kcmc":"操作系统","skjs":"牛虹婷"}]}"""
            )
        "/spocnewht/inco/ht/queryListByPage" ->
            respondJson(
                """{"code":200,"content":{"pageNum":1,"pageSize":15,"pages":1,"hasNextPage":false,"list":[{"zyid":"a1","tjzt":"未做","zyjzsj":"2026-03-31T15:59:59.000+00:00","zymc":"练习题作业1","zykssj":"2026-03-24T08:00:00.000+00:00","sskcid":"course-1","kcmc":"操作系统","mf":"满分:0"}]}}"""
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val api = SpocApi()
    val first = api.getAssignments()
    val second = api.getAssignments()

    assertTrue(first.isSuccess, first.exceptionOrNull()?.message.orEmpty())
    assertTrue(second.isSuccess, second.exceptionOrNull()?.message.orEmpty())
    assertEquals(1, casRequests)
    assertEquals(1, casLoginRequests)
  }

  @Test
  fun `same SPOC client logs in again after connection mode changes`() = runTest {
    var logins = 0
    val roleCodes = mutableListOf<String?>()
    val engine = MockEngine { request ->
      when {
        request.url.encodedPath.endsWith("/spocnewht/cas") -> {
          logins++
          respond(
              ByteReadChannel.Empty,
              HttpStatusCode.Found,
              headersOf(
                  HttpHeaders.Location,
                  "https://spoc.buaa.edu.cn/spocnew/cas?token=same-token&refreshToken=same-refresh",
              ),
          )
        }
        request.url.encodedPath.endsWith("/spocnewht/sys/casLogin") ->
            respondJson("""{"code":200,"content":{"jsdm":"${if (logins == 1) "01" else "02"}"}}""")
        request.url.encodedPath.endsWith("/spocnewht/jxkj/queryKclb") -> {
          roleCodes += request.headers["RoleCode"]
          respondJson("""{"code":200,"content":[]}""")
        }
        else -> error("Unexpected synthetic request: ${request.url}")
      }
    }
    useMockUpstream(engine)
    val client = LocalSpocClient()

    client.getCourses("term")
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
        )
    )
    client.getCourses("term")

    assertEquals(2, logins)
    assertEquals(listOf<String?>("01", "02"), roleCodes)
  }

  @Test
  fun `same SPOC client clears credentials and plane student mapping for a new auth session`() =
      runTest {
        var logins = 0
        var profileReads = 0
        val writeRoleCodes = mutableListOf<String?>()
        val engine = MockEngine { request ->
          when {
            request.url.encodedPath.endsWith("/spocnewht/cas") -> {
              logins++
              respond(
                  ByteReadChannel.Empty,
                  HttpStatusCode.Found,
                  headersOf(
                      HttpHeaders.Location,
                      "https://spoc.buaa.edu.cn/spocnew/cas?token=same-token&refreshToken=same-refresh",
                  ),
              )
            }
            request.url.encodedPath.endsWith("/spocnewht/sys/casLogin") ->
                respondJson(
                    """{"code":200,"content":{"jsdm":"${if (logins == 1) "01" else "02"}"}}"""
                )
            request.url.encodedPath.endsWith("/spocnewht/grzx/queryByInfo") -> {
              profileReads++
              respondJson("""{"code":200,"content":{"yhdm":"mock-student"}}""")
            }
            request.url.encodedPath.endsWith("/spocnewht/inco/ht/queryOne") ->
                respondJson("""{"code":200,"content":{"yhlx":"2"}}""")
            request.url.encodedPath.endsWith("/spocnewht/inco/ht/add") -> {
              writeRoleCodes += request.headers["RoleCode"]
              respondJson("""{"code":200,"content":null}""")
            }
            else -> error("Unexpected synthetic request: ${request.url}")
          }
        }
        useMockUpstream(engine)
        val client = LocalSpocClient()

        client.markPaperPlaneViewed("plane-a", "mock-student")
        LocalAuthSessionStore.save(
            LocalAuthSession(
                username = "22373334",
                user = UserData(name = "New Test User", schoolid = "22373334"),
                authenticatedAt = "2026-10-16T08:00:00Z",
                lastActivity = "2026-10-16T08:00:00Z",
            )
        )
        client.markPaperPlaneViewed("plane-b", "mock-student")

        assertEquals(2, logins)
        assertEquals(2, profileReads)
        assertEquals(listOf<String?>("01", "02"), writeRoleCodes)
      }

  @Test
  fun `spoc api uses direct upstream backend to fetch assignment detail`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/spocnewht/cas" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                    ),
            )
        "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
        "/spocnewht/inco/ht/queryOne" ->
            respondJson("""{"code":200,"content":{"dqxq":"2026年春季学期","mrxq":"2025-20262"}}""")
        "/spocnewht/jxkj/queryKclb" ->
            respondJson(
                """{"code":200,"content":[{"kcid":"course-1","kcmc":"操作系统","skjs":"牛虹婷,王良"}]}"""
            )
        "/spocnewht/inco/ht/queryListByPage" ->
            respondJson(
                """{"code":200,"content":{"pageNum":1,"pageSize":15,"pages":1,"hasNextPage":false,"list":[{"zyid":"a1","tjzt":"未做","zyjzsj":"2026-03-31T15:59:59.000+00:00","zymc":"练习题作业1","zykssj":"2026-03-24T08:00:00.000+00:00","sskcid":"course-1","kcmc":"操作系统","mf":"满分:0"}]}}"""
            )
        "/spocnewht/kczy/queryKczyInfoByid" -> {
          assertEquals("a1", request.url.parameters["id"])
          respondJson(
              """{"code":200,"content":{"id":"a1","zymc":"练习题作业1","zynr":"<p>请尽量给出自己的思考。</p>","zykssj":"2026-03-24T08:00:00.000+00:00","zyjzsj":"2026-03-31T15:59:59.000+00:00","zyfs":"满分:0","sskcid":"course-1"}}"""
          )
        }
        "/spocnewht/kczy/queryXsSubmitKczyInfo" -> {
          assertEquals("a1", request.url.parameters["kczyid"])
          respondJson(
              """{"code":200,"content":{"tjzt":"1","tjsj":"2026-03-31T15:40:00.000+00:00"}}"""
          )
        }
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = SpocApi().getAssignmentDetail("a1")

    assertTrue(result.isSuccess)
    val detail = result.getOrNull()
    assertEquals("a1", detail?.assignmentId)
    assertEquals("练习题作业1", detail?.title)
    assertEquals("操作系统", detail?.courseName)
    assertEquals("牛虹婷,王良", detail?.teacherName)
    assertEquals("请尽量给出自己的思考。", detail?.contentPlainText)
    assertEquals("<p>请尽量给出自己的思考。</p>", detail?.contentHtml)
    assertEquals(SpocSubmissionStatus.SUBMITTED, detail?.submissionStatus)
    assertEquals("已提交", detail?.submissionStatusText)
    assertEquals("2026-03-31 23:40:00", detail?.submittedAt)
  }

  @Test
  fun `official keyword and feeling requests preserve anonymous parameters and add split order`() =
      runTest {
        val writes = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/spocnewht/cas" ->
                respond(
                    ByteReadChannel.Empty,
                    HttpStatusCode.Found,
                    headersOf(
                        HttpHeaders.Location,
                        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh",
                    ),
                )
            "/spocnewht/sys/casLogin" -> respondJson("""{"code":200,"content":{"jsdm":"01"}}""")
            else -> {
              assertEquals(HttpMethod.Post, request.method)
              val encrypted =
                  json
                      .parseToJsonElement((request.body as TextContent).text)
                      .jsonObject["param"]!!
                      .jsonPrimitive
                      .content
              val body = json.parseToJsonElement(LocalSpocCrypto.decryptParam(encrypted)).jsonObject
              when (request.url.encodedPath) {
                "/spocnewht/inco/ht/queryList" -> {
                  val content =
                      when (body["sqlid"]!!.jsonPrimitive.content) {
                        "173406145116435b4757f4d39d3338cda1daf71e59732b8e" ->
                            """[{"id":"mock-feeling","tbmc":"收获满满","icon":"mock-icon","fz":5}]"""
                        "1734923858382e1226bbb2655f545e9d8cd4459c5fdef8e" ->
                            """[{"bankid":"mock-keyword","word":"案例教学"}]"""
                        "1735277243511d86648d54c365a91a523748bf9e4eebd55e" -> {
                          assertEquals("mock-class", body["hdktids"]!!.jsonPrimitive.content)
                          "[]"
                        }
                        "17351717617689bb6d2856fa68b951fd449743ad125da8b1" ->
                            """[{"zgh":"mock-teacher","jsxm":"模拟教师"}]"""
                        else -> error("Unexpected mock query")
                      }
                  respondJson("""{"code":200,"content":$content}""")
                }
                "/spocnewht/inco/ht/add",
                "/spocnewht/ansj/splitPaperAirplanes" -> {
                  writes += request.url.encodedPath to body
                  respondJson("""{"code":200,"content":{}}""")
                }
                else -> error("Unexpected mock path")
              }
            }
          }
        }
        useMockUpstream(engine)
        val client = LocalSpocClient()
        val course =
            app.bucc.api.plane.PaperPlaneCourseSession(
                kcid = "mock-course",
                kcmc = "模拟课程",
                hdktid = "mock-class",
                xn = "mock-year",
                xq = "mock-term",
                kkyx = "mock-department",
                kcdm = "mock-code",
                kczc = "mock-week",
                bjm = "mock-group",
                kcjc = "mock-sections",
                skrq = "2026-10-13",
            )
        val options = client.getPaperPlaneEvaluationOptions(course, "mock-student")
        assertEquals(false, options.feelingRecorded)
        assertEquals("mock-teacher", client.getPaperPlaneInstructors(course).single().dm)
        client.recordPaperPlaneFeeling(course, options.feelings.single(), "mock-student")
        repeat(5) {
          client.throwPaperPlane(
              course,
              "mock-teacher",
              "mock-student",
              options.keywords.single().bankid!!,
          )
        }
        assertEquals(11, writes.size)
        assertEquals("mock-icon", writes.first().second["gstb"]!!.jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(5), writes.first().second["gsdf"])
        for (index in 1..9 step 2) {
          assertEquals("/spocnewht/inco/ht/add", writes[index].first)
          assertEquals("/spocnewht/ansj/splitPaperAirplanes", writes[index + 1].first)
          assertEquals(writes[index].second, writes[index + 1].second)
          val message =
              (writes[index].second["list"] as kotlinx.serialization.json.JsonArray)
                  .single()
                  .jsonObject
          assertEquals("mock-keyword", message["plgjc"]!!.jsonPrimitive.content)
          assertEquals("1", message["sfnm"]!!.jsonPrimitive.content)
          assertEquals("", message["nr"]!!.jsonPrimitive.content)
        }
      }

  private fun useMockUpstream(engine: MockEngine) {
    LocalUpstreamClientProvider.clientFactory = { followRedirects ->
      HttpClient(engine) {
        this.followRedirects = followRedirects
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpCookies) { storage = LocalCookieStore.storage(ConnectionMode.DIRECT) }
      }
    }
  }
}

private fun MockRequestHandleScope.respondJson(body: String) =
    respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
