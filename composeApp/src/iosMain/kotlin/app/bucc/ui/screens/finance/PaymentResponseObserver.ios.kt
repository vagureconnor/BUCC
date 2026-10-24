@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSSelectorFromString
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKWebView
import platform.darwin.NSObject

internal actual fun paymentResponseObserver(
    onResponse: (String, Int) -> Unit,
): PaymentResponseObserver = IosPaymentResponseObserver(onResponse)

private class IosPaymentResponseObserver(
    private val onResponse: (String, Int) -> Unit,
) : PaymentResponseObserver {
  private var view: NativeWebView? = null
  private var previous: WKNavigationDelegateProtocol? = null

  private val delegate =
      object : NSObject(), WKNavigationDelegateProtocol {
        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationResponse: WKNavigationResponse,
            decisionHandler: (WKNavigationResponsePolicy) -> Unit,
        ) {
          val navigationResponse = decidePolicyForNavigationResponse
          if (navigationResponse.isForMainFrame()) {
            val response = navigationResponse.response as? NSHTTPURLResponse
            val url = response?.URL?.absoluteString
            if (response != null && url != null) {
              runCatching { onResponse(url, response.statusCode.toInt()) }
            }
          }

          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString(
                          "webView:decidePolicyForNavigationResponse:decisionHandler:"
                      ),
                  )
          ) {
            upstream.webView(
                webView,
                decidePolicyForNavigationResponse = navigationResponse,
                decisionHandler = decisionHandler,
            )
          } else {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
          }
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString(
                          "webView:decidePolicyForNavigationAction:decisionHandler:"
                      ),
                  )
          ) {
            upstream.webView(
                webView,
                decidePolicyForNavigationAction = decidePolicyForNavigationAction,
                decisionHandler = decisionHandler,
            )
          } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
          }
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString("webView:didStartProvisionalNavigation:")
                  )
          ) {
            upstream.webView(webView, didStartProvisionalNavigation = didStartProvisionalNavigation)
          }
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(NSSelectorFromString("webView:didCommitNavigation:"))
          ) {
            upstream.webView(webView, didCommitNavigation = didCommitNavigation)
          }
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(NSSelectorFromString("webView:didFinishNavigation:"))
          ) {
            upstream.webView(webView, didFinishNavigation = didFinishNavigation)
          }
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFailProvisionalNavigation: WKNavigation?,
            withError: NSError,
        ) {
          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString("webView:didFailProvisionalNavigation:withError:")
                  )
          ) {
            upstream.webView(
                webView,
                didFailProvisionalNavigation = didFailProvisionalNavigation,
                withError = withError,
            )
          }
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFailNavigation: WKNavigation?,
            withError: NSError,
        ) {
          val upstream = previous
          if (
              upstream != null &&
                  upstream.respondsToSelector(
                      NSSelectorFromString("webView:didFailNavigation:withError:")
                  )
          ) {
            upstream.webView(
                webView,
                didFailNavigation = didFailNavigation,
                withError = withError,
            )
          }
        }
      }

  override fun attach(webView: NativeWebView) {
    close()
    view = webView
    previous = webView.navigationDelegate
    webView.navigationDelegate = delegate
  }

  override fun close() {
    view?.let { attachedView ->
      if (attachedView.navigationDelegate === delegate) {
        attachedView.navigationDelegate = previous
      }
    }
    view = null
    previous = null
  }
}
