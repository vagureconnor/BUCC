package app.bucc.api.finance

import io.ktor.http.formUrlEncode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RechargeKind {
  Internet,
  Card,
}

enum class RechargeFlowStage {
  REJECTED,
  CREATE_ORDER,
  RESOLVE_PAYMENT_PAGE,
  CONNECT_PAYMENT_SESSION,
}

class RechargeFlowException(
    val stage: RechargeFlowStage,
    cause: Throwable? = null,
    val paymentPage: OfficialPaymentPage? = null,
) : Exception(null, cause)

object RechargeFlowFailureReason {
  const val REJECTED = "官方未接受本次充值请求，请检查充值条件后重新准备。"
  const val CREATE_ORDER = "建单结果暂不明确，请先核实官方订单或余额，确认后再决定是否重新提交。"
  const val RESOLVE_PAYMENT_PAGE = "充值已提交，但官方支付入口暂未识别，请先核实官方订单，避免重复充值。"
  const val CONNECT_PAYMENT_SESSION = "充值已提交，支付会话暂未接续，请先核实官方订单，避免重复充值。"
}

data class RechargePreparation(
    val available: Boolean,
    val reason: String? = null,
    val captchaImage: ByteArray? = null,
) {
  override fun toString(): String =
      "RechargePreparation(available=$available, captchaImage=[redacted])"
}

class OfficialPaymentPage
private constructor(
    val url: String,
    private var formPost: ByteArray?,
    val isFormSubmission: Boolean,
) {
  constructor(url: String) : this(url, null, false)

  private val submissionMutex = Mutex()

  /** Consumed before handing off to the browser; remounts and errors cannot replay it. */
  suspend fun takeFormPost(): ByteArray? =
      submissionMutex.withLock { formPost.also { formPost = null } }

  companion object {
    fun zfwFormSubmission(fields: Map<String, String>): OfficialPaymentPage {
      val required =
          setOf(
              "OneCardForm[cardNo]",
              "OneCardForm[productId]",
              "OneCardForm[amount]",
              "OneCardForm[verifyCode]",
          )
      val csrf = fields.keys.singleOrNull { Regex("^_csrf-[A-Za-z0-9_-]+$").matches(it) }
      require(
          csrf != null && fields.keys == required + csrf && fields.values.all(String::isNotBlank)
      ) {
        "充值表单不完整，未提交"
      }
      val body =
          io.ktor.http.Parameters.build { fields.forEach { (name, value) -> append(name, value) } }
              .formUrlEncode()
      return OfficialPaymentPage(
          financeDirectUrl("https://zfw.buaa.edu.cn/pay/card"),
          body.encodeToByteArray(),
          true,
      )
    }
  }

  override fun toString(): String = "OfficialPaymentPage([redacted])"
}

class PaymentSessionCookie(
    val name: String,
    val value: String,
    val domain: String?,
    val path: String?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val originUrl: String? = null,
) {
  override fun toString(): String = "PaymentSessionCookie([redacted])"
}

/** Transfers only BUCC's own scoped cookies, never another browser's cookie storage. */
suspend fun paymentSessionCookies(
    pageUrl: String,
    includeZfw: Boolean = false,
): List<PaymentSessionCookie> {
  require(
      isOfficialPaymentUrl(pageUrl) || (includeZfw && isOfficialInternetSubmissionUrl(pageUrl))
  ) {
    "支付页面地址无法验证"
  }
  val pageOrigin = io.ktor.http.Url(pageUrl).let { "https://${it.host}/" }
  val sources =
      listOf(
          pageUrl to pageOrigin,
          "https://mall.cc-pay.cn/" to "https://mall.cc-pay.cn/",
          "https://cashier.cc-pay.cn/" to "https://cashier.cc-pay.cn/",
          "https://pass.cc-pay.cn/" to "https://pass.cc-pay.cn/",
      ) +
          listOf(
              "https://sso.buaa.edu.cn/login" to "https://sso.buaa.edu.cn/",
              app.bucc.api.local.localUpstreamUrl("https://sso.buaa.edu.cn/login") to
                  app.bucc.api.local.localUpstreamUrl("https://sso.buaa.edu.cn/login").let {
                    "https://${io.ktor.http.Url(it).host}/"
                  },
          )
  val storage = app.bucc.api.local.LocalCookieStore.storage()
  val directStorage =
      app.bucc.api.local.LocalCookieStore.storage(app.bucc.api.ConnectionMode.DIRECT)
  // The ZFW form is a direct session; do not let a stale current-mode ZFW cookie shadow it.
  val primary = if (includeZfw) directStorage else storage
  val secondary = if (includeZfw) storage else directStorage
  val unique = linkedMapOf<Triple<String, String?, String?>, PaymentSessionCookie>()
  sources.forEach { (requestUrl, originUrl) ->
    (primary.get(io.ktor.http.Url(requestUrl)) + secondary.get(io.ktor.http.Url(requestUrl)))
        .forEach { cookie ->
          val key = Triple(cookie.name, cookie.domain?.lowercase(), cookie.path)
          if (key !in unique) {
            unique[key] =
                PaymentSessionCookie(
                    cookie.name,
                    cookie.value,
                    cookie.domain,
                    cookie.path,
                    cookie.secure,
                    cookie.httpOnly,
                    originUrl = originUrl,
                )
          }
        }
  }
  return unique.values.toList()
}

interface CampusRechargeBackend {
  suspend fun prepare(kind: RechargeKind): RechargePreparation

  suspend fun createOrder(
      kind: RechargeKind,
      amount: String,
      verificationCode: String? = null,
  ): OfficialPaymentPage
}

sealed interface CampusRechargeState {
  data object Loading : CampusRechargeState

  data class Unavailable(val reason: String) : CampusRechargeState

  data class Ready(val kind: RechargeKind, val captchaImage: ByteArray? = null) :
      CampusRechargeState {
    override fun toString(): String = "Ready(kind=$kind, captchaImage=[redacted])"
  }

  data class Creating(val kind: RechargeKind, val amount: String) : CampusRechargeState {
    override fun toString(): String = "Creating(kind=$kind, amount=[redacted])"
  }

  data class OfficialPage(val page: app.bucc.api.finance.OfficialPaymentPage) : CampusRechargeState

  data class Failed(val reason: String, val continuation: OfficialPaymentPage? = null) :
      CampusRechargeState {
    override fun toString(): String =
        "Failed(reason=$reason, continuation=${if (continuation == null) "null" else "[redacted]"})"
  }
}

/** Official order creation is unavailable until its contract and session flow are confirmed. */
object UnavailableCampusRechargeBackend : CampusRechargeBackend {
  private const val REASON = "官方充值订单接口尚未确认，当前无法充值。"

  override suspend fun prepare(kind: RechargeKind) =
      RechargePreparation(available = false, reason = REASON)

  override suspend fun createOrder(
      kind: RechargeKind,
      amount: String,
      verificationCode: String?,
  ): OfficialPaymentPage = error(REASON)
}

class CampusRechargeController(
    private val backend: CampusRechargeBackend = UnavailableCampusRechargeBackend,
    private val resumePaymentPage: suspend (String) -> OfficialPaymentPage =
        ::resumeOfficialPaymentPage,
) {
  private val mutex = Mutex()
  private var preparedKind: RechargeKind? = null
  private var creationAttempted = false

  var state: CampusRechargeState = CampusRechargeState.Loading
    private set

  val canSubmit: Boolean
    get() = preparedKind != null && !creationAttempted

  val canPrepare: Boolean
    get() = state !is CampusRechargeState.Creating

  suspend fun prepare(kind: RechargeKind) {
    mutex.withLock {
      if (state is CampusRechargeState.Creating) return
      creationAttempted = false
      preparedKind = null
      state = CampusRechargeState.Loading
      try {
        val result = backend.prepare(kind)
        if (result.available) {
          preparedKind = kind
          state = CampusRechargeState.Ready(kind, result.captchaImage)
        } else {
          state =
              CampusRechargeState.Unavailable(
                  result.reason?.takeIf(String::isNotBlank) ?: "当前暂不可充值。"
              )
        }
      } catch (e: CancellationException) {
        state = CampusRechargeState.Failed("暂时无法检查充值服务状态。")
        throw e
      } catch (_: Exception) {
        state = CampusRechargeState.Failed("暂时无法检查充值服务状态。")
      }
    }
  }

  suspend fun create(amount: String, verificationCode: String? = null) {
    mutex.withLock {
      if (creationAttempted) return
      val kind = preparedKind ?: return
      if ((amount.trim().substringAfter('.', "").length > 2)) {
        state = CampusRechargeState.Failed("充值金额最多支持两位小数。")
        return
      }
      if (
          (state as? CampusRechargeState.Ready)?.captchaImage != null &&
              verificationCode.isNullOrBlank()
      ) {
        state = CampusRechargeState.Failed("请填写充值验证码。")
        return
      }
      val formattedAmount = formatFinanceAmount(amount)
      if (formattedAmount == null || formattedAmount == "0.00" || formattedAmount.startsWith('-')) {
        state = CampusRechargeState.Failed("请输入有效的正数金额，最多按分计。")
        return
      }
      if (kind == RechargeKind.Card && campusCardAmountInFen(formattedAmount) == null) {
        state = CampusRechargeState.Failed("校园卡单次充值金额须大于零且不超过 1000 元。")
        return
      }

      // Set before the request because cancellation or a timeout may leave order creation unknown.
      creationAttempted = true
      state = CampusRechargeState.Creating(kind, formattedAmount)
      try {
        val page = backend.createOrder(kind, formattedAmount, verificationCode)
        if (
            if (page.isFormSubmission)
                kind != RechargeKind.Internet || !isOfficialInternetSubmissionUrl(page.url)
            else !isOfficialPaymentUrl(page.url) || isPaymentLoginUrl(page.url)
        ) {
          state = CampusRechargeState.Failed("官方支付页面地址无法验证，请勿继续付款。")
        } else {
          state = CampusRechargeState.OfficialPage(page)
        }
      } catch (e: RechargeFlowException) {
        val continuation =
            if (e.stage == RechargeFlowStage.CONNECT_PAYMENT_SESSION) {
              e.paymentPage?.takeIf(::isPaymentContinuation)
            } else null
        state =
            CampusRechargeState.Failed(
                when (e.stage) {
                  RechargeFlowStage.REJECTED -> RechargeFlowFailureReason.REJECTED
                  RechargeFlowStage.CREATE_ORDER -> RechargeFlowFailureReason.CREATE_ORDER
                  RechargeFlowStage.RESOLVE_PAYMENT_PAGE ->
                      RechargeFlowFailureReason.RESOLVE_PAYMENT_PAGE
                  RechargeFlowStage.CONNECT_PAYMENT_SESSION ->
                      RechargeFlowFailureReason.CONNECT_PAYMENT_SESSION
                },
                continuation,
            )
      } catch (e: CancellationException) {
        state = CampusRechargeState.Failed(RechargeFlowFailureReason.CREATE_ORDER)
        throw e
      } catch (_: Exception) {
        state = CampusRechargeState.Failed(RechargeFlowFailureReason.CREATE_ORDER)
      }
    }
  }

  suspend fun resumePayment() {
    mutex.withLock {
      val failed = state as? CampusRechargeState.Failed ?: return
      val continuation = failed.continuation?.takeIf(::isPaymentContinuation) ?: return
      try {
        val page = resumePaymentPage(continuation.url)
        if (isPaymentContinuation(page)) {
          state = CampusRechargeState.OfficialPage(page)
        } else {
          state = failed.copy(reason = PAYMENT_CONTINUATION_FAILED)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        state = failed.copy(reason = PAYMENT_CONTINUATION_FAILED)
      }
    }
  }
}

private const val PAYMENT_CONTINUATION_FAILED = "支付页面暂未接续，可重试接续或返回。"

private fun isPaymentContinuation(page: OfficialPaymentPage): Boolean =
    !page.isFormSubmission && isOfficialPaymentUrl(page.url) && !isPaymentLoginUrl(page.url)

/** Accepts confirmed official cashier and campus-card product entry paths. */
fun isOfficialPaymentUrl(url: String): Boolean {
  val match =
      Regex(
              "(?i)^https://(cashier\\.cc-pay\\.cn|mall\\.cc-pay\\.cn)(/[^?#]*)?(?:\\?[^#]*)?(?:#.*)?$"
          )
          .matchEntire(url.trim()) ?: return false
  val path = match.groupValues[2].ifEmpty { "/" }
  return path == "/cashier" ||
      path == "/cashier/" ||
      (match.groupValues[1].equals("mall.cc-pay.cn", true) &&
          Regex("^/entry/card/[A-Za-z0-9_-]{1,128}$").matches(path))
}

/** Identifies common login routes so an embedded payment flow cannot present another sign-in. */
fun isPaymentLoginUrl(url: String): Boolean {
  if (
      runCatching { io.ktor.http.Url(url).host.equals("pass.cc-pay.cn", ignoreCase = true) }
          .getOrDefault(false)
  )
      return true
  val match =
      Regex("(?i)^https?://[^/?#]+(/[^?#]*)?(?:\\?[^#]*)?(?:#.*)?$").matchEntire(url.trim())
          ?: return false
  val loginSegments =
      setOf("login", "signin", "sign-in", "sso", "auth", "authorize", "oauth", "cas")
  return match.groupValues[1].split('/').filter(String::isNotEmpty).any { segment ->
    val normalized = segment.lowercase()
    normalized in loginSegments || normalized.startsWith("login.")
  }
}

/** Read-only payment-session continuation; never submits the ZFW order form again. */
suspend fun resumeOfficialPaymentPage(target: String): OfficialPaymentPage {
  require(isOfficialPaymentUrl(target)) { "支付页面地址无法验证" }
  check(app.bucc.api.local.LocalAuthSessionStore.get() != null) { "支付会话未接续" }
  val client = app.bucc.api.local.LocalUpstreamClientProvider.newFinanceClient()
  return try {
    connectOfficialPaymentPage(client, target)
  } finally {
    client.close()
  }
}

/** Unwrap only the selected official gateway; callers still validate the original payment route. */
fun paymentNavigationOriginalUrl(raw: String): String? {
  val parsed = runCatching { io.ktor.http.Url(raw) }.getOrNull() ?: return null
  if (parsed.host != "d.buaa.edu.cn") return raw
  if (
      !app.bucc.api.local.localBusinessUsesWebVpnGateway() ||
          parsed.protocol != io.ktor.http.URLProtocol.HTTPS ||
          parsed.port != 443 ||
          !parsed.user.isNullOrEmpty() ||
          !parsed.password.isNullOrEmpty()
  )
      return null
  val decoded = app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(raw)
  if (decoded == raw || app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl(decoded) != raw)
      return null
  return decoded
}

fun isOfficialInternetSubmissionUrl(url: String): Boolean {
  val direct = "https://zfw.buaa.edu.cn/pay/card"
  // ZFW now uses financeDirectUrl for the form target; WebVPN-mode validation
  // must accept that direct URL as well as the legacy gateway-wrapped one.
  return url == direct || url == app.bucc.api.local.localUpstreamUrl(direct)
}

/** Match the actual session transport, including the WebVPN gateway route. */
fun isOfficialInternetPaymentSourceUrl(raw: String): Boolean {
  val original = paymentNavigationOriginalUrl(raw) ?: return false
  val parsed = runCatching { io.ktor.http.Url(original) }.getOrNull() ?: return false
  return parsed.protocol == io.ktor.http.URLProtocol.HTTPS &&
      parsed.host == "zfw.buaa.edu.cn" &&
      parsed.port == 443 &&
      parsed.user.isNullOrEmpty() &&
      parsed.password.isNullOrEmpty() &&
      parsed.encodedPath in setOf("/pay/card", "/qrcode") &&
      parsed.fragment.isEmpty() &&
      (raw == original || raw == app.bucc.api.local.localUpstreamUrl(original))
}
