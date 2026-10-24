package app.bucc.api

import app.bucc.api.core.DefaultApiFactory
import app.bucc.api.feature.JudgeApi
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalJudgeApiBackend
import app.bucc.api.local.LocalJudgeApiCache
import app.bucc.api.local.LocalJudgeHistoricalCourseStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.model.dto.JudgeAssignmentDetailKeyDto
import app.bucc.model.dto.JudgeSubmissionStatus
import app.bucc.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime

class LocalJudgeApiBackendTest {
  // The April fixtures must not age across the production six-month cutoff as the test clock
  // advances.
  private fun fixtureJudgeApi() =
      JudgeApi(LocalJudgeApiBackend(nowProvider = { LocalDateTime.parse("2026-05-01T12:00:00") }))

  @BeforeTest
  fun setup() {
    runTest { localConnectionTestMutex.lock() }
    ConnectionModeStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    LocalJudgeHistoricalCourseStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    ConnectionRuntime.apiFactoryProvider = { DefaultApiFactory }
    LocalJudgeApiCache.clearAll()
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
  fun `judge api uses direct upstream backend to fetch assignments`() = runTest {
    val requestedPaths = mutableListOf<String>()
    val engine = MockEngine { request ->
      requestedPaths +=
          request.url.encodedPath +
              request.url.encodedQuery.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "/" -> respondHtml("<html><body>judge ready</body></html>")
        "/courselist.jsp" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "/assignment/index.jsp" ->
            if (request.url.parameters["assignID"] == "101") {
              respondHtml(
                  """
                  <html><body>
                    作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                    作业满分： 100.00 ，共 1道 题
                    <table><tbody>
                      <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                    </tbody></table>
                  </body></html>
                  """
              )
            } else {
              respondHtml(
                  """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
              )
            }
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = fixtureJudgeApi().getAssignments()

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    val assignment = result.getOrNull()?.assignments?.single()
    assertEquals("1", assignment?.courseId)
    assertEquals("101", assignment?.assignmentId)
    assertEquals("设计作业", assignment?.title)
    assertEquals(JudgeSubmissionStatus.UNSUBMITTED, assignment?.submissionStatus)
    assertTrue(requestedPaths.contains("/courselist.jsp?courseID=0"))
    assertTrue(requestedPaths.contains("/courselist.jsp?courseID=1"))
    assertTrue(requestedPaths.contains("/assignment/index.jsp?assignID=101"))
  }

  @Test
  fun `judge api fetches assignment details until six month cutoff per course`() = runTest {
    val requestedUrls = mutableListOf<String>()
    val engine = MockEngine { request ->
      requestedUrls += request.url.toString()
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """
                <html><body>
                  <a href="assignment/index.jsp?assignID=101">设计作业</a>
                  <a href="assignment/index.jsp?assignID=103">历史作业</a>
                  <a href="assignment/index.jsp?assignID=104">更早作业</a>
                </body></html>
                """
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                  作业满分： 100.00 ，共 1道 题
                  <table><tbody>
                    <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=103" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2025-10-30 08:00:00 至 2025-11-10 23:00:00
                  作业满分： 10.00 ，共 1道 题 总分：10.00
                  <table><tbody>
                    <tr><th>1.</th><td>历史题</td><td>10.00</td><td>最后一次提交时间：2025-11-01 12:00:00 得分：10.00</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=104" ->
            error("Assignment after six month cutoff should not be fetched")
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)
    val api =
        JudgeApi(LocalJudgeApiBackend(nowProvider = { LocalDateTime.parse("2026-05-01T12:00:00") }))

    val result = api.getAssignments()

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals(listOf("101"), result.getOrNull()?.assignments?.map { it.assignmentId })
    assertTrue(
        requestedUrls.contains("https://judge.buaa.edu.cn/assignment/index.jsp?assignID=103")
    )
    assertEquals(listOf("1"), result.getOrNull()?.historicalCutoffCourseIds)
  }

  @Test
  fun `judge api parses multiline course links and upstream assignment links`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            respondHtml(
                """
                <html><body>
                  <a
                    class="list-group-item"
                    href="courselist.jsp?courseID=1">
                    软件工程
                  </a>
                </body></html>
                """
            )
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """
                <html><body>
                  <a href="index.jsp?courseID=1&assignID=101" class="list-group-item">设计作业</a>
                </body></html>
                """
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                  作业满分： 100.00 ，共 1道 题
                  <table><tbody>
                    <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = JudgeApi().getAssignments(includeExpired = true)

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals(listOf("101"), result.getOrNull()?.assignments?.map { it.assignmentId })
  }

  @Test
  fun `judge api parses current assignments with dash date separators and card titles`() = runTest {
    val details =
        mapOf(
            "101" to "2026-10-19 10:00:00 - 2026-11-18 12:00:00",
            "102" to "2026-10-19 10:00:00–2026-11-18 12:00:00",
            "103" to "2026-10-19 10:00:00 — 2026-11-18 12:00:00",
            "104" to "2026-10-19 10:00:00   至   2026-11-18 12:00:00",
        )
    var detailRequests = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "/" -> respondHtml("<html><body>judge ready</body></html>")
        "/courselist.jsp" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>""",
            )
        "/assignment/index.jsp" -> {
          val id = request.url.parameters["assignID"]
          if (id != null) {
            detailRequests++
            val time = details[id] ?: error("Unexpected assignment id: $id")
            respondHtml("<html><body>作业时间： $time</body></html>")
          } else {
            val list =
                details.keys.joinToString("\n") { assignmentId ->
                  """<div class="assignment-card"><h4>课程作业 $assignmentId</h4><a href="assignment/index.jsp?assignID=$assignmentId">进入作业</a></div>"""
                }
            respondHtml("<html><body>$list</body></html>")
          }
        }
        else -> error("Unexpected request: ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = fixtureJudgeApi().getAssignments(includeExpired = true)

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    val assignments = result.getOrNull()!!.assignments
    assertEquals(listOf("101", "102", "103", "104"), assignments.map { it.assignmentId })
    assertEquals(
        listOf("课程作业 101", "课程作业 102", "课程作业 103", "课程作业 104"),
        assignments.map { it.title },
    )
    assertEquals(
        List(4) { "2026-10-19 10:00:00" to "2026-11-18 12:00:00" },
        assignments.map { it.startTime to it.dueTime },
    )
    assertEquals(4, detailRequests)
  }

  @Test
  fun `judge api records cutoff courses locally and skips them on later default refresh`() =
      runTest {
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine { request ->
          requestedUrls += request.url.toString()
          when (request.url.toString()) {
            "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
                )
            "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
            "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
                respondHtml(
                    """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
                )
            "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
                respondHtml("<html><body>course selected</body></html>")
            "https://judge.buaa.edu.cn/assignment/index.jsp" ->
                respondHtml(
                    """
                    <html><body>
                      <a href="assignment/index.jsp?assignID=101">设计作业</a>
                      <a href="assignment/index.jsp?assignID=103">历史作业</a>
                    </body></html>
                    """
                )
            "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
                respondHtml(
                    """
                    <html><body>
                      作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                      作业满分： 100.00 ，共 1道 题
                      <table><tbody>
                        <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                      </tbody></table>
                    </body></html>
                    """
                )
            "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=103" ->
                respondHtml(
                    """
                    <html><body>
                      作业时间：2025-10-30 08:00:00 至 2025-11-10 23:00:00
                      作业满分： 10.00 ，共 1道 题 总分：10.00
                      <table><tbody>
                        <tr><th>1.</th><td>历史题</td><td>10.00</td><td>最后一次提交时间：2025-11-01 12:00:00 得分：10.00</td></tr>
                      </tbody></table>
                    </body></html>
                    """
                )
            else -> error("Unexpected request: ${request.method.value} ${request.url}")
          }
        }
        useMockUpstream(engine)
        val api =
            JudgeApi(
                LocalJudgeApiBackend(nowProvider = { LocalDateTime.parse("2026-05-01T12:00:00") })
            )

        val first = api.getAssignments()
        LocalJudgeApiCache.clearAll()
        requestedUrls.clear()
        val second = api.getAssignments()
        val defaultRefreshCourseSelectionRequests =
            requestedUrls.count { it == "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" }
        val includeExpired = api.getAssignments(includeExpired = true)

        assertTrue(first.isSuccess, first.exceptionOrNull()?.message.orEmpty())
        assertTrue(second.isSuccess, second.exceptionOrNull()?.message.orEmpty())
        assertTrue(includeExpired.isSuccess, includeExpired.exceptionOrNull()?.message.orEmpty())
        assertEquals(listOf("101"), first.getOrNull()?.assignments?.map { it.assignmentId })
        assertEquals(emptyList(), second.getOrNull()?.assignments)
        assertEquals(0, defaultRefreshCourseSelectionRequests)
        assertEquals(
            listOf("103", "101"),
            includeExpired.getOrNull()?.assignments?.map { it.assignmentId },
        )
      }

  @Test
  fun `judge api activates direct judge cas session before fetching assignments`() = runTest {
    var judgeSessionActive = false
    val requestedUrls = mutableListOf<String>()
    val engine = MockEngine { request ->
      requestedUrls += request.url.toString()
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" -> {
          judgeSessionActive = true
          respond(
              content = ByteReadChannel.Empty,
              status = HttpStatusCode.Found,
              headers = headersOf(HttpHeaders.Location, "http://judge.buaa.edu.cn/"),
          )
        }
        "http://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            if (judgeSessionActive) {
              respondHtml(
                  """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
              )
            } else {
              respondHtml(
                  """
                  <html><body>
                    <form><input name="execution" value="e1s1" /></form>
                    统一身份认证
                  </body></html>
                  """
              )
            }
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                  作业满分： 100.00 ，共 1道 题
                  <table><tbody>
                    <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine, followRedirectsOverride = false)

    val result = fixtureJudgeApi().getAssignments()

    assertTrue(
        result.isSuccess,
        "${result.exceptionOrNull()?.message.orEmpty()}; urls=$requestedUrls",
    )
    assertEquals("设计作业", result.getOrNull()?.assignments?.single()?.title)
    assertEquals(
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F",
        requestedUrls.firstOrNull(),
    )
    assertEquals("http://judge.buaa.edu.cn/", requestedUrls.getOrNull(1))
  }

  @Test
  fun `judge api does not cache empty assignment lists`() = runTest {
    var assignmentListRequests = 0
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                  作业满分： 100.00 ，共 1道 题
                  <table><tbody>
                    <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp" -> {
          assignmentListRequests++
          if (assignmentListRequests == 1) {
            respondHtml("<html><body>暂无作业</body></html>")
          } else {
            respondHtml(
                """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
            )
          }
        }
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)
    val api = fixtureJudgeApi()

    val first = api.getAssignments()
    val second = api.getAssignments()

    assertTrue(first.isSuccess, first.exceptionOrNull()?.message.orEmpty())
    assertTrue(second.isSuccess, second.exceptionOrNull()?.message.orEmpty())
    assertEquals(emptyList(), first.getOrNull()?.assignments)
    assertEquals(listOf("101"), second.getOrNull()?.assignments?.map { it.assignmentId })
    assertEquals(2, assignmentListRequests)
  }

  @Test
  fun `judge api sends browser headers like python client`() = runTest {
    val engine = MockEngine { request ->
      val userAgent = request.headers[HttpHeaders.UserAgent].orEmpty()
      val accept = request.headers[HttpHeaders.Accept].orEmpty()
      val acceptLanguage = request.headers[HttpHeaders.AcceptLanguage].orEmpty()
      if (
          !userAgent.startsWith("Mozilla/5.0") ||
              !accept.contains("text/html") ||
              !acceptLanguage.contains("zh-CN")
      ) {
        return@MockEngine respond(
            content = ByteReadChannel.Empty,
            status = HttpStatusCode.ServiceUnavailable,
        )
      }

      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                  作业满分： 100.00 ，共 1道 题
                  <table><tbody>
                    <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = fixtureJudgeApi().getAssignments()

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals("设计作业", result.getOrNull()?.assignments?.single()?.title)
  }

  @Test
  fun `judge api reactivates session and retries when judge page returns login html`() = runTest {
    var serviceActivations = 0
    var courseListRequests = 0
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" -> {
          serviceActivations++
          respond(
              content = ByteReadChannel.Empty,
              status = HttpStatusCode.Found,
              headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
          )
        }
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" -> {
          courseListRequests++
          if (courseListRequests == 1) {
            respondHtml(
                """
                <html><body>
                  <form><input name="execution" value="e1s1" /></form>
                  统一身份认证
                </body></html>
                """
            )
          } else {
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
          }
        }
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                <html><body>
                  作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                  作业满分： 100.00 ，共 1道 题
                  <table><tbody>
                    <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                  </tbody></table>
                </body></html>
                """
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = fixtureJudgeApi().getAssignments()

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals("设计作业", result.getOrNull()?.assignments?.single()?.title)
    assertEquals(3, serviceActivations)
    assertEquals(2, courseListRequests)
  }

  @Test
  fun `judge api uses direct upstream backend to fetch assignment detail`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "/" -> respondHtml("<html><body>judge ready</body></html>")
        "/courselist.jsp" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "/assignment/index.jsp" ->
            if (request.url.parameters["assignID"] == "101") {
              respondHtml(
                  """
                  <html><body>
                    作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                    作业满分： 100.00 ，共 2道 题
                    <table><tbody>
                      <tr><th>1.</th><td>设计说明</td><td>60.00</td><td>初次提交时间: 2026-04-17 12:24:26 得分：60.00</td></tr>
                      <tr><th>2.</th><td>用例设计</td><td>40.00</td><td>未提交答案</td></tr>
                    </tbody></table>
                  </body></html>
                  """
              )
            } else {
              respondHtml(
                  """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
              )
            }
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)

    val result = JudgeApi().getAssignmentDetail("1", "101")

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    val detail = result.getOrNull()
    assertEquals("设计作业", detail?.title)
    assertEquals(2, detail?.totalProblems)
    assertEquals(listOf("设计说明", "用例设计"), detail?.problems?.map { it.name })
  }

  @Test
  fun `judge api batch details reuses local cache until session reset`() = runTest {
    var detailRequests = 0
    val engine = MockEngine { request ->
      when (request.url.toString()) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "https://judge.buaa.edu.cn",
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" -> {
          detailRequests++
          respondHtml(
              """
                  <html><body>
                    作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                    作业满分： 100.00 ，共 1道 题
                    <table><tbody>
                      <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                    </tbody></table>
                  </body></html>
                  """
          )
        }
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine)
    val api = JudgeApi()
    val keys = listOf(JudgeAssignmentDetailKeyDto("1", "101"))

    val first = api.getAssignmentDetails(keys)
    val second = api.getAssignmentDetails(keys)
    ConnectionRuntime.resetSession()
    useMockUpstream(engine)
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
        )
    )
    val third = api.getAssignmentDetails(keys)

    assertTrue(first.isSuccess, first.exceptionOrNull()?.message.orEmpty())
    assertTrue(second.isSuccess, second.exceptionOrNull()?.message.orEmpty())
    assertTrue(third.isSuccess, third.exceptionOrNull()?.message.orEmpty())
    assertEquals(listOf("101"), first.getOrNull()?.details?.map { it.assignmentId })
    assertEquals(2, detailRequests)
  }

  @Test
  fun `judge api batch details supports webvpn upstream urls`() = runTest {
    ConnectionRuntime.switchMode(ConnectionMode.WEBVPN)
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
        )
    )
    val requestedHosts = mutableListOf<String>()
    val engine = MockEngine { request ->
      requestedHosts += request.url.host
      when (LocalWebVpnSupport.fromWebVpnUrl(request.url.toString())) {
        "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fjudge.buaa.edu.cn%2F" ->
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.Found,
                headers =
                    headersOf(
                        HttpHeaders.Location,
                        LocalWebVpnSupport.toWebVpnUrl("https://judge.buaa.edu.cn/"),
                    ),
            )
        "https://judge.buaa.edu.cn",
        "https://judge.buaa.edu.cn/" -> respondHtml("<html><body>judge ready</body></html>")
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=0" ->
            respondHtml(
                """<html><body><a href="courselist.jsp?courseID=1">软件工程</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/courselist.jsp?courseID=1" ->
            respondHtml("<html><body>course selected</body></html>")
        "https://judge.buaa.edu.cn/assignment/index.jsp" ->
            respondHtml(
                """<html><body><a href="assignment/index.jsp?assignID=101">设计作业</a></body></html>"""
            )
        "https://judge.buaa.edu.cn/assignment/index.jsp?assignID=101" ->
            respondHtml(
                """
                    <html><body>
                      作业时间：2026-04-20 19:00:00 至 2026-05-03 23:00:00
                      作业满分： 100.00 ，共 1道 题
                      <table><tbody>
                        <tr><th>1.</th><td>设计说明</td><td>100.00</td><td>未提交答案</td></tr>
                      </tbody></table>
                    </body></html>
                    """
            )
        else -> error("Unexpected request: ${request.method.value} ${request.url}")
      }
    }
    useMockUpstream(engine, storageMode = ConnectionMode.WEBVPN)

    val result = JudgeApi().getAssignmentDetails(listOf(JudgeAssignmentDetailKeyDto("1", "101")))

    assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
    assertEquals("101", result.getOrNull()?.details?.single()?.assignmentId)
    assertTrue(requestedHosts.all { it == "d.buaa.edu.cn" })
  }

  @Test
  fun `judge discovers dropdown courses and reads all pages without merging courses`() = runTest {
    var selected = "0"
    val engine = MockEngine { request ->
      val query = request.url.parameters
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                ByteReadChannel.Empty,
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "/" -> respondHtml("<html>ready</html>")
        "/courselist.jsp" -> {
          val id = query["courseID"]!!
          if (id != "0") selected = id
          respondHtml(
              """<html><li onclick="location.href='courselist.jsp?courseID=1'">课程甲</li><li onclick="location.href='courselist.jsp?courseID=2'">课程乙</li></html>"""
          )
        }
        "/assignment/index.jsp" ->
            when {
              query["assignID"] != null ->
                  respondHtml("<html>作业时间：2026-04-20 10:00:00 至 2026-05-03 23:00:00 共1道</html>")
              query["page"] == "2" ->
                  respondHtml("<html><a href='index.jsp?assignID=102'>相同标题</a></html>")
              else ->
                  respondHtml(
                      "<html><a href='index.jsp?assignID=101'>相同标题</a><a href='?page=2'>下一页</a></html>"
                  )
            }
        else -> error("Unexpected mock path")
      }
    }
    useMockUpstream(engine)
    val result = fixtureJudgeApi().getAssignments(includeExpired = true).getOrThrow()
    assertEquals(
        setOf("1" to "101", "1" to "102", "2" to "101", "2" to "102"),
        result.assignments.map { it.courseId to it.assignmentId }.toSet(),
    )
  }

  @Test
  fun `judge gateway course selection cannot interleave across course transactions`() = runTest {
    var selected = "0"
    val engine = MockEngine { request ->
      val query = request.url.parameters
      when (request.url.encodedPath) {
        "/login" ->
            respond(
                ByteReadChannel.Empty,
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
            )
        "/" -> respondHtml("<html>ready</html>")
        "/courselist.jsp" -> {
          val id = query["courseID"]!!
          if (id != "0") {
            selected = id
            kotlinx.coroutines.delay(1)
          }
          respondHtml(
              "<html><a href='courselist.jsp?courseID=1'>甲</a><a href='courselist.jsp?courseID=2'>乙</a></html>"
          )
        }
        "/assignment/index.jsp" ->
            if (query["assignID"] != null) {
              respondHtml(
                  "<html>作业时间：2026-04-20 10:00:00 至 2026-05-03 23:00:00 共${selected}道</html>"
              )
            } else respondHtml("<html><a href='index.jsp?assignID=${selected}01'>作业</a></html>")
        else -> error("Unexpected mock path")
      }
    }
    useMockUpstream(engine)
    val rows = fixtureJudgeApi().getAssignments(includeExpired = true).getOrThrow().assignments
    assertEquals(
        setOf("1" to "101", "2" to "201"),
        rows.map { it.courseId to it.assignmentId }.toSet(),
    )
    assertTrue(rows.all { it.totalProblems == it.courseId.toInt() })
  }

  @Test
  fun `judge unrecognized course page is an explicit failure rather than zero assignments`() =
      runTest {
        useMockUpstream(
            MockEngine { request ->
              if (request.url.encodedPath == "/login")
                  respond(
                      ByteReadChannel.Empty,
                      HttpStatusCode.Found,
                      headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
                  )
              else respondHtml("<html>changed page structure</html>")
            }
        )
        assertTrue(fixtureJudgeApi().getAssignments(includeExpired = true).isFailure)
      }

  @Test
  fun `judge keeps healthy courses when another course fails`() = runTest {
    var selected = "0"
    useMockUpstream(
        MockEngine { request ->
          val query = request.url.parameters
          when (request.url.encodedPath) {
            "/login" ->
                respond(
                    ByteReadChannel.Empty,
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
                )
            "/" -> respondHtml("<html>ready</html>")
            "/courselist.jsp" -> {
              query["courseID"]?.let { selected = it }
              respondHtml(
                  "<html><a href='courselist.jsp?courseID=1'>甲</a><a href='courselist.jsp?courseID=2'>乙</a></html>"
              )
            }
            "/assignment/index.jsp" ->
                when {
                  selected == "1" -> respond("", HttpStatusCode.InternalServerError)
                  query["assignID"] != null ->
                      respondHtml("<html>作业时间：2026-04-20 10:00:00 至 2026-05-03 23:00:00 共1道</html>")
                  else -> respondHtml("<html><a href='index.jsp?assignID=201'>作业</a></html>")
                }
            else -> error("Unexpected mock path")
          }
        }
    )
    val result = fixtureJudgeApi().getAssignments(includeExpired = true)
    assertTrue(result.isSuccess)
    assertEquals(listOf("2"), result.getOrThrow().assignments.map { it.courseId })
    assertEquals(listOf("1"), result.getOrThrow().failedCourseIds)
  }

  @Test
  fun `judge distinguishes an explicit empty course from an unrecognized assignment page`() =
      runTest {
        for ((page, failed) in
            listOf(
                "<html>暂无作业</html>" to false,
                "<html>服务维护中</html>" to true,
                "<html>您没有权限访问此作业</html>" to true,
            )) {
          LocalJudgeApiCache.clearAll()
          useMockUpstream(
              MockEngine { request ->
                when (request.url.encodedPath) {
                  "/login" ->
                      respond(
                          ByteReadChannel.Empty,
                          HttpStatusCode.Found,
                          headersOf(HttpHeaders.Location, "https://judge.buaa.edu.cn/"),
                      )
                  "/" -> respondHtml("<html>ready</html>")
                  "/courselist.jsp" ->
                      respondHtml("<html><a href='courselist.jsp?courseID=1'>课程</a></html>")
                  "/assignment/index.jsp" -> respondHtml(page)
                  else -> error("Unexpected mock path")
                }
              }
          )
          val result = fixtureJudgeApi().getAssignments(includeExpired = true).getOrThrow()
          assertTrue(result.assignments.isEmpty())
          assertEquals(if (failed) listOf("1") else emptyList(), result.failedCourseIds)
          LocalUpstreamClientProvider.reset()
        }
      }

  private fun useMockUpstream(
      engine: MockEngine,
      followRedirectsOverride: Boolean? = null,
      storageMode: ConnectionMode = ConnectionMode.DIRECT,
  ) {
    LocalUpstreamClientProvider.clientFactory = { followRedirects ->
      HttpClient(engine) {
        this.followRedirects = followRedirectsOverride ?: followRedirects
        install(HttpCookies) { storage = LocalCookieStore.storage(storageMode) }
      }
    }
    LocalUpstreamClientProvider.isolatedClientFactory = { followRedirects, cookieStorage ->
      HttpClient(engine) {
        this.followRedirects = followRedirectsOverride ?: followRedirects
        install(HttpCookies) { storage = cookieStorage }
      }
    }
  }
}

private fun MockRequestHandleScope.respondHtml(body: String) =
    respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
    )
