package app.bucc.api.finance

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** The public cashier redirects to /signIn; its browser app presents the canonical /login route. */
fun officialPaymentLoginBackUrl(raw: String): String? {
  val url = runCatching { Url(raw) }.getOrNull() ?: return null
  if (
      url.protocol != URLProtocol.HTTPS ||
          url.port != 443 ||
          url.host != "pass.cc-pay.cn" ||
          url.encodedPath !in setOf("/login", "/signIn") ||
          url.fragment.isNotEmpty() ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty()
  )
      return null
  if (!url.parameters.names().all { it == "backUrl" || it == "channel" }) return null
  val channels = url.parameters.getAll("channel").orEmpty()
  if (channels.size > 1 || channels.singleOrNull()?.let { it != "BUAASSO" } == true) return null
  return url.parameters.getAll("backUrl")?.singleOrNull()?.takeIf(::isOfficialPaymentUrl)
}

/** Validates the limited service destinations used by the official payment CAS handoff. */
fun isOfficialPaymentCasService(raw: String): Boolean {
  val url = runCatching { Url(raw) }.getOrNull() ?: return false
  if (
      url.protocol != URLProtocol.HTTPS ||
          url.port != 443 ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty()
  )
      return false
  if (isOfficialPaymentUrl(raw)) return true
  if (url.host != "pass.cc-pay.cn" || url.encodedPath != "/login" || url.fragment.isNotEmpty())
      return false
  val backUrls = url.parameters.getAll("backUrl").orEmpty()
  if (backUrls.isNotEmpty() && (backUrls.size != 1 || !isOfficialPaymentUrl(backUrls.single())))
      return false
  val channels = url.parameters.getAll("channel").orEmpty()
  if (channels.size > 1 || channels.singleOrNull()?.let { it != "BUAASSO" } == true) return false
  return url.parameters.names().all { it == "backUrl" || it == "channel" }
}
