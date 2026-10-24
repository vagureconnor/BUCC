package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.ensureLocalWebVpnSession
import app.bucc.api.local.localGatewayServiceUrl
import app.bucc.api.local.localServiceSsoUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlin.math.ceil
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class TdHealthCloudCounts(
    val term: String,
    val todayCount: Int,
    val semesterCount: Int,
    val dayCounts: Map<String, Int>,
)

internal class TdHealthCloudClient(
    private val httpClient: HttpClient,
    baseUrl: String = TdDefaults.healthCloudBaseUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val prepareGateway: suspend () -> Unit = ::ensureLocalWebVpnSession,
) {
  private val directBaseUrl = baseUrl.trimEnd('/') + "/"
  private var baseUrl = directBaseUrl
  private var queryMode: ConnectionMode? = null

  suspend fun queryTodayValidExitCount(
      studentId: String,
      password: String,
      day: LocalDate,
      eventNumber: String = TdDefaults.eventNumber,
  ): Int {
    login(studentId, password)
    val term = currentTerm()
    return queryDayCount(studentId, day.toString(), term, eventNumber)
  }

  suspend fun queryCounts(
      studentId: String,
      password: String,
      throughDay: LocalDate,
      eventNumber: String = TdDefaults.eventNumber,
      cachedTerm: String? = null,
      cachedDayCounts: Map<String, Int> = emptyMap(),
  ): TdHealthCloudCounts {
    login(studentId, password)
    val term = currentTerm()
    val todayKey = throughDay.toString()
    val todayCount = queryDayCount(studentId, todayKey, term, eventNumber)
    val startDay = termStartDay(term)
    val refreshFromEpochDay = maxOf(startDay.toEpochDays(), throughDay.toEpochDays() - 1)
    val result = linkedMapOf(todayKey to todayCount.coerceIn(0, 3))
    val missingDays = mutableListOf<LocalDate>()
    for (epochDay in startDay.toEpochDays()..throughDay.toEpochDays()) {
      val day = LocalDate.fromEpochDays(epochDay)
      val key = day.toString()
      if (key in result) continue
      val cached = cachedDayCounts[key]
      if (cachedTerm == term && cached != null && epochDay < refreshFromEpochDay) {
        result[key] = cached.coerceIn(0, 3)
      } else missingDays += day
    }
    // All day reads share the authenticated session; cap parallelism to avoid flooding the gateway.
    val permits = Semaphore(4)
    val refreshed = coroutineScope {
      missingDays
          .map { day ->
            async {
              permits.withPermit {
                val key = day.toString()
                key to queryDayCount(studentId, key, term, eventNumber).coerceIn(0, 3)
              }
            }
          }
          .awaitAll()
    }
    result.putAll(refreshed)
    return TdHealthCloudCounts(
        term = term,
        todayCount = todayCount,
        semesterCount = result.values.sum(),
        dayCounts = result,
    )
  }

  private suspend fun login(studentId: String, password: String) {
    queryMode = ConnectionRuntime.currentMode()
    if (queryMode == ConnectionMode.WEBVPN) prepareGateway()
    baseUrl = directBaseUrl
    val loginPage = requestGet(resolve("index.php"))
    val form = findLoginForm(loginPage.body)
    if (form == null) {
      val page = Url(loginPage.url)
      val origin = Url(baseUrl)
      if (
          page.host == origin.host &&
              page.protocol == origin.protocol &&
              page.port == origin.port &&
              page.encodedPath == "${origin.encodedPath}main.php"
      ) {
        if (hasValidTerm(loginPage.body)) return
        val scorePage = requestGet(resolve(PAGE_URL))
        val scoreUrl = Url(scorePage.url)
        if (
            scoreUrl.host == origin.host &&
                scoreUrl.protocol == origin.protocol &&
                scoreUrl.port == origin.port &&
                scoreUrl.encodedPath == page.encodedPath &&
                hasValidTerm(scorePage.body)
        )
            return
      }
      throw IllegalStateException("健康云登录页面格式无法识别，请重新登录 BUCC 后刷新")
    }
    check(password.isNotBlank()) { "健康云会话已过期，请重新登录 BUCC 后刷新" }
    val fields = form.fields.toMutableMap()
    fields["username"] = studentId
    fields["password"] = password
    if ("type" !in fields) fields["type"] = "username_password"
    if ("_eventId" !in fields) fields["_eventId"] = "submit"
    if ("submit" !in fields) fields["submit"] = "LOGIN"
    val response =
        requestForm(resolveAgainst(loginPage.url, form.action.ifBlank { "login" }), fields)
    if (findLoginForm(response.body) != null) {
      throw IllegalStateException("健康云登录失败，请检查学号和密码")
    }
  }

  private fun hasValidTerm(page: String): Boolean =
      runCatching { termStartDay(extractTerm(page)) }.isSuccess

  private suspend fun currentTerm(): String {
    val page = requestGet(resolve(PAGE_URL)).body
    return extractTerm(page).takeIf(String::isNotBlank)
        ?: throw IllegalStateException("健康云未返回学年学期信息，请检查登录状态")
  }

  private suspend fun queryDayCount(
      studentId: String,
      day: String,
      term: String,
      eventNumber: String,
  ): Int {
    val records = mutableListOf<JsonObject>()
    var page = 1
    var pageCount = 1
    var infoProperty = 1
    while (page <= pageCount) {
      val response =
          requestForm(
                  resolve(RECORD_DETAIL_URL),
                  mapOf(
                      "stuNo" to studentId,
                      "eventNo" to eventNumber,
                      "detailDate" to day,
                      "detailYearTerm" to term,
                      "trgtPage" to page.toString(),
                      "pageSize" to PAGE_SIZE.toString(),
                  ),
              )
              .body
      val payload =
          runCatching { json.parseToJsonElement(response).jsonObject }
              .getOrElse { throw IllegalStateException("健康云流水响应格式无效") }
      if (payload["status"]?.jsonPrimitive?.content != "1") {
        throw IllegalStateException(payload["msg"]?.jsonPrimitive?.contentOrNull ?: "健康云流水查询失败")
      }
      val count = payload["count"]?.jsonPrimitive?.intOrNull ?: 0
      infoProperty = payload["infoProperty"]?.jsonPrimitive?.intOrNull ?: infoProperty
      pageCount = maxOf(1, ceil(count.toDouble() / PAGE_SIZE).toInt())
      payload["infoList"]?.jsonArray?.forEach { element ->
        runCatching { element.jsonObject }.getOrNull()?.let(records::add)
      }
      page++
    }
    return records.count { record -> isValidExitRecord(record, day, infoProperty) }
  }

  private suspend fun requestGet(url: String): TextResponse = execute {
    httpClient.get(checkedUrl(url)) { header(HttpHeaders.UserAgent, "BUCC AutoTD") }
  }

  private suspend fun requestForm(url: String, fields: Map<String, String>): TextResponse =
      execute {
        httpClient.submitForm(
            url = checkedUrl(url),
            formParameters =
                Parameters.build { fields.forEach { (key, value) -> append(key, value) } },
        ) {
          header(HttpHeaders.UserAgent, "BUCC AutoTD")
        }
      }

  private suspend fun execute(block: suspend () -> HttpResponse): TextResponse {
    var response =
        try {
          block()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (cause: Throwable) {
          throw HealthCloudConnectionException("连接健康云失败", cause)
        }
    // Login POST responses use 302/303; follow as GET without resending credentials.
    var redirects = 0
    while (response.status.value in setOf(301, 302, 303)) {
      check(redirects++ < 10) { "健康云登录重定向次数过多" }
      val location =
          response.headers[HttpHeaders.Location] ?: throw IllegalStateException("健康云重定向缺少目标地址")
      val responseUrl = LocalWebVpnSupport.fromWebVpnUrl(response.call.request.url.toString())
      val gatewayLocation =
          when {
            location.startsWith("/http/") ||
                location.startsWith("/https/") ||
                location.startsWith("/http-") ||
                location.startsWith("/https-") -> "https://d.buaa.edu.cn$location"
            location.startsWith("//") -> "https:$location"
            else -> location
          }
      val next =
          checkedUrl(resolveAgainst(responseUrl, LocalWebVpnSupport.fromWebVpnUrl(gatewayLocation)))
      response = httpClient.get(next) { header(HttpHeaders.UserAgent, "BUCC AutoTD") }
    }
    if (!response.status.isSuccess()) {
      throw HealthCloudConnectionException("连接健康云失败（HTTP ${response.status.value}）")
    }
    return TextResponse(
        response.bodyAsText(),
        LocalWebVpnSupport.fromWebVpnUrl(response.call.request.url.toString()),
    )
  }

  private fun checkedUrl(value: String): String {
    val mode = ConnectionRuntime.currentMode()
    check(mode == queryMode) { "TD 健康云访问地址与当前连接模式不匹配，请重新查询" }
    val target = Url(LocalWebVpnSupport.fromWebVpnUrl(value))
    val selected = Url(baseUrl)
    check(target.user.isNullOrEmpty() && target.password.isNullOrEmpty()) { "健康云跳转地址无效" }
    val sameOrigin =
        target.protocol == selected.protocol &&
            target.host == selected.host &&
            target.port == selected.port
    val officialSso =
        target.protocol.name == "https" && target.host == "sso.buaa.edu.cn" && target.port == 443
    check(sameOrigin || officialSso) { "健康云跳转到了 VPN 登录门户，请先重新登录 WebVPN 后重试" }
    val upstream = target.toString()
    return if (officialSso) localServiceSsoUrl(upstream) else localGatewayServiceUrl(upstream)
  }

  private fun resolve(path: String): String = checkedUrl(resolveAgainst(baseUrl, path))

  private data class TextResponse(val body: String, val url: String)

  private companion object {
    const val PAGE_SIZE = 20
    const val PAGE_URL = "main.php?module=stu&title=stu_sun_score"
    const val RECORD_DETAIL_URL =
        "api.php?module=stu&file=ajax_stu&title=stu_sun_score&action=getRecordDetail&isAjax=1"
  }
}

private class HealthCloudConnectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private data class TdLoginForm(val action: String, val fields: Map<String, String>)

private fun findLoginForm(page: String): TdLoginForm? {
  val formRegex = Regex("<form\\b([^>]*)>([\\s\\S]*?)</form>", RegexOption.IGNORE_CASE)
  val inputRegex = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
  for (match in formRegex.findAll(page)) {
    val fields = linkedMapOf<String, String>()
    inputRegex.findAll(match.groupValues[2]).forEach { input ->
      val attributes = input.groupValues[1]
      val name = htmlAttribute(attributes, "name")
      if (name.isNotBlank()) fields[name] = htmlAttribute(attributes, "value")
    }
    if ("username" in fields && "password" in fields) {
      return TdLoginForm(
          action = htmlAttribute(match.groupValues[1], "action"),
          fields = fields,
      )
    }
  }
  return null
}

private fun extractTerm(page: String): String {
  val input =
      Regex("<input\\b([^>]*\\bid\\s*=\\s*['\"]xnxq['\"][^>]*)>", RegexOption.IGNORE_CASE)
          .find(page)
          ?.groupValues
          ?.get(1)
          ?.let { htmlAttribute(it, "value") }
  if (!input.isNullOrBlank()) return input.trim()

  val container =
      Regex(
              "<(select|[^>\\s]+)\\b([^>]*\\bid\\s*=\\s*['\"]xnxq['\"][^>]*)>([\\s\\S]*?)</\\1>",
              RegexOption.IGNORE_CASE,
          )
          .find(page)
  if (container != null) {
    val body = container.groupValues[3]
    val selected =
        Regex("<option\\b([^>]*\\bselected\\b[^>]*)>([\\s\\S]*?)</option>", RegexOption.IGNORE_CASE)
            .find(body)
    if (selected != null) {
      val value = htmlAttribute(selected.groupValues[1], "value")
      return (value.ifBlank { stripHtml(selected.groupValues[2]) }).trim()
    }
    val text = stripHtml(body).trim()
    if (text.isNotBlank()) return text
  }

  val function =
      Regex(
              "function\\s+getStuEventDetail\\s*\\([^)]*\\)([\\s\\S]*?)(?=function\\s+getRecordDetail)",
              RegexOption.IGNORE_CASE,
          )
          .find(page)
          ?.groupValues
          ?.get(1) ?: return ""
  val year = Regex("\\bvar\\s+xn\\s*=\\s*['\"]([^'\"]+)['\"]").find(function)?.groupValues?.get(1)
  val semester =
      Regex("\\bvar\\s+xq\\s*=\\s*['\"]([^'\"]+)['\"]").find(function)?.groupValues?.get(1)
  return if (year != null && semester != null) "$year-$semester" else ""
}

private fun termStartDay(term: String): LocalDate {
  val match =
      Regex("(\\d{4})-(\\d{4})-([1-3])").matchEntire(term)
          ?: throw IllegalStateException("健康云学期格式无法识别")
  val firstYear = match.groupValues[1].toInt()
  val secondYear = match.groupValues[2].toInt()
  return when (match.groupValues[3].toInt()) {
    1 -> LocalDate(firstYear, 8, 1)
    2 -> LocalDate(secondYear, 1, 1)
    else -> LocalDate(secondYear, 5, 1)
  }
}

private fun isValidExitRecord(record: JsonObject, day: String, infoProperty: Int): Boolean {
  val mark = record["mark"]?.jsonPrimitive?.content ?: "0"
  val valid =
      mark == "1" ||
          (infoProperty != 1 &&
              mark == "0" &&
              record["notmarkreason"]?.jsonPrimitive?.contentOrNull.orEmpty().isEmpty())
  val location = record["location"]?.jsonPrimitive?.contentOrNull.orEmpty()
  val swapTime = record["swaptime"]?.jsonPrimitive?.contentOrNull.orEmpty()
  return valid && "出口" in location && swapTime.take(10) == day
}

private fun htmlAttribute(attributes: String, name: String): String =
    Regex("\\b${Regex.escape(name)}\\s*=\\s*(['\"])([\\s\\S]*?)\\1", RegexOption.IGNORE_CASE)
        .find(attributes)
        ?.groupValues
        ?.get(2)
        .orEmpty()

private fun stripHtml(value: String): String = value.replace(Regex("<[^>]+>"), "")

private fun resolveAgainst(base: String, path: String): String {
  if (path.startsWith("http://") || path.startsWith("https://")) return path
  val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1).orEmpty()
  if (path.startsWith('/')) return origin + path
  val directory = base.substringBeforeLast('/', missingDelimiterValue = base.trimEnd('/'))
  return "$directory/$path"
}
