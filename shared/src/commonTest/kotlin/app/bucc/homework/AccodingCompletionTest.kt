package app.bucc.homework

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class AccodingCompletionTest {
  private val problems =
      Json.parseToJsonElement("""{"problems":[{"id":11},{"id":12}]}""") as JsonObject

  private fun status(vararg records: String) =
      accodingSubmissionStatus(
          problems,
          (Json.parseToJsonElement(records.joinToString(",", "[", "]")) as JsonArray).toList(),
      )

  @Test
  fun `all problems require full score AC and a later failed attempt does not erase a pass`() {
    assertEquals(
        HomeworkSubmission.SUBMITTED,
        status(
            """{"problem_id":11,"result":"AC","score":1}""",
            """{"problem_id":11,"result":"WA","score":0}""",
            """{"problem_id":12,"result":"AC","score":1.0}""",
        ),
    )
    assertEquals(
        HomeworkSubmission.PARTIAL,
        status("""{"problem_id":11,"result":"AC","score":1}"""),
    )
    assertEquals(
        HomeworkSubmission.PARTIAL,
        status(
            """{"problem_id":11,"result":"AC","score":0.5}""",
            """{"problem_id":12,"result":"AC","score":1}""",
        ),
    )
    assertEquals(
        HomeworkSubmission.PARTIAL,
        status(
            """{"problem_id":11,"result":"WA","score":1}""",
            """{"problem_id":12,"result":"AC","score":1}""",
        ),
    )
    assertEquals(HomeworkSubmission.UNSUBMITTED, status())
    assertEquals(HomeworkSubmission.UNKNOWN, status("""{"problem_id":11,"result":"AC"}"""))
    assertEquals(
        HomeworkSubmission.UNKNOWN,
        accodingSubmissionStatus(
            Json.parseToJsonElement("""{"problems":[]}""") as JsonObject,
            emptyList(),
        ),
    )
    assertEquals(
        HomeworkSubmission.UNSUBMITTED,
        status("""{"problem_id":99,"result":"AC","score":1}"""),
    )
  }

  @Test
  fun `collects ended current term work and keeps items whose score query fails`() = runTest {
    fun row(id: Int, title: String, date: String) =
        "<tr><td>$id</td><td>已结束</td><td><a href='/contest-ng/index.html#/$id'>$title</a></td><td>课程</td><td>$date 10:00:00</td><td>$date 12:00:00</td></tr>"
    val seen = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              assertEquals("GET", request.method.value)
              seen += request.url.encodedPath
              when (request.url.encodedPath) {
                "/api/users/me" -> respond("""{"id":1}""")
                "/contest/index" ->
                    respond(
                        if (request.url.parameters["page"] == "1")
                            row(1, "第一次上机赛", "2026-09-16") +
                                row(9, "旧作业", "2026-04-01") +
                                row(8, "《C语言程序设计实训教程》练习", "2026-10-01") +
                                "<a href='?page=2'>2</a>"
                        else row(2, "第二次上机赛", "2026-10-14") + row(1, "重复", "2026-09-16")
                    )
                "/api/contests/1",
                "/api/contests/2" -> respond("""{"problems":[{"id":11}]}""")
                "/api/contests/1/submissions" ->
                    respond("""[{"problem_id":11,"result":"AC","score":1}]""")
                "/api/contests/2/submissions" ->
                    respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected request")
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
      val items = adapter.load()
      assertEquals(listOf("accoding:1", "accoding:2"), items.map { it.id })
      assertEquals(HomeworkSubmission.SUBMITTED, items[0].submission)
      assertEquals(HomeworkSubmission.UNKNOWN, items[1].submission)
      assertNotNull(items[1].submissionHint)
      assertFalse(seen.any { it.startsWith("/api/contests/9") || it.startsWith("/api/contests/8") })
    } finally {
      client.close()
    }
  }

  @Test
  fun `all homework sources can be locally marked and restored`() = runTest {
    val settings = MapSettings()
    for (source in HomeworkSource.entries) {
      val original =
          HomeworkItem(
              "${source.name}:1",
              source,
              "1",
              "作业",
              submission = HomeworkSubmission.UNSUBMITTED,
          )
      val adapter =
          object : HomeworkAdapter {
            override val source = original.source

            override suspend fun load() = listOf(original)
          }
      val repository = HomeworkRepository("fixture", listOf(adapter), settings) { true }
      repository.refresh(source)
      val loaded = repository.state.value.sources.getValue(source).items.single()
      assertTrue(loaded.canMarkComplete)
      assertFalse(loaded.completed)
      repository.markCompleted(loaded, true)
      assertTrue(repository.state.value.sources.getValue(source).items.single().completed)
      val restored = HomeworkRepository("fixture", listOf(adapter), settings) { true }
      assertTrue(restored.state.value.sources.getValue(source).items.single().completed)
      restored.markCompleted(original, false)
      assertFalse(restored.state.value.sources.getValue(source).items.single().completed)
      assertEquals(
          HomeworkSubmission.UNSUBMITTED,
          restored.state.value.sources.getValue(source).items.single().submission,
      )
    }
  }
}
