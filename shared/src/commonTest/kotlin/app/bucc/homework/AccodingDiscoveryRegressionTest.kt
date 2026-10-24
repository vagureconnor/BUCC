package app.bucc.homework

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class AccodingDiscoveryRegressionTest {
  private fun plainRow(
      id: Int,
      title: String,
      start: String = "2026-10-01",
      due: String = "2026-10-30",
  ) =
      "<tr><td>$id</td><td>已结束</td><td>$title</td><td>课程</td><td>$start 10:00:00</td><td>$due 12:00:00</td></tr>"

  @Test
  fun parsesPlainTitleRowsAndOfficialContestViewLinksUsingStableIds() {
    val html =
        """
      ${plainRow(501, "第一次训练赛")}
      <tr><td>502</td><td>已结束</td><td><a href="/contest/view?id=502">第二次训练赛</a></td><td>课程</td><td>2026-10-03 10:00:00</td><td>2026-10-30 12:00:00</td></tr>
      ${plainRow(501, "标题不同但赛事相同")}
    """
            .trimIndent()

    val parsed = parseAccodingPage(html)

    assertEquals(listOf("accoding:501", "accoding:502"), parsed.items.map { it.id }.distinct())
    assertEquals("501", parsed.items.first().upstreamId)
    assertTrue(parsed.items.any { it.title == "第二次训练赛" && it.upstreamId == "502" })
  }

  @Test
  fun followsPageParameterAfterHtmlEncodedQuerySeparator() {
    val parsed = parseAccodingPage("<a href='/contest/index?sort=date&amp;page=7'>末页</a>")

    assertEquals(setOf(7), parsed.pages)
  }

  @Test
  fun oneContestPermissionFailureDoesNotHideOtherAssignmentsWhenAccountRemainsValid() = runTest {
    val checkedIdentity = mutableListOf<Unit>()
    val queried = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              when {
                request.url.encodedPath == "/api/users/me" -> {
                  checkedIdentity += Unit
                  respond("""{"id":17}""")
                }
                request.url.encodedPath == "/contest/index" ->
                    respond(
                        plainRow(1, "第一场赛事") + plainRow(2, "无法读取成绩的赛事") + plainRow(3, "第三场赛事"),
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                    )
                request.url.encodedPath.startsWith("/api/contests/") -> {
                  val id =
                      request.url.encodedPath.removePrefix("/api/contests/").substringBefore('/')
                  queried += "${id}:${request.url.encodedPath.endsWith("/submissions")}"
                  when {
                    id == "2" -> respond("", HttpStatusCode.Forbidden)
                    request.url.encodedPath.endsWith("/submissions") -> respond("[]")
                    else -> respond("""{"problems":[{"id":11}]}""")
                  }
                }
                else -> error("Unexpected request path")
              }
            }
        )
    try {
      val items =
          AccodingHomeworkAdapter(
                  client,
                  prepareGateway = {},
                  semester = {
                    HomeworkSemester("fixture", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
                  },
              )
              .load()

      assertEquals(listOf("accoding:1", "accoding:2", "accoding:3"), items.map { it.id })
      assertEquals(HomeworkSubmission.UNKNOWN, items[1].submission)
      assertEquals(HomeworkSubmission.UNSUBMITTED, items[0].submission)
      assertEquals(HomeworkSubmission.UNSUBMITTED, items[2].submission)
      assertTrue(queried.contains("3:false"))
      assertEquals(2, checkedIdentity.size)
    } finally {
      client.close()
    }
  }

  @Test
  fun contestPermissionFailureWithExpiredAccountRequiresIndependentLogin() = runTest {
    var identityChecks = 0
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/api/users/me" -> {
                  identityChecks += 1
                  if (identityChecks == 1) respond("""{"id":17}""")
                  else respond("", HttpStatusCode.Unauthorized)
                }
                "/contest/index" -> respond(plainRow(1, "当前作业"))
                "/api/contests/1" -> respond("", HttpStatusCode.Forbidden)
                else -> error("Unexpected request path")
              }
            }
        )
    try {
      val adapter =
          AccodingHomeworkAdapter(
              client,
              prepareGateway = {},
              semester = {
                HomeworkSemester("fixture", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
              },
          )
      assertFailsWith<HomeworkLoginRequiredException> { adapter.load() }
    } finally {
      client.close()
    }
  }

  @Test
  fun loginPageReturnedAsSuccessfulListResponseIsNotAnEmptyAssignmentList() = runTest {
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/api/users/me" -> respond("""{"id":17}""")
                "/contest/index" -> respond("<html><form action='/user/login'>请登录</form></html>")
                else -> error("Unexpected request path")
              }
            }
        )
    try {
      val adapter =
          AccodingHomeworkAdapter(
              client,
              prepareGateway = {},
              semester = {
                HomeworkSemester("fixture", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
              },
          )
      assertFailsWith<IllegalStateException> { adapter.load() }
    } finally {
      client.close()
    }
  }

  @Test
  fun mixedPageWithAnUnparseableNonTutorialRowFailsInsteadOfDroppingThatAssignment() = runTest {
    val malformed =
        "<tr><td></td><td>已结束</td><td>没有稳定ID的真实赛事</td><td>课程</td><td>2026-10-02 10:00:00</td><td>2026-10-30 12:00:00</td></tr>"
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/api/users/me" -> respond("""{"id":17}""")
                "/contest/index" -> respond("<table>${plainRow(1, "可解析赛事")}$malformed</table>")
                else -> error("Unexpected request path")
              }
            }
        )
    try {
      val adapter =
          AccodingHomeworkAdapter(
              client,
              prepareGateway = {},
              semester = {
                HomeworkSemester("fixture", LocalDate(2026, 9, 1), LocalDate(2027, 1, 31))
              },
          )
      assertFailsWith<IllegalStateException> { adapter.load() }
    } finally {
      client.close()
    }
  }
}
