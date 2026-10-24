package app.bucc.ui.screens.finance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.multiplatform.webview.web.NativeWebView
import java.io.ByteArrayInputStream
import java.util.EnumMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun paymentPopupHandler(
    allowZfw: Boolean,
    onPaymentAppLaunchStarted: () -> Unit,
    onPopup: (String, Boolean) -> Unit,
): PaymentPopupHandler = AndroidPaymentWindows(allowZfw, onPaymentAppLaunchStarted, onPopup)

internal class PaymentRootWebView(context: android.content.Context) : WebView(context) {
  internal var installedClient: WebViewClient? = null
    private set

  internal var installedChrome: WebChromeClient? = null
    private set

  override fun setWebViewClient(client: WebViewClient) {
    installedClient = client
    super.setWebViewClient(client)
  }

  override fun setWebChromeClient(client: WebChromeClient?) {
    installedChrome = client
    super.setWebChromeClient(client)
  }
}

private class AndroidPaymentWindows(
    private val allowZfw: Boolean,
    private val onPaymentAppLaunchStarted: () -> Unit,
    private val onPopup: (String, Boolean) -> Unit,
) : PaymentPopupHandler {
  private var root: WebView? = null
  private var decor: FrameLayout? = null
  private var previousClient: WebViewClient? = null
  private var previousChrome: WebChromeClient? = null
  private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
  private var rootLayoutListener: View.OnLayoutChangeListener? = null
  private val children = mutableListOf<WebView>()
  private val covers = mutableMapOf<WebView, FrameLayout>()
  private var coverMessage: String? = null
  private val gate = PaymentRequestGate(allowZfw)

  private fun activeView(): WebView? = children.lastOrNull() ?: root

  override fun activeUrl(): String? = activeView()?.url

  private fun hasQrPageInHistory(view: WebView): Boolean {
    if (!allowZfw) return false
    val history = view.copyBackForwardList()
    return history.currentIndex > 0 &&
        (0 until history.currentIndex).any { index ->
          paymentQrSourceForCurrentUrl(history.getItemAtIndex(index)?.url.orEmpty()) != null
        }
  }

  private fun releaseCompletedTargetCover(view: WebView) {
    val hasRecoverableQrPage =
        allowZfw &&
            (hasQrPageInHistory(view) ||
                (listOfNotNull(root) + children).any { other ->
                  other !== view && paymentQrSourceForCurrentUrl(other.url.orEmpty()) != null
                })
    if (hasRecoverableQrPage) removeCover(view) else coverPaymentResponse(null)
  }

  private fun notifyBlocked(view: WebView, url: String) {
    view.post { onPopup(url, false) }
  }

  private fun openPaymentApp(view: WebView, url: String): Boolean =
      runCatching {
            val target = paymentAppLaunchTarget(url) ?: return false
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
            var context: Context? = view.context
            while (context != null) {
              if (context is Activity) {
                onPaymentAppLaunchStarted()
                context.startActivity(intent)
                return@runCatching true
              }
              val next = (context as? ContextWrapper)?.baseContext
              if (next === context) break
              context = next
            }
            onPaymentAppLaunchStarted()
            view.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
          }
          .getOrDefault(false)

  private fun handleNavigation(
      view: WebView,
      url: String,
      method: String,
      isRoot: Boolean,
  ): Boolean? =
      when (gate.decision(url, method, isRoot)) {
        1 -> null
        2 -> {
          val opened = openPaymentApp(view, url)
          view.post { onPopup(url, opened) }
          true
        }
        else -> {
          notifyBlocked(view, url)
          true
        }
      }

  private fun blockedResponse() =
      WebResourceResponse(
          "text/plain",
          "utf-8",
          403,
          "Blocked",
          emptyMap(),
          ByteArrayInputStream(ByteArray(0)),
      )

  private val client =
      object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
          val rootRequest = view === root
          // Main-frame destinations are checked here as well as in shouldOverrideUrlLoading so
          // server redirects cannot bypass the official-host allowlist. POST bodies are never
          // replayed.
          if (request.isForMainFrame || request.method.equals("POST", true)) {
            val decision = gate.decision(request.url.toString(), request.method, rootRequest)
            if (decision == 2)
                return null // Let shouldOverrideUrlLoading hand this vetted app URL to Android.
            if (decision != 1) {
              notifyBlocked(view, request.url.toString())
              return blockedResponse()
            }
          }
          if (!rootRequest) return null
          return previousClient?.shouldInterceptRequest(view, request)
        }

        @Suppress("DEPRECATION")
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
          if (view === root) {
            when (gate.decision(url, "GET", true)) {
              2 -> return null // The navigation callback opens recognized payment-app links.
              1 -> Unit
              else -> {
                notifyBlocked(view, url)
                return blockedResponse()
              }
            }
          }
          if (view !== root) return null
          return previousClient?.shouldInterceptRequest(view, url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
          val url = request.url.toString()
          // POST is decided only by shouldInterceptRequest. WebView does not normally route POSTs
          // through this callback, and we deliberately do not turn it into a replayable GET.
          if (request.method.equals("POST", true)) return false
          handleNavigation(view, url, request.method, view === root)?.let {
            return it
          }
          return if (view === root) previousClient?.shouldOverrideUrlLoading(view, request) ?: false
          else false
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
          handleNavigation(view, url, "GET", view === root)?.let {
            return it
          }
          return if (view === root) previousClient?.shouldOverrideUrlLoading(view, url) ?: false
          else false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
          if (view === activeView() && allowZfw && paymentQrSourceForCurrentUrl(url) != null) {
            coverMessage?.let { ensureCover(view, it) }
          }
          if (view === root) previousClient?.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
          if (view === activeView()) {
            val currentUrl = view.url ?: url
            if (currentUrl.isNotBlank()) {
              if (allowZfw && paymentQrSourceForCurrentUrl(currentUrl) != null) {
                coverMessage?.let { ensureCover(view, it) }
              } else if (app.bucc.api.finance.isOfficialPaymentContentUrl(currentUrl)) {
                releaseCompletedTargetCover(view)
              }
              onPopup(currentUrl, true)
            }
          }
          if (view === root) previousClient?.onPageFinished(view, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
          if (view === root) previousClient?.onPageCommitVisible(view, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
          if (view === root) previousClient?.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun onLoadResource(view: WebView, url: String) {
          if (view === root) previousClient?.onLoadResource(view, url)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
          if (view === root)
              previousClient?.onReceivedError(view, errorCode, description, failingUrl)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
          if (view === root) previousClient?.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
          if (view === root) previousClient?.onReceivedHttpError(view, request, errorResponse)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
          if (view === root)
              previousClient?.onReceivedSslError(view, handler, error) ?: handler.cancel()
          else handler.cancel()
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String,
        ) {
          if (view === root)
              previousClient?.onReceivedHttpAuthRequest(view, handler, host, realm)
                  ?: handler.cancel()
          else handler.cancel()
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
          if (view === root)
              previousClient?.onReceivedClientCertRequest(view, request) ?: request.cancel()
          else request.cancel()
        }

        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
          dontResend.sendToTarget()
        }
      }

  private val chrome =
      object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
          val child = createChild() ?: return false
          val transport =
              resultMsg.obj as? WebView.WebViewTransport
                  ?: run {
                    dismiss(child)
                    return false
                  }
          transport.webView = child
          resultMsg.sendToTarget()
          return true
        }

        override fun onCloseWindow(window: WebView) {
          dismiss(window)
          if (window === root) previousChrome?.onCloseWindow(window)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
          if (view === root) previousChrome?.onProgressChanged(view, newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
          if (view === root) previousChrome?.onReceivedTitle(view, title)
        }
      }

  private fun configure(view: WebView) {
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.setSupportMultipleWindows(true)
    view.settings.javaScriptCanOpenWindowsAutomatically = true
    view.webChromeClient = chrome
  }

  private fun placeChild(child: WebView) {
    root?.let { placeViewOverWebView(it, child) }
    placeCover(child)
  }

  private fun placeCover(webView: WebView) {
    covers[webView]?.let { placeViewOverWebView(webView, it) }
  }

  private fun placeViewOverWebView(webView: WebView, overlayView: View) {
    val host = decor ?: return
    if (webView.width <= 0 || webView.height <= 0) return
    val webLocation = IntArray(2)
    val hostLocation = IntArray(2)
    webView.getLocationInWindow(webLocation)
    host.getLocationInWindow(hostLocation)
    val params =
        overlayView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(webView.width, webView.height, Gravity.TOP or Gravity.LEFT)
    val left = webLocation[0] - hostLocation[0]
    val top = webLocation[1] - hostLocation[1]
    if (
        params.width == webView.width &&
            params.height == webView.height &&
            params.leftMargin == left &&
            params.topMargin == top &&
            overlayView.layoutParams != null
    )
        return
    params.width = webView.width
    params.height = webView.height
    params.leftMargin = left
    params.topMargin = top
    params.rightMargin = 0
    params.bottomMargin = 0
    overlayView.layoutParams = params
  }

  private fun ensureCover(view: WebView, message: String) {
    val host = decor ?: return
    val cover =
        covers.getOrPut(view) {
          FrameLayout(view.context)
              .apply {
                setBackgroundColor(Color.rgb(248, 249, 250))
                isClickable = true
                isFocusable = true
                addView(
                    TextView(view.context).apply {
                      text = message
                      setTextColor(Color.rgb(35, 42, 50))
                      textSize = 16f
                      gravity = Gravity.CENTER
                      setPadding(24, 24, 24, 24)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
              }
              .also {
                host.addView(it, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
              }
        }
    ((cover.getChildAt(0) as? TextView))?.text = message
    placeViewOverWebView(view, cover)
    cover.bringToFront()
  }

  private fun removeCover(view: WebView) {
    covers.remove(view)?.let { (it.parent as? ViewGroup)?.removeView(it) }
  }

  private fun createChild(): WebView? {
    val parent = root ?: return null
    val overlay = decor ?: return null
    val child = WebView(parent.context)
    configure(child)
    child.webViewClient = client
    children += child
    overlay.addView(child, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
    placeChild(child)
    coverMessage?.let { ensureCover(child, it) }
    return child
  }

  private tailrec fun findActivity(context: Context): Activity? =
      when (context) {
        is Activity -> context
        is ContextWrapper ->
            if (context.baseContext === context) null else findActivity(context.baseContext)
        else -> null
      }

  private fun dismiss(view: WebView) {
    val index = children.indexOf(view)
    if (index < 0) return
    children.drop(index).reversed().forEach { child ->
      child.stopLoading()
      removeCover(child)
      (child.parent as? ViewGroup)?.removeView(child)
      child.destroy()
    }
    while (children.size > index) children.removeAt(children.lastIndex)
    activeView()?.let { parent ->
      if (parent.url?.let(::paymentQrSourceForCurrentUrl) != null) {
        coverPaymentResponse("支付页面已关闭，请返回充值页；不会重发充值请求。")
      }
    }
  }

  override fun open(url: String): Boolean {
    if (!gate.allows(url, "GET", false)) {
      onPopup(url, false)
      return false
    }
    val child = createChild() ?: return false
    child.loadUrl(url)
    onPopup(url, true)
    return true
  }

  override fun goBack(): Boolean {
    val active = activeView() ?: return false
    val history = active.copyBackForwardList()
    val previousUrl = history.getItemAtIndex(history.currentIndex - 1)?.url
    if (active.canGoBack() && canReturnToPaymentHistory(previousUrl, allowZfw)) {
      active.goBack()
      return true
    }
    if (children.isNotEmpty()) {
      val parentUrl = (children.dropLast(1).lastOrNull() ?: root)?.url
      if (parentUrl != null && paymentQrSourceForCurrentUrl(parentUrl) != null) return false
      dismiss(active)
      return true
    }
    return false
  }

  override suspend fun evaluateActive(script: String): String? {
    val view = activeView() ?: return null
    val encoded =
        withContext(Dispatchers.Main.immediate) {
          if (activeView() !== view) return@withContext null
          kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
            view.evaluateJavascript(script) { value ->
              if (continuation.isActive) continuation.resume(value)
            }
          }
        } ?: return null
    if (activeView() !== view) return null
    return runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull()
        ?: encoded
  }

  override suspend fun renderedQrPayloads(sourceUrl: String): List<String> {
    val view = activeView() ?: return emptyList()
    fun stillOnSource() =
        activeView() === view &&
            activeUrl() == sourceUrl &&
            paymentQrSourceForCurrentUrl(sourceUrl) != null
    if (!stillOnSource()) return emptyList()
    val bitmap =
        withContext(Dispatchers.Main.immediate) {
          if (!stillOnSource() || view.width <= 0 || view.height <= 0) return@withContext null
          val scale = minOf(1f, 1024f / maxOf(view.width, view.height))
          val bitmap =
              Bitmap.createBitmap(
                  (view.width * scale).toInt().coerceAtLeast(1),
                  (view.height * scale).toInt().coerceAtLeast(1),
                  Bitmap.Config.ARGB_8888,
              )
          val canvas = Canvas(bitmap)
          canvas.scale(scale, scale)
          view.draw(canvas)
          if (stillOnSource()) bitmap else bitmap.also(Bitmap::recycle)
        } ?: return emptyList()
    return try {
      if (!stillOnSource()) return emptyList()
      val payloads =
          withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val hints =
                EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                  put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                  put(DecodeHintType.TRY_HARDER, true)
                }
            val reader = MultiFormatReader().apply { setHints(hints) }
            try {
              GenericMultipleBarcodeReader(reader)
                  .decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints)
                  .mapNotNull { it.text?.takeIf(String::isNotBlank) }
                  .distinct()
            } catch (_: Exception) {
              emptyList()
            } finally {
              reader.reset()
              pixels.fill(0)
            }
          }
      if (stillOnSource()) payloads else emptyList()
    } finally {
      bitmap.recycle()
    }
  }

  override fun coverPaymentResponse(message: String?) {
    coverMessage = message
    val views = listOfNotNull(root) + children
    if (message == null) views.forEach(::removeCover)
    else views.forEach { ensureCover(it, message) }
  }

  override fun attach(webView: NativeWebView) {
    close()
    val activity = findActivity(webView.context) ?: return
    val overlay = activity.window.decorView as? FrameLayout ?: return
    root = webView
    decor = overlay
    val tracked = webView as? PaymentRootWebView
    previousClient = tracked?.installedClient
    previousChrome = tracked?.installedChrome
    webView.webViewClient = client
    configure(webView)
    val listener =
        ViewTreeObserver.OnGlobalLayoutListener {
          root?.let(::placeCover)
          children.forEach(::placeChild)
        }
    layoutListener = listener
    overlay.viewTreeObserver.addOnGlobalLayoutListener(listener)
    val rootListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
          root?.let(::placeCover)
          children.forEach(::placeChild)
        }
    rootLayoutListener = rootListener
    webView.addOnLayoutChangeListener(rootListener)
  }

  override fun close() {
    children.firstOrNull()?.let(::dismiss)
    root?.let(::removeCover)
    val listener = layoutListener
    val observer = decor?.viewTreeObserver
    if (listener != null && observer?.isAlive == true)
        observer.removeOnGlobalLayoutListener(listener)
    rootLayoutListener?.let { root?.removeOnLayoutChangeListener(it) }
    (root as? PaymentRootWebView)?.let { tracked ->
      previousClient?.let { tracked.webViewClient = it }
      previousChrome?.let { tracked.webChromeClient = it }
    }
    root = null
    decor = null
    previousClient = null
    previousChrome = null
    layoutListener = null
    rootLayoutListener = null
    coverMessage = null
  }
}
