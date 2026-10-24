package app.bucc.ui.screens.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.cef.CefClient
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import sun.misc.Unsafe

class PaymentCefHandlersTest {
  private fun browser(): org.cef.browser.CefBrowser =
      java.lang.reflect.Proxy.newProxyInstance(
          org.cef.browser.CefBrowser::class.java.classLoader,
          arrayOf(org.cef.browser.CefBrowser::class.java),
      ) { _, method, _ ->
        when (method.name) {
          "getIdentifier" -> 1
          "toString" -> "SyntheticBrowser"
          else ->
              when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
              }
        }
      } as org.cef.browser.CefBrowser

  // Isolate Java handler dispatch without initializing the native browser in headless tests.
  private fun client(): CefClient {
    val unsafe =
        Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            as Unsafe
    return (unsafe.allocateInstance(CefClient::class.java) as CefClient).apply {
      CefClient::class
          .java
          .getDeclaredField("lifeSpanHandlers_")
          .apply { isAccessible = true }
          .set(this, mutableListOf<org.cef.handler.CefLifeSpanHandler>())
    }
  }

  @Test
  fun `handler replacement preserves actual delegates and never captures client dispatcher`() {
    val client = client()
    val originalRequest = object : CefRequestHandlerAdapter() {}
    var loaded = 0
    val originalLoad =
        object : CefLoadHandlerAdapter() {
          override fun onLoadEnd(
              browser: org.cef.browser.CefBrowser?,
              frame: org.cef.browser.CefFrame?,
              status: Int,
          ) {
            loaded++
          }
        }
    client.addRequestHandler(originalRequest)
    client.addLoadHandler(originalLoad)
    assertSame(originalRequest, client.paymentRequestHandler())
    assertSame(originalLoad, client.paymentLoadHandler())
    val delegate = client.paymentLoadHandler()
    val wrapper =
        object : CefLoadHandlerAdapter() {
          override fun onLoadEnd(
              browser: org.cef.browser.CefBrowser?,
              frame: org.cef.browser.CefFrame?,
              status: Int,
          ) {
            delegate?.onLoadEnd(browser, frame, status)
          }
        }
    client.replacePaymentLoadHandler(wrapper)
    assertSame(wrapper, client.paymentLoadHandler())
    client.onLoadEnd(browser(), null, 200)
    assertEquals(1, loaded)
    client.replacePaymentLoadHandler(originalLoad)
    assertSame(originalLoad, client.paymentLoadHandler())
    client.replacePaymentRequestHandler(null)
    assertNull(client.paymentRequestHandler())
  }

  @Test
  fun `response observer installs its wrapper and restores the real prior handler`() {
    val nativeClient = client()
    val unsafe =
        Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            as Unsafe
    val kcefClient =
        unsafe.allocateInstance(dev.datlag.kcef.KCEFClient::class.java)
            as dev.datlag.kcef.KCEFClient
    dev.datlag.kcef.KCEFClient::class
        .java
        .getDeclaredField("client")
        .apply { isAccessible = true }
        .set(kcefClient, nativeClient)
    val view =
        dev.datlag.kcef.KCEFBrowser::class
            .java
            .constructors
            .single()
            .newInstance(kcefClient, browser()) as dev.datlag.kcef.KCEFBrowser
    var calls = 0
    val original =
        object : CefRequestHandlerAdapter() {
          override fun onBeforeBrowse(
              browser: org.cef.browser.CefBrowser?,
              frame: org.cef.browser.CefFrame?,
              request: org.cef.network.CefRequest?,
              gesture: Boolean,
              redirect: Boolean,
          ): Boolean {
            calls++
            return false
          }
        }
    nativeClient.addRequestHandler(original)
    val observer = paymentResponseObserver { _, _ -> }
    observer.attach(view)
    kotlin.test.assertNotSame(original, nativeClient.paymentRequestHandler())
    nativeClient.paymentRequestHandler()?.onBeforeBrowse(null, null, null, false, false)
    assertEquals(1, calls)
    observer.close()
    assertSame(original, nativeClient.paymentRequestHandler())
    observer.close()
    assertSame(original, nativeClient.paymentRequestHandler())
  }

  @Test
  fun `lifecycle removal keeps existing handlers without forwarding back into dispatcher`() {
    val client = client()
    var created = 0
    val original =
        object : CefLifeSpanHandlerAdapter() {
          override fun onBeforePopup(
              browser: org.cef.browser.CefBrowser?,
              frame: org.cef.browser.CefFrame?,
              url: String?,
              name: String?,
          ): Boolean {
            created++
            return false
          }
        }
    val popup = object : CefLifeSpanHandlerAdapter() {}
    client.addLifeSpanHandler(original)
    val previous = client.installPaymentLifeSpanHandler(popup)
    client.onBeforePopup(browser(), null, "fixture", null)
    assertEquals(0, created)
    client.restorePaymentLifeSpanHandlers(popup, previous)
    client.onBeforePopup(browser(), null, "fixture", null)
    assertEquals(1, created)
  }
}
