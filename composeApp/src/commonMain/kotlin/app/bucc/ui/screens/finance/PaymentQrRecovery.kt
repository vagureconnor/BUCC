package app.bucc.ui.screens.finance

import app.bucc.api.finance.isOfficialInternetPaymentSourceUrl
import app.bucc.api.finance.isSupportedOfficialPaymentTarget
import app.bucc.api.finance.paymentNavigationOriginalUrl
import bucc.composeapp.generated.resources.Res
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * Loads the pinned local decoder and creates a bounded scan over pixels already rendered in the
 * page.
 */
suspend fun paymentQrDecodeScript(): String {
  val decoder = Res.readBytes("files/payment-qr/jsqr-1.4.0.js").decodeToString()
  val scanner = Res.readBytes("files/payment-qr/payment-qr-scanner.js").decodeToString()
  return "$decoder\n;$scanner"
}

/** Page context that the host is allowed to scan for a current payment response. */
enum class PaymentQrSource {
  ZFW_PAY_CARD,
  ZFW_QRCODE,
}

/** Resolves only the current mode's official ZFW form or QR response page. */
fun paymentQrSourceForCurrentUrl(rawUrl: String): PaymentQrSource? {
  if (!isOfficialInternetPaymentSourceUrl(rawUrl)) return null
  val originalUrl = paymentNavigationOriginalUrl(rawUrl) ?: return null
  val source = runCatching { Url(originalUrl) }.getOrNull() ?: return null
  return when (source.encodedPath) {
    "/pay/card" -> PaymentQrSource.ZFW_PAY_CARD
    "/qrcode" -> PaymentQrSource.ZFW_QRCODE
    else -> null
  }
}

/** One QR recovery window, created when the one-shot form submission begins. */
class PaymentQrRecoveryAttempt(
    val startedAtMonotonicMillis: Long,
    val source: PaymentQrSource,
    private val supportedUrl: (String) -> Boolean = ::isSupportedOfficialPaymentTarget,
) {
  private var navigationCommitted = false
  private var expiresAtMillis = startedAtMonotonicMillis + MAX_AGE_MILLIS

  init {
    require(startedAtMonotonicMillis >= 0L)
  }

  fun decide(
      nowMonotonicMillis: Long,
      payloads: List<String>,
      pageRemainingMillis: Long? = null,
  ): PaymentQrRecoveryDecision {
    if (pageRemainingMillis != null)
        expiresAtMillis =
            minOf(
                expiresAtMillis,
                nowMonotonicMillis + pageRemainingMillis.coerceIn(0, MAX_AGE_MILLIS),
            )
    if (nowMonotonicMillis < startedAtMonotonicMillis) return PaymentQrRecoveryDecision.Invalid
    if (nowMonotonicMillis >= expiresAtMillis) {
      return PaymentQrRecoveryDecision.Expired
    }
    if (navigationCommitted) return PaymentQrRecoveryDecision.AlreadyNavigated
    val distinctPayloads =
        payloads
            .map { payload ->
              paymentNavigationOriginalUrl(payload)?.takeIf(supportedUrl) ?: payload
            }
            .distinct()
    if (distinctPayloads.isEmpty() || distinctPayloads.size > MAX_CANDIDATES) {
      return PaymentQrRecoveryDecision.Invalid
    }
    if (distinctPayloads.size > 1) return PaymentQrRecoveryDecision.Ambiguous

    val payload = distinctPayloads.single()
    if (payload.isBlank() || payload.length > MAX_PAYLOAD_LENGTH) {
      return PaymentQrRecoveryDecision.Invalid
    }
    if (!Regex("(?i)^https?://[^\\s/?#]+(?:[/?#].*)?$").matches(payload)) {
      return PaymentQrRecoveryDecision.Invalid
    }
    val parsedUrl =
        runCatching { Url(payload) }.getOrNull() ?: return PaymentQrRecoveryDecision.Invalid
    if (
        parsedUrl.protocol !in setOf(URLProtocol.HTTP, URLProtocol.HTTPS) ||
            parsedUrl.host.isBlank()
    ) {
      return PaymentQrRecoveryDecision.Invalid
    }
    if (!supportedUrl(payload)) return PaymentQrRecoveryDecision.Unsupported
    navigationCommitted = true
    return PaymentQrRecoveryDecision.OpenOfficialPayment(payload)
  }

  override fun toString(): String =
      "PaymentQrRecoveryAttempt(startedAtMonotonicMillis=$startedAtMonotonicMillis, source=$source)"

  companion object {
    const val MAX_AGE_MILLIS: Long = 15 * 60 * 1000L
    const val MAX_CANDIDATES: Int = 16
    const val MAX_PAYLOAD_LENGTH: Int = 8192
  }
}

sealed interface PaymentQrRecoveryDecision {
  class OpenOfficialPayment internal constructor(val url: String) : PaymentQrRecoveryDecision {
    override fun toString(): String = "OpenOfficialPayment(url=[redacted])"
  }

  data object AlreadyNavigated : PaymentQrRecoveryDecision

  data object Ambiguous : PaymentQrRecoveryDecision

  data object Expired : PaymentQrRecoveryDecision

  data object Invalid : PaymentQrRecoveryDecision

  data object Unsupported : PaymentQrRecoveryDecision
}

/** Reads only explicit lifetime metadata around the displayed QR, never form/account fields. */
internal const val PAYMENT_QR_VALIDITY_SCRIPT =
    """
(function(){
 var e=document.querySelector('[data-qr-expires-at],[data-qr-remaining-seconds],[data-qr-expired]');
 if(!e)return null;
 if(e.getAttribute('data-qr-expired')==='true')return 0;
 var seconds=e.getAttribute('data-qr-remaining-seconds');
 if(seconds!==null && /^\d+$/.test(seconds))return Math.max(0,Math.min(900000,Number(seconds)*1000));
 var raw=e.getAttribute('data-qr-expires-at');
 if(!raw)return null;
 var end=/^\d+$/.test(raw)?Number(raw):Date.parse(raw);
 if(end<100000000000)end*=1000;
 return Number.isFinite(end)?Math.max(0,Math.min(900000,end-Date.now())):null;
})()
"""

internal fun paymentQrRemainingMillis(raw: String?): Long? =
    raw?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..900000.0 }?.toLong()

/** A covered response remains rendered for decoding; only a verified payment page is revealed. */
enum class PaymentRecoveryPhase {
  PROCESSING,
  OPENING,
  READY,
  FAILED,
}

fun paymentRecoveryPhase(
    currentUrl: String?,
    navigating: Boolean = false,
    failed: Boolean = false,
): PaymentRecoveryPhase =
    when {
      failed -> PaymentRecoveryPhase.FAILED
      navigating -> PaymentRecoveryPhase.OPENING
      currentUrl != null && app.bucc.api.finance.isOfficialPaymentContentUrl(currentUrl) ->
          PaymentRecoveryPhase.READY
      else -> PaymentRecoveryPhase.PROCESSING
    }
