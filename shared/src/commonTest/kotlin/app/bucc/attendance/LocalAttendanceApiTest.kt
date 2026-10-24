package app.bucc.attendance

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class LocalAttendanceApiTest {
  private val termBody =
      """{"STATUS":"0","result":[{"code":"T1","name":"示例学期","beginDate":"2026-09-01","endDate":"2027-01-31"}]}"""

  private fun api(
      engine: MockEngine,
      scope: () -> String = { "DIRECT:synthetic-student" },
  ): LocalAttendanceApi {
    val client = HttpClient(engine)
    return LocalAttendanceApi(
        client = { client },
        sessionProvider = { AttendanceSession("synthetic-user", "synthetic-session") },
        route = { path -> "https://fixture.invalid${Url(path).encodedPath}" },
        scope = scope,
    )
  }

  private fun MockRequestHandleScope.response(
      body: String,
      status: HttpStatusCode = HttpStatusCode.OK,
  ) =
      respond(
          content = ByteReadChannel(body),
          status = status,
          headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
      )

  @Test
  fun `queries complete term by stable course ids and removes only duplicate same-course records`() =
      runTest {
        val observed = mutableListOf<String>()
        val engine = MockEngine { request ->
          val path = request.url.encodedPath
          observed +=
              "${request.method.value} $path ${request.url.parameters.entries().joinToString { (key, values) -> "$key=${values.joinToString()}" }}"
          assertEquals("synthetic-session", request.headers["sessionId"])
          when (path) {
            "/eschool/app/course/get_base_school_year.action" -> {
              assertEquals(HttpMethod.Get, request.method)
              assertEquals("synthetic-user", request.url.parameters["userId"])
              assertEquals("2", request.url.parameters["type"])
              response(termBody)
            }
            "/app/choosecourse/get_myall_course.action" -> {
              assertEquals(HttpMethod.Get, request.method)
              assertEquals("1", request.url.parameters["user_type"])
              assertEquals("synthetic-user", request.url.parameters["id"])
              assertEquals("T1", request.url.parameters["xq_code"])
              response(
                  """{"STATUS":"0","result":[{"course_id":"c-1","course_name":"大学物理实验报告"},{"course_id":"c-2","course_name":"大学物理实验报告"}]}"""
              )
            }
            "/eschool/app/my/get_my_course_sign_detail.action" -> {
              assertEquals(HttpMethod.Post, request.method)
              assertEquals("synthetic-user", request.url.parameters["id"])
              val courseId = request.url.parameters["courseId"]
              response(
                  when (courseId) {
                    "c-1" ->
                        """{"STATUS":"0","result":[{"id":"same-history-id","teachTime":"2026-09-03 08:00:00","classBeginTime":"08:00","classEndTime":"09:00","signStatus":"1"},{"id":"same-history-id","teachTime":"2026-09-03 08:00:00","classBeginTime":"08:00","classEndTime":"09:00","signStatus":"1"},{"id":"outside-term","teachTime":"2026-08-31 08:00:00","signStatus":"0"}]}"""
                    "c-2" ->
                        """{"STATUS":"0","result":[{"id":"same-history-id","teachTime":"2026-09-03 10:00:00","classBeginTime":"10:00","classEndTime":"11:00","signStatus":"3"},{"id":"end-date","teachTime":"2027-01-31 12:00:00","signStatus":"0"},{"id":"outside-end","teachTime":"2027-02-01 12:00:00","signStatus":"0"}]}"""
                    else -> error("Unexpected course id: $courseId")
                  }
              )
            }
            else -> error("Unexpected endpoint $path; attendance queries must remain read-only")
          }
        }

        val client = api(engine)
        val terms = client.getTerms().getOrThrow()
        val semester = client.getSemester("T1").getOrThrow()

        assertEquals("示例学期", terms.single().itemName)
        assertEquals(listOf("c-1", "c-2"), semester.courses.map { it.id })
        assertEquals(3, semester.records.size)
        assertEquals(
            setOf("c-1:same-history-id", "c-2:same-history-id", "c-2:end-date"),
            semester.records.map { it.id }.toSet(),
        )
        assertEquals(setOf("2026-09-03", "2027-01-31"), semester.records.map { it.date }.toSet())
        assertEquals(
            "未知状态（3）",
            semester.records
                .single { it.courseId == "c-2" && it.id.endsWith("same-history-id") }
                .status,
        )
        assertEquals(
            2,
            observed.count { it.contains("/eschool/app/my/get_my_course_sign_detail.action") },
        )
        assertFalse(observed.any { it.contains("stu_scan_sign", ignoreCase = true) })
      }

  @Test
  fun `nonzero status and malformed json are failures instead of empty attendance`() = runTest {
    for (body in listOf("not-json", """{"STATUS":"1","result":[]}""")) {
      val engine = MockEngine { request ->
        when (request.url.encodedPath) {
          "/eschool/app/course/get_base_school_year.action" -> response(termBody)
          "/app/choosecourse/get_myall_course.action" ->
              response("""{"STATUS":"0","result":[{"course_id":"c-1","course_name":"课程"}]}""")
          "/eschool/app/my/get_my_course_sign_detail.action" -> response(body)
          else -> error("Unexpected endpoint ${request.url.encodedPath}")
        }
      }
      val result = api(engine).getSemester("T1")
      assertTrue(result.isFailure)
      assertNotNull(result.exceptionOrNull()?.message)
    }
  }

  @Test
  fun `official no-history response preserves empty course without hiding other courses`() =
      runTest {
        val engine = MockEngine { request ->
          when (request.url.encodedPath) {
            "/eschool/app/course/get_base_school_year.action" -> response(termBody)
            "/app/choosecourse/get_myall_course.action" ->
                response(
                    """{"STATUS":"0","result":[{"course_id":"c1","course_name":"有记录课程"},{"course_id":"c2","course_name":"无记录课程"}]}"""
                )
            "/eschool/app/my/get_my_course_sign_detail.action" ->
                response(
                    if (request.url.parameters["courseId"] == "c2") """{"STATUS":"2"}"""
                    else
                        """{"STATUS":"0","result":[{"id":"r1","teachTime":"20260903","signStatus":"1"}]}"""
                )
            else -> error("Unexpected endpoint")
          }
        }
        val semester = api(engine).getSemester("T1").getOrThrow()
        assertEquals(2, semester.courses.size)
        assertEquals(listOf("c1"), semester.records.map { it.courseId })
      }

  @Test
  fun `missing history date fails instead of silently hiding the record`() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/eschool/app/course/get_base_school_year.action" -> response(termBody)
        "/app/choosecourse/get_myall_course.action" ->
            response("""{"STATUS":"0","result":[{"course_id":"c-1","course_name":"课程"}]}""")
        "/eschool/app/my/get_my_course_sign_detail.action" ->
            response("""{"STATUS":"0","result":[{"id":"r1","teachTime":"","signStatus":"1"}]}""")
        else -> error("Unexpected endpoint ${request.url.encodedPath}")
      }
    }

    val result = api(engine).getSemester("T1")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("日期"))
  }

  @Test
  fun `account or mode scope change during history request rejects result`() = runTest {
    var currentScope = "DIRECT:synthetic-a"
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/eschool/app/course/get_base_school_year.action" -> response(termBody)
        "/app/choosecourse/get_myall_course.action" ->
            response("""{"STATUS":"0","result":[{"course_id":"c-1","course_name":"课程"}]}""")
        "/eschool/app/my/get_my_course_sign_detail.action" -> {
          currentScope = "WEBVPN:synthetic-b"
          response(
              """{"STATUS":"0","result":[{"id":"r1","teachTime":"2026-09-03","signStatus":"1"}]}"""
          )
        }
        else -> error("Unexpected endpoint ${request.url.encodedPath}")
      }
    }

    val result = api(engine, { currentScope }).getSemester("T1")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("变化"))
  }

  @Test
  fun `course history fanout is limited to three concurrent requests`() = runTest {
    var active = 0
    var maxActive = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/eschool/app/course/get_base_school_year.action" -> response(termBody)
        "/app/choosecourse/get_myall_course.action" ->
            response(
                """{"STATUS":"0","result":[{"course_id":"c1","course_name":"一"},{"course_id":"c2","course_name":"二"},{"course_id":"c3","course_name":"三"},{"course_id":"c4","course_name":"四"}]}"""
            )
        "/eschool/app/my/get_my_course_sign_detail.action" -> {
          active++
          maxActive = maxOf(maxActive, active)
          delay(10)
          active--
          response("""{"STATUS":"0","result":[]}""")
        }
        else -> error("Unexpected endpoint ${request.url.encodedPath}")
      }
    }

    assertTrue(api(engine).getSemester("T1").isSuccess)
    assertEquals(3, maxActive)
  }
}
