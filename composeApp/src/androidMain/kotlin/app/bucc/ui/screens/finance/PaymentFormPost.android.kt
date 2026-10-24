package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView

// Android runtime verification remains deferred.
internal actual fun postOfficialPaymentForm(webView: NativeWebView, url: String, body: ByteArray) {
  require(app.bucc.api.finance.isOfficialInternetSubmissionUrl(url) && body.isNotEmpty())
  webView.postUrl(url, body)
}
