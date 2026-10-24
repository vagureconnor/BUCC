package app.bucc.ui.screens.finance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.bucc.api.finance.OfficialPaymentPage
import app.bucc.api.finance.PaymentSessionCookie
import app.bucc.api.finance.paymentSessionCookies
import app.bucc.api.finance.resumeOfficialPaymentPage
import app.bucc.diagnostics.*
import com.multiplatform.webview.cookie.Cookie
import com.multiplatform.webview.request.RequestInterceptor
import com.multiplatform.webview.request.WebRequest
import com.multiplatform.webview.request.WebRequestInterceptResult
import com.multiplatform.webview.util.KLogSeverity
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebContent
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewState
import com.multiplatform.webview.web.rememberWebViewNavigator
import kotlin.coroutines.resume
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

@Composable
actual fun OfficialPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
    onExternalPaymentAppReturn: (() -> Unit)?,
) {
  if (platformPaymentBrowser(page, onBackHandlerChanged)) return
  val scope = rememberCoroutineScope()
  val diagnostic =
      remember(page) {
        LocalDiagnostics.session(DiagnosticModule.RECHARGE, DiagnosticOperation.PAYMENT_BROWSER)
      }
  var loading by remember { mutableStateOf(true) }
  var ready by remember { mutableStateOf(false) }
  var unavailable by remember { mutableStateOf(false) }
  var sessionBlocked by remember { mutableStateOf(false) }
  var paymentAppLaunchPending by remember(page) { mutableStateOf(false) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val paymentAppLaunchState = rememberUpdatedState(paymentAppLaunchPending)
  val externalPaymentAppReturnState = rememberUpdatedState(onExternalPaymentAppReturn)

  val webViewState =
      remember(page) {
        WebViewState(WebContent.NavigatorOnly).apply {
          webSettings.logSeverity = KLogSeverity.Assert
        }
      }
  var paymentConnectAttempted by remember(page) { mutableStateOf(false) }
  var formResponseReceived by remember(page) { mutableStateOf(false) }
  var formHandedOff by remember(page) { mutableStateOf(false) }
  val startedAt = remember(page) { TimeSource.Monotonic.markNow() }
  val qrAttempt = remember(page) { PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_PAY_CARD) }
  var qrScanRevision by remember(page) { mutableStateOf(0) }
  var qrMessage by remember(page) { mutableStateOf<String?>(null) }
  var recoveredTarget by remember(page) { mutableStateOf<String?>(null) }
  val popupRef = remember(page) { PaymentWindowReference() }
  val navigationPolicy = remember(page) { PaymentWebNavigationPolicy(page.isFormSubmission) }

  fun connectPaymentTarget(target: String): Boolean {
    if (paymentConnectAttempted) return false
    recoveredTarget = target
    paymentConnectAttempted = true
    scope.launch {
      if (
          qrAttempt.decide(startedAt.elapsedNow().inWholeMilliseconds, emptyList()) ==
              PaymentQrRecoveryDecision.Expired
      ) {
        qrMessage = "二维码已过期，请返回并手动刷新充值准备。"
        return@launch
      }
      diagnostic.run {
        try {
          diagnostic.record(
              DiagnosticStage.CONNECT_PAYMENT_SESSION,
              DiagnosticOutcome.STARTED,
              target,
          )
          val connectedUrl =
              if (app.bucc.api.finance.isOfficialPaymentUrl(target)) {
                val connected = resumeOfficialPaymentPage(target)
                paymentSessionCookies(connected.url).forEach { cookie ->
                  webViewState.cookieManager.setCookie(
                      cookie.originUrl ?: connected.url,
                      cookie.toWebViewCookie(),
                  )
                }
                connected.url
              } else {
                check(app.bucc.api.finance.isOfficialProviderPaymentUrl(target))
                target // Browser origin rules retain provider cookies; campus cookies are never
                // copied here.
              }
          if (
              qrAttempt.decide(startedAt.elapsedNow().inWholeMilliseconds, emptyList()) ==
                  PaymentQrRecoveryDecision.Expired
          ) {
            qrMessage = "二维码已过期，请返回并手动刷新充值准备。"
          } else if (popupRef.value?.open(connectedUrl) != true) {
            sessionBlocked = true
          }
          diagnostic.record(
              DiagnosticStage.CONNECT_PAYMENT_SESSION,
              DiagnosticOutcome.SUCCESS,
              connectedUrl,
          )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          diagnostic.record(
              DiagnosticStage.CONNECT_PAYMENT_SESSION,
              DiagnosticOutcome.FAILURE,
              error = error,
          )
          sessionBlocked = true
          qrMessage = "官方支付页面暂时无法打开，可重试打开或返回。"
        }
      }
    }
    return true
  }

  fun resumePaymentSession(url: String): Boolean {
    val target = officialPaymentSessionTarget(url)?.takeIf { page.isFormSubmission } ?: return false
    return connectPaymentTarget(target)
  }

  val responseObserver =
      remember(page) {
        paymentResponseObserver { url, status ->
          scope.launch {
            diagnostic.record(
                DiagnosticStage.HTTP,
                if (status in 200..399) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILURE,
                url,
                httpStatus = status,
            )
            if (page.isFormSubmission && status in 200..299) formResponseReceived = true
          }
        }
      }
  val popup =
      remember(page) {
        paymentPopupHandler(
            allowZfw = page.isFormSubmission,
            onPaymentAppLaunchStarted = { paymentAppLaunchPending = true },
        ) { url, allowed ->
          val paymentApp =
              when (
                  paymentAppLaunchTarget(url)?.let {
                    runCatching { io.ktor.http.Url(it).protocol.name.lowercase() }.getOrNull()
                  }
              ) {
                "alipay",
                "alipays" -> "支付宝"
                "weixin",
                "wxpay",
                "weixinpay",
                "tenpay" -> "微信"
                "uppay",
                "unionpay" -> "云闪付"
                else -> null
              }
          if (paymentApp != null && !allowed) paymentAppLaunchPending = false
          val resumed = !allowed && resumePaymentSession(url)
          scope.launch {
            diagnostic.record(
                DiagnosticStage.NEW_WINDOW,
                if (allowed || resumed) DiagnosticOutcome.STARTED else DiagnosticOutcome.BLOCKED,
                url,
            )
            if (!allowed && !resumed) sessionBlocked = true
          }
        }
      }
  popupRef.value = popup

  DisposableEffect(lifecycleOwner, page) {
    var leftForPaymentApp = false
    var returnHandled = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP -> {
          if (paymentAppLaunchState.value) leftForPaymentApp = true
        }
        Lifecycle.Event.ON_RESUME -> {
          if (leftForPaymentApp && paymentAppLaunchState.value && !returnHandled) {
            returnHandled = true
            externalPaymentAppReturnState.value?.invoke()
          }
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val interceptor =
      remember(page) {
        object : RequestInterceptor {
          override fun onInterceptUrlRequest(
              request: WebRequest,
              navigator: com.multiplatform.webview.web.WebViewNavigator,
          ): WebRequestInterceptResult {
            if (request.url == "about:blank") {
              return WebRequestInterceptResult.Allow
            }
            if (resumePaymentSession(request.url)) return WebRequestInterceptResult.Reject
            // Native request gates validate the original method and consume the order POST once.
            val allowed = navigationPolicy.decide(request.url, "GET", false) == 1
            scope.launch {
              diagnostic.record(
                  if (request.isRedirect) DiagnosticStage.REDIRECT else DiagnosticStage.SAME_PAGE,
                  if (allowed) DiagnosticOutcome.STARTED else DiagnosticOutcome.BLOCKED,
                  request.url,
              )
            }
            return if (allowed) {
              WebRequestInterceptResult.Allow
            } else {
              sessionBlocked = true
              WebRequestInterceptResult.Reject
            }
          }
        }
      }
  val navigator = rememberWebViewNavigator(requestInterceptor = interceptor)
  val backHandlerChanged by androidx.compose.runtime.rememberUpdatedState(onBackHandlerChanged)
  androidx.compose.runtime.DisposableEffect(popup) {
    backHandlerChanged { popup.goBack() }
    onDispose { backHandlerChanged(null) }
  }

  LaunchedEffect(formHandedOff, qrScanRevision) {
    if (!page.isFormSubmission || !formHandedOff || paymentConnectAttempted) return@LaunchedEffect
    val script = paymentQrDecodeScript()
    suspend fun evaluate(script: String): String? =
        withTimeoutOrNull(3_000) {
          if (popup.activeUrl() != null) popup.evaluateActive(script)
          else
              suspendCancellableCoroutine { continuation ->
                navigator.evaluateJavaScript(script) {
                  if (continuation.isActive) continuation.resume(it)
                }
              }
        }
    val scanStarted = TimeSource.Monotonic.markNow()
    fun currentUrl() = popup.activeUrl() ?: webViewState.lastLoadedUrl
    repeat(40) { scan ->
      if (scan > 0) delay(500)
      if (scanStarted.elapsedNow().inWholeMilliseconds >= 25_000) {
        qrMessage = "支付入口识别超时，请返回或重新识别；不会重发充值请求。"
        return@LaunchedEffect
      }
      val source = currentUrl()
      if (source != null && app.bucc.api.finance.isOfficialPaymentContentUrl(source))
          return@LaunchedEffect
      if (source == null || paymentQrSourceForCurrentUrl(source) == null) {
        if (scan == 39) qrMessage = "支付入口未能加载，请返回或重试识别；不会重发充值请求。"
        return@repeat
      }
      val elapsed = startedAt.elapsedNow().inWholeMilliseconds
      val lifetime = paymentQrRemainingMillis(evaluate(PAYMENT_QR_VALIDITY_SCRIPT))
      if (
          qrAttempt.decide(elapsed, emptyList(), pageRemainingMillis = lifetime) ==
              PaymentQrRecoveryDecision.Expired
      ) {
        qrMessage = "支付入口已过期，请返回并手动刷新充值准备。"
        popup.coverPaymentResponse(qrMessage)
        return@LaunchedEffect
      }
      val links = evaluate(PAYMENT_RESPONSE_CANDIDATES_SCRIPT)
      var payloads =
          app.bucc.api.finance.officialPaymentTargetsFromBrowserCandidates(
              source,
              listOf(source) + links?.let(::paymentResponseCandidates).orEmpty(),
          )
      if (payloads.isEmpty()) {
        val values = evaluate(script)?.let(::paymentResponseCandidates).orEmpty()
        payloads = values.filterNot { it.startsWith("__BUCC_QR_") }
        if (payloads.isEmpty() && (values.contains("__BUCC_QR_CROSS_ORIGIN__") || scan == 39)) {
          payloads = withTimeoutOrNull(4_000) { popup.renderedQrPayloads(source) }.orEmpty()
        }
      }
      if (source != currentUrl()) return@repeat
      if (payloads.isEmpty() && scan < 39) return@repeat
      when (
          val decision =
              qrAttempt.decide(startedAt.elapsedNow().inWholeMilliseconds, payloads, lifetime)
      ) {
        is PaymentQrRecoveryDecision.OpenOfficialPayment -> {
          recoveredTarget = decision.url
          qrMessage = null
          popup.coverPaymentResponse("正在打开官方支付入口…")
          connectPaymentTarget(decision.url)
        }
        PaymentQrRecoveryDecision.Expired -> qrMessage = "支付入口已过期，请返回并手动刷新充值准备。"
        PaymentQrRecoveryDecision.Unsupported -> qrMessage = "暂不支持此支付入口，可返回或重新识别。"
        PaymentQrRecoveryDecision.Ambiguous -> qrMessage = "页面存在多个支付入口，无法安全选择，可返回或重新识别。"
        PaymentQrRecoveryDecision.Invalid -> qrMessage = "未能识别有效支付入口，可返回或重新识别。"
        PaymentQrRecoveryDecision.AlreadyNavigated -> Unit
      }
      qrMessage?.let(popup::coverPaymentResponse)
      return@LaunchedEffect
    }
  }

  LaunchedEffect(page, webViewState) {
    diagnostic.record(DiagnosticStage.START, DiagnosticOutcome.STARTED)
    loading = true
    ready = false
    unavailable = false
    sessionBlocked = false
    if (!isAllowedPaymentNavigation(page.url, page.isFormSubmission)) {
      sessionBlocked = true
      loading = false
      return@LaunchedEffect
    }
    try {
      if (!initializePaymentBrowser()) {
        unavailable = true
        return@LaunchedEffect
      }
      diagnostic.record(DiagnosticStage.COOKIE_SYNC, DiagnosticOutcome.STARTED)
      val cookies = paymentSessionCookies(page.url, includeZfw = page.isFormSubmission)
      cookies.forEach { cookie ->
        webViewState.cookieManager.setCookie(cookie.originUrl ?: page.url, cookie.toWebViewCookie())
      }
      diagnostic.record(DiagnosticStage.COOKIE_SYNC, DiagnosticOutcome.SUCCESS)
      if (!page.isFormSubmission) webViewState.content = WebContent.Url(page.url)
      ready = true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      diagnostic.record(DiagnosticStage.COOKIE_SYNC, DiagnosticOutcome.FAILURE, error = error)
      unavailable = true
    } finally {
      loading = false
      diagnostic.record(
          DiagnosticStage.FINISH,
          if (ready) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.BLOCKED,
      )
    }
  }

  LaunchedEffect(webViewState.lastLoadedUrl) {
    webViewState.lastLoadedUrl?.let {
      diagnostic.record(DiagnosticStage.NAVIGATION, DiagnosticOutcome.SUCCESS, it)
    }
  }
  LaunchedEffect(webViewState.errorsForCurrentRequest.toList()) {
    if (webViewState.errorsForCurrentRequest.isNotEmpty())
        diagnostic.record(
            DiagnosticStage.NAVIGATION,
            DiagnosticOutcome.FAILURE,
            webViewState.lastLoadedUrl,
        )
  }

  LaunchedEffect(qrMessage) {
    if (page.isFormSubmission && qrMessage != null) popup.coverPaymentResponse(qrMessage)
  }

  Column(Modifier.fillMaxSize()) {
    qrMessage?.let { message ->
      Text(message, color = MaterialTheme.colorScheme.error)
      if (startedAt.elapsedNow().inWholeMilliseconds < PaymentQrRecoveryAttempt.MAX_AGE_MILLIS) {
        androidx.compose.material3.TextButton(
            onClick = {
              qrMessage = null
              val target = recoveredTarget
              if (target != null) {
                paymentConnectAttempted = false
                connectPaymentTarget(target)
              } else qrScanRevision++
            }
        ) {
          Text(if (recoveredTarget == null) "重新识别" else "重试打开")
        }
      }
    }
    if (sessionBlocked && ready) {
      Text("该链接暂不支持或会话未接续，可返回上一页。", color = MaterialTheme.colorScheme.error)
      androidx.compose.material3.TextButton(
          onClick = {
            popup.goBack()
            sessionBlocked = false
          }
      ) {
        Text("返回上一页")
      }
    }
    when {
      sessionBlocked && !ready ->
          Text("支付页面无法安全打开，请返回充值页面重试", color = MaterialTheme.colorScheme.error)
      unavailable -> Text("支付页面暂不可用，请勿重复建单", color = MaterialTheme.colorScheme.error)
      loading ->
          Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("正在打开官方支付页面…")
          }
      ready ->
          WebView(
              state = webViewState,
              captureBackPresses = false,
              factory = ::paymentNativeWebViewFactory,
              modifier = Modifier.weight(1f).fillMaxWidth(),
              navigator = navigator,
              onCreated = { native ->
                scope.launch {
                  // The library installs platform clients after onCreated returns.
                  yield()
                  popup.attach(native)
                  if (page.isFormSubmission) {
                    popup.coverPaymentResponse("正在打开支付页面…")
                    try {
                      responseObserver.attach(native)
                    } catch (error: Exception) {
                      diagnostic.record(
                          DiagnosticStage.HTTP,
                          DiagnosticOutcome.FAILURE,
                          error = error,
                      )
                    }
                    val body = page.takeFormPost()
                    if (body == null) {
                      diagnostic.record(DiagnosticStage.CREATE_ORDER, DiagnosticOutcome.BLOCKED)
                      unavailable = true
                    } else {
                      try {
                        diagnostic.record(
                            DiagnosticStage.CREATE_ORDER,
                            DiagnosticOutcome.STARTED,
                            page.url,
                        )
                        formHandedOff = true
                        postOfficialPaymentForm(native, page.url, body)
                      } catch (cancelled: CancellationException) {
                        diagnostic.record(
                            DiagnosticStage.CREATE_ORDER,
                            DiagnosticOutcome.UNCERTAIN,
                            error = cancelled,
                        )
                        throw cancelled
                      } catch (error: Exception) {
                        diagnostic.record(
                            DiagnosticStage.CREATE_ORDER,
                            DiagnosticOutcome.UNCERTAIN,
                            error = error,
                        )
                        unavailable = true
                      } finally {
                        body.fill(0)
                      }
                    }
                  }
                }
              },
              onDispose = { _ ->
                responseObserver.close()
                popup.close()
              },
          )
    }
  }
}

internal interface PaymentPopupHandler {
  fun goBack(): Boolean

  fun open(url: String): Boolean

  fun attach(webView: NativeWebView)

  fun activeUrl(): String? = null

  suspend fun evaluateActive(script: String): String? = null

  suspend fun renderedQrPayloads(sourceUrl: String): List<String> = emptyList()

  fun coverPaymentResponse(message: String?) {}

  fun close()
}

internal expect fun paymentPopupHandler(
    allowZfw: Boolean = false,
    onPaymentAppLaunchStarted: () -> Unit = {},
    onPopup: (String, Boolean) -> Unit,
): PaymentPopupHandler

private fun PaymentSessionCookie.toWebViewCookie() =
    Cookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        isSecure = secure,
        isHttpOnly = httpOnly,
        isSessionOnly = true,
    )

internal expect fun postOfficialPaymentForm(webView: NativeWebView, url: String, body: ByteArray)

internal interface PaymentResponseObserver {
  fun attach(webView: NativeWebView)

  fun activeUrl(): String? = null

  suspend fun evaluateActive(script: String): String? = null

  suspend fun renderedQrPayloads(sourceUrl: String): List<String> = emptyList()

  fun coverPaymentResponse(message: String?) {}

  fun close()
}

internal expect fun paymentResponseObserver(
    onResponse: (String, Int) -> Unit
): PaymentResponseObserver

private class PaymentWindowReference(var value: PaymentPopupHandler? = null)

@Composable
internal expect fun platformPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
): Boolean
