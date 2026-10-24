package app.bucc.homework

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HomeworkAdapterTest {
  private lateinit var previousConnectionSettings: com.russhwolf.settings.Settings

  @BeforeTest
  fun useDirectModeForLegacyFixtures() {
    previousConnectionSettings = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.DIRECT)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
  }

  @AfterTest
  fun restoreConnectionMode() {
    app.bucc.api.ConnectionModeStore.settings = previousConnectionSettings
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
  }

  @Test
  fun patpatLoginUsesOfficialFieldsAndConfirmsAccountWithSelfEndpoint() = runTest {
    val calls = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              val path = request.url.encodedPath
              calls += "${request.method.value} $path"
              when (path) {
                "/api/auth/login" -> {
                  assertEquals(HttpMethod.Post, request.method)
                  val body =
                      (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                  val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                  assertEquals("mock-buaa-id", json["buaaId"]?.jsonPrimitive?.content)
                  assertEquals("mock-only-password", json["password"]?.jsonPrimitive?.content)
                  assertNull(json["username"])
                  respond("""{"status":200}""")
                }
                "/api/account/self" -> {
                  assertEquals(HttpMethod.Get, request.method)
                  respond("""{"data":{"id":17,"buaaId":"mock-buaa-id"}}""")
                }
                else -> error("Unexpected request: ${request.method} $path")
              }
            }
        )
    try {
      val adapter = PatpatHomeworkAdapter(client)
      assertTrue(adapter.requiresSeparateLogin)
      adapter.login("mock-buaa-id", "mock-only-password")
      assertEquals(listOf("POST /api/auth/login", "GET /api/account/self"), calls)
    } finally {
      client.close()
    }
  }

  @Test
  fun patpatLoadsLabAndIterationDetailsAndScoresWithoutWriteRequests() = runTest {
    val calls = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              val path = request.url.encodedPath
              calls += "${request.method.value} $path"
              assertEquals(HttpMethod.Get, request.method)
              when (path) {
                "/api/account/self" -> respond("""{"data":{"id":17,"buaaId":"mock-buaa-id"}}""")
                "/api/task/lab/query" -> respond("""{"data":[{"id":31,"title":"Mock lab"}]}""")
                "/api/task/iter/query" ->
                    respond("""{"data":[{"id":42,"title":"Mock iteration"}]}""")
                "/api/task/lab/query/31" ->
                    respond(
                        """{"data":{"id":31,"title":"Mock lab detail","content":"Synthetic content","startTime":"2026-10-10 08:00:00","deadlineTime":"2026-10-20 23:00:00","endTime":"2026-10-21 00:00:00"}}"""
                    )
                "/api/task/iter/query/42" ->
                    respond(
                        """{"data":{"id":42,"title":"Mock iteration detail","content":"Synthetic iteration","startTime":"2026-10-11 09:00:00","deadlineTime":"2026-10-22 23:00:00","endTime":"2026-10-23 00:00:00"}}"""
                    )
                "/api/task/lab/score/31" -> respond("""{"data":{"filename":"mock-report.pdf"}}""")
                "/api/task/iter/score/42" -> respond("""{"data":null}""")
                else -> error("Unexpected request: ${request.method} $path")
              }
            }
        )
    try {
      val items = PatpatHomeworkAdapter(client).load()
      assertEquals(setOf("patpat:lab:31", "patpat:iter:42"), items.map { it.id }.toSet())
      val lab = items.single { it.id == "patpat:lab:31" }
      assertEquals("Mock lab detail", lab.title)
      assertEquals("Synthetic content", lab.content)
      assertEquals(LocalDateTime.parse("2026-10-10T08:00"), lab.startAt)
      assertEquals(LocalDateTime.parse("2026-10-20T23:00"), lab.dueAt)
      assertEquals(HomeworkSubmission.SUBMITTED, lab.submission)
      val iteration = items.single { it.id == "patpat:iter:42" }
      assertEquals(HomeworkSubmission.UNSUBMITTED, iteration.submission)
      assertTrue(calls.all { it.startsWith("GET ") })
      assertFalse(calls.any { it.contains("/submit/") || it.contains("/course/select/") })
    } finally {
      client.close()
    }
  }

  @Test
  fun patpatLoginRejectsSelfAccountThatDiffersFromSubmittedIdentifier() = runTest {
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/api/auth/login" -> respond("""{"status":200}""")
                "/api/account/self" ->
                    respond("""{"data":{"id":17,"buaaId":"different-mock-id"}}""")
                else -> error("Unexpected request: ${request.method} ${request.url.encodedPath}")
              }
            }
        )
    try {
      val failure =
          assertFailsWith<IllegalStateException> {
            PatpatHomeworkAdapter(client).login("mock-buaa-id", "mock-only-password")
          }
      assertTrue(failure.message.orEmpty().contains("不一致"))
    } finally {
      client.close()
    }
  }

  @Test
  fun patpatReportsConcreteHttpStatusAndRejectsMissingScoreField() = runTest {
    val rejectedClient =
        HttpClient(
            MockEngine { request -> respond("not authorized", status = HttpStatusCode.Forbidden) }
        )
    try {
      val failure =
          assertFailsWith<IllegalStateException> {
            PatpatHomeworkAdapter(rejectedClient).login("mock-buaa-id", "mock-only-password")
          }
      assertTrue(failure.message.orEmpty().contains("403"))
    } finally {
      rejectedClient.close()
    }

    val missingFieldClient =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/api/account/self" -> respond("""{"data":{"id":17}}""")
                "/api/task/lab/query" -> respond("""{"data":[{"id":31,"title":"Mock lab"}]}""")
                "/api/task/iter/query" -> respond("""{"data":[]}""")
                "/api/task/lab/query/31" ->
                    respond(
                        """{"data":{"id":31,"title":"Mock lab","content":"","startTime":null,"deadlineTime":null,"endTime":null}}"""
                    )
                "/api/task/lab/score/31" -> respond("""{"data":{}}""")
                else -> error("Unexpected path ${request.url.encodedPath}")
              }
            }
        )
    try {
      val failure =
          assertFailsWith<IllegalStateException> {
            PatpatHomeworkAdapter(missingFieldClient).load()
          }
      assertTrue(failure.message.orEmpty().contains("filename"))
    } finally {
      missingFieldClient.close()
    }
  }

  @Test
  fun patpatPreservesSimilarTitlesAndSameNumericIdAcrossTaskKinds() = runTest {
    val client =
        HttpClient(
            MockEngine { request ->
              assertEquals("211.71.15.49", request.url.host)
              assertEquals(HttpMethod.Get, request.method)
              val path = request.url.encodedPath
              when {
                path == "/api/account/self" -> respond("""{"status":200,"data":{"id":17}}""")
                path == "/api/task/lab/query" ->
                    respond(
                        """{"status":200,"data":[{"id":31,"title":"物理实验报告"},{"id":32,"title":"物理实验报告"}]}"""
                    )
                path == "/api/task/iter/query" ->
                    respond("""{"status":200,"data":[{"id":31,"title":"物理实验报告"}]}""")
                "/score/" in path -> respond("""{"status":200,"data":null}""")
                else ->
                    respond(
                        """{"status":200,"data":{"title":"物理实验报告","content":"$path","startTime":null,"deadlineTime":null,"endTime":null}}"""
                    )
              }
            }
        )
    try {
      val adapter = PatpatHomeworkAdapter(client)
      val items = adapter.load()
      assertEquals(3, items.size)
      assertEquals(
          setOf("patpat:lab:31", "patpat:lab:32", "patpat:iter:31"),
          items.map { it.id }.toSet(),
      )
      assertTrue(
          items.all { it.title == "物理实验报告" && it.submission == HomeworkSubmission.UNSUBMITTED }
      )
      assertEquals(
          "/api/task/lab/query/32",
          adapter.detail(items.single { it.id == "patpat:lab:32" }).content,
      )
    } finally {
      client.close()
    }
  }

  @Test
  fun patpatEmptyListsAreValidButMissingDataIsAnError() = runTest {
    for (listBody in listOf("""{"status":200,"data":[]}""", """{"status":200}""")) {
      val client =
          HttpClient(
              MockEngine { request ->
                if (request.url.encodedPath == "/api/account/self")
                    respond("""{"data":{"id":17}}""")
                else respond(listBody)
              }
          )
      try {
        val adapter = PatpatHomeworkAdapter(client)
        if ("data" in listBody) assertTrue(adapter.load().isEmpty())
        else
            assertContains(
                assertFailsWith<IllegalStateException> { adapter.load() }.message.orEmpty(),
                "缺少 data",
            )
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun accodingFollowsPaginationGraphAndFiltersTextbookOnEveryPage() = runTest {
    val visited = mutableListOf<Int>()
    fun row(id: Int, title: String, status: String = "正在进行中…") =
        "<tr><td>$id</td><td><label>$status</label></td><td><a href='/contest-ng/index.html#/$id'>$title</a></td><td>课程</td><td>2026-10-01 00:00:00</td><td>2026-10-20 00:00:00</td></tr>"
    val client =
        HttpClient(
            MockEngine { request ->
              val body =
                  if (request.url.encodedPath == "/api/users/me") """{"id":1}"""
                  else if (request.url.encodedPath.endsWith("/submissions")) "[]"
                  else if (request.url.encodedPath.startsWith("/api/contests/"))
                      """{"problems":[{"id":1}]}"""
                  else {
                    val page = request.url.parameters["page"]!!.toInt()
                    visited += page
                    when (page) {
                      1 ->
                          row(1, "练习一") +
                              "<a href='./index?page=2'>下一页</a><a href='./index?page=5'>末页</a>"
                      2 -> row(2, "《C 语言程序设计实训教程》练习") + "<a href='./index?page=3'>下一页</a>"
                      3 -> row(3, "练习三") + row(4, "已结束作业", "已结束")
                      5 -> row(5, "练习五") + "<a href='./index?page=1'>首页</a>"
                      else -> error("Unexpected page $page")
                    }
                  }
              respond(body)
            }
        )
    try {
      val items =
          AccodingHomeworkAdapter(
                  client,
                  semester = {
                    HomeworkSemester(
                        "fixture",
                        kotlinx.datetime.LocalDate(2026, 9, 1),
                        kotlinx.datetime.LocalDate(2027, 1, 31),
                    )
                  },
              )
              .load()
      assertEquals(listOf(1, 2, 3, 5), visited)
      assertEquals(
          listOf("accoding:1", "accoding:3", "accoding:4", "accoding:5"),
          items.map { it.id },
      )
      assertTrue(items.all { it.submission == HomeworkSubmission.UNSUBMITTED })
    } finally {
      client.close()
    }
  }

  @Test
  fun accodingLoginUsesIndependentCredentialsAndCsrfForm() = runTest {
    val requests = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              assertEquals("accoding.buaa.edu.cn", request.url.host)
              val path = request.url.encodedPath
              requests += "${request.method.value} $path"
              when (path) {
                "/user/login" ->
                    when (request.method) {
                      HttpMethod.Get ->
                          respond(
                              "<html><input type=\"hidden\" name=\"_csrf\" value=\"mock-csrf-token\"></html>"
                          )
                      HttpMethod.Post -> {
                        val form =
                            (request.body as OutgoingContent.ByteArrayContent)
                                .bytes()
                                .decodeToString()
                        assertEquals(
                            "username=mock.user%40example.test&password=mock-only-password&_csrf=mock-csrf-token",
                            form,
                        )
                        respond(
                            "redirecting",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "/index"),
                        )
                      }
                      else -> error("Unexpected method ${request.method} for $path")
                    }
                "/api/users/me" -> {
                  assertEquals(HttpMethod.Get, request.method)
                  respond("""{"id":17}""")
                }
                else -> error("Unexpected request: ${request.method} $path")
              }
            }
        )
    try {
      AccodingHomeworkAdapter(client).login("mock.user@example.test", "mock-only-password")
      assertEquals(
          listOf("GET /user/login", "POST /user/login", "GET /api/users/me"),
          requests,
      )
    } finally {
      client.close()
    }
  }

  @Test
  fun accodingUsesGatewayInWebVpnMode() = runTest {
    for (mode in listOf(app.bucc.api.ConnectionMode.WEBVPN)) {
      app.bucc.api.ConnectionModeStore.save(mode)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      val paths = mutableListOf<String>()
      var preparations = 0
      val client =
          HttpClient(
              MockEngine { request ->
                assertEquals("d.buaa.edu.cn", request.url.host)
                val original =
                    Url(app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(request.url.toString()))
                assertEquals("accoding.buaa.edu.cn", original.host)
                paths += original.encodedPath
                when (original.encodedPath) {
                  "/user/login" ->
                      if (request.method == HttpMethod.Get)
                          respond("<input name='_csrf' value='fixture'>")
                      else respond("登录成功")
                  "/api/users/me" -> respond("""{"id":1}""")
                  "/contest/index" ->
                      respond(
                          if (original.parameters["page"] == "1") "<a href='?page=2'>2</a>"
                          else "<table></table>"
                      )
                  "/api/contests/fixture" -> respond("""{"description":"fixture detail"}""")
                  else -> error("Unexpected decoded Accoding path ${original.encodedPath}")
                }
              }
          )
      try {
        val adapter = AccodingHomeworkAdapter(client, prepareGateway = { preparations++ })
        adapter.login("fixture-user", "fixture-password")
        assertTrue(adapter.load().isEmpty())
        assertEquals(
            "fixture detail",
            adapter
                .detail(HomeworkItem("fixture", HomeworkSource.ACCODING, "fixture", "fixture"))
                .content,
        )
        assertEquals(3, preparations)
        assertEquals(
            listOf(
                "/user/login",
                "/user/login",
                "/api/users/me",
                "/api/users/me",
                "/contest/index",
                "/contest/index",
                "/api/contests/fixture",
            ),
            paths,
        )
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun accodingLoginRejectsEmptyCurrentUserResponse() = runTest {
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/user/login" ->
                    when (request.method) {
                      HttpMethod.Get ->
                          respond(
                              "<html><input type=\"hidden\" name=\"_csrf\" value=\"mock-csrf-token\"></html>"
                          )
                      HttpMethod.Post -> respond("logged in")
                      else -> error("Unexpected method ${request.method}")
                    }
                "/api/users/me" -> respond("")
                else -> error("Unexpected request: ${request.method} ${request.url.encodedPath}")
              }
            }
        )
    try {
      val failure =
          assertFailsWith<IllegalStateException> {
            AccodingHomeworkAdapter(client).login("mock.user@example.test", "mock-only-password")
          }
      assertEquals("Accoding 登录失败，请检查邮箱和密码", failure.message)
    } finally {
      client.close()
    }
  }

  @Test
  fun accodingEmptyJsonCurrentUserResponseIsAnExpiredIndependentSession() = runTest {
    val client =
        HttpClient(
            MockEngine {
              respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
    try {
      assertFailsWith<HomeworkLoginRequiredException> {
        AccodingHomeworkAdapter(client, prepareGateway = {}).load()
      }
    } finally {
      client.close()
    }
  }

  @Test
  fun accodingClassifiesOnlyVerifiedExpiredSessionResponses() = runTest {
    val expiredResponses =
        listOf(
            HttpStatusCode.Unauthorized to "expired",
            HttpStatusCode.Forbidden to "expired",
            HttpStatusCode.OK to "null",
            HttpStatusCode.OK to "{}",
            HttpStatusCode.OK to "{\"id\":null}",
        )
    for ((status, body) in expiredResponses) {
      val client = HttpClient(MockEngine { respond(body, status = status) })
      try {
        assertFailsWith<HomeworkLoginRequiredException> {
          AccodingHomeworkAdapter(client, prepareGateway = {}).load()
        }
      } finally {
        client.close()
      }
    }

    for ((status, body) in
        listOf(
            HttpStatusCode.InternalServerError to "server unavailable",
            HttpStatusCode.OK to "<html>gateway error</html>",
            HttpStatusCode.OK to "[]",
        )) {
      val client = HttpClient(MockEngine { respond(body, status = status) })
      try {
        assertFailsWith<IllegalStateException> {
          AccodingHomeworkAdapter(client, prepareGateway = {}).load()
        }
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun retainedByxtAdapterUsesFreshClientAfterMainSessionReset() = runTest {
    val provider = app.bucc.api.local.LocalUpstreamClientProvider
    val originalFactory = provider.clientFactory
    fun client(title: String) =
        HttpClient(
            MockEngine { request ->
              val body =
                  when {
                    request.url.encodedPath.endsWith("index.do") -> "<html></html>"
                    request.url.encodedPath.endsWith("queryAcademicYearSemester.do") ->
                        """{"code":"0","datas":{"queryAcademicYearSemester":"fixture-term"}}"""
                    else ->
                        """{"code":"0","datas":{"get":[{"taskId":"course","rounds":[{"roundId":"round","projectName":"$title","reports":[{}]}]}]}}"""
                  }
              respond(body)
            }
        )
    try {
      provider.reset()
      provider.clientFactory = { client("before-recovery") }
      val adapter = ByxtHomeworkAdapter(null, { emptyList() }, {})
      assertEquals("before-recovery", adapter.load().single().title)
      provider.reset()
      provider.clientFactory = { client("after-recovery") }
      assertEquals("after-recovery", adapter.load().single().title)
    } finally {
      provider.reset()
      provider.clientFactory = originalFactory
    }
  }

  @Test
  fun byxtUsesVerifiedFormRequestsAndDataEnvelope() = runTest {
    var authenticated = false
    val calls = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              assertTrue(authenticated)
              calls += request.url.encodedPath
              val body =
                  when {
                    request.url.encodedPath.endsWith("index.do") -> "<html></html>"
                    request.url.encodedPath.endsWith("queryAcademicYearSemester.do") -> {
                      assertEquals(HttpMethod.Post, request.method)
                      """{"code":"0","datas":{"queryAcademicYearSemester":"2026-2027-1"}}"""
                    }
                    request.url.encodedPath.endsWith("get.do") -> {
                      assertEquals(HttpMethod.Post, request.method)
                      val form =
                          (request.body as OutgoingContent.ByteArrayContent)
                              .bytes()
                              .decodeToString()
                      assertEquals("XNXQDM=2026-2027-1", form)
                      """{"code":"0","datas":{"get":[{"taskId":"course","courseName":"实验","rounds":[{"roundId":"round","projectName":"项目","reports":[{"reportAttachmentToken":"uploaded"},{"reportAttachmentToken":null,"endTime":"2026-10-20 00:00:00"}]}]}]}}"""
                    }
                    else -> error("Unexpected request ${request.url}")
                  }
              respond(body)
            }
        )
    try {
      val items =
          ByxtHomeworkAdapter(
                  client,
                  {
                    assertEquals("2026-2027-1", it)
                    emptyList()
                  },
                  { authenticated = true },
              )
              .load()
      assertEquals(3, calls.size)
      val item = items.single()
      assertEquals(HomeworkSubmission.SUBMITTED, item.submission)
      assertEquals(listOf(true, false), item.reports.map { it.uploaded })
      assertFalse(item.reports[1].uploaded)
      assertNull(item.experimentAt) // Unknown experimental dates are not guessed.
      assertFalse(item.showOnHome(LocalDateTime.parse("2026-10-12T12:00")))
    } finally {
      client.close()
    }
  }
}
