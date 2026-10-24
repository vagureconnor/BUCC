package app.bucc.homework

import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.ensureLocalWebVpnSession
import app.bucc.api.local.localGatewayServiceUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("服务返回的数据格式有误")

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonElement.array(): List<JsonElement> =
    (this as? JsonArray)?.toList() ?: error("服务返回的列表格式有误")

class HomeworkLoginRequiredException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun homeworkPlainText(html: String): String =
    html
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .trim()

class PatpatHomeworkAdapter internal constructor(private val client: HttpClient) : HomeworkAdapter {
  constructor(
      account: String
  ) : this(LocalUpstreamClientProvider.newClient(HomeworkCookies(account, HomeworkSource.PATPAT)))

  override val source = HomeworkSource.PATPAT
  override val requiresSeparateLogin = true
  private val json = Json { ignoreUnknownKeys = true }
  private val base = "http://211.71.15.49"

  override suspend fun login(username: String, password: String) {
    val response =
        client.post("$base/api/auth/login") {
          contentType(ContentType.Application.Json)
          setBody(
              buildJsonObject {
                    put("buaaId", username)
                    put("password", password)
                  }
                  .toString()
          )
        }
    val root = patpatJsonResponse(response, "Patpat 登录失败")
    check(root["status"]?.let { it != JsonNull } == true) { "Patpat 登录失败：响应缺少 status" }
    requireAccount(expectedBuaaId = username)
  }

  override suspend fun load(): List<HomeworkItem> {
    requireAccount()
    return listOf("lab", "iter")
        .flatMap { kind ->
          val list =
              patpatJsonResponse(client.get("$base/api/task/$kind/query"), "Patpat 作业列表加载失败")
                  .patpatEnvelope("Patpat 作业列表")
                  .array()
          list.map { row ->
            val fields = row.obj()
            val upstreamId =
                fields.text("id")?.takeIf(String::isNotBlank) ?: error("Patpat 作业列表缺少 id")
            val listTitle =
                fields.text("title")?.takeIf(String::isNotBlank) ?: error("Patpat 作业列表缺少 title")
            val detail = loadTaskDetail(kind, upstreamId)
            val scoreData =
                patpatJsonResponse(
                        client.get("$base/api/task/$kind/score/$upstreamId"),
                        "Patpat 提交状态加载失败",
                    )
                    .patpatEnvelope("Patpat 提交状态")
            val filename =
                when (scoreData) {
                  JsonNull -> null
                  is JsonObject -> {
                    check(scoreData.containsKey("filename")) { "Patpat 提交状态缺少 filename" }
                    when (val value = scoreData["filename"]) {
                      JsonNull -> null
                      is JsonPrimitive -> value.contentOrNull ?: error("Patpat filename 格式有误")
                      else -> error("Patpat filename 格式有误")
                    }
                  }
                  else -> error("Patpat 提交状态格式有误")
                }
            HomeworkItem(
                id = "patpat:$kind:$upstreamId",
                source = HomeworkSource.PATPAT,
                upstreamId = "$kind:$upstreamId",
                title = detail.title.ifBlank { listTitle },
                startAt = detail.startAt,
                dueAt = detail.dueAt,
                submission =
                    if (filename.isNullOrBlank()) HomeworkSubmission.UNSUBMITTED
                    else HomeworkSubmission.SUBMITTED,
                url = "",
                content = detail.content,
            )
          }
        }
        .distinctBy { it.id }
  }

  override suspend fun detail(item: HomeworkItem): HomeworkItem {
    require(item.source == HomeworkSource.PATPAT) { "作业来源不匹配" }
    val (kind, upstreamId) =
        item.upstreamId.split(':', limit = 2).takeIf { it.size == 2 } ?: error("Patpat 作业标识格式有误")
    val detail = loadTaskDetail(kind, upstreamId)
    return item.copy(title = detail.title, content = detail.content)
  }

  private suspend fun requireAccount(expectedBuaaId: String? = null) {
    val root = patpatJsonResponse(client.get("$base/api/account/self"), "Patpat 登录校验失败")
    val account = root["data"] as? JsonObject ?: error("Patpat 登录校验失败：响应缺少 data")
    check((account["id"] as? JsonPrimitive)?.contentOrNull != null) { "Patpat 登录校验失败：账号信息缺少 id" }
    expectedBuaaId?.let { expected ->
      val actual = (account["buaaId"] as? JsonPrimitive)?.contentOrNull
      check(actual == expected) { "Patpat 登录失败：账号信息与输入账号不一致" }
    }
  }

  private data class PatpatTaskDetail(
      val title: String,
      val content: String,
      val startAt: kotlinx.datetime.LocalDateTime?,
      val dueAt: kotlinx.datetime.LocalDateTime?,
  )

  private suspend fun loadTaskDetail(kind: String, id: String): PatpatTaskDetail {
    require(kind == "lab" || kind == "iter") { "Patpat 作业类型有误" }
    val detail =
        patpatJsonResponse(client.get("$base/api/task/$kind/query/$id"), "Patpat 作业详情加载失败")
            .patpatEnvelope("Patpat 作业详情")
            .obj()
    val title = detail.text("title")?.takeIf(String::isNotBlank) ?: error("Patpat 作业详情缺少 title")
    val content = detail.text("content") ?: error("Patpat 作业详情缺少 content")
    val startAt = detail.patpatDate("startTime")
    val dueAt = detail.patpatDate("deadlineTime")
    detail.patpatDate("endTime")
    return PatpatTaskDetail(title, homeworkPlainText(content), startAt, dueAt)
  }

  private fun JsonObject.patpatDate(key: String): kotlinx.datetime.LocalDateTime? {
    check(containsKey(key)) { "Patpat 作业详情缺少 $key" }
    val value = getValue(key)
    if (value is JsonNull) return null
    val raw = (value as? JsonPrimitive)?.contentOrNull ?: error("Patpat $key 格式有误")
    return parseHomeworkDate(raw) ?: error("Patpat $key 格式有误")
  }

  private suspend fun patpatJsonResponse(
      response: io.ktor.client.statement.HttpResponse,
      failure: String,
  ): JsonObject {
    val body = response.bodyAsText()
    check(response.status.isSuccess()) { "$failure（HTTP ${response.status.value}）" }
    return runCatching { json.parseToJsonElement(body).obj() }
        .getOrElse { error("$failure：响应数据格式有误") }
  }

  private fun JsonObject.patpatEnvelope(label: String): JsonElement {
    // Axios response.data is already the JSON body here; unwrap its data only once.
    check(containsKey("data")) { "${label}响应缺少 data" }
    return getValue("data")
  }

  override fun close() = client.close()
}

internal data class AccodingPage(val items: List<HomeworkItem>, val pages: Set<Int>)

private fun accodingTutorial(title: String, course: String): Boolean =
    title.replace(Regex("\\s+"), "").contains("C语言程序设计实训教程") ||
        course.contains("配套教材练习") ||
        course.contains("教程练习")

internal fun parseAccodingPage(html: String): AccodingPage {
  val items =
      Regex("(?is)<tr\\b[^>]*>(.*?)</tr>")
          .findAll(html)
          .mapNotNull { row ->
            val cells =
                Regex("(?is)<td\\b[^>]*>(.*?)</td>")
                    .findAll(row.groupValues[1])
                    .map { it.groupValues[1] }
                    .toList()
            if (cells.size < 6) return@mapNotNull null
            val title = homeworkPlainText(cells[2])
            val courseName = homeworkPlainText(cells[3])
            if (accodingTutorial(title, courseName)) return@mapNotNull null
            val titleCell = cells[2].replace("&amp;", "&")
            val linkedId =
                Regex("(?i)(?:/contest-ng/index\\.html#/|/contest/view\\?[^\"']*\\bid=)([0-9]+)")
                    .find(titleCell)
                    ?.groupValues
                    ?.get(1)
            // The official list exposes the contest ID in its first table column; the title is
            // plain text.
            val id =
                linkedId
                    ?: homeworkPlainText(cells[0]).takeIf { it.matches(Regex("[0-9]+")) }
                    ?: return@mapNotNull null
            HomeworkItem(
                id = "accoding:$id",
                source = HomeworkSource.ACCODING,
                upstreamId = id,
                title = title,
                courseName = courseName,
                startAt = parseHomeworkDate(homeworkPlainText(cells[4])),
                dueAt = parseHomeworkDate(homeworkPlainText(cells[5])),
                canMarkComplete = false,
                url = "https://accoding.buaa.edu.cn/contest-ng/index.html#/$id",
            )
          }
          .toList()
  val pages =
      Regex("(?is)<a\\b([^>]*)>")
          .findAll(html)
          .mapNotNull { anchor ->
            val attributes = anchor.groupValues[1]
            if (attributes.contains("disabled", true)) return@mapNotNull null
            val href =
                Regex("(?is)href\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))")
                    .find(attributes)
                    ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                    ?.replace("&amp;", "&") ?: return@mapNotNull null
            Regex("(?:[?&])page=([0-9]+)(?:&|$)")
                .find(href)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
          }
          .toSet()
  return AccodingPage(items, pages)
}

class AccodingHomeworkAdapter
internal constructor(
    private val client: HttpClient,
    private val prepareGateway: suspend () -> Unit = ::ensureLocalWebVpnSession,
    private val semester: suspend () -> HomeworkSemester = HomeworkSemesterProvider()::load,
) : HomeworkAdapter {
  constructor(
      account: String
  ) : this(LocalUpstreamClientProvider.newClient(HomeworkCookies(account, HomeworkSource.ACCODING)))

  override val source = HomeworkSource.ACCODING
  override val requiresSeparateLogin = true
  private val base = "https://accoding.buaa.edu.cn"
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun login(username: String, password: String) {
    prepareGateway()
    val html = client.get(localGatewayServiceUrl("$base/user/login")).bodyAsText()
    val token =
        Regex(
                "<input[^>]*name=[\"']_csrf[\"'][^>]*value=[\"']([^\"']+)[\"']",
                RegexOption.IGNORE_CASE,
            )
            .find(html)
            ?.groupValues
            ?.get(1) ?: error("Accoding 登录页发生变化，请重试")
    val response =
        client.post(localGatewayServiceUrl("$base/user/login")) {
          setBody(
              FormDataContent(
                  Parameters.build {
                    append("username", username)
                    append("password", password)
                    append("_csrf", token)
                  }
              )
          )
        }
    check(response.status.isSuccess() || response.status.value in listOf(302, 303)) {
      "Accoding 登录失败（${response.status.value}）"
    }
    requireLogin("Accoding 登录失败，请检查邮箱和密码")
  }

  private suspend fun requireLogin(
      message: String = "请在 BUCC 中登录 Accoding",
      classifyExpiredSession: Boolean = false,
  ) {
    val response = client.get(localGatewayServiceUrl("$base/api/users/me"))
    val body = response.bodyAsText()
    if (classifyExpiredSession && response.status.value in listOf(401, 403)) {
      throw HomeworkLoginRequiredException("Accoding 登录状态已过期，请重新登录")
    }
    check(response.status.isSuccess()) { "$message（HTTP ${response.status.value}）" }
    val emptyJsonSession =
        body.isBlank() &&
            response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.equals("application/json", ignoreCase = true) == true
    val payload =
        runCatching { if (emptyJsonSession) JsonNull else json.parseToJsonElement(body) }
            .getOrElse {
              if (classifyExpiredSession) error("$message：响应数据格式有误") else error(message)
            }
    if (
        classifyExpiredSession &&
            (payload is JsonNull || (payload is JsonObject && payload.text("id").isNullOrBlank()))
    ) {
      throw HomeworkLoginRequiredException("Accoding 登录状态已过期，请重新登录")
    }
    val user = payload as? JsonObject ?: error("$message：响应数据格式有误")
    check(!user.text("id").isNullOrBlank()) { message }
  }

  override suspend fun load(): List<HomeworkItem> {
    prepareGateway()
    requireLogin(classifyExpiredSession = true)
    val pending = mutableSetOf(1)
    val visited = mutableSetOf<Int>()
    val items = mutableListOf<HomeworkItem>()
    while (pending.isNotEmpty()) {
      val page = pending.min()
      pending.remove(page)
      if (!visited.add(page)) continue
      val response = client.get(localGatewayServiceUrl("$base/contest/index?page=$page"))
      check(response.status.isSuccess()) { "Accoding 第 $page 页加载失败" }
      val html = response.bodyAsText()
      val parsed = parseAccodingPage(html)
      validateAccodingListPage(html, parsed)
      items += parsed.items
      pending += parsed.pages - visited
    }
    val unique = items.distinctBy { it.id }
    if (unique.isEmpty()) return emptyList()
    val current = semester()
    check(unique.none { it.startAt == null && it.dueAt == null }) { "Accoding 作业日期无法确认，请刷新重试" }
    return unique
        .filter { current.contains(it.startAt, it.dueAt) }
        .map { item -> loadSubmission(item) }
  }

  private suspend fun readContestJson(path: String): JsonElement {
    val response = client.get(localGatewayServiceUrl("$base$path"))
    if (!response.status.isSuccess()) throw AccodingRequestException(response.status.value)
    return json.parseToJsonElement(response.bodyAsText())
  }

  private suspend fun loadSubmission(item: HomeworkItem): HomeworkItem =
      try {
        val contest = readContestJson("/api/contests/${item.upstreamId}").obj()
        val submissions = readContestJson("/api/contests/${item.upstreamId}/submissions").array()
        val status = accodingSubmissionStatus(contest, submissions)
        item.copy(
            submission = status,
            submissionHint =
                if (status == HomeworkSubmission.UNKNOWN) "题目或得分信息暂未确认，请刷新重试" else null,
        )
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (expired: HomeworkLoginRequiredException) {
        throw expired
      } catch (access: AccodingRequestException) {
        if (access.status in listOf(401, 403)) verifyLoginAfterContestAccessFailure()
        item.copy(submission = HomeworkSubmission.UNKNOWN, submissionHint = "提交状态读取失败，请刷新重试")
      } catch (_: Exception) {
        item.copy(submission = HomeworkSubmission.UNKNOWN, submissionHint = "提交状态读取失败，请刷新重试")
      }

  private suspend fun verifyLoginAfterContestAccessFailure() {
    try {
      requireLogin(classifyExpiredSession = true)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (expired: HomeworkLoginRequiredException) {
      throw expired
    } catch (_: Exception) {
      // A failed account check cannot prove expiry; keep this contest's status unknown.
    }
  }

  override suspend fun detail(item: HomeworkItem): HomeworkItem {
    prepareGateway()
    val response = client.get(localGatewayServiceUrl("$base/api/contests/${item.upstreamId}"))
    check(response.status.isSuccess()) { "比赛详情加载失败" }
    val detail = json.parseToJsonElement(response.bodyAsText()).obj()
    return item.copy(content = homeworkPlainText(detail.text("description").orEmpty()))
  }

  override fun close() = client.close()
}

private class AccodingRequestException(val status: Int) : RuntimeException()

private fun validateAccodingListPage(html: String, parsed: AccodingPage) {
  val text = homeworkPlainText(html)
  check(
      !Regex("(?is)<form\\b[^>]*action=[\"'][^\"']*/user/login|请登录|登录以继续").containsMatchIn(html)
  ) {
    "Accoding 登录状态已失效，请重新登录"
  }
  val hasExplicitEmptyState =
      Regex(
              "暂无(?:比赛|赛事|竞赛)|没有(?:比赛|赛事|竞赛)|no\\s+(?:contests|competitions)",
              RegexOption.IGNORE_CASE,
          )
          .containsMatchIn(text)
  val tableRows =
      Regex("(?is)<tr\\b[^>]*>(.*?)</tr>")
          .findAll(html)
          .map { row ->
            Regex("(?is)<td\\b[^>]*>(.*?)</td>")
                .findAll(row.groupValues[1])
                .map { it.groupValues[1] }
                .toList()
          }
          .filter { cells -> cells.any { homeworkPlainText(it).isNotBlank() } }
          .toList()
  val headerRows = tableRows.filter(::accodingHeaderRow)
  val dataRows = tableRows - headerRows.toSet()
  val tutorialRows =
      dataRows.filter { cells ->
        cells.size >= 4 &&
            accodingTutorial(homeworkPlainText(cells[2]), homeworkPlainText(cells[3]))
      }
  val nonTutorialRows = dataRows - tutorialRows.toSet()
  check(nonTutorialRows.size == parsed.items.size) { "Accoding 作业列表存在无法识别的记录，请刷新重试" }
  val onlyTutorialRows = dataRows.isNotEmpty() && tutorialRows.size == dataRows.size
  val hasTable = Regex("(?is)<table\\b").containsMatchIn(html)
  val hasPagination = Regex("(?is)<a\\b[^>]*href=[\"'][^\"']*[?&]page=[0-9]+").containsMatchIn(html)
  check(
      parsed.items.isNotEmpty() ||
          hasExplicitEmptyState ||
          onlyTutorialRows ||
          ((hasTable || hasPagination) &&
              dataRows.isEmpty() &&
              (headerRows.isEmpty() || headerRows.size == tableRows.size))
  ) {
    "Accoding 作业列表响应格式异常，请刷新重试"
  }
}

private fun accodingHeaderRow(cells: List<String>): Boolean {
  if (cells.isEmpty()) return false
  val first = homeworkPlainText(cells[0]).trim()
  return first.matches(Regex("(?i)(?:比赛|赛事|竞赛)?(?:ID|编号)"))
}

/** The official contest UI uses personal submissions' AC result; scores are normalized to 1. */
internal fun accodingSubmissionStatus(
    contest: JsonObject,
    submissions: List<JsonElement>,
): HomeworkSubmission {
  val problems =
      (contest["problems"] as? JsonArray)?.map { it.obj().text("id")?.takeIf(String::isNotBlank) }
          ?: return HomeworkSubmission.UNKNOWN
  if (problems.isEmpty() || problems.any { it == null }) return HomeworkSubmission.UNKNOWN
  val records = submissions.map { it.obj() }.filter { it.text("problem_id") in problems }
  val full =
      problems.distinct().count { id ->
        records.any {
          it.text("problem_id") == id &&
              it.text("result") == "AC" &&
              it.text("score")?.toDoubleOrNull() == 1.0
        }
      }
  return when {
    full == problems.distinct().size -> HomeworkSubmission.SUBMITTED
    records.any {
      it.text("result").isNullOrBlank() || it.text("score")?.toDoubleOrNull()?.isFinite() != true
    } -> HomeworkSubmission.UNKNOWN
    records.isNotEmpty() -> HomeworkSubmission.PARTIAL
    else -> HomeworkSubmission.UNSUBMITTED
  }
}
