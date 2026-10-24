package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView

// Android runtime verification is deferred.
internal actual fun paymentResponseObserver(
    onResponse: (String, Int) -> Unit
): PaymentResponseObserver =
    object : PaymentResponseObserver {
      override fun attach(webView: NativeWebView) = Unit

      override fun close() = Unit
    }
