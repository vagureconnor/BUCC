package app.bucc.homework

import app.bucc.api.local.*
import app.bucc.model.dto.Week
import app.bucc.repository.ScheduleRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

/** Verified against the authenticated syjxapp report-management page. */
class ByxtHomeworkAdapter
internal constructor(
    private val client: HttpClient?,
    private val weeks: suspend (String) -> List<Week>,
    private val ensureAccess: suspend () -> Unit,
) : HomeworkAdapter {
  constructor() :
      this(
          null,
          { term -> ScheduleRepository().loadWeeks(term).getOrThrow() },
          {
            withLocalUndergradPortalAccess("当前账号不支持本科实验报告查询", "schedule_error") {
                  Result.success(Unit)
                }
                .getOrThrow()
          },
      )

  private val currentClient: HttpClient
    get() = client ?: LocalUpstreamClientProvider.shared()

  override val source = HomeworkSource.BYXT
  private val base = "https://byxt.buaa.edu.cn/jwapp/sys/syjxapp"
  private val json = Json { ignoreUnknownKeys = true }

  private suspend fun request(path: String, params: Map<String, String> = emptyMap()): JsonElement {
    val response =
        currentClient.post(localUpstreamUrl("$base/api/$path.do")) {
          header("X-Requested-With", "XMLHttpRequest")
          header(HttpHeaders.Referrer, localUpstreamUrl("$base/*default/index.do"))
          setBody(
              FormDataContent(
                  Parameters.build { params.forEach { (key, value) -> append(key, value) } }
              )
          )
        }
    check(response.status.isSuccess()) { "实验报告查询失败，请重新登录校园账号（${response.status.value}）" }
    val body = response.bodyAsText()
    check(body.trimStart().startsWith("{")) { "实验报告会话已过期，请重新登录校园账号" }
    val envelope = json.parseToJsonElement(body).obj()
    check(envelope.text("code") == "0") { envelope.text("msg") ?: "实验报告查询失败" }
    return envelope["datas"]?.obj()?.get(path.substringAfterLast('/')) ?: error("实验报告数据缺失")
  }

  override suspend fun load(): List<HomeworkItem> {
    ensureAccess()
    // Entering this app completes the existing campus SSO session for syjxapp.
    currentClient.get(localUpstreamUrl("$base/*default/index.do"))
    val term =
        (request("cssz/queryAcademicYearSemester") as? JsonPrimitive)?.contentOrNull?.takeIf {
          it.isNotBlank()
        } ?: error("实验系统未返回当前学期")
    val data = request("sybggl/scsybg/get", mapOf("XNXQDM" to term))
    return parseByxtReports(data, term, weeks(term))
  }
}

internal fun parseByxtReports(
    data: JsonElement,
    term: String,
    weeks: List<Week>,
): List<HomeworkItem> =
    data.array().flatMap { entry ->
      val course = entry.obj()
      val courseId = course.text("taskId") ?: error("实验课程缺少标识")
      (course["rounds"] as? JsonArray).orEmpty().mapNotNull { value ->
        val round = value.obj()
        val reportData = round["reports"] as? JsonArray ?: return@mapNotNull null
        if (reportData.isEmpty()) return@mapNotNull null
        val roundId = round.text("roundId") ?: error("实验轮次缺少标识")
        val reports =
            reportData.mapIndexed { index, element ->
              val report = element.obj()
              ReportUpload(
                  index + 1,
                  !report.text("reportAttachmentToken").isNullOrBlank(),
                  report.text("reportAttachmentName")?.takeIf { it.isNotBlank() },
                  parseHomeworkDate(report.text("startTime")),
                  parseHomeworkDate(report.text("endTime")),
              )
            }
        val weekNumber = Regex("\\d+").find(round.text("weeks").orEmpty())?.value?.toIntOrNull()
        val day =
            mapOf(
                "星期一" to 1,
                "星期二" to 2,
                "星期三" to 3,
                "星期四" to 4,
                "星期五" to 5,
                "星期六" to 6,
                "星期日" to 7,
                "星期天" to 7,
            )[round.text("dayOfWeek")]
        val time =
            Regex("\\[(\\d{2}:\\d{2})-")
                .find(round.text("sectionPeriod").orEmpty())
                ?.groupValues
                ?.get(1)
        val date =
            weeks
                .firstOrNull { it.serialNumber == weekNumber }
                ?.startDate
                ?.let { start ->
                  day?.let { LocalDate.parse(start).plus(DatePeriod(days = it - 1)) }
                }
        val experimentAt =
            if (date != null && time != null) LocalDateTime(date, LocalTime.parse(time)) else null
        val missing = reports.filterNot { it.uploaded }
        HomeworkItem(
            id = "byxt:$term:$courseId:$roundId",
            source = HomeworkSource.BYXT,
            upstreamId = roundId,
            courseId = courseId,
            courseName = course.text("courseName").orEmpty(),
            title =
                listOfNotNull(round.text("projectName"), round.text("roundName"))
                    .joinToString(" · "),
            startAt = missing.mapNotNull { it.startAt }.minOrNull(),
            dueAt = missing.mapNotNull { it.dueAt }.minOrNull(),
            submission =
                if (reports.any { it.uploaded }) HomeworkSubmission.SUBMITTED
                else HomeworkSubmission.UNSUBMITTED,
            reports = reports,
            experimentAt = experimentAt,
            content =
                listOfNotNull(
                        round.text("weeks"),
                        round.text("dayOfWeek"),
                        round.text("sectionPeriod"),
                        round.text("teacher"),
                        round.text("classRoom"),
                    )
                    .joinToString(" · "),
            url = "https://byxt.buaa.edu.cn/jwapp/sys/syjxapp/*default/index.do#/sybggl",
        )
      }
    }

fun defaultHomeworkAdapters(account: String): List<HomeworkAdapter> {
  val semester = HomeworkSemesterProvider()
  return listOf(
      SpocHomeworkAdapter(),
      JudgeHomeworkAdapter(account, semester = semester::load),
      AccodingHomeworkAdapter(account),
      PatpatHomeworkAdapter(account),
      ByxtHomeworkAdapter(),
  )
}
