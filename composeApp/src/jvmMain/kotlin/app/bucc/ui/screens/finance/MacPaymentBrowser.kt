package app.bucc.ui.screens.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import app.bucc.api.finance.OfficialPaymentPage
import app.bucc.api.finance.PaymentSessionCookie
import app.bucc.api.finance.paymentSessionCookies
import app.bucc.api.finance.resumeOfficialPaymentPage
import app.bucc.diagnostics.*
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import io.ktor.http.encodeURLParameter
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

internal interface MacPaymentPolicyCallback : Callback {
  fun invoke(url: String?, method: String?, root: Int): Int
}

internal interface MacPaymentEventCallback : Callback {
  fun invoke(id: Long, event: String?)
}

internal interface MacPaymentNative : Library {
  fun bucc_payment_cover(id: Long, message: String?)

  fun bucc_payment_qr_snapshot(id: Long, source: String, token: Long)

  fun bucc_payment_available(): Int

  fun bucc_payment_open(
      config: String,
      policy: MacPaymentPolicyCallback,
      event: MacPaymentEventCallback,
  ): Long

  fun bucc_payment_back(id: Long): Int

  fun bucc_payment_close(id: Long)

  fun bucc_payment_show(id: Long)

  fun bucc_payment_eval(id: Long, script: String, token: Long)

  fun bucc_payment_navigate(id: Long, config: String)

  fun bucc_payment_bounds(id: Long, x: Double, y: Double, width: Double, height: Double)

  fun bucc_payment_message(id: Long, message: String)
}

private object MacPaymentLibrary {
  val native: MacPaymentNative by lazy {
    val resources = System.getProperty("compose.application.resources.dir")
    val library =
        listOfNotNull(
                resources?.let { File(it, "native/libbucc-native.dylib") },
                File("composeApp/build/native/libbucc-native.dylib"),
                File("build/native/libbucc-native.dylib"),
            )
            .firstOrNull { it.isFile } ?: error("macOS 内嵌浏览器组件不可用")
    Native.load(
        library.absolutePath,
        MacPaymentNative::class.java,
        mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
    )
  }
}

internal fun macPaymentBrowserAvailable(): Boolean =
    runCatching { MacPaymentLibrary.native.bucc_payment_available() == 1 }.getOrDefault(false)

/** Owns callbacks for exactly one native window; native values never enter files or logs. */
internal class MacPaymentWindow(private val policy: (String, String, Boolean) -> Int) {
  private val native
    get() = MacPaymentLibrary.native

  @Volatile private var id = 0L
  @Volatile private var canGoBack = false
  @Volatile private var disposed = false
  private var released = false
  private val sequence = AtomicLong()
  private val scripts = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
  val events = Channel<JsonObject>(Channel.UNLIMITED)
  private val policyCallback =
      object : MacPaymentPolicyCallback {
        override fun invoke(url: String?, method: String?, root: Int): Int =
            if (url == null) 0
            else runCatching { policy(url, method ?: "GET", root == 1) }.getOrDefault(0)
      }
  private val eventCallback =
      object : MacPaymentEventCallback {
        override fun invoke(id: Long, event: String?) {
          val value =
              event
                  ?.takeIf { it.length <= 262144 }
                  ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                  ?: return
          value["canGoBack"]?.jsonPrimitive?.booleanOrNull?.let { canGoBack = it }
          if (value["type"]?.jsonPrimitive?.content == "released") {
            synchronized(this@MacPaymentWindow) {
              released = true
              closingWindows.remove(id)
              if (this@MacPaymentWindow.id == id) this@MacPaymentWindow.id = 0
            }
            return
          }
          if (value["type"]?.jsonPrimitive?.content == "script") {
            value["token"]?.jsonPrimitive?.longOrNull?.let {
              scripts.remove(it)?.complete(value["value"])
            }
          } else events.trySend(value)
        }
      }

  fun open(
      url: String,
      body: ByteArray?,
      cookies: List<PaymentSessionCookie>,
      ephemeral: Boolean = false,
      host: MacPaymentHost? = null,
  ): Boolean {
    check(id == 0L && !disposed)
    val config = buildJsonObject {
      put("url", url)
      put("cookies", cookieJson(cookies))
      put("ephemeral", ephemeral)
      host?.let {
        put("parentWindow", it.window)
        put("x", it.x)
        put("y", it.y)
        put("width", it.width)
        put("height", it.height)
      }
      body?.let { put("post", Base64.getEncoder().encodeToString(it)) }
    }
    val opened = native.bucc_payment_open(config.toString(), policyCallback, eventCallback)
    return synchronized(this) {
      if (released) false
      else if (disposed && opened > 0) {
        closingWindows[opened] = this
        native.bucc_payment_close(opened)
        false
      } else {
        id = opened
        id > 0
      }
    }
  }

  fun back(): Boolean = id > 0 && canGoBack && native.bucc_payment_back(id) == 1

  fun bounds(host: MacPaymentHost) {
    if (id > 0) native.bucc_payment_bounds(id, host.x, host.y, host.width, host.height)
  }

  fun message(value: String) {
    if (id > 0) native.bucc_payment_message(id, value)
  }

  fun navigate(url: String, cookies: List<PaymentSessionCookie> = emptyList()) {
    check(id > 0)
    native.bucc_payment_navigate(
        id,
        buildJsonObject {
              put("url", url)
              put("cookies", cookieJson(cookies))
            }
            .toString(),
    )
  }

  suspend fun evaluate(script: String): JsonElement? {
    if (id <= 0) return null
    val token = sequence.incrementAndGet()
    val result = CompletableDeferred<JsonElement?>()
    scripts[token] = result
    return try {
      native.bucc_payment_eval(id, script, token)
      withTimeoutOrNull(3000) { result.await() }
    } finally {
      scripts.remove(token)
    }
  }

  fun coverResponse(message: String?) {
    if (id > 0) native.bucc_payment_cover(id, message)
  }

  suspend fun snapshotQr(source: String): List<String> {
    if (id <= 0) return emptyList()
    val token = sequence.incrementAndGet()
    val result = CompletableDeferred<JsonElement?>()
    scripts[token] = result
    return try {
      native.bucc_payment_qr_snapshot(id, source, token)
      val value = withTimeoutOrNull(4000) { result.await() } ?: return emptyList()
      paymentResponseCandidates(value.toString())
    } finally {
      scripts.remove(token)
    }
  }

  @Synchronized
  fun close() {
    disposed = true
    val closing = id
    id = 0
    canGoBack = false
    if (closing > 0) {
      // Native destruction is asynchronous: keep JNA callbacks alive until it acknowledges release.
      closingWindows[closing] = this
      native.bucc_payment_close(closing)
    }
    scripts.values.forEach { it.cancel() }
    scripts.clear()
    events.close()
  }

  private companion object {
    val closingWindows = ConcurrentHashMap<Long, MacPaymentWindow>()
  }

  private fun cookieJson(cookies: List<PaymentSessionCookie>): JsonArray = buildJsonArray {
    cookies.forEach { cookie ->
      add(
          buildJsonObject {
            put("name", cookie.name)
            put("value", cookie.value)
            cookie.domain?.let { put("domain", it) }
            cookie.path?.let { put("path", it) }
            cookie.originUrl?.let { put("origin", it) }
            put("secure", cookie.secure)
            put("httpOnly", cookie.httpOnly)
          }
      )
    }
  }
}

@Composable
internal actual fun platformPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
): Boolean {
  if (!System.getProperty("os.name").startsWith("Mac", true)) return false
  val allowed =
      remember(page) {
        PaymentWebNavigationPolicy(page.isFormSubmission)
            .decide(page.url, if (page.isFormSubmission) "POST" else "GET", true) == 1
      }
  if (allowed) MacOfficialPaymentBrowser(page, onBackHandlerChanged)
  else Text("支付页面无法安全打开，请返回充值页面重试", color = MaterialTheme.colorScheme.error)
  return true
}

/** The pass login page does not auto-start BUAASSO; enter the channel URL directly. */
internal fun paymentBrowserStartUrl(page: OfficialPaymentPage): String =
    if (!page.isFormSubmission && app.bucc.api.finance.isOfficialPaymentUrl(page.url))
        "https://pass.cc-pay.cn/login?backUrl=${page.url.encodeURLParameter()}&channel=BUAASSO"
    else page.url

@Composable
private fun MacOfficialPaymentBrowser(
    page: OfficialPaymentPage,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
) {
  val diagnostic =
      remember(page) {
        LocalDiagnostics.session(DiagnosticModule.RECHARGE, DiagnosticOperation.PAYMENT_BROWSER)
      }
  val policy = remember(page) { PaymentWebNavigationPolicy(page.isFormSubmission) }
  val window = remember(page) { MacPaymentWindow(policy::decide) }
  val scope = rememberCoroutineScope()
  var message by remember(page) { mutableStateOf("正在加载官方支付页面…") }
  var host by remember(page) { mutableStateOf<MacPaymentHost?>(null) }
  var opened by remember(page) { mutableStateOf(false) }
  var closed by remember(page) { mutableStateOf(false) }
  val backChanged by rememberUpdatedState(onBackHandlerChanged)
  val started = remember(page) { TimeSource.Monotonic.markNow() }
  val attempt = remember(page) { PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_PAY_CARD) }
  var currentUrl by remember(page) { mutableStateOf("") }
  var scanJob by remember(page) { mutableStateOf<Job?>(null) }
  var recoveredTarget by remember(page) { mutableStateOf<String?>(null) }
  var connectionJob by remember(page) { mutableStateOf<Job?>(null) }
  var connectionGeneration by remember(page) { mutableStateOf(0L) }
  var automaticSessionRecovery by remember(page) { mutableStateOf(false) }

  fun inform(value: String) {
    message = value
    window.message(value)
    if (page.isFormSubmission) window.coverResponse(value)
  }
  fun invalidateConnection() {
    connectionGeneration++
    connectionJob?.cancel()
    connectionJob = null
  }
  fun openRecoveredTarget(target: String) {
    invalidateConnection()
    val generation = connectionGeneration
    val source = currentUrl
    connectionJob =
        scope.launch {
          inform("正在接续官方支付会话…")
          try {
            val (url, cookies) = withContext(Dispatchers.IO) { resolveMacPaymentTarget(target) }
            if (closed || generation != connectionGeneration || currentUrl != source) return@launch
            if (
                attempt.decide(started.elapsedNow().inWholeMilliseconds, emptyList()) ==
                    PaymentQrRecoveryDecision.Expired
            ) {
              inform("支付入口已过期，请返回并手动刷新充值准备。")
            } else {
              diagnostic.record(
                  DiagnosticStage.CONNECT_PAYMENT_SESSION,
                  DiagnosticOutcome.SUCCESS,
                  url,
              )
              window.navigate(url, cookies)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (closed || generation != connectionGeneration || currentUrl != source) return@launch
            diagnostic.record(DiagnosticStage.CONNECT_PAYMENT_SESSION, DiagnosticOutcome.FAILURE)
            inform("支付会话暂未接续，可重新识别以重试接续；不会重发充值请求。")
          }
        }
  }
  fun scanQr() {
    if (!page.isFormSubmission || paymentQrSourceForCurrentUrl(currentUrl) == null) return
    scanJob?.cancel()
    val source = currentUrl
    scanJob =
        scope.launch {
          val script = paymentQrDecodeScript()
          val scanStarted = TimeSource.Monotonic.markNow()
          repeat(30) { index ->
            if (index > 0) delay(500)
            if (scanStarted.elapsedNow().inWholeMilliseconds >= 25_000) {
              inform("支付入口识别超时，请返回或重新识别；不会重发充值请求。")
              return@launch
            }
            if (currentUrl != source || closed) return@launch
            val lifetime =
                window.evaluate(PAYMENT_QR_VALIDITY_SCRIPT)?.let {
                  (it as? JsonPrimitive)?.longOrNull
                }
            if (
                attempt.decide(started.elapsedNow().inWholeMilliseconds, emptyList(), lifetime) ==
                    PaymentQrRecoveryDecision.Expired
            ) {
              inform("支付入口已过期，请返回并手动刷新充值准备。")
              return@launch
            }
            val links = window.evaluate(PAYMENT_RESPONSE_CANDIDATES_SCRIPT)
            var candidates =
                app.bucc.api.finance.officialPaymentTargetsFromBrowserCandidates(
                    source,
                    listOf(source) +
                        paymentResponseCandidates(
                            links
                                ?.let {
                                  if (it is JsonPrimitive && it.isString) it.content
                                  else it.toString()
                                }
                                .orEmpty()
                        ),
                )
            if (candidates.isEmpty()) {
              val result = window.evaluate(script)
              val values =
                  paymentResponseCandidates(
                      result
                          ?.let {
                            if (it is JsonPrimitive && it.isString) it.content else it.toString()
                          }
                          .orEmpty()
                  )
              candidates = values.filterNot { it.startsWith("__BUCC_QR_") }
              if (
                  candidates.isEmpty() &&
                      (values.any { it == "__BUCC_QR_CROSS_ORIGIN__" } || index == 29)
              ) {
                candidates = window.snapshotQr(source)
              }
            }
            if (
                currentUrl != source ||
                    closed ||
                    window.evaluate("location.href")?.jsonPrimitive?.content != source
            )
                return@launch
            if (candidates.isEmpty() && index < 29) return@repeat
            when (
                val decision =
                    attempt.decide(started.elapsedNow().inWholeMilliseconds, candidates, lifetime)
            ) {
              is PaymentQrRecoveryDecision.OpenOfficialPayment -> {
                recoveredTarget = decision.url
                openRecoveredTarget(decision.url)
              }
              PaymentQrRecoveryDecision.Expired -> inform("支付入口已过期，请返回并手动刷新充值准备。")
              PaymentQrRecoveryDecision.Unsupported -> inform("暂不支持此官方支付入口，请返回或重试识别。")
              PaymentQrRecoveryDecision.Ambiguous -> inform("页面有多个支付入口，无法安全选择，请返回或重试识别。")
              PaymentQrRecoveryDecision.Invalid -> inform("暂未识别到有效支付入口，可重新识别或返回；不会重发充值请求。")
              PaymentQrRecoveryDecision.AlreadyNavigated -> Unit
            }
            return@launch
          }
        }
  }

  DisposableEffect(window) {
    backChanged { window.back() }
    onDispose {
      backChanged(null)
      scanJob?.cancel()
      invalidateConnection()
      window.close()
    }
  }
  LaunchedEffect(window) {
    for (event in window.events) when (event["type"]?.jsonPrimitive?.content) {
      "finished" -> {
        if (event["active"]?.jsonPrimitive?.booleanOrNull == false) continue
        val nextUrl = event["url"]?.jsonPrimitive?.content.orEmpty()
        if (nextUrl != currentUrl) invalidateConnection()
        currentUrl = nextUrl
        val status = event["status"]?.jsonPrimitive?.intOrNull ?: 200
        diagnostic.record(
            DiagnosticStage.NAVIGATION,
            if (status >= 400) DiagnosticOutcome.FAILURE else DiagnosticOutcome.SUCCESS,
            currentUrl,
            httpStatus = status,
        )
        if (status >= 400) inform("官方支付页面返回错误，可返回或重试打开。")
        else {
          if (paymentRecoveryPhase(currentUrl) == PaymentRecoveryPhase.READY)
              window.coverResponse(null)
          message = "官方支付页面已打开。"
          if (
              page.isFormSubmission &&
                  paymentQrSourceForCurrentUrl(currentUrl) == null &&
                  paymentRecoveryPhase(currentUrl) != PaymentRecoveryPhase.READY
          ) {
            inform("支付入口未能接续，请返回或重试打开；不会重发充值请求。")
          } else scanQr()
        }
      }
      "navigating" -> invalidateConnection()
      "window" -> diagnostic.record(DiagnosticStage.NEW_WINDOW, DiagnosticOutcome.STARTED)
      "closed" -> {
        closed = true
        scanJob?.cancel()
        invalidateConnection()
        message = "支付页面已关闭，请返回充值页面；不会自动重发充值请求。"
      }
      "retry" -> {
        val target = recoveredTarget
        if (
            attempt.decide(started.elapsedNow().inWholeMilliseconds, emptyList()) ==
                PaymentQrRecoveryDecision.Expired
        ) {
          inform("支付入口已过期，请返回并手动刷新充值准备。")
        } else if (target != null) {
          openRecoveredTarget(target)
        } else scanQr()
      }
      "blocked" -> {
        val raw = event["url"]?.jsonPrimitive?.content.orEmpty()
        val target =
            runCatching {
                  io.ktor.http.Url(app.bucc.api.finance.paymentNavigationOriginalUrl(raw) ?: raw)
                }
                .getOrNull()
        val category =
            when {
              target?.protocol != io.ktor.http.URLProtocol.HTTPS -> "payment_redirect_insecure"
              target.host == "pass.cc-pay.cn" -> "payment_redirect_pass_route"
              target.host == "cashier.cc-pay.cn" -> "payment_redirect_cashier_route"
              target.host == "mall.cc-pay.cn" -> "payment_redirect_mall_route"
              target.host == "sso.buaa.edu.cn" -> "payment_redirect_sso_route"
              target.host == "zfw.buaa.edu.cn" -> "payment_redirect_zfw_route"
              else -> "payment_redirect_other_origin"
            }
        diagnostic.record(
            DiagnosticStage.SAME_PAGE,
            DiagnosticOutcome.BLOCKED,
            raw,
            businessCode = category,
        )
        val backUrl = officialPaymentSessionTarget(raw)
        if (backUrl != null && !automaticSessionRecovery) {
          automaticSessionRecovery = true
          recoveredTarget = backUrl
          openRecoveredTarget(backUrl)
        } else inform("此跳转不在官方支付范围内，可返回或重试打开。")
      }
      "error" -> {
        val reason = event["reason"]?.jsonPrimitive?.contentOrNull
        val code = event["code"]?.jsonPrimitive?.intOrNull
        val category =
            when {
              reason == "timeout" || code == -1001 -> "webkit_timeout"
              reason == "http" -> "webkit_http"
              reason == "process" -> "webkit_process"
              code == -1003 || code == -1006 -> "webkit_dns"
              code in -1206..-1200 || code == -1022 -> "webkit_tls"
              code == -1004 || code == -1005 || code == -1009 -> "webkit_connection"
              else -> "webkit_navigation"
            }
        diagnostic.record(
            DiagnosticStage.NAVIGATION,
            DiagnosticOutcome.FAILURE,
            businessCode = category,
        )
        if (connectionJob?.isActive != true) inform("官方支付页面未能完成加载，可返回或重试打开；不会自动重发充值请求。")
      }
    }
  }
  LaunchedEffect(host, opened) { if (opened) host?.let(window::bounds) }
  LaunchedEffect(page, host?.window) {
    val target = host ?: return@LaunchedEffect
    if (opened || closed) return@LaunchedEffect
    var body: ByteArray? = null
    try {
      diagnostic.record(DiagnosticStage.START, DiagnosticOutcome.STARTED)
      check(macPaymentBrowserAvailable())
      val cookies = paymentSessionCookies(page.url, includeZfw = page.isFormSubmission)
      if (page.isFormSubmission) {
        body = page.takeFormPost()
        check(body != null)
      }
      diagnostic.record(
          if (page.isFormSubmission) DiagnosticStage.CREATE_ORDER else DiagnosticStage.NAVIGATION,
          DiagnosticOutcome.STARTED,
          page.url,
      )
      opened =
          withContext(Dispatchers.IO) {
            window.open(paymentBrowserStartUrl(page), body, cookies, host = target)
          }
      check(opened)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostic.record(DiagnosticStage.FINISH, DiagnosticOutcome.FAILURE)
      message = "支付页面无法打开，请返回；充值请求不会自动重发。"
      closed = true
    } finally {
      body?.fill(0)
    }
  }
  Column(Modifier.fillMaxSize()) {
    if (closed) {
      Text(message, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
    } else {
      SwingPanel(factory = { MacPaymentHostPanel { host = it } }, modifier = Modifier.fillMaxSize())
    }
  }
}

internal suspend fun resolveMacPaymentTarget(
    target: String,
    connect: suspend (String) -> OfficialPaymentPage = ::resumeOfficialPaymentPage,
    cookies: suspend (String) -> List<PaymentSessionCookie> = { paymentSessionCookies(it) },
): Pair<String, List<PaymentSessionCookie>> {
  if (app.bucc.api.finance.isOfficialPaymentUrl(target)) {
    val page = connect(target)
    check(!page.isFormSubmission && app.bucc.api.finance.isOfficialPaymentUrl(page.url))
    return page.url to cookies(page.url)
  }
  check(app.bucc.api.finance.isOfficialProviderPaymentUrl(target))
  return target to emptyList()
}
