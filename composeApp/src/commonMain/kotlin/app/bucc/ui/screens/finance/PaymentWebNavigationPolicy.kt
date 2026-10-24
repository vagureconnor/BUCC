package app.bucc.ui.screens.finance

import app.bucc.api.finance.isOfficialPaymentCasService
import app.bucc.api.finance.paymentNavigationOriginalUrl
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow

private val paymentAppSchemes =
    setOf("alipay", "alipays", "weixin", "wxpay", "weixinpay", "tenpay", "uppay", "unionpay")

/**
 * Returns only known payment-app links, including the official Alipay wrappers and Android intent
 * form.
 */
internal fun paymentAppLaunchTarget(raw: String): String? {
  if (raw.length > 16384) return null
  val intentMarker = raw.indexOf("#Intent;")
  val candidate =
      when {
        raw.startsWith("intent://") && intentMarker > 0 && raw.endsWith(";end") -> {
          val intentOptions =
              raw.substring(intentMarker + "#Intent;".length, raw.length - ";end".length)
          val schemes =
              Regex("(?:^|;)scheme=([a-zA-Z][a-zA-Z0-9+.-]*)(?:;|$)")
                  .findAll(intentOptions)
                  .map { it.groupValues[1].lowercase() }
                  .toList()
          val scheme = schemes.singleOrNull()?.takeIf(paymentAppSchemes::contains) ?: return null
          "$scheme:${raw.substring("intent:".length, intentMarker)}"
        }
        else -> raw
      }
  val url = runCatching { Url(candidate) }.getOrNull() ?: return null
  if (url.protocol.name in paymentAppSchemes) {
    return candidate.takeIf {
      url.host.isNotBlank() &&
          url.user.isNullOrEmpty() &&
          url.password.isNullOrEmpty() &&
          url.port == 0
    }
  }
  if (
      url.protocol != URLProtocol.HTTPS ||
          url.port != 443 ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty()
  )
      return null
  if (url.host == "qr.alipay.com") return candidate.takeIf(::isOfficialProviderPaymentUrl)

  val wrapperPath =
      when (url.host) {
        "ds.alipay.com" -> url.encodedPath in setOf("", "/")
        "render.alipay.com" -> url.encodedPath == "/p/s/i/" || url.encodedPath == "/p/s/i"
        else -> false
      }
  if (!wrapperPath) return null
  val wrapped = url.parameters.getAll("scheme")?.singleOrNull() ?: return null
  return paymentAppLaunchTarget(wrapped)
}

/** Navigation within one user-opened official payment window. Cookies remain browser-scoped. */
internal class PaymentWebNavigationPolicy(private val allowZfw: Boolean) {
  private val submitted = MutableStateFlow(false)

  // 0 = block, 1 = embedded navigation, 2 = offer an explicit OS payment-app confirmation.
  fun decide(raw: String, method: String, root: Boolean): Int {
    if (raw == "about:blank") return 1
    if (raw.length > 16384) return 0
    if (paymentAppLaunchTarget(raw) != null) return if (method.equals("GET", true)) 2 else 0
    val url = runCatching { Url(raw) }.getOrNull() ?: return 0
    if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return 0
    if (url.protocol.name in paymentAppSchemes) return 0
    val original = paymentNavigationOriginalUrl(raw) ?: return 0
    val target = runCatching { Url(original) }.getOrNull() ?: return 0
    if (
        target.protocol != URLProtocol.HTTPS ||
            target.port != 443 ||
            !target.user.isNullOrEmpty() ||
            !target.password.isNullOrEmpty()
    )
        return 0
    val allowed =
        when (target.host) {
          "cashier.cc-pay.cn",
          "mall.cc-pay.cn" -> true
          "pass.cc-pay.cn" ->
              target.encodedPath in setOf("/login", "/signIn") &&
                  (isOfficialPaymentCasService(original) ||
                      target.parameters
                          .getAll("backUrl")
                          ?.singleOrNull()
                          ?.let(::isOfficialPaymentCasService) == true ||
                      (target.parameters.getAll("ticket")?.size == 1 &&
                          target.parameters.getAll("backUrl").orEmpty().let {
                            it.isEmpty() ||
                                (it.size == 1 && isOfficialPaymentCasService(it.single()))
                          }))
          "sso.buaa.edu.cn" ->
              target.encodedPath == "/login" &&
                  target.parameters
                      .getAll("service")
                      ?.singleOrNull()
                      ?.let(::isOfficialPaymentCasService) == true
          "zfw.buaa.edu.cn" ->
              allowZfw && target.encodedPath !in setOf("/", "/login", "/site/login")
          else ->
              listOf("alipay.com", "tenpay.com", "weixin.qq.com", "95516.com", "unionpay.com").any {
                target.host == it || target.host.endsWith(".$it")
              }
        }
    if (!allowed) return 0
    val order =
        target.host == "zfw.buaa.edu.cn" &&
            target.encodedPath == "/pay/card" &&
            method.equals("POST", true)
    return if (!order || (root && submitted.compareAndSet(false, true))) 1 else 0
  }
}

internal fun isOfficialProviderPaymentUrl(raw: String): Boolean =
    app.bucc.api.finance.isOfficialProviderPaymentUrl(raw)
