package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.diagnostics.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ZfwRechargeForm(
    val fields: Map<String, String>,
    val captchaUrl: String,
    val captchaRefreshUrl: String? = null,
) {
  override fun toString() = "ZfwRechargeForm([redacted])"
}

internal fun formText(value: String): String =
    Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
        .replace(value) { match ->
          val raw = match.groupValues[1]
          val number = if (raw.startsWith('x')) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
          number?.takeIf { it in 1..65535 && it !in 0xD800..0xDFFF }?.toChar()?.toString()
              ?: match.value
        }
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

internal fun formAttributes(tag: String): Map<String, String> =
    Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""").findAll(tag).associate {
      it.groupValues[1].lowercase() to
          formText(it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty())
    }

internal fun parseZfwRechargeForm(html: String): ZfwRechargeForm? {
  val form =
      Regex("(?is)<form\\b([^>]*)>(.*?)</form>").findAll(html).firstOrNull {
        val attrs = formAttributes(it.groupValues[1])
        attrs["method"].equals("post", true) &&
            normalizeZfwUrl(attrs["action"].orEmpty(), setOf("/pay/card")) != null
      } ?: return null
  val formBody = form.groupValues[2]
  val fields = mutableMapOf<String, String>()
  Regex("(?is)<input\\b[^>]*>").findAll(formBody).forEach {
    val attrs = formAttributes(it.value)
    attrs["name"]?.let { name -> fields[name] = attrs["value"].orEmpty() }
  }
  Regex("(?is)<select\\b([^>]*)>(.*?)</select>").findAll(formBody).forEach { select ->
    val name = formAttributes(select.groupValues[1])["name"] ?: return@forEach
    val options = Regex("(?is)<option\\b([^>]*)>").findAll(select.groupValues[2]).toList()
    val chosen =
        options.singleOrNull {
          Regex("(?i)\\bselected(?:\\s|=|$)").containsMatchIn(it.groupValues[1])
        }
            ?: options
                .filter { !formAttributes(it.groupValues[1])["value"].isNullOrBlank() }
                .singleOrNull()
    fields[name] = chosen?.let { formAttributes(it.groupValues[1])["value"] }.orEmpty()
  }
  if (
      fields.keys.count { it.startsWith("_csrf-") } != 1 ||
          fields.filterKeys { it.startsWith("_csrf-") }.values.single().isBlank() ||
          fields["OneCardForm[cardNo]"].isNullOrBlank() ||
          fields["OneCardForm[productId]"].isNullOrBlank() ||
          "OneCardForm[amount]" !in fields ||
          "OneCardForm[verifyCode]" !in fields
  )
      return null
  val captcha =
      Regex("(?is)<img\\b[^>]*>")
          .findAll(formBody)
          .mapNotNull { formAttributes(it.value)["src"] }
          .firstNotNullOfOrNull { normalizeZfwUrl(it, setOf("/pay/captcha")) } ?: return null
  val refresh =
      Regex("\"refreshUrl\"\\s*:\\s*\"([^\"]+)\"")
          .findAll(html)
          .mapNotNull {
            runCatching {
                  kotlinx.serialization.json.Json.decodeFromString<String>(
                      "\"${it.groupValues[1]}\""
                  )
                }
                .getOrNull()
          }
          .mapNotNull { normalizeZfwUrl(it, setOf("/pay/captcha")) }
          .firstOrNull {
            val url = Url(it)
            url.parameters.getAll("refresh")?.singleOrNull() == "1"
          }
  return ZfwRechargeForm(
      fields.filterKeys {
        it.startsWith("_csrf-") || it in setOf("OneCardForm[cardNo]", "OneCardForm[productId]")
      },
      captcha,
      refresh,
  )
}

internal fun officialCashierFromResponse(
    html: String,
    location: String? = null,
    supportedUrl: (String) -> Boolean = ::isOfficialPaymentUrl,
): String? {
  fun resolve(raw: String): String? {
    var candidate = formText(raw)
    repeat(4) {
      if (supportedUrl(candidate)) return candidate
      paymentNavigationOriginalUrl(candidate)
          ?.takeIf { it != candidate && supportedUrl(it) }
          ?.let {
            return it
          }
      val absolute =
          when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith('/') -> "https://zfw.buaa.edu.cn$candidate"
            else -> candidate
          }
      val url = runCatching { Url(absolute) }.getOrNull() ?: return null
      if (
          url.protocol == URLProtocol.HTTPS &&
              url.host == "zfw.buaa.edu.cn" &&
              url.port == 443 &&
              url.user.isNullOrEmpty() &&
              url.password.isNullOrEmpty() &&
              url.encodedPath in setOf("/qrcode", "/pay/qrcode")
      ) {
        candidate = url.parameters.getAll("url")?.singleOrNull() ?: return null
      } else if (absolute != candidate && supportedUrl(absolute)) {
        return absolute
      } else if (Regex("(?i)^https?%(?:25)*3a").containsMatchIn(candidate)) {
        candidate = runCatching { candidate.decodeURLPart() }.getOrNull() ?: return null
      } else return null
    }
    return null
  }
  val candidates =
      buildList {
            location?.let(::add)
            Regex("(?is)<(?:a|img|iframe)\\b[^>]*>").findAll(html).forEach {
              val attrs = formAttributes(it.value)
              addAll(listOfNotNull(attrs["href"], attrs["src"]))
            }
          }
          .mapNotNull(::resolve)
          .distinct()
  // Ambiguous destinations are not an invitation to choose an order.
  return candidates.singleOrNull()
}

/** Reads a real official form using BUCC's own session; it never opens another login. */
class OfficialCampusRechargeBackend
internal constructor(
    private val clientFactory: (Boolean) -> HttpClient,
    private val hasAppSession: () -> Boolean,
    private val ensureSession: suspend () -> Boolean = { true },
    private val cardClientFactory: () -> HttpClient = { clientFactory(false) },
    private val zfwClientFactory: () -> HttpClient = { clientFactory(false) },
    private val refreshCasOnPrepare: Boolean = false,
    private val directCardOnPrepare: Boolean = false,
) : CampusRechargeBackend {
  constructor() :
      this(
          { follow -> LocalUpstreamClientProvider.newClient(LocalCookieStore.storage(), follow) },
          { LocalAuthSessionStore.get() != null },
          { CampusFinanceSession.current().ensurePaymentSession() is ZfwSessionState.Ready },
          { LocalUpstreamClientProvider.newCampusCardClient() },
          {
            LocalUpstreamClientProvider.newFinanceClient(
                LocalCookieStore.storage(ConnectionMode.DIRECT)
            )
          },
          refreshCasOnPrepare = true,
          directCardOnPrepare = true,
      )

  private val mutex = Mutex()
  private var preparedForm: ZfwRechargeForm? = null
  private var preparedClient: HttpClient? = null
  private var attempted = false
  private val cardBackend by lazy {
    OfficialCampusCardRechargeBackend(
        clientFactory,
        hasAppSession,
        cardClientFactory,
        financeClientFactory = {
          LocalUpstreamClientProvider.newCardClient(LocalCookieStore.storage(ConnectionMode.DIRECT))
        },
        refreshCasOnPrepare = refreshCasOnPrepare,
        directCardOnPrepare = directCardOnPrepare,
    )
  }

  override suspend fun prepare(kind: RechargeKind): RechargePreparation =
      withLocalDiagnostics(
          DiagnosticModule.RECHARGE,
          DiagnosticOperation.PREPARE_RECHARGE,
          outcome = { if (it.available) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.BLOCKED },
      ) {
        mutex.withLock {
          attempted = false
          close()
          if (kind == RechargeKind.Card) return@withLock cardBackend.prepare(kind)
          if (!hasAppSession()) return@withLock RechargePreparation(false, "请先完成 BUCC 主账号登录。")
          if (!ensureSession()) return@withLock RechargePreparation(false, "请完成校园网验证码验证后继续。")
          // ZFW rejects the WebVPN gateway now; the official page is read directly.
          val client = zfwClientFactory()
          try {
            val response = readZfwPage(client, "https://zfw.buaa.edu.cn/pay/card")
            if (!response.status.isSuccess())
                return@withLock RechargePreparation(false, "BUCC 网费支付会话暂不可用，未创建订单。")
            val html = response.bodyAsText()
            if (html.contains("不在缴费时间"))
                return@withLock RechargePreparation(false, "当前不在官方缴费时间，请在开放时段重试。未创建订单。")
            val form =
                parseZfwRechargeForm(html)
                    ?: return@withLock RechargePreparation(false, "网费支付会话或充值信息无法自动接续，未创建订单。")
            // Do not refresh the captcha here: ZFW rotates the CSRF token with it and the
            // captured form fields would no longer match the cookie on POST.
            val captcha = readZfwPage(client, form.captchaUrl)
            if (
                !captcha.status.isSuccess() ||
                    !captcha.contentType().toString().startsWith("image/")
            ) {
              return@withLock RechargePreparation(false, "暂时无法读取充值验证码。")
            }
            val image = captcha.bodyAsBytes()
            if (image.isEmpty()) return@withLock RechargePreparation(false, "暂时无法读取充值验证码。")
            preparedForm = form
            preparedClient = client
            RechargePreparation(true, captchaImage = image)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            recordDiagnostic(DiagnosticStage.PREPARE, DiagnosticOutcome.FAILURE, error = error)
            RechargePreparation(false, "网费支付会话暂不可用，未创建订单。")
          } finally {
            if (preparedClient !== client) client.close()
          }
        }
      }

  override suspend fun createOrder(
      kind: RechargeKind,
      amount: String,
      verificationCode: String?,
  ): OfficialPaymentPage =
      withLocalDiagnostics(
          DiagnosticModule.RECHARGE,
          if (kind == RechargeKind.Internet) DiagnosticOperation.PREPARE_SUBMISSION
          else DiagnosticOperation.CREATE_ORDER,
      ) {
        mutex.withLock {
          if (kind == RechargeKind.Card) {
            check(!attempted)
            attempted = true
            return@withLock cardBackend.createOrder(kind, amount, verificationCode)
          }
          require(!attempted && kind == RechargeKind.Internet) { "本次准备已使用，请重新准备后再提交" }
          val form = preparedForm ?: error("充值信息尚未准备完成")
          checkNotNull(preparedClient) { "支付会话尚未准备完成" }
          val money =
              formatFinanceAmount(amount)?.takeIf { it != "0.00" && !it.startsWith('-') }
                  ?: error("充值金额无效")
          require(!verificationCode.isNullOrBlank()) { "请填写充值验证码" }
          attempted = true
          recordDiagnostic(DiagnosticStage.PREPARE, DiagnosticOutcome.STARTED)
          try {
            val fields =
                form.fields +
                    mapOf(
                        "OneCardForm[amount]" to money,
                        "OneCardForm[verifyCode]" to verificationCode,
                    )
            // Submit in Ktor, then read the official QR carrier's cashier URL. This avoids
            // WebKit form-POST cookie handling and lets the QR be resolved silently.
            val response =
                preparedClient!!.post(financeDirectUrl("https://zfw.buaa.edu.cn/pay/card")) {
                  header(HttpHeaders.Referrer, "https://zfw.buaa.edu.cn/pay/card")
                  setBody(
                      io.ktor.client.request.forms.FormDataContent(
                          io.ktor.http.Parameters.build {
                            fields.forEach { (k, v) -> append(k, v) }
                          }
                      )
                  )
                }
            val cashier =
                officialCashierFromResponse(
                    response.bodyAsText(),
                    response.headers[HttpHeaders.Location],
                ) ?: throw RechargeFlowException(RechargeFlowStage.RESOLVE_PAYMENT_PAGE)
            OfficialPaymentPage(cashier)
          } finally {
            close()
          }
        }
      }

  fun close() {
    cardBackend.close()
    preparedClient?.close()
    preparedClient = null
    preparedForm = null
  }
}
