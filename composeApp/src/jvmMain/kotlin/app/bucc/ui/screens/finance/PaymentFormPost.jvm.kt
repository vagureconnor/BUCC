package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import org.cef.network.CefPostData
import org.cef.network.CefPostDataElement
import org.cef.network.CefRequest

internal actual fun postOfficialPaymentForm(webView: NativeWebView, url: String, body: ByteArray) {
  require(app.bucc.api.finance.isOfficialInternetSubmissionUrl(url) && body.isNotEmpty())
  val request =
      CefRequest.create().apply {
        setURL(url)
        setMethod("POST")
        firstPartyForCookies = url
        setHeaderMap(
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to "https://${io.ktor.http.Url(url).host}",
            )
        )
        setReferrer(url, CefRequest.ReferrerPolicy.REFERRER_POLICY_DEFAULT)
        postData =
            CefPostData.create().apply {
              addElement(CefPostDataElement.create().apply { setToBytes(body.size, body) })
            }
      }
  webView.loadRequest(request)
}
