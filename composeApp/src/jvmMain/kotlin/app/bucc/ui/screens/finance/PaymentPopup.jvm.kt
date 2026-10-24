package app.bucc.ui.screens.finance

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.multiplatform.webview.web.NativeWebView
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.net.URI
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.handler.*
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.network.CefRequest

internal actual fun paymentPopupHandler(
    allowZfw: Boolean,
    onPaymentAppLaunchStarted: () -> Unit,
    onPopup: (String, Boolean) -> Unit,
): PaymentPopupHandler = JvmPaymentWindows(allowZfw, onPaymentAppLaunchStarted, onPopup)

private class JvmPaymentWindows(
    private val allowZfw: Boolean,
    private val onPaymentAppLaunchStarted: () -> Unit,
    private val onPopup: (String, Boolean) -> Unit,
) : PaymentPopupHandler {
  private var root: NativeWebView? = null
  private var previousLifeSpan = emptyList<CefLifeSpanHandler>()
  private var previousRequest: CefRequestHandler? = null
  private var previousLoad: CefLoadHandler? = null
  private val children =
      java.util.concurrent.CopyOnWriteArrayList<Pair<NativeWebView, KCEFClient>>()
  private val pendingRequests = java.util.concurrent.ConcurrentHashMap<NativeWebView, CefRequest>()
  private val history = java.util.concurrent.ConcurrentHashMap<Int, MutableList<String>>()
  private val routers = ConcurrentHashMap<NativeWebView, CefMessageRouter>()
  private val pendingEvaluations = ConcurrentHashMap<Long, (String?) -> Unit>()
  private val evaluationIds = AtomicLong()
  private val covers = ConcurrentHashMap<NativeWebView, JLayeredPane>()
  private val coverPanels = ConcurrentHashMap<NativeWebView, JPanel>()
  private var coverMessage: String? = null
  private var gate = PaymentRequestGate(allowZfw)

  private fun openPaymentApp(url: String): Boolean =
      runCatching {
            val target = paymentAppLaunchTarget(url) ?: return false
            if (
                !Desktop.isDesktopSupported() ||
                    !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            )
                return false
            onPaymentAppLaunchStarted()
            Desktop.getDesktop().browse(URI(target))
            true
          }
          .getOrDefault(false)

  private val lifespan =
      object : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(
            browser: CefBrowser?,
            frame: CefFrame?,
            targetUrl: String?,
            targetFrameName: String?,
        ): Boolean {
          val url = targetUrl?.ifEmpty { "about:blank" } ?: "about:blank"
          open(url)
          return true // The child is embedded below; never open an OS browser/window.
        }

        override fun onAfterCreated(browser: CefBrowser?) {
          if (browser?.identifier == root?.identifier)
              previousLifeSpan.forEach { it.onAfterCreated(browser) }
          if (browser != null) {
            pendingRequests.keys
                .firstOrNull { it.identifier == browser.identifier }
                ?.let { child -> pendingRequests.remove(child)?.let { browser.loadRequest(it) } }
          }
        }

        override fun onAfterParentChanged(browser: CefBrowser?) {
          if (browser?.identifier == root?.identifier)
              previousLifeSpan.forEach { it.onAfterParentChanged(browser) }
        }

        override fun onBeforeClose(browser: CefBrowser?) {
          if (browser?.identifier == root?.identifier)
              previousLifeSpan.forEach { it.onBeforeClose(browser) }
        }

        override fun doClose(browser: CefBrowser?): Boolean {
          if (browser?.identifier == root?.identifier) {
            var handled = false
            previousLifeSpan.forEach { handled = it.doClose(browser) || handled }
            return handled
          }
          children
              .firstOrNull { it.first.identifier == browser?.identifier }
              ?.let { SwingUtilities.invokeLater { dismiss(it.first) } }
          return true
        }
      }
  private val requestHandler =
      object : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean {
          val url = request?.url ?: return true
          val decision =
              gate.decision(
                  url,
                  request.method,
                  isRoot = browser?.identifier == root?.identifier && frame?.isMain == true,
              )
          if (decision == 2) {
            onPopup(url, openPaymentApp(url))
            return true
          }
          val allowed = decision == 1
          if (!allowed) {
            onPopup(url, false)
            return true
          }
          if (frame?.name?.startsWith("bucc-payment-window-") == true && url != "about:blank") {
            val copy = CefRequest.create()
            val headers = mutableMapOf<String, String>()
            request.getHeaderMap(headers)
            copy.set(url, request.method, request.postData, headers)
            copy.setReferrer(request.referrerURL, request.referrerPolicy)
            copy.firstPartyForCookies = request.firstPartyForCookies
            openRequest(url, copy)
            browser?.executeJavaScript(
                "(function(){var a=window.__buccBlankPaymentWindows||[];while(a.length)a.pop().remove();})()",
                browser.url,
                0,
            )
            return true
          }
          return if (browser?.identifier == root?.identifier)
              previousRequest?.onBeforeBrowse(browser, frame, request, userGesture, isRedirect)
                  ?: false
          else false
        }
      }
  private val loadHandler =
      object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
          if (browser != null && frame?.isMain == true) {
            val entries =
                history.getOrPut(browser.identifier) {
                  java.util.Collections.synchronizedList(mutableListOf())
                }
            if (entries.lastOrNull() != frame.url) entries += frame.url
          }
          if (browser?.identifier == root?.identifier)
              previousLoad?.onLoadEnd(browser, frame, httpStatusCode)
          if (
              browser != null &&
                  frame?.isMain == true &&
                  app.bucc.api.finance.isOfficialPaymentContentUrl(frame.url)
          )
              (listOfNotNull(root) + children.map { it.first })
                  .firstOrNull { it.identifier == browser.identifier }
                  ?.let(::releaseCompletedTargetCover)
          // Old JCEF has no popup transport. A blank script-created window uses an embedded
          // frame so its real Window/document/form survive until its first navigation.
          frame?.executeJavaScript(BLANK_PAYMENT_WINDOW_SCRIPT, frame.url, 0)
        }

        override fun onLoadingStateChange(
            browser: CefBrowser?,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ) {
          if (browser?.identifier == root?.identifier)
              previousLoad?.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward)
        }

        override fun onLoadStart(
            browser: CefBrowser?,
            frame: CefFrame?,
            transitionType: org.cef.network.CefRequest.TransitionType?,
        ) {
          if (frame?.isMain == true) cancelEvaluations()
          frame?.executeJavaScript(BLANK_PAYMENT_WINDOW_SCRIPT, frame.url, 0)
          if (browser?.identifier == root?.identifier)
              previousLoad?.onLoadStart(browser, frame, transitionType)
        }

        override fun onLoadError(
            browser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
          if (browser?.identifier == root?.identifier)
              previousLoad?.onLoadError(browser, frame, errorCode, errorText, failedUrl)
        }
      }

  override fun open(url: String): Boolean = openRequest(url, null)

  private fun openRequest(url: String, request: CefRequest?): Boolean {
    val decision = gate.decision(url, request?.method ?: "GET", isRoot = false)
    if (decision == 2) {
      onPopup(url, openPaymentApp(url))
      return false
    }
    if (decision != 1) {
      onPopup(url, false)
      return false
    }
    val parent = root ?: return false
    SwingUtilities.invokeLater {
      val activeView = children.lastOrNull()?.first ?: parent
      val activeCover = covers[activeView]
      val activeComponent = activeCover ?: activeView.uiComponent
      val host = activeComponent.parent ?: return@invokeLater
      val client = KCEF.newClientBlocking()
      client.addLifeSpanHandler(lifespan)
      client.addRequestHandler(requestHandler)
      client.addLoadHandler(loadHandler)
      val child =
          client.createBrowser(
              if (request == null) url else "about:blank",
              CefRendering.DEFAULT,
              false,
              parent.requestContext,
          )
      val active = children.lastOrNull()?.first ?: parent
      host.remove(activeCover ?: active.uiComponent)
      children += child to client
      host.add(child.uiComponent, BorderLayout.CENTER)
      host.revalidate()
      host.repaint()
      if (request != null) pendingRequests[child] = request
      child.createImmediately()
      installRouter(child)
      coverMessage?.let { ensureCover(child, it) }
    }
    onPopup(url, true)
    return true
  }

  private fun dismiss(child: NativeWebView) {
    val index = children.indexOfFirst { it.first === child }
    if (index < 0) return
    val current = children.last().first
    val host = (covers[current] ?: current.uiComponent).parent
    children.drop(index).reversed().forEach { (view, client) ->
      pendingRequests.remove(view)
      routers.remove(view)?.let {
        view.client.removeMessageRouter(it)
        it.dispose()
      }
      cancelEvaluations()
      coverPanels.remove(view)
      host?.remove(covers.remove(view) ?: view.uiComponent)
      view.stopLoad()
      view.close(true)
      client.dispose()
    }
    while (children.size > index) children.removeAt(children.lastIndex)
    (children.lastOrNull()?.first ?: root)?.let {
      host?.add(covers[it] ?: it.uiComponent, BorderLayout.CENTER)
    }
    host?.revalidate()
    host?.repaint()
  }

  override fun goBack(): Boolean {
    val active = children.lastOrNull()?.first ?: root ?: return false
    val entries = history[active.identifier]
    val previousUrl = entries?.dropLast(1)?.lastOrNull()
    if (previousUrl != null && paymentQrSourceForCurrentUrl(previousUrl) != null) return false
    if (active.canGoBack() && canReturnToPaymentHistory(previousUrl, allowZfw)) {
      entries?.removeAt(entries.lastIndex)
      active.goBack()
      return true
    }
    if (children.isNotEmpty()) {
      SwingUtilities.invokeLater { dismiss(active) }
      return true
    }
    return false
  }

  override fun attach(webView: NativeWebView) {
    close()
    root = webView
    val client = webView.client.paymentNativeClient()
    previousRequest = client.paymentRequestHandler()
    previousLoad = client.paymentLoadHandler()
    previousLifeSpan = client.installPaymentLifeSpanHandler(lifespan)
    client.replacePaymentRequestHandler(requestHandler)
    client.replacePaymentLoadHandler(loadHandler)
    installRouter(webView)
    coverMessage?.let { ensureCover(webView, it) }
  }

  override fun activeUrl(): String? = (children.lastOrNull()?.first ?: root)?.url

  override suspend fun evaluateActive(script: String): String? {
    val view = children.lastOrNull()?.first ?: root ?: return null
    val source = view.url ?: return null
    return suspendCancellableCoroutine { continuation ->
      val token = evaluationIds.incrementAndGet()
      pendingEvaluations[token] = { value ->
        if (continuation.isActive)
            continuation.resume(
                if ((children.lastOrNull()?.first ?: root) === view && view.url == source)
                    decodeJavascriptResult(value)
                else null
            )
      }
      continuation.invokeOnCancellation { pendingEvaluations.remove(token) }
      val wrapped =
          "(function(){var t='$token';try{var v=(function(){$script})();cefQuery({request:t+'\\n'+JSON.stringify(v),onSuccess:function(){},onFailure:function(){}})}catch(e){cefQuery({request:t+'\\nnull',onSuccess:function(){},onFailure:function(){}})}})();"
      view.executeJavaScript(wrapped, source, 0)
    }
  }

  override suspend fun renderedQrPayloads(sourceUrl: String): List<String> {
    val view = children.lastOrNull()?.first ?: root ?: return emptyList()
    fun stillOnSource() =
        (children.lastOrNull()?.first ?: root) === view &&
            activeUrl() == sourceUrl &&
            paymentQrSourceForCurrentUrl(sourceUrl) != null
    if (!stillOnSource()) return emptyList()
    val image =
        try {
          view.createScreenshot(false).awaitImage()
        } catch (_: Exception) {
          null
        } ?: return emptyList()
    try {
      if (!stillOnSource()) return emptyList()
      val bounded = scaleScreenshot(image, 1024)
      try {
        val payloads = withContext(Dispatchers.Default) { decodePaymentQrCodes(bounded) }
        return if (stillOnSource()) payloads else emptyList()
      } finally {
        if (bounded !== image) bounded.flush()
      }
    } finally {
      image.flush()
    }
  }

  override fun coverPaymentResponse(message: String?) {
    coverMessage = message
    (listOfNotNull(root) + children.map { it.first }).distinct().forEach { view ->
      if (message == null) removeCover(view) else ensureCover(view, message)
    }
  }

  private fun installRouter(view: NativeWebView) {
    if (routers.containsKey(view)) return
    val handler =
        object : CefMessageRouterHandlerAdapter() {
          override fun onQuery(
              browser: CefBrowser?,
              frame: CefFrame?,
              queryId: Long,
              request: String?,
              persistent: Boolean,
              callback: org.cef.callback.CefQueryCallback?,
          ): Boolean {
            if (
                persistent ||
                    browser?.identifier != view.identifier ||
                    frame?.isMain != true ||
                    (children.lastOrNull()?.first ?: root) !== view ||
                    browser.url != activeUrl()
            )
                return false
            val text = request ?: return false
            val split = text.indexOf('\n')
            if (split <= 0) return false
            val token = text.substring(0, split).toLongOrNull() ?: return false
            pendingEvaluations.remove(token)?.invoke(text.substring(split + 1))
            callback?.success("")
            return true
          }
        }
    val router = CefMessageRouter.create(handler)
    view.client.addMessageRouter(router)
    routers[view] = router
  }

  private fun ensureCover(view: NativeWebView, message: String) {
    SwingUtilities.invokeLater {
      if (view.isClosed) return@invokeLater
      var layer = covers[view]
      if (layer == null) {
        val host = view.uiComponent.parent ?: return@invokeLater
        host.remove(view.uiComponent)
        layer =
            object : JLayeredPane() {
              override fun doLayout() {
                components.forEach { it.setBounds(0, 0, width, height) }
              }

              override fun getPreferredSize() =
                  Dimension(
                      view.uiComponent.width.coerceAtLeast(1),
                      view.uiComponent.height.coerceAtLeast(1),
                  )
            }
        layer.add(view.uiComponent, JLayeredPane.DEFAULT_LAYER)
        val panel =
            JPanel(BorderLayout()).apply {
              isOpaque = true
              background = Color.WHITE
              add(
                  JLabel(message).apply { horizontalAlignment = JLabel.CENTER },
                  BorderLayout.CENTER,
              )
            }
        coverPanels[view] = panel
        layer.add(panel, JLayeredPane.PALETTE_LAYER)
        host.add(layer, BorderLayout.CENTER)
        covers[view] = layer
        host.revalidate()
        host.repaint()
      } else {
        coverPanels[view]?.apply {
          removeAll()
          add(JLabel(message).apply { horizontalAlignment = JLabel.CENTER }, BorderLayout.CENTER)
          revalidate()
          repaint()
        }
      }
    }
  }

  private fun removeCover(view: NativeWebView) {
    SwingUtilities.invokeLater {
      val layer = covers.remove(view) ?: return@invokeLater
      coverPanels.remove(view)
      val host = layer.parent ?: return@invokeLater
      host.remove(layer)
      host.add(view.uiComponent, BorderLayout.CENTER)
      host.revalidate()
      host.repaint()
    }
  }

  private fun releaseCompletedTargetCover(view: NativeWebView) {
    val hasQrParent =
        (listOfNotNull(root) + children.map { it.first }).any {
          it !== view && paymentQrSourceForCurrentUrl(it.url.orEmpty()) != null
        }
    if (hasQrParent) removeCover(view) else coverPaymentResponse(null)
  }

  private fun cancelEvaluations() {
    pendingEvaluations.values.toList().forEach { it(null) }
    pendingEvaluations.clear()
  }

  override fun close() {
    children.firstOrNull()?.let { dismiss(it.first) }
    root?.client?.let { client ->
      client.paymentNativeClient().apply {
        restorePaymentLifeSpanHandlers(lifespan, previousLifeSpan)
        replacePaymentRequestHandler(previousRequest)
        replacePaymentLoadHandler(previousLoad)
      }
    }
    routers.forEach { (view, router) ->
      view.client.removeMessageRouter(router)
      router.dispose()
    }
    routers.clear()
    cancelEvaluations()
    covers.keys.toList().forEach(::removeCover)
    coverMessage = null
    root = null
    previousLifeSpan = emptyList()
    previousRequest = null
    previousLoad = null
    pendingRequests.clear()
    history.clear()
    gate = PaymentRequestGate(allowZfw)
  }
}

internal const val BLANK_PAYMENT_WINDOW_SCRIPT =
    """
(function(){
 if(window.__buccPaymentOpenInstalled)return;
 window.__buccPaymentOpenInstalled=true;
 var original=window.open;
 var named=Object.create(null);
 function blank(name){
   if(name && named[name] && named[name].isConnected)return named[name];
   var f=document.createElement('iframe');
   f.name='bucc-payment-window-'+Date.now()+'-'+Math.random();
   f.src='about:blank';f.style.cssText='position:fixed;inset:0;width:100%;height:100%;border:0;background:white;z-index:2147483647';
   document.documentElement.appendChild(f);
   (window.__buccBlankPaymentWindows||(window.__buccBlankPaymentWindows=[])).push(f);
   if(name)named[name]=f;
   return f;
 }
 window.open=function(url,name,features){
   if(url && url!=='about:blank')return original.call(window,url,name,features);
   return blank(name).contentWindow;
 };
 function formTarget(form){
   var target=form.target;
   if(target==='_blank' || named[target])form.target=blank(target==='_blank'?null:target).name;
 }
 document.addEventListener('submit',function(event){if(event.target)formTarget(event.target);},true);
 var originalSubmit=HTMLFormElement.prototype.submit;
 HTMLFormElement.prototype.submit=function(){formTarget(this);return originalSubmit.apply(this,arguments);};
})()
"""

private suspend fun java.util.concurrent.CompletableFuture<BufferedImage>.awaitImage():
    BufferedImage? = suspendCancellableCoroutine { continuation ->
  whenComplete { image, error ->
    if (continuation.isActive) continuation.resume(if (error == null) image else null)
  }
  continuation.invokeOnCancellation { cancel(true) }
}

private fun decodeJavascriptResult(value: String?): String? {
  if (value == null) return null
  val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value) }.getOrNull()
  return if (json is kotlinx.serialization.json.JsonPrimitive && json.isString) json.content
  else value
}

internal fun scaleScreenshot(image: BufferedImage, maxEdge: Int): BufferedImage {
  val scale = minOf(1.0, maxEdge.toDouble() / maxOf(image.width, image.height))
  val width = (image.width * scale).toInt().coerceAtLeast(1)
  val height = (image.height * scale).toInt().coerceAtLeast(1)
  if (width == image.width && height == image.height) return image
  return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { scaled ->
    val graphics = scaled.createGraphics()
    try {
      graphics.drawImage(image, 0, 0, width, height, null)
    } finally {
      graphics.dispose()
    }
  }
}

internal fun decodePaymentQrCodes(image: BufferedImage): List<String> {
  val pixels = IntArray(image.width * image.height)
  image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
  val source = RGBLuminanceSource(image.width, image.height, pixels)
  val hints =
      EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        put(DecodeHintType.TRY_HARDER, true)
      }
  val reader = MultiFormatReader().apply { setHints(hints) }
  return try {
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
