package app.bucc.ui.screens.finance

import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFClient
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Headful, fully synthetic JCEF runtime coverage. Opt in with BUCC_PAYMENT_MOCK_UI=1. */
class PaymentWindowsRuntimeTest {
  @Test
  fun `native popup handler embeds official child pages and restores parents`() {
    assumeTrue(
        "Set BUCC_PAYMENT_MOCK_UI=1 to run native JCEF UI coverage",
        System.getenv("BUCC_PAYMENT_MOCK_UI") == "1",
    )
    assumeTrue(
        "A visible desktop is required for native JCEF",
        !java.awt.GraphicsEnvironment.isHeadless(),
    )
    // The application has an AWT event loop before opening finance; mirror that order.
    SwingUtilities.invokeAndWait {}
    val fixtureProfile =
        java.nio.file.Files.createTempDirectory("bucc-payment-synthetic-profile").toFile()
    val installed = paymentBrowserInstallDirectory(paymentBrowserCacheRoot())
    if (paymentBrowserRuntimeIsCompatible(installed)) {
      java.nio.file.Files.createSymbolicLink(
          paymentBrowserInstallDirectory(fixtureProfile).toPath(),
          installed.toPath(),
      )
    }
    assertTrue(
        runBlocking { initializePaymentBrowser(fixtureProfile) },
        "KCEF initialization failed",
    )

    val allowedRequests = ConcurrentLinkedQueue<String>()
    val deniedRequests = ConcurrentLinkedQueue<String>()
    val callbackEvents = ConcurrentLinkedQueue<Pair<String, Boolean>>()
    val fixture = SyntheticPaymentResources(allowedRequests, deniedRequests)
    val cefApp =
        CefApp.getInstanceIfAny() ?: throw AssertionError("KCEF initialized without a CEF app")
    assertTrue(cefApp.registerSchemeHandlerFactory("https", "cashier.cc-pay.cn", fixture))
    assertTrue(cefApp.registerSchemeHandlerFactory("https", "mall.cc-pay.cn", fixture))
    val initialUrl = "https://cashier.cc-pay.cn/mock/root"
    val client = KCEF.newClientBlocking()
    val root = client.createBrowser(initialUrl, CefRendering.DEFAULT, false)
    val handler =
        paymentPopupHandler(allowZfw = false) { url, allowed -> callbackEvents += url to allowed }
    val frame =
        JFrame("BUCC synthetic payment window test").apply {
          defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
          setSize(900, 650)
          setLocation(60, 60)
          layout = BorderLayout()
        }
    try {
      root.client.addLifeSpanHandler(
          com.multiplatform.webview.web.DisablePopupWindowsLifeSpanHandler()
      )
      handler.attach(root)
      val delegate = currentRequestHandler(root)
      root.client
          .paymentNativeClient()
          .replacePaymentRequestHandler(SyntheticRequestHandler(delegate, fixture, deniedRequests))
      SwingUtilities.invokeAndWait {
        frame.contentPane.add(root.uiComponent, BorderLayout.CENTER)
        frame.isVisible = true
        root.createImmediately()
      }

      await("root fixture page") { root.documentSummary().contains("payment-parent") }
      assertContains(root.documentSummary(), "bucc_mock=synthetic-session")

      root.click("alipay")
      await("target=_blank child") {
        childBrowsers(handler).size == 1 &&
            activeBrowser(handler, root).documentSummary().contains("payment-alipay")
      }
      assertContains(activeBrowser(handler, root).documentSummary(), "bucc_mock=synthetic-session")
      assertContains(root.documentSummary(), "payment-parent")

      activeBrowser(handler, root).click("nested")
      await("nested popup child") {
        childBrowsers(handler).size == 2 &&
            activeBrowser(handler, root).documentSummary().contains("payment-nested")
      }
      assertTrue(handler.goBack())
      await("nested child return") {
        childBrowsers(handler).size == 1 &&
            activeBrowser(handler, root).documentSummary().contains("payment-alipay")
      }
      assertTrue(handler.goBack())
      await("root return after target=_blank") {
        childBrowsers(handler).isEmpty() && root.documentSummary().contains("payment-parent")
      }

      root.click("wechat")
      await("window.open child") {
        childBrowsers(handler).size == 1 &&
            activeBrowser(handler, root).documentSummary().contains("payment-wechat")
      }
      assertContains(activeBrowser(handler, root).documentSummary(), "bucc_mock=synthetic-session")
      activeBrowser(handler, root).click("nested")
      await("nested window.open child") {
        childBrowsers(handler).size == 2 &&
            activeBrowser(handler, root).documentSummary().contains("payment-nested")
      }
      assertTrue(handler.goBack())
      await("window.open child return") {
        childBrowsers(handler).size == 1 &&
            activeBrowser(handler, root).documentSummary().contains("payment-wechat")
      }
      assertTrue(handler.goBack())
      await("root return after window.open") {
        childBrowsers(handler).isEmpty() && root.documentSummary().contains("payment-parent")
      }

      root.click("bank")
      await("blank script window becomes native child") {
        childBrowsers(handler).size == 1 &&
            activeBrowser(handler, root).documentSummary().contains("payment-bank")
      }
      assertContains(activeBrowser(handler, root).documentSummary(), "bucc_mock=synthetic-session")
      activeBrowser(handler, root).click("nested")
      await("bank nested child") {
        childBrowsers(handler).size == 2 &&
            activeBrowser(handler, root).documentSummary().contains("payment-nested")
      }
      assertTrue(handler.goBack())
      await("bank child return") {
        childBrowsers(handler).size == 1 &&
            activeBrowser(handler, root).documentSummary().contains("payment-bank")
      }
      assertTrue(handler.goBack())
      await("bank parent return") {
        childBrowsers(handler).isEmpty() && root.documentSummary().contains("payment-parent")
      }

      assertTrue(callbackEvents.any { it.first.contains("/mock/child/alipay") && it.second })
      assertTrue(callbackEvents.any { it.first.contains("/mock/child/wechat") && it.second })
      assertTrue(allowedRequests.isNotEmpty())
      assertTrue(allowedRequests.all(::isSyntheticPaymentUrl))
      assertTrue(
          deniedRequests.isEmpty(),
          "Unexpected non-fixture request attempted: $deniedRequests",
      )
    } finally {
      handler.close()
      root.stopLoad()
      root.close(true)
      client.dispose()
      cefApp.clearSchemeHandlerFactories()
      SwingUtilities.invokeAndWait { frame.dispose() }
    }
  }

  private fun currentRequestHandler(
      root: com.multiplatform.webview.web.NativeWebView
  ): CefRequestHandler? = root.client.paymentNativeClient().paymentRequestHandler()

  @Suppress("UNCHECKED_CAST")
  private fun childBrowsers(
      handler: PaymentPopupHandler
  ): List<Pair<com.multiplatform.webview.web.NativeWebView, KCEFClient>> {
    val field = handler.javaClass.getDeclaredField("children").apply { isAccessible = true }
    return field.get(handler) as List<Pair<com.multiplatform.webview.web.NativeWebView, KCEFClient>>
  }

  private fun activeBrowser(
      handler: PaymentPopupHandler,
      root: com.multiplatform.webview.web.NativeWebView,
  ): com.multiplatform.webview.web.NativeWebView =
      childBrowsers(handler).lastOrNull()?.first ?: root

  private fun com.multiplatform.webview.web.NativeWebView.documentSummary(): String =
      evaluateFixtureScript("JSON.stringify({title:document.title,cookie:document.cookie})")
          .orEmpty()

  private fun com.multiplatform.webview.web.NativeWebView.evaluateFixtureScript(
      script: String
  ): String? = runBlocking {
    withTimeoutOrNull(1_000) {
      suspendCancellableCoroutine { continuation ->
        evaluateJavaScript(script) { value ->
          if (continuation.isActive) continuation.resume(value)
        }
      }
    }
  }

  private fun com.multiplatform.webview.web.NativeWebView.click(id: String) {
    assertEquals(
        "true",
        evaluateFixtureScript(
            "(function(){var e=document.getElementById('$id');if(!e)return false;e.click();return true;})()"
        ),
    )
  }

  private fun await(description: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + 15_000_000_000L
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait {}
      if (runCatching(predicate).getOrDefault(false)) return
      Thread.sleep(100)
    }
    throw AssertionError("Timed out waiting for $description")
  }
}

private class SyntheticRequestHandler(
    private val delegate: CefRequestHandler?,
    private val fixture: SyntheticPaymentResources,
    private val denied: ConcurrentLinkedQueue<String>,
) : CefRequestHandlerAdapter() {
  override fun onBeforeBrowse(
      browser: CefBrowser?,
      frame: CefFrame?,
      request: CefRequest?,
      userGesture: Boolean,
      isRedirect: Boolean,
  ): Boolean {
    val url = request?.url ?: return true
    if (!isSyntheticPaymentUrl(url)) {
      denied += url
      return true
    }
    return delegate?.onBeforeBrowse(browser, frame, request, userGesture, isRedirect) ?: false
  }

  override fun getResourceRequestHandler(
      browser: CefBrowser?,
      frame: CefFrame?,
      request: CefRequest?,
      isNavigation: Boolean,
      isDownload: Boolean,
      requestInitiator: String?,
      disableDefaultHandling: BoolRef?,
  ) = fixture

  override fun onOpenURLFromTab(
      browser: CefBrowser?,
      frame: CefFrame?,
      targetUrl: String?,
      userGesture: Boolean,
  ): Boolean = delegate?.onOpenURLFromTab(browser, frame, targetUrl, userGesture) ?: true

  override fun getAuthCredentials(
      browser: CefBrowser?,
      originUrl: String?,
      isProxy: Boolean,
      host: String?,
      port: Int,
      realm: String?,
      scheme: String?,
      callback: org.cef.callback.CefAuthCallback?,
  ): Boolean = false
}

private class SyntheticPaymentResources(
    private val allowed: ConcurrentLinkedQueue<String>,
    private val denied: ConcurrentLinkedQueue<String>,
) : CefResourceRequestHandlerAdapter(), org.cef.callback.CefSchemeHandlerFactory {
  override fun onBeforeResourceLoad(
      browser: CefBrowser?,
      frame: CefFrame?,
      request: CefRequest?,
  ): Boolean {
    val url = request?.url.orEmpty()
    return if (isSyntheticPaymentUrl(url)) {
      allowed += url
      false
    } else {
      denied += url
      true
    }
  }

  override fun getResourceHandler(
      browser: CefBrowser?,
      frame: CefFrame?,
      request: CefRequest?,
  ): CefResourceHandler {
    val url = request?.url.orEmpty()
    if (isSyntheticPaymentUrl(url)) allowed += url else denied += url
    return SyntheticHtmlHandler(
        if (isSyntheticPaymentUrl(url)) fixtureFor(url) else blockedPage,
        if (isSyntheticPaymentUrl(url)) 200 else 403,
    )
  }

  override fun create(
      browser: CefBrowser?,
      frame: CefFrame?,
      schemeName: String?,
      request: CefRequest?,
  ): CefResourceHandler {
    val url = request?.url.orEmpty()
    if (isSyntheticPaymentUrl(url)) allowed += url else denied += url
    return SyntheticHtmlHandler(
        if (isSyntheticPaymentUrl(url)) fixtureFor(url) else blockedPage,
        if (isSyntheticPaymentUrl(url)) 200 else 403,
    )
  }

  private fun fixtureFor(url: String): String =
      when (url.substringAfter("/mock/")) {
        "root" ->
            page(
                "payment-parent",
                """
                <h1>Synthetic cashier parent</h1>
                <button id="alipay" onclick="window.open('https://cashier.cc-pay.cn/mock/child/alipay','_blank')">支付宝</button>
                <button id="wechat" onclick="window.open('https://mall.cc-pay.cn/mock/child/wechat','_blank')">微信</button>
                <button id="bank" onclick="var w=window.open('about:blank');setTimeout(function(){w.location='https://cashier.cc-pay.cn/mock/child/bank'},150)">银行卡</button>
                """
                    .trimIndent(),
            )
        "child/alipay" -> page("payment-alipay", childBody("支付宝子页面"))
        "child/wechat" -> page("payment-wechat", childBody("微信子页面"))
        "child/bank" -> page("payment-bank", childBody("银行卡子页面"))
        "nested/alipay" -> page("payment-nested", childBody("嵌套支付子页面"))
        else -> page("payment-fixture-not-found", "<h1>Unknown synthetic route</h1>")
      }

  private fun childBody(label: String) =
      "<h1>$label</h1><a id=\"nested\" target=\"_blank\" href=\"https://mall.cc-pay.cn/mock/nested/alipay\">继续到嵌套页面</a>"

  private fun page(title: String, body: String) =
      """<!doctype html><html><head><meta charset="utf-8"><title>$title</title><style>body{font:24px sans-serif;padding:32px}button,a{display:block;margin:18px;padding:20px;background:#e8eef8;border:1px solid #789;border-radius:8px;color:#123;text-decoration:none}</style></head><body>$body<script>document.cookie='bucc_mock=synthetic-session; Domain=.cc-pay.cn; Path=/; Secure; SameSite=Lax';</script></body></html>"""

  companion object {
    private const val blockedPage = "<!doctype html><title>blocked</title>"
  }
}

private class SyntheticHtmlHandler(private val body: String, private val status: Int) :
    CefResourceHandlerAdapter() {
  private val bytes = body.toByteArray(StandardCharsets.UTF_8)
  private var offset = 0

  override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
    callback?.Continue()
    return true
  }

  override fun getResponseHeaders(
      response: CefResponse?,
      responseLength: IntRef?,
      redirectUrl: StringRef?,
  ) {
    response?.status = status
    response?.statusText = if (status == 200) "OK" else "Blocked"
    response?.mimeType = "text/html"
    response?.setHeaderByName("Content-Type", "text/html; charset=utf-8", true)
    response?.setHeaderByName(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src 'none'; frame-src https://cashier.cc-pay.cn https://mall.cc-pay.cn",
        true,
    )
    responseLength?.set(bytes.size)
  }

  override fun readResponse(
      dataOut: ByteArray?,
      bytesToRead: Int,
      bytesRead: IntRef?,
      callback: CefCallback?,
  ): Boolean {
    if (dataOut == null || offset >= bytes.size) {
      bytesRead?.set(0)
      return false
    }
    val count = minOf(bytesToRead, bytes.size - offset)
    bytes.copyInto(dataOut, 0, offset, offset + count)
    offset += count
    bytesRead?.set(count)
    return true
  }
}

private fun isSyntheticPaymentUrl(raw: String): Boolean =
    runCatching {
          val uri = java.net.URI(raw)
          uri.scheme == "https" &&
              uri.host in setOf("cashier.cc-pay.cn", "mall.cc-pay.cn") &&
              uri.path.startsWith("/mock/")
        }
        .getOrDefault(false)

class SyntheticPaymentResourcePolicyTest {
  @Test
  fun `unknown resources are cancelled before any network request`() {
    val allowed = ConcurrentLinkedQueue<String>()
    val denied = ConcurrentLinkedQueue<String>()
    val resources = SyntheticPaymentResources(allowed, denied)
    assertTrue(resources.onBeforeResourceLoad(null, null, null))
    assertTrue(allowed.isEmpty())
    assertEquals(listOf(""), denied.toList())
  }
}
