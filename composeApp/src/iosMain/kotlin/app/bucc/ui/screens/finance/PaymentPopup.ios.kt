@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.Foundation.*
import platform.UIKit.*
import platform.WebKit.*
import platform.darwin.NSObject

internal actual fun paymentPopupHandler(
    allowZfw: Boolean,
    onPaymentAppLaunchStarted: () -> Unit,
    onPopup: (String, Boolean) -> Unit,
): PaymentPopupHandler = IosPaymentWindows(allowZfw, onPaymentAppLaunchStarted, onPopup)

private class IosPaymentWindows(
    private val allowZfw: Boolean,
    private val onPaymentAppLaunchStarted: () -> Unit,
    private val onPopup: (String, Boolean) -> Unit,
) : PaymentPopupHandler {
  private var root: WKWebView? = null
  private var previous: WKUIDelegateProtocol? = null
  private var previousNavigation: WKNavigationDelegateProtocol? = null
  private var gate = PaymentRequestGate(allowZfw)
  private val children = mutableListOf<WKWebView>()
  private val covers = mutableMapOf<WKWebView, UIView>()
  private var coverMessage: String? = null

  private fun activeView(): WKWebView? = children.lastOrNull() ?: root

  override fun activeUrl(): String? = activeView()?.URL?.absoluteString

  private fun openPaymentApp(rawUrl: String, onComplete: (Boolean) -> Unit) {
    val target = paymentAppLaunchTarget(rawUrl)
    val url = target?.let { NSURL.URLWithString(it) }
    if (url == null) {
      onComplete(false)
      return
    }
    onPaymentAppLaunchStarted()
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>()) { opened ->
      onComplete(opened)
    }
  }

  private fun hasQrPageInHistory(view: WKWebView): Boolean =
      allowZfw &&
          paymentQrSourceForCurrentUrl(
              view.backForwardList.backItem?.URL?.absoluteString.orEmpty()
          ) != null

  private fun releaseCompletedTargetCover(view: WKWebView) {
    val hasRecoverableQrPage =
        allowZfw &&
            (hasQrPageInHistory(view) ||
                (listOfNotNull(root) + children).any { other ->
                  other !== view &&
                      paymentQrSourceForCurrentUrl(other.URL?.absoluteString.orEmpty()) != null
                })
    if (hasRecoverableQrPage) removeCover(view) else coverPaymentResponse(null)
  }

  private val navigation =
      object : NSObject(), WKNavigationDelegateProtocol {
        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
          val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
          val decision =
              gate.decision(
                  url,
                  decidePolicyForNavigationAction.request.HTTPMethod.orEmpty(),
                  isRoot =
                      webView === root &&
                          decidePolicyForNavigationAction.targetFrame?.mainFrame == true,
              )
          if (decision == 2) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            openPaymentApp(url) { opened -> onPopup(url, opened) }
            return
          }
          val allowed = decision == 1
          if (!allowed) onPopup(url, false)
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              allowed &&
                  upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString(
                          "webView:decidePolicyForNavigationAction:decisionHandler:"
                      )
                  )
          ) {
            upstream.webView(
                webView,
                decidePolicyForNavigationAction = decidePolicyForNavigationAction,
                decisionHandler = decisionHandler,
            )
          } else {
            decisionHandler(
                if (allowed) WKNavigationActionPolicy.WKNavigationActionPolicyAllow
                else WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            )
          }
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationResponse: WKNavigationResponse,
            decisionHandler: (WKNavigationResponsePolicy) -> Unit,
        ) {
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString(
                          "webView:decidePolicyForNavigationResponse:decisionHandler:"
                      )
                  )
          ) {
            upstream.webView(
                webView,
                decidePolicyForNavigationResponse = decidePolicyForNavigationResponse,
                decisionHandler = decisionHandler,
            )
          } else decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString("webView:didStartProvisionalNavigation:")
                  )
          )
              upstream.webView(
                  webView,
                  didStartProvisionalNavigation = didStartProvisionalNavigation,
              )
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
          val url = webView.URL?.absoluteString
          if (
              webView === activeView() &&
                  allowZfw &&
                  !url.isNullOrBlank() &&
                  paymentQrSourceForCurrentUrl(url) != null
          ) {
            coverMessage?.let { ensureCover(webView, it) }
          }
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              upstream != null &&
                  upstream.respondsToSelector(NSSelectorFromString("webView:didCommitNavigation:"))
          )
              upstream.webView(webView, didCommitNavigation = didCommitNavigation)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
          if (webView === activeView()) {
            val url = webView.URL?.absoluteString
            if (!url.isNullOrBlank()) {
              if (allowZfw && paymentQrSourceForCurrentUrl(url) != null) {
                coverMessage?.let { ensureCover(webView, it) }
              } else if (app.bucc.api.finance.isOfficialPaymentContentUrl(url)) {
                releaseCompletedTargetCover(webView)
              }
              onPopup(url, true)
            }
          }
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              upstream != null &&
                  upstream.respondsToSelector(NSSelectorFromString("webView:didFinishNavigation:"))
          )
              upstream.webView(webView, didFinishNavigation = didFinishNavigation)
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFailProvisionalNavigation: WKNavigation?,
            withError: NSError,
        ) {
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString("webView:didFailProvisionalNavigation:withError:")
                  )
          )
              upstream.webView(
                  webView,
                  didFailProvisionalNavigation = didFailProvisionalNavigation,
                  withError = withError,
              )
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFailNavigation: WKNavigation?,
            withError: NSError,
        ) {
          val upstream = previousNavigation.takeIf { webView === root }
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString("webView:didFailNavigation:withError:")
                  )
          )
              upstream.webView(
                  webView,
                  didFailNavigation = didFailNavigation,
                  withError = withError,
              )
        }
      }
  private val delegate =
      object : NSObject(), WKUIDelegateProtocol {
        override fun webView(
            webView: WKWebView,
            createWebViewWithConfiguration: WKWebViewConfiguration,
            forNavigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures,
        ): WKWebView? {
          val url = forNavigationAction.request.URL?.absoluteString ?: "about:blank"
          val decision = gate.decision(url, forNavigationAction.request.HTTPMethod ?: "GET", false)
          if (decision == 2) {
            openPaymentApp(url) { opened -> onPopup(url, opened) }
            return null
          }
          val allowed = decision == 1
          onPopup(url, allowed)
          if (!allowed) return null
          // Returning the real child preserves the original request, opener and POST semantics.
          return createChild(createWebViewWithConfiguration)
        }

        override fun webViewDidClose(webView: WKWebView) {
          dismiss(webView)
        }
      }

  private fun createChild(configuration: WKWebViewConfiguration): WKWebView? {
    val parent = root ?: return null
    val container = parent.superview ?: return null
    val child = WKWebView(frame = parent.frame, configuration = configuration)
    child.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    child.UIDelegate = delegate
    child.navigationDelegate = navigation
    (children.lastOrNull() ?: parent).hidden = true
    children += child
    container.addSubview(child)
    coverMessage?.let { ensureCover(child, it) }
    return child
  }

  private fun ensureCover(view: WKWebView, message: String) {
    val cover =
        covers.getOrPut(view) {
          UIView(frame = view.bounds)
              .apply {
                backgroundColor = UIColor.colorWithRed(0.976, 0.980, 0.984, 1.0)
                opaque = true
                autoresizingMask =
                    UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                setUserInteractionEnabled(true)
                val label =
                    UILabel(frame = bounds).apply {
                      text = message
                      textColor = UIColor.colorWithRed(0.137, 0.165, 0.196, 1.0)
                      textAlignment = NSTextAlignmentCenter
                      numberOfLines = 0
                      autoresizingMask =
                          UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                    }
                addSubview(label)
              }
              .also(view::addSubview)
        }
    (cover.subviews.firstOrNull() as? UILabel)?.text = message
    cover.setFrame(view.bounds)
    view.bringSubviewToFront(cover)
  }

  private fun removeCover(view: WKWebView) {
    covers.remove(view)?.removeFromSuperview()
  }

  private fun dismiss(child: WKWebView) {
    val index = children.indexOf(child)
    if (index < 0) return
    children.drop(index).reversed().forEach {
      it.stopLoading()
      removeCover(it)
      it.UIDelegate = null
      it.navigationDelegate = null
      it.removeFromSuperview()
    }
    while (children.size > index) children.removeAt(children.lastIndex)
    (children.lastOrNull() ?: root)?.hidden = false
    activeView()?.let { parent ->
      if (parent.URL?.absoluteString?.let(::paymentQrSourceForCurrentUrl) != null) {
        coverPaymentResponse("支付页面已关闭，请返回充值页；不会重发充值请求。")
      }
    }
  }

  override fun open(url: String): Boolean {
    if (!gate.allows(url, "GET", false)) {
      onPopup(url, false)
      return false
    }
    val parent = root ?: return false
    val child = createChild(parent.configuration) ?: return false
    child.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(url) ?: return false))
    onPopup(url, true)
    return true
  }

  override fun goBack(): Boolean {
    val active = activeView() ?: return false
    if (
        active.canGoBack &&
            canReturnToPaymentHistory(
                active.backForwardList.backItem?.URL?.absoluteString,
                allowZfw,
            )
    ) {
      active.goBack()
      return true
    }
    if (children.isNotEmpty()) {
      val parentUrl = (children.dropLast(1).lastOrNull() ?: root)?.URL?.absoluteString
      if (parentUrl != null && paymentQrSourceForCurrentUrl(parentUrl) != null) return false
      dismiss(active)
      return true
    }
    return false
  }

  override suspend fun evaluateActive(script: String): String? {
    val view = activeView() ?: return null
    val result =
        withContext(Dispatchers.Main.immediate) {
          if (activeView() !== view) return@withContext null
          suspendCancellableCoroutine<String?> { continuation ->
            view.evaluateJavaScript(script) { value, _ ->
              if (continuation.isActive && activeView() === view)
                  continuation.resume(value?.toString())
            }
          }
        }
    return if (activeView() === view) result else null
  }

  override suspend fun renderedQrPayloads(sourceUrl: String): List<String> {
    val view = activeView() ?: return emptyList()
    fun stillOnSource() =
        activeView() === view &&
            activeUrl() == sourceUrl &&
            paymentQrSourceForCurrentUrl(sourceUrl) != null
    if (!stillOnSource()) return emptyList()
    val snapshot =
        withContext(Dispatchers.Main.immediate) {
          if (!stillOnSource()) return@withContext null
          suspendCancellableCoroutine<UIImage?> { continuation ->
            val configuration =
                WKSnapshotConfiguration().apply { snapshotWidth = NSNumber(int = 1024) }
            view.takeSnapshotWithConfiguration(configuration) { image, _ ->
              if (continuation.isActive) continuation.resume(image)
            }
          }
        } ?: return emptyList()
    if (!stillOnSource()) return emptyList()
    val payloads =
        withContext(Dispatchers.Default) {
          runCatching {
                val cgImage = snapshot.CGImage ?: return@runCatching emptyList()
                val image = CIImage.imageWithCGImage(cgImage)
                val detector =
                    CIDetector.detectorOfType(
                        CIDetectorTypeQRCode,
                        context = null,
                        options = mapOf(CIDetectorAccuracy to CIDetectorAccuracyHigh),
                    ) ?: return@runCatching emptyList()
                detector
                    .featuresInImage(image)
                    .mapNotNull {
                      (it as? CIQRCodeFeature)?.messageString?.takeIf(String::isNotBlank)
                    }
                    .distinct()
              }
              .getOrDefault(emptyList())
        }
    return if (stillOnSource()) payloads else emptyList()
  }

  override fun coverPaymentResponse(message: String?) {
    coverMessage = message
    val views = listOfNotNull(root) + children
    if (message == null) views.forEach(::removeCover)
    else views.forEach { ensureCover(it, message) }
  }

  override fun attach(webView: NativeWebView) {
    close()
    root = webView
    previous = webView.UIDelegate
    previousNavigation = webView.navigationDelegate
    webView.navigationDelegate = navigation
    webView.UIDelegate = delegate
    webView.configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
    coverMessage?.let { ensureCover(webView, it) }
  }

  override fun close() {
    children.firstOrNull()?.let(::dismiss)
    root?.let(::removeCover)
    root?.let {
      if (it.UIDelegate === delegate) it.UIDelegate = previous
      if (it.navigationDelegate === navigation) it.navigationDelegate = previousNavigation
    }
    root = null
    previous = null
    previousNavigation = null
    gate = PaymentRequestGate(allowZfw)
    coverMessage = null
  }
}
