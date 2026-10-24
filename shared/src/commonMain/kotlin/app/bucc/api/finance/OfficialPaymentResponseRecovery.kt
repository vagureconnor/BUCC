package app.bucc.api.finance

private const val MAX_PAYMENT_RESPONSE_CANDIDATES = 64
private const val MAX_PAYMENT_RESPONSE_CANDIDATE_LENGTH = 8192
private const val MAX_PAYMENT_RESPONSE_CANDIDATE_TOTAL_LENGTH = 65_536

/**
 * Recovers a validated official payment destination from navigation candidates in the current
 * browser response.
 */
fun officialPaymentFromBrowserCandidates(sourceUrl: String, candidates: List<String>): String? =
    officialPaymentTargetsFromBrowserCandidates(sourceUrl, candidates).singleOrNull()

fun officialPaymentTargetsFromBrowserCandidates(
    sourceUrl: String,
    candidates: List<String>,
): List<String> {
  if (candidates.size > MAX_PAYMENT_RESPONSE_CANDIDATES) return emptyList()

  var totalLength = 0
  for (candidate in candidates) {
    if (candidate.length > MAX_PAYMENT_RESPONSE_CANDIDATE_LENGTH) return emptyList()
    totalLength += candidate.length
    if (totalLength > MAX_PAYMENT_RESPONSE_CANDIDATE_TOTAL_LENGTH) return emptyList()
  }

  if (!isOfficialInternetPaymentSourceUrl(sourceUrl)) return emptyList()

  val qrCandidates =
      candidates.filter { candidate ->
        normalizeZfwUrl(candidate, setOf("/qrcode", "/pay/qrcode"))?.let { raw ->
          io.ktor.http.Url(raw).parameters.contains("url")
        } == true
      }
  // A QR carrier is the current order's destination; decorative/navigation links are only a
  // fallback.
  return qrCandidates
      .ifEmpty { candidates }
      .asSequence()
      .mapNotNull { candidate ->
        val zfwCandidate = normalizeZfwUrl(candidate, setOf("/qrcode", "/pay/qrcode")) ?: candidate
        officialCashierFromResponse("", zfwCandidate, ::isSupportedOfficialPaymentTarget)
            ?.takeIf(::isSupportedOfficialPaymentTarget)
      }
      .distinct()
      .toList()
}

/**
 * QR navigation accepts HTTPS payment providers only; native app schemes require user confirmation.
 */
fun isOfficialProviderPaymentUrl(raw: String): Boolean {
  val url = runCatching { io.ktor.http.Url(raw) }.getOrNull() ?: return false
  if (
      url.protocol != io.ktor.http.URLProtocol.HTTPS ||
          url.port != 443 ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty()
  )
      return false
  return when (url.host) {
    "qr.alipay.com" -> Regex("^/[A-Za-z0-9_-]{1,256}$").matches(url.encodedPath)
    "openapi.alipay.com",
    "mapi.alipay.com" -> url.encodedPath == "/gateway.do"
    "wx.tenpay.com" -> url.encodedPath == "/cgi-bin/mmpayweb-bin/checkmweb"
    "gateway.95516.com" -> url.encodedPath == "/gateway/api/frontTransReq.do"
    else -> false
  }
}

fun isSupportedOfficialPaymentTarget(raw: String): Boolean =
    isOfficialPaymentUrl(raw) || isOfficialProviderPaymentUrl(raw)

/**
 * Display-only policy after a validated payment entry: provider redirects may use additional
 * routes.
 */
fun isOfficialPaymentContentUrl(raw: String): Boolean {
  val original = paymentNavigationOriginalUrl(raw) ?: return false
  val url = runCatching { io.ktor.http.Url(original) }.getOrNull() ?: return false
  if (
      url.protocol != io.ktor.http.URLProtocol.HTTPS ||
          url.port != 443 ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty()
  )
      return false
  return url.host in setOf("cashier.cc-pay.cn", "mall.cc-pay.cn") ||
      listOf("alipay.com", "tenpay.com", "weixin.qq.com", "95516.com", "unionpay.com").any {
        url.host == it || url.host.endsWith(".$it")
      }
}
