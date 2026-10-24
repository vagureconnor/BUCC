package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import dev.datlag.kcef.KCEFClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

internal actual fun paymentResponseObserver(
    onResponse: (String, Int) -> Unit
): PaymentResponseObserver = JvmPaymentResponseObserver(onResponse)

private class JvmPaymentResponseObserver(
    private val onResponse: (String, Int) -> Unit,
) : PaymentResponseObserver {
  private var client: KCEFClient? = null
  private var previousHandler: CefRequestHandler? = null
  private var installedHandler: CefRequestHandler? = null

  override fun attach(webView: NativeWebView) {
    close()

    val cefClient = webView.client
    val previous = currentRequestHandler(cefClient)
    val delegate = previous ?: object : CefRequestHandlerAdapter() {}
    val observerHandler =
        object : CefRequestHandler by delegate {
          override fun getResourceRequestHandler(
              browser: CefBrowser?,
              frame: CefFrame?,
              request: CefRequest?,
              isNavigation: Boolean,
              isDownload: Boolean,
              requestInitiator: String?,
              disableDefaultHandling: BoolRef?,
          ): CefResourceRequestHandler? {
            val originalResourceHandler =
                delegate.getResourceRequestHandler(
                    browser,
                    frame,
                    request,
                    isNavigation,
                    isDownload,
                    requestInitiator,
                    disableDefaultHandling,
                )
            if (request?.resourceType != CefRequest.ResourceType.RT_MAIN_FRAME)
                return originalResourceHandler
            val resourceHandler =
                originalResourceHandler ?: object : CefResourceRequestHandlerAdapter() {}

            return object : CefResourceRequestHandler by resourceHandler {
              override fun onResourceResponse(
                  browser: CefBrowser?,
                  frame: CefFrame?,
                  request: CefRequest?,
                  response: CefResponse?,
              ): Boolean {
                val continueLoading =
                    resourceHandler.onResourceResponse(browser, frame, request, response)
                val url = request?.url
                val status = response?.status
                if (
                    request?.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME &&
                        url != null &&
                        (url.startsWith("http://", ignoreCase = true) ||
                            url.startsWith("https://", ignoreCase = true)) &&
                        status != null &&
                        status in 100..599
                ) {
                  runCatching { onResponse(url, status) }
                }
                return continueLoading
              }
            }
          }
        }

    client = cefClient
    previousHandler = previous
    installedHandler = observerHandler
    cefClient.paymentNativeClient().replacePaymentRequestHandler(observerHandler)
  }

  override fun close() {
    val attachedClient = client
    val observerHandler = installedHandler
    if (
        attachedClient != null &&
            observerHandler != null &&
            currentRequestHandler(attachedClient) === observerHandler
    ) {
      if (previousHandler == null) {
        attachedClient.paymentNativeClient().replacePaymentRequestHandler(null)
      } else {
        attachedClient.paymentNativeClient().replacePaymentRequestHandler(previousHandler)
      }
    }
    client = null
    previousHandler = null
    installedHandler = null
  }

  private fun currentRequestHandler(client: KCEFClient): CefRequestHandler? =
      client.paymentNativeClient().paymentRequestHandler()
}
