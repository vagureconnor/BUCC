@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package app.bucc.ui.screens.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.ArkUIView
import androidx.compose.ui.napi.asBoolean
import androidx.compose.ui.napi.asString
import androidx.compose.ui.napi.js
import app.bucc.api.finance.*
import app.bucc.platform.HarmonyHost
import app.bucc.platform.status
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

private class HarmonyPaymentRules(val page: OfficialPaymentPage) {
  val gate = PaymentRequestGate(page.isFormSubmission)
  val cookieOrigins = mutableSetOf<String>()
}

private var nextPaymentId = 1
private val paymentRules = mutableMapOf<Int, HarmonyPaymentRules>()

@kotlin.native.CName("bucc_payment_allow_url")
fun allowHarmonyPaymentUrl(id: Int, raw: CPointer<ByteVar>): Boolean =
    paymentRules[id]?.let { it.gate.allows(raw.toKString(), "GET", false) } ?: false

@kotlin.native.CName("bucc_payment_allow_request")
fun allowHarmonyPaymentRequest(
    id: Int,
    raw: CPointer<ByteVar>,
    method: CPointer<ByteVar>,
    isRoot: Boolean,
): Boolean =
    paymentRules[id]?.let { rules ->
      val url = raw.toKString()
      if (url == "about:blank") method.toKString().equals("GET", true)
      else rules.gate.allows(url, method.toKString(), isRoot)
    } ?: false

@kotlin.native.CName("bucc_payment_allow_back")
fun allowHarmonyPaymentBack(id: Int, raw: CPointer<ByteVar>): Boolean =
    paymentRules[id]?.let { canReturnToPaymentHistory(raw.toKString(), it.page.isFormSubmission) }
        ?: false

@kotlin.native.CName("bucc_payment_allow_cookie")
fun allowHarmonyPaymentCookie(id: Int, raw: CPointer<ByteVar>): Boolean =
    paymentRules[id]?.cookieOrigins?.contains(raw.toKString()) == true

actual suspend fun initializePaymentBrowser(): Boolean =
    HarmonyHost.request("initializePaymentBrowser").status == "success"

@Composable
actual fun OfficialPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
    onExternalPaymentAppReturn: (() -> Unit)?,
) {
  val id = remember(page) { nextPaymentId++ }
  val scope = rememberCoroutineScope()
  val callback by rememberUpdatedState(onBackHandlerChanged)
  val started = remember(page) { TimeSource.Monotonic.markNow() }
  val qrAttempt = remember(page) { PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_PAY_CARD) }
  var ready by remember(page) { mutableStateOf(false) }
  var failed by remember(page) { mutableStateOf(false) }
  var message by remember(page) { mutableStateOf<String?>(null) }
  var currentUrl by remember(page) { mutableStateOf<String?>(null) }
  var scanRevision by remember(page) { mutableStateOf(0) }
  var connectAttempted by remember(page) { mutableStateOf(false) }
  var recoveredTarget by remember(page) { mutableStateOf<String?>(null) }
  var externalPaymentTarget by remember(page) { mutableStateOf<String?>(null) }

  fun goBack(): Boolean =
      HarmonyHost.bridge.call("paymentBack", js { "id"(id) }).asBoolean() == true
  fun connect(target: String) {
    if (connectAttempted) return
    connectAttempted = true
    recoveredTarget = target
    val source = currentUrl
    scope.launch {
      try {
        if (
            qrAttempt.decide(started.elapsedNow().inWholeMilliseconds, emptyList()) ==
                PaymentQrRecoveryDecision.Expired
        ) {
          message = "二维码已过期，请返回并手动刷新充值准备。"
          return@launch
        }
        val connectedUrl =
            if (app.bucc.api.finance.isOfficialPaymentUrl(target)) {
              resumeOfficialPaymentPage(target).url
            } else {
              check(app.bucc.api.finance.isOfficialProviderPaymentUrl(target))
              target
            }
        val cookies =
            if (app.bucc.api.finance.isOfficialPaymentUrl(connectedUrl)) {
              harmonyPaymentCookies(id, connectedUrl, false)
            } else JsonArray(emptyList())
        if (currentUrl != source) return@launch
        if (
            qrAttempt.decide(started.elapsedNow().inWholeMilliseconds, emptyList()) ==
                PaymentQrRecoveryDecision.Expired
        ) {
          message = "二维码已过期，请返回并手动刷新充值准备。"
          return@launch
        }
        check(
            HarmonyHost.request(
                    "navigatePayment",
                    buildJsonObject {
                      put("id", id)
                      put("url", connectedUrl)
                      put("cookies", cookies)
                    },
                )
                .status == "success"
        )
        message = null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        message = "官方支付页面暂时无法打开，可重试打开或返回。"
      }
    }
  }

  DisposableEffect(id) {
    paymentRules[id] = HarmonyPaymentRules(page)
    callback { goBack() }
    onDispose {
      callback(null)
      paymentRules.remove(id)
      HarmonyHost.bridge.call("closePayment", js { "id"(id) })
    }
  }

  LaunchedEffect(page) {
    try {
      check(isAllowedPaymentNavigation(page.url, page.isFormSubmission))
      check(initializePaymentBrowser())
      val cookies = harmonyPaymentCookies(id, page.url, page.isFormSubmission)
      val body = page.takeFormPost()
      check(!page.isFormSubmission || body != null)
      val encoded =
          try {
            body?.let(Base64::encode)
          } finally {
            body?.fill(0)
          }
      check(
          HarmonyHost.request(
                  "openPayment",
                  buildJsonObject {
                    put("id", id)
                    put("url", page.url)
                    put("cookies", cookies)
                    encoded?.let { put("formPost", it) }
                  },
              )
              .status == "success"
      )
      ready = true
      while (true) {
        val event = HarmonyHost.request("nextPaymentEvent", buildJsonObject { put("id", id) })
        if (event.status == "cancelled") break
        val url = event["url"].asString().orEmpty()
        when (event["kind"].asString()) {
          "loading",
          "loaded" -> {
            currentUrl = url
            if (
                event["kind"].asString() == "loaded" &&
                    app.bucc.api.finance.isOfficialPaymentContentUrl(url)
            ) {
              HarmonyHost.request("revealPayment", buildJsonObject { put("id", id) })
            }
          }
          "blocked" -> {
            val paymentAppTarget = paymentAppLaunchTarget(url)
            if (paymentAppTarget != null) {
              if (externalPaymentTarget != paymentAppTarget) {
                externalPaymentTarget = paymentAppTarget
                val launched =
                    runCatching {
                          HarmonyHost.request(
                                  "openPaymentApp",
                                  buildJsonObject { put("url", paymentAppTarget) },
                              )
                              .status == "success"
                        }
                        .getOrDefault(false)
                if (!launched) {
                  externalPaymentTarget = null
                  message = "无法打开对应支付应用，请确认已安装后重试。"
                }
              }
            } else {
              val target = if (page.isFormSubmission) officialPaymentSessionTarget(url) else null
              if (target != null) connect(target) else message = "该链接暂不支持，可返回上一页。"
            }
          }
          "error" -> message = "支付页面加载失败，可返回上一页；请勿重复建单。"
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      failed = true
    }
  }

  LaunchedEffect(currentUrl, scanRevision, ready) {
    val source = currentUrl ?: return@LaunchedEffect
    if (
        !ready ||
            !page.isFormSubmission ||
            connectAttempted ||
            paymentQrSourceForCurrentUrl(source) == null
    )
        return@LaunchedEffect
    val script = paymentQrDecodeScript()
    suspend fun inspect(code: String, timeout: Long): String? =
        withTimeoutOrNull(timeout) {
          val response =
              HarmonyHost.request(
                  "inspectPayment",
                  buildJsonObject {
                    put("id", id)
                    put("sourceUrl", source)
                    put("script", code)
                  },
              )
          response["value"].asString().takeIf { response.status == "success" }
        }
    var crossOriginQr = false
    repeat(8) { scan ->
      if (scan > 0) delay(500)
      if (currentUrl != source) return@LaunchedEffect
      val remaining = paymentQrRemainingMillis(inspect(PAYMENT_QR_VALIDITY_SCRIPT, 1000))
      if (
          qrAttempt.decide(started.elapsedNow().inWholeMilliseconds, emptyList(), remaining) ==
              PaymentQrRecoveryDecision.Expired
      ) {
        message = "二维码已过期，请返回并手动刷新充值准备。"
        return@LaunchedEffect
      }
      val links =
          inspect(PAYMENT_RESPONSE_CANDIDATES_SCRIPT, 3000)
              ?.let(::paymentResponseCandidates)
              .orEmpty()
      var payloads =
          app.bucc.api.finance.officialPaymentTargetsFromBrowserCandidates(
              source,
              listOf(source) + links,
          )
      if (currentUrl != source) return@LaunchedEffect
      if (payloads.isEmpty()) {
        val values = inspect(script, 3000)?.let(::paymentResponseCandidates).orEmpty()
        crossOriginQr = crossOriginQr || values.contains("__BUCC_QR_CROSS_ORIGIN__")
        payloads = values.filterNot { it.startsWith("__BUCC_QR_") }
      }
      if (currentUrl != source) return@LaunchedEffect
      if (payloads.isEmpty() && scan < 7) return@repeat
      when (
          val decision =
              qrAttempt.decide(started.elapsedNow().inWholeMilliseconds, payloads, remaining)
      ) {
        is PaymentQrRecoveryDecision.OpenOfficialPayment -> connect(decision.url)
        PaymentQrRecoveryDecision.Expired -> message = "二维码已过期，请返回并手动刷新充值准备。"
        PaymentQrRecoveryDecision.Unsupported -> message = "二维码不是受支持的官方支付入口，可返回或重新识别。"
        PaymentQrRecoveryDecision.Ambiguous -> message = "页面存在多个二维码入口，无法安全选择，可返回或重新识别。"
        PaymentQrRecoveryDecision.Invalid ->
            message =
                if (crossOriginQr) {
                  "支付二维码来自跨域图片，当前设备无法安全读取像素内容，可返回或重新识别。"
                } else "未能识别有效二维码，可返回或重新识别。"
        PaymentQrRecoveryDecision.AlreadyNavigated -> Unit
      }
      return@LaunchedEffect
    }
  }

  Column(Modifier.fillMaxSize()) {
    message?.let {
      Text(it, color = MaterialTheme.colorScheme.error)
      TextButton(onClick = { if (goBack()) message = null }) { Text("返回上一页") }
      if (started.elapsedNow().inWholeMilliseconds < PaymentQrRecoveryAttempt.MAX_AGE_MILLIS) {
        TextButton(
            onClick = {
              message = null
              recoveredTarget?.let { target ->
                connectAttempted = false
                connect(target)
              } ?: run { scanRevision++ }
            }
        ) {
          Text(if (recoveredTarget == null) "重新识别" else "重试打开")
        }
      }
    }
    when {
      failed -> Text("支付页面暂不可用，请返回并手动刷新充值准备，请勿重复建单。", color = MaterialTheme.colorScheme.error)
      !ready -> CircularProgressIndicator()
      else ->
          ArkUIView(
              "BUCCPayment",
              modifier = Modifier.fillMaxSize(),
              parameter = remember(id) { js { "id"(id) } },
          )
    }
  }
}

/** Host-only copies limit each transferred cookie to the official origins that need it. */
private suspend fun harmonyPaymentCookies(
    id: Int,
    pageUrl: String,
    includeZfw: Boolean,
): JsonArray {
  val known =
      listOf(
              pageUrl,
              "https://mall.cc-pay.cn/",
              "https://cashier.cc-pay.cn/",
              "https://pass.cc-pay.cn/",
          )
          .map { Url(it).let { url -> "https://${url.host}/" } }
          .distinct()
  return JsonArray(
      paymentSessionCookies(pageUrl, includeZfw).flatMap { cookie ->
        require(cookie.name.matches(Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")))
        require(cookie.value.none { it <= ' ' || it == ';' || it == '\u007f' })
        val primary = cookie.originUrl ?: Url(pageUrl).let { "https://${it.host}/" }
        val domain = cookie.domain?.trimStart('.')?.lowercase()
        val origins =
            (listOf(primary) +
                    known.filter { origin ->
                      val host = Url(origin).host
                      domain != null && (host == domain || host.endsWith(".$domain"))
                    })
                .distinct()
        val path = cookie.path ?: "/"
        require(path.startsWith('/') && path.none { it <= ' ' || it == ';' || it == '\u007f' })
        val header =
            "${cookie.name}=${cookie.value}; Path=$path" +
                (if (cookie.secure) "; Secure" else "") +
                (if (cookie.httpOnly) "; HttpOnly" else "")
        origins.map { origin ->
          paymentRules[id]?.cookieOrigins?.add(origin)
          buildJsonObject {
            put("origin", origin)
            put("header", header)
          }
        }
      }
  )
}
