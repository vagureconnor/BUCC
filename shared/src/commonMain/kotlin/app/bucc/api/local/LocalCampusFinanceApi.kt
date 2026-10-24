package app.bucc.api.local

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.finance.CampusFinanceBalances
import app.bucc.api.finance.CampusNetworkTraffic
import app.bucc.api.finance.FinanceBalance
import app.bucc.api.finance.formatFinanceAmount
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val ZFW_HOME_TRAFFIC_LABEL_USED = "已用流量"
private const val ZFW_HOME_TRAFFIC_LABEL_FREE = "免费流量剩余(不含套餐)"
private const val ZFW_HOME_TRAFFIC_LABEL_CHARGED = "计费流量剩余(不含套餐)"

internal class LocalCampusFinanceApi(
    private val clientFactory: () -> HttpClient = {
      LocalUpstreamClientProvider.newClient(
          LocalCookieStore.storage(ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT)
      )
    },
    private val portalBaseUrl: String = "https://it.buaa.edu.cn",
    private val zfwBaseUrl: String = "https://zfw.buaa.edu.cn",
    private val ensureZfwSession: suspend () -> Boolean = { true },
    private val zfwClientFactory: () -> HttpClient = clientFactory,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun loadBalances(
      account: String?,
      includeCard: Boolean = true,
      includeInternet: Boolean = true,
  ): CampusFinanceBalances {
    if (account.isNullOrBlank()) {
      val error = "请先登录 BUCC，再刷新余额。也可以直接打开信息化服务平台。"
      return CampusFinanceBalances(
          internetFee = FinanceBalance(error = error),
          campusCard = FinanceBalance(error = error),
          internetTraffic = CampusNetworkTraffic(error = "请先登录 BUCC，再打开校园网自助服务平台查询流量。"),
      )
    }

    val client = clientFactory()
    return try {
      val portalError =
          try {
            establishPortalSession(client)
            null
          } catch (e: CancellationException) {
            throw e
          } catch (e: FinancePortalException) {
            e.message
          } catch (_: Exception) {
            "信息化服务平台暂时无法读取，请稍后刷新。"
          }
      val campusCard =
          if (!includeCard) FinanceBalance()
          else if (portalError != null) FinanceBalance(error = portalError)
          else readSafely { loadCampusCardBalance(client) }
      val internetFee =
          if (!includeInternet) FinanceBalance()
          else if (portalError != null) FinanceBalance(error = portalError)
          else readSafely { loadInternetFeeBalance(client) }
      val internetTraffic =
          if (includeInternet) readTrafficSafely { loadInternetTraffic(client) }
          else CampusNetworkTraffic()
      CampusFinanceBalances(internetFee, campusCard, internetTraffic = internetTraffic)
    } finally {
      client.close()
    }
  }

  private suspend fun establishPortalSession(client: HttpClient) {
    // The portal's browser frontend navigates through this service URL to exchange the
    // existing unified-auth session for an IT Portal session. Native Ktor does not run
    // that frontend JavaScript, so perform the observed service exchange directly.
    val homeUrl = portalUrl("/home/")
    val response =
        client.get(portalUrl("/manage/common/cas_login/30001")) { parameter("redirect", homeUrl) }
    if (!response.status.isSuccess()) {
      throw FinancePortalException("信息化服务平台未能建立登录会话，请打开官方门户后重试。")
    }
    val finalUrl = response.call.request.url
    val expectedHome = Url(homeUrl)
    if (
        !finalUrl.host.equals(expectedHome.host, ignoreCase = true) ||
            !finalUrl.protocol.name.equals(expectedHome.protocol.name, ignoreCase = true) ||
            finalUrl.encodedPath.trimEnd('/') != expectedHome.encodedPath.trimEnd('/')
    ) {
      throw FinancePortalException("信息化服务平台登录状态无效，请打开官方门户登录后重试。")
    }
  }

  private suspend fun loadCampusCardBalance(client: HttpClient): FinanceBalance {
    val data = getPortalData(client, "/buaa/frontend/card/balance")
    val topLevel = data as? JsonObject
    val balanceObject = topLevel?.get("data") as? JsonObject ?: topLevel
    val rawValue =
        balanceObject?.get("value").primitiveContent()?.takeIf { it.isNotBlank() }
            ?: throw FinancePortalException("官方门户返回了无法识别的校园卡余额数据。")
    val value =
        formatFinanceAmount(rawValue) ?: throw FinancePortalException("官方门户返回了无法识别的校园卡余额数据。")
    val unit = balanceObject?.get("unit").primitiveContent() ?: "元"
    return FinanceBalance(value = value, unit = unit)
  }

  private suspend fun loadInternetFeeBalance(client: HttpClient): FinanceBalance {
    val itemsData =
        getPortalData(client, "/personal/frontend/data/items") {
          parameter("type", "personal_data")
        }
    val items =
        (itemsData as? JsonObject)?.get("data") as? JsonArray
            ?: throw FinancePortalException("官方门户未返回网费数据配置。")
    val item =
        items.firstOrNull { entry ->
          (entry as? JsonObject)?.get("key").primitiveContent() == "net.netFee"
        } as? JsonObject ?: throw FinancePortalException("官方门户没有提供网费余额项目。")
    val itemId =
        item["id"].primitiveContent()?.takeIf { it.isNotBlank() }
            ?: throw FinancePortalException("官方门户的网费余额项目缺少查询编号。")
    val detail = getPortalData(client, "/personal/frontend/data/detail") { parameter("id", itemId) }
    val rawValue =
        ((detail as? JsonObject)?.get("data") as? JsonObject)
            ?.get("value")
            .primitiveContent()
            ?.takeIf { it.isNotBlank() } ?: throw FinancePortalException("官方门户返回了无法识别的网费余额数据。")
    val value = formatFinanceAmount(rawValue) ?: throw FinancePortalException("官方门户返回了无法识别的网费余额数据。")
    return FinanceBalance(value = value, unit = "元")
  }

  private suspend fun loadInternetTraffic(client: HttpClient): CampusNetworkTraffic {
    if (!ensureZfwSession()) {
      throw FinancePortalException("请完成 BUCC 内的校园网验证码验证。")
    }
    // ZFW is direct in both modes; reading /home through the WebVPN gateway loses the
    // direct session and produces a false "login invalid" result.
    val zfwClient = zfwClientFactory()
    val response =
        try {
          zfwClient.get("$zfwBaseUrl/home") { header("Accept", "text/html,application/xhtml+xml") }
        } finally {
          zfwClient.close()
        }
    if (
        response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
    ) {
      throw FinancePortalException("校园网流量查询需要先在官方自助服务平台完成登录。")
    }
    if (!response.status.isSuccess()) {
      throw FinancePortalException("校园网自助服务平台暂时无法读取流量，请稍后重试。")
    }

    val finalUrl = Url(LocalWebVpnSupport.fromWebVpnUrl(response.call.request.url.toString()))
    val expectedHost = Url(zfwBaseUrl).host
    if (
        !finalUrl.host.equals(expectedHost, ignoreCase = true) ||
            finalUrl.encodedPath.trimEnd('/') != "/home"
    ) {
      throw FinancePortalException("校园网登录状态无效，请在官方自助服务平台登录后重试。")
    }

    val html = response.bodyAsText()
    if (!html.contains(ZFW_HOME_TRAFFIC_LABEL_USED)) {
      throw FinancePortalException("校园网首页没有返回流量数据，请确认官方账号已登录。")
    }
    return parseZfwHomeTraffic(html)
  }

  private suspend fun readSafely(block: suspend () -> FinanceBalance): FinanceBalance =
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: FinancePortalException) {
        FinanceBalance(error = e.message)
      } catch (_: Exception) {
        FinanceBalance(error = "暂时无法读取，请打开官方门户确认登录状态。")
      }

  private suspend fun readTrafficSafely(
      block: suspend () -> CampusNetworkTraffic
  ): CampusNetworkTraffic =
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: FinancePortalException) {
        CampusNetworkTraffic(error = e.message)
      } catch (_: Exception) {
        CampusNetworkTraffic(error = "校园网暂时无法读取流量，请打开官方页面确认登录状态。")
      }

  private suspend fun getPortalData(
      client: HttpClient,
      path: String,
      configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
  ): JsonElement {
    val response =
        client.get(portalUrl(path)) {
          header("X-Requested-With", "XMLHttpRequest")
          header("Accept", "application/json, text/plain, */*")
          configure()
        }
    if (
        response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
    ) {
      throw FinancePortalException("信息化服务平台登录已失效，请打开官方门户重新登录。")
    }
    if (!response.status.isSuccess()) {
      throw FinancePortalException("信息化服务平台暂时无法读取余额，请稍后重试。")
    }

    val body = response.bodyAsText()
    if (body.trimStart().startsWith('<')) {
      throw FinancePortalException("信息化服务平台要求重新登录，请打开官方门户登录后重试。")
    }
    val envelope =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw FinancePortalException("信息化服务平台返回了无法识别的数据。")
    val errorCode =
        envelope["e"].primitiveContent()
            ?: envelope["code"].primitiveContent()
            ?: throw FinancePortalException("信息化服务平台返回了无法识别的数据。")
    if (errorCode == "10013") {
      throw FinancePortalException("信息化服务平台登录已失效，请打开官方门户重新登录。")
    }
    val isSuccess = errorCode == "0" || errorCode == "OK" || errorCode == "200"
    if (!isSuccess) {
      throw FinancePortalException("信息化服务平台暂时无法读取余额，请稍后重试。")
    }
    return envelope["d"] ?: throw FinancePortalException("信息化服务平台返回了无法识别的数据。")
  }

  private fun portalUrl(path: String): String = localUpstreamUrl("$portalBaseUrl$path")
}

private class FinancePortalException(message: String) : Exception(message)

internal fun parseZfwHomeTraffic(html: String): CampusNetworkTraffic {
  val table =
      Regex("(?is)<table\\b[^>]*>.*?</table>")
          .findAll(html)
          .firstOrNull { match ->
            listOf(
                    ZFW_HOME_TRAFFIC_LABEL_USED,
                    ZFW_HOME_TRAFFIC_LABEL_FREE,
                    ZFW_HOME_TRAFFIC_LABEL_CHARGED,
                )
                .all { label -> zfwHtmlText(match.value).contains(label) }
          }
          ?.value ?: throw FinancePortalException("校园网首页没有返回完整的流量项目。")

  val header =
      Regex("(?is)<thead\\b[^>]*>(.*?)</thead>").find(table)?.groupValues?.get(1)
          ?: throw FinancePortalException("校园网首页的流量表格格式无法识别。")
  val labels =
      Regex("(?is)<th\\b[^>]*>(.*?)</th>")
          .findAll(header)
          .map { zfwHtmlText(it.groupValues[1]) }
          .toList()
  val body =
      Regex("(?is)<tbody\\b[^>]*>(.*?)</tbody>").find(table)?.groupValues?.get(1)
          ?: throw FinancePortalException("校园网首页暂未返回流量数据。")
  val firstRow =
      Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").find(body)?.groupValues?.get(1)
          ?: throw FinancePortalException("校园网首页暂未返回流量数据。")
  val cells =
      Regex("(?is)<td\\b[^>]*>(.*?)</td>")
          .findAll(firstRow)
          .map { zfwHtmlText(it.groupValues[1]) }
          .toList()

  fun valueFor(label: String): String {
    val index = labels.indexOf(label)
    if (index < 0) throw FinancePortalException("校园网首页缺少流量项目：$label。")
    return cells.getOrNull(index)?.takeIf(String::isNotBlank)
        ?: throw FinancePortalException("校园网首页暂未返回流量项目：$label。")
  }

  return CampusNetworkTraffic(
      used = valueFor(ZFW_HOME_TRAFFIC_LABEL_USED),
      freeRemaining = valueFor(ZFW_HOME_TRAFFIC_LABEL_FREE),
      chargedRemaining = valueFor(ZFW_HOME_TRAFFIC_LABEL_CHARGED),
  )
}

private fun zfwHtmlText(html: String): String =
    html
        .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace(Regex("(?i)&nbsp;|&#160;|&#xA0;"), " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun JsonElement?.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
