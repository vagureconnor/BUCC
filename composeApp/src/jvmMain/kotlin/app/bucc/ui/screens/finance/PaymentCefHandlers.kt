package app.bucc.ui.screens.finance

import dev.datlag.kcef.KCEFClient
import org.cef.CefClient
import org.cef.handler.CefLifeSpanHandler
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefRequestHandler

// This pinned JCEF API's getters return the client dispatcher, not the installed handler.
internal fun KCEFClient.paymentNativeClient(): CefClient =
    KCEFClient::class.java.getDeclaredField("client").apply { isAccessible = true }.get(this)
        as CefClient

internal fun CefClient.paymentRequestHandler(): CefRequestHandler? =
    CefClient::class
        .java
        .getDeclaredField("requestHandler_")
        .apply { isAccessible = true }
        .get(this) as? CefRequestHandler

internal fun CefClient.paymentLoadHandler(): CefLoadHandler? =
    CefClient::class.java.getDeclaredField("loadHandler_").apply { isAccessible = true }.get(this)
        as? CefLoadHandler

internal fun CefClient.replacePaymentRequestHandler(handler: CefRequestHandler?) {
  removeRequestHandler()
  handler?.let { addRequestHandler(it) }
}

internal fun CefClient.replacePaymentLoadHandler(handler: CefLoadHandler?) {
  removeLoadHandler()
  handler?.let { addLoadHandler(it) }
}

@Suppress("UNCHECKED_CAST")
private fun CefClient.paymentLifeSpanHandlers(): MutableList<CefLifeSpanHandler> =
    CefClient::class
        .java
        .getDeclaredField("lifeSpanHandlers_")
        .apply { isAccessible = true }
        .get(this) as MutableList<CefLifeSpanHandler>

internal fun CefClient.installPaymentLifeSpanHandler(
    handler: CefLifeSpanHandler
): List<CefLifeSpanHandler> {
  val handlers = paymentLifeSpanHandlers()
  return synchronized(handlers) {
    val previous = handlers.toList()
    handlers.clear()
    handlers.add(handler)
    previous
  }
}

internal fun CefClient.restorePaymentLifeSpanHandlers(
    handler: CefLifeSpanHandler,
    previous: List<CefLifeSpanHandler>,
) {
  val handlers = paymentLifeSpanHandlers()
  synchronized(handlers) {
    handlers.remove(handler)
    handlers.addAll(0, previous)
  }
}
