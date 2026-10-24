package app.bucc.attendance

import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalSigninApiBackend
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.localUpstreamUrl
import app.bucc.model.dto.Term
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AttendanceSession(val userId: String, val sessionId: String)

private fun attendanceSessionProvider(): suspend () -> AttendanceSession {
  val signin = LocalSigninApiBackend()
  return { signin.sessionForAttendance().let { AttendanceSession(it.userId, it.sessionId) } }
}

/** Read-only student endpoints verified against the official MyCenter attendance page. */
class LocalAttendanceApi
internal constructor(
    private val client: () -> HttpClient,
    private val sessionProvider: suspend () -> AttendanceSession,
    private val route: (String) -> String = ::localUpstreamUrl,
    private val scope: () -> String = {
      "${ConnectionRuntime.currentMode()}:${LocalAuthSessionStore.get()?.username}"
    },
) : AttendanceApi {
  constructor() : this({ LocalUpstreamClientProvider.shared() }, attendanceSessionProvider())

  private val json = Json { ignoreUnknownKeys = true }
  private var terms: List<JsonObject> = emptyList()
  private var termsScope: String? = null

  override suspend fun getTerms(): Result<List<Term>> = attempt {
    val owner = scope()
    val session = sessionProvider()
    val rows = request(TERMS, session, owner, mapOf("userId" to session.userId, "type" to "2"))
    terms = rows
    termsScope = owner
    rows
        .mapIndexed { index, row ->
          Term(row.required("code"), row.required("name"), index == 0, index)
        }
        .distinctBy { it.itemCode }
  }

  override suspend fun getSemester(termCode: String): Result<AttendanceSemester> = attempt {
    val owner = scope()
    if (termsScope != owner || terms.none { it.text("code") == termCode }) getTerms().getOrThrow()
    val term = terms.firstOrNull { it.text("code") == termCode } ?: error("所选学期不存在，请刷新学期列表")
    val session = sessionProvider()
    val courses =
        request(
                COURSES,
                session,
                owner,
                mapOf("user_type" to "1", "id" to session.userId, "xq_code" to termCode),
            )
            .map { AttendanceCourse(it.required("course_id"), it.required("course_name")) }
            .distinctBy { it.id }
    val start = attendanceDate(term.text("beginDate"))
    val end = attendanceDate(term.text("endDate"))
    val semaphore = Semaphore(3)
    val records = coroutineScope {
      courses
          .map { course ->
            async {
              semaphore.withPermit {
                request(
                        HISTORY,
                        session,
                        owner,
                        mapOf("id" to session.userId, "courseId" to course.id),
                        post = true,
                    )
                    .map { row ->
                      val date =
                          attendanceDate(row.text("teachTime")) ?: error("出勤记录日期格式无法识别，请使用官方页面核对")
                      val begin = attendanceTime(row.text("classBeginTime"))
                      val finish = attendanceTime(row.text("classEndTime"))
                      val upstreamId = row.text("id").ifBlank { "${date}:$begin:$finish" }
                      AttendanceRecord(
                          id = "${course.id}:$upstreamId",
                          courseId = course.id,
                          date = date.toString(),
                          time = listOf(begin, finish).filter { it.isNotBlank() }.joinToString("–"),
                          status =
                              when (val value = row.text("signStatus")) {
                                "0" -> "缺勤"
                                "1" -> "出勤"
                                else -> if (value.isBlank()) "未知状态" else "未知状态（$value）"
                              },
                      )
                    }
                    .filter { row ->
                      val day = LocalDate.parse(row.date)
                      (start == null || day >= start) && (end == null || day <= end)
                    }
              }
            }
          }
          .awaitAll()
          .flatten()
    }
    check(scope() == owner) { "账号或连接模式已变化，请重新查询" }
    AttendanceSemester(
        courses,
        records.distinctBy { it.id }.sortedWith(compareBy({ it.date }, { it.time }, { it.id })),
    )
  }

  private suspend fun request(
      path: String,
      session: AttendanceSession,
      owner: String,
      params: Map<String, String>,
      post: Boolean = false,
  ): List<JsonObject> {
    check(scope() == owner) { "账号或连接模式已变化，请重新查询" }
    val response =
        if (post)
            client().post(route("$BASE$path")) {
              header("sessionId", session.sessionId)
              params.forEach { (key, value) -> parameter(key, value) }
            }
        else
            client().get(route("$BASE$path")) {
              header("sessionId", session.sessionId)
              params.forEach { (key, value) -> parameter(key, value) }
            }
    check(scope() == owner) { "账号或连接模式已变化，请重新查询" }
    check(response.status.value == 200) { "iClass 出勤查询失败，请重试或打开官方页面" }
    val body = response.bodyAsText()
    val payload =
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error("iClass 登录状态失效或返回格式异常，请重新登录后查询") }
    // The official student page renders this exact no-history response as 暂无数据.
    // Keep it scoped to history; other status codes and malformed payloads remain failures.
    if (path == HISTORY && payload.keys == setOf("STATUS") && payload.text("STATUS") == "2")
        return emptyList()
    val operation =
        when (path) {
          TERMS -> "学期列表"
          COURSES -> "课程列表"
          else -> "出勤历史"
        }
    check(payload.text("STATUS") == "0") { "iClass ${operation}未返回有效数据，请重新登录或使用官方页面" }
    return payload["result"]?.jsonArray?.map { it.jsonObject } ?: error("iClass 出勤数据缺失，请重试或使用官方页面")
  }

  private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
      try {
        Result.success(block())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Result.failure(error)
      }

  private companion object {
    const val BASE = "https://iclass.buaa.edu.cn:8346"
    const val TERMS = "/eschool/app/course/get_base_school_year.action"
    const val COURSES = "/app/choosecourse/get_myall_course.action"
    const val HISTORY = "/eschool/app/my/get_my_course_sign_detail.action"
  }
}

fun officialAttendanceUrl(): String =
    if (ConnectionRuntime.currentMode()?.usesWebVpnGateway == true)
        LocalWebVpnSupport.toWebVpnUrl(
            "https://iclass.buaa.edu.cn:8346/?type=jumpMyCenter#/MyCenter"
        )
    else "https://iclass.buaa.edu.cn:8346/?type=jumpMyCenter#/MyCenter"

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.required(key: String): String =
    text(key).takeIf { it.isNotBlank() } ?: error("iClass 出勤数据缺少必要字段，请使用官方页面核对")

internal fun attendanceDate(value: String): LocalDate? {
  val day = value.take(10)
  val normalized =
      if (day.length >= 8 && day.take(8).all(Char::isDigit))
          "${day.take(4)}-${day.substring(4, 6)}-${day.substring(6, 8)}"
      else day
  return runCatching { LocalDate.parse(normalized) }.getOrNull()
}

private fun attendanceTime(value: String): String =
    Regex("""\b\d{2}:\d{2}\b""").find(value)?.value.orEmpty()
