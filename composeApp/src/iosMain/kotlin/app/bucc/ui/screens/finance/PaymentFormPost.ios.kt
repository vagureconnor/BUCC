@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*

internal actual fun postOfficialPaymentForm(webView: NativeWebView, url: String, body: ByteArray) {
  require(app.bucc.api.finance.isOfficialInternetSubmissionUrl(url) && body.isNotEmpty())
  val request = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
  request.HTTPMethod = "POST"
  request.HTTPBody =
      body.usePinned { NSData.create(bytes = it.addressOf(0), length = body.size.toULong()) }
  request.setValue(
      "application/x-www-form-urlencoded; charset=UTF-8",
      forHTTPHeaderField = "Content-Type",
  )
  request.setValue("https://${io.ktor.http.Url(url).host}", forHTTPHeaderField = "Origin")
  request.setValue(url, forHTTPHeaderField = "Referer")
  webView.loadRequest(request)
}
