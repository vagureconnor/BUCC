package app.bucc.ui.screens.finance

import app.bucc.api.finance.isPaymentLoginUrl
import io.ktor.http.Url

internal fun routePaymentPopup(url: String, allowZfw: Boolean = false, load: () -> Unit): Boolean {
  if (url == "about:blank" || !isAllowedPaymentNavigation(url, allowZfw)) return false
  load()
  return true
}

internal fun isAllowedPaymentNavigation(inputUrl: String, allowZfw: Boolean = false): Boolean {
  val rawUrl =
      if (allowZfw) app.bucc.api.finance.paymentNavigationOriginalUrl(inputUrl) ?: return false
      else inputUrl
  if (rawUrl == "about:blank") return true
  if (isPaymentLoginUrl(rawUrl)) return false
  val schemeEnd = rawUrl.indexOf("://")
  if (schemeEnd < 0 || !rawUrl.substring(0, schemeEnd).equals("https", ignoreCase = true))
      return false
  val authorityStart = schemeEnd + 3
  val authorityEnd =
      rawUrl.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let {
        if (it < 0) rawUrl.length else it
      }
  val authority = rawUrl.substring(authorityStart, authorityEnd)
  if (authority.isBlank() || authority.contains('@')) return false
  val authorityPort = authority.substringAfterLast(':', missingDelimiterValue = "")
  if (':' in authority && authorityPort != "443") return false
  val host = authority.substringBefore(':').lowercase()
  if (host == "pass.cc-pay.cn") return false
  if (allowZfw && host == "zfw.buaa.edu.cn") {
    val path = rawUrl.substring(authorityEnd).substringBefore('?').substringBefore('#')
    return path !in setOf("/login", "/site/login", "/")
  }
  if (host == "cashier.cc-pay.cn" || host == "mall.cc-pay.cn") return true
  return app.bucc.api.finance.isOfficialProviderPaymentUrl(rawUrl)
}

/** Only the documented official login backUrl can trigger native CAS continuation. */
internal fun officialPaymentSessionTarget(raw: String): String? {
  val original = app.bucc.api.finance.paymentNavigationOriginalUrl(raw) ?: return null
  return app.bucc.api.finance.officialPaymentLoginBackUrl(original)
}

internal const val PAYMENT_RESPONSE_CANDIDATES_SCRIPT =
    """
(function(){
 var qr=Array.from(document.querySelectorAll('a[href*="/qrcode"],iframe[src*="/qrcode"],img[src*="/qrcode"]')).slice(0,64);
 var other=Array.from(document.querySelectorAll('a[href],iframe[src],img[src]')).slice(0,64);
 return JSON.stringify(Array.from(new Set(qr.concat(other).map(x=>x.href||x.src||'').filter(x=>x.length>0&&x.length<=8192))).slice(0,64));
})()
"""

internal fun paymentResponseCandidates(raw: String): List<String> {
  if (raw.length > 131_072) return emptyList()
  var value =
      runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
          ?: return emptyList()
  if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
    value =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value.content) }
            .getOrNull() ?: return emptyList()
  }
  val values = value as? kotlinx.serialization.json.JsonArray ?: return emptyList()
  if (values.size > 64) return emptyList()
  val strings =
      values.map {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: return emptyList()
      }
  return strings.takeIf { list ->
    list.all { it.length <= 8192 } && list.sumOf { it.length } <= 65_536
  } ?: emptyList()
}

/** A browser return must never revisit the one-shot POST response through history. */
internal fun canReturnToPaymentHistory(url: String?, allowZfw: Boolean = false): Boolean {
  if (url == null || url == "about:blank" || !isAllowedPaymentNavigation(url, allowZfw))
      return false
  val original = app.bucc.api.finance.paymentNavigationOriginalUrl(url) ?: return false
  return runCatching {
        Url(original).let {
          it.host != "zfw.buaa.edu.cn" || it.encodedPath !in setOf("/pay/card", "/qrcode")
        }
      }
      .getOrDefault(false)
}
