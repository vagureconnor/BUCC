package app.bucc.ui.screens.finance

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.bucc.api.finance.PaymentSessionCookie
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MacWebKitPaymentRuntimeTest {
  private fun assertToolbarLayout(event: JsonObject) {
    val layout = event.getValue("toolbarLayout").jsonObject
    for (key in listOf("buttonsWithin", "statusWithin", "separated", "contentBelow")) {
      assertEquals(
          true,
          layout.getValue(key).jsonPrimitive.booleanOrNull,
          "Native toolbar geometry: $key",
      )
    }
    assertEquals(3, layout.getValue("buttonCount").jsonPrimitive.int)
  }

  @Test
  fun `closing during native callback never blocks the Swing event thread`() = runBlocking {
    assumeTrue(
        System.getenv("BUCC_WEBKIT_MOCK_UI") == "1" &&
            System.getProperty("os.name").startsWith("Mac")
    )
    SwingUtilities.invokeAndWait {}
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val returned = java.util.concurrent.CountDownLatch(1)
    val window = MacPaymentWindow { _, _, _ ->
      entered.countDown()
      release.await(5, java.util.concurrent.TimeUnit.SECONDS)
      1
    }
    try {
      assertTrue(window.open("about:blank", null, emptyList(), ephemeral = true))
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
      SwingUtilities.invokeLater {
        window.close()
        window.close()
        returned.countDown()
        release.countDown()
      }
      val responsive = returned.await(750, java.util.concurrent.TimeUnit.MILLISECONDS)
      val blockedStack =
          Thread.getAllStackTraces()
              .entries
              .firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
              ?.value
              ?.take(12)
              ?.joinToString("\n") { it.toString() }
              .orEmpty()
      assertTrue(responsive, "Swing close waited for the native UI callback:\n$blockedStack")
    } finally {
      release.countDown()
      returned.await(6, java.util.concurrent.TimeUnit.SECONDS)
      window.close()
      SwingUtilities.invokeAndWait {}
    }
  }

  @Test
  fun `payment dialogs can be cancelled by closing without freezing the application`() =
      runBlocking {
        assumeTrue(
            System.getenv("BUCC_WEBKIT_MOCK_UI") == "1" &&
                System.getProperty("os.name").startsWith("Mac")
        )
        SwingUtilities.invokeAndWait {}
        for (dialog in
            listOf("alert('synthetic')", "confirm('synthetic')", "prompt('synthetic','')")) {
          val window = MacPaymentWindow { _, _, _ -> 1 }
          try {
            assertTrue(window.open("about:blank", null, emptyList(), ephemeral = true))
            withTimeout(10000) {
              while (window.events.receive()["type"]?.jsonPrimitive?.content != "finished") {}
            }
            assertEquals(
                true,
                window
                    .evaluate("setTimeout(function() { $dialog; }, 50);true")
                    ?.jsonPrimitive
                    ?.booleanOrNull,
            )
            withTimeout(5000) {
              while (window.events.receive()["type"]?.jsonPrimitive?.content != "dialog") {}
            }
            val heartbeat = CompletableDeferred<Boolean>()
            SwingUtilities.invokeLater {
              window.close()
              heartbeat.complete(true)
            }
            assertTrue(withTimeout(1000) { heartbeat.await() })
            delay(150)
          } finally {
            window.close()
          }
        }
        // A new WebKit page still works after cancelling all three kinds of outstanding callback.
        val next = MacPaymentWindow { _, _, _ -> 1 }
        try {
          assertTrue(next.open("about:blank", null, emptyList(), ephemeral = true))
          withTimeout(10000) {
            while (next.evaluate("1+1")?.jsonPrimitive?.intOrNull != 2) delay(100)
          }
        } finally {
          next.close()
        }
      }

  @Test
  fun `native host does not retain a second text toolbar behind WebKit`() {
    val panel = MacPaymentHostPanel {}
    assertEquals(0, panel.componentCount)
  }

  @Test
  fun `payment view attaches to existing Compose window without replacing its content`() =
      runBlocking {
        assumeTrue(
            System.getenv("BUCC_WEBKIT_MOCK_UI") == "1" &&
                System.getProperty("os.name").startsWith("Mac")
        )
        var frame: androidx.compose.ui.awt.ComposeWindow? = null
        var originalContent: java.awt.Container? = null
        val hostReady = CompletableDeferred<MacPaymentHost>()
        SwingUtilities.invokeAndWait {
          val owner =
              androidx.compose.ui.awt.ComposeWindow().apply {
                title = "Synthetic embedded payment host"
              }
          owner.setSize(900, 700)
          originalContent = owner.contentPane
          owner.setContent {
            androidx.compose.material3.MaterialTheme {
              androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("Existing application content")
                androidx.compose.ui.awt.SwingPanel(
                    factory = { MacPaymentHostPanel { if (it != null) hostReady.complete(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
              }
            }
          }
          owner.isVisible = true
          frame = owner
        }
        val host = withTimeout(5000) { hostReady.await() }
        val native =
            com.sun.jna.Native.load(
                System.getProperty("compose.application.resources.dir")?.let {
                  java.io.File(it, "native/libbucc-native.dylib").absolutePath
                } ?: java.io.File("build/native/libbucc-native.dylib").absolutePath,
                MacPaymentNative::class.java,
            )
        val ready = CompletableDeferred<JsonObject>()
        val released = CompletableDeferred<Unit>()
        val resized =
            kotlinx.coroutines.channels.Channel<JsonObject>(
                kotlinx.coroutines.channels.Channel.UNLIMITED
            )
        val policy =
            object : MacPaymentPolicyCallback {
              override fun invoke(url: String?, method: String?, root: Int) = 1
            }
        val callback =
            object : MacPaymentEventCallback {
              override fun invoke(id: Long, event: String?) {
                val value = Json.parseToJsonElement(event!!).jsonObject
                if (value["type"]?.jsonPrimitive?.content == "finished") ready.complete(value)
                if (value["type"]?.jsonPrimitive?.content == "released") released.complete(Unit)
                if (value["type"]?.jsonPrimitive?.content == "script") resized.trySend(value)
              }
            }
        var id = 0L
        try {
          val config = buildJsonObject {
            put("url", "about:blank")
            put("cookies", JsonArray(emptyList()))
            put("ephemeral", true)
            put("post", "") // Synthetic empty POST keeps all three toolbar controls visible.
            put("parentWindow", host.window)
            put("x", host.x)
            put("y", host.y)
            put("width", host.width)
            put("height", host.height)
          }
          id = native.bucc_payment_open(config.toString(), policy, callback)
          assertTrue(id > 0)
          val event = withTimeout(15000) { ready.await() }
          assertEquals(true, event["recoveryCovered"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(true, event["recoveryCoverOnTop"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(true, event["recoveryCoverFillsContent"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(true, event["embedded"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(host.width.toInt(), event["width"]?.jsonPrimitive?.intOrNull)
          native.bucc_payment_bounds(id, host.x, host.y, 540.0, 360.0)
          native.bucc_payment_message(id, "官方支付页面已完成跳转；这是用于验证长文字不会覆盖返回、重新识别与关闭按钮的脱敏提示。")
          native.bucc_payment_eval(id, "({width:innerWidth,height:innerHeight})", 1)
          val normalEvent = withTimeout(5000) { resized.receive() }
          val dimensions = normalEvent.getValue("value").jsonObject
          assertEquals(540, dimensions.getValue("width").jsonPrimitive.int)
          assertEquals(296, dimensions.getValue("height").jsonPrimitive.int)
          assertToolbarLayout(normalEvent)
          native.bucc_payment_bounds(id, host.x, host.y, 320.0, 360.0)
          native.bucc_payment_eval(id, "({width:innerWidth,height:innerHeight})", 2)
          val narrowEvent = withTimeout(5000) { resized.receive() }
          val narrow = narrowEvent.getValue("value").jsonObject
          assertEquals(320, narrow.getValue("width").jsonPrimitive.int)
          assertEquals(296, narrow.getValue("height").jsonPrimitive.int)
          assertToolbarLayout(narrowEvent)
          SwingUtilities.invokeAndWait { assertSame(originalContent, frame!!.contentPane) }
        } finally {
          if (id > 0) {
            native.bucc_payment_close(id)
            withTimeout(5000) { released.await() }
          }
          SwingUtilities.invokeAndWait {
            assertTrue(frame!!.isDisplayable)
            frame!!.dispose()
          }
        }
      }

  @Test
  fun `upstream production widget supports script opened blank window and POST`() = runBlocking {
    assumeTrue(
        System.getenv("BUCC_WEBKIT_MOCK_UI") == "1" &&
            System.getProperty("os.name").startsWith("Mac")
    )
    SwingUtilities.invokeAndWait {}
    val server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
    val executor =
        Executors.newSingleThreadExecutor { task ->
          Thread(task, "synthetic-widget-http").apply { isDaemon = true }
        }
    val origin = "http://127.0.0.1:${server.localPort}"
    val received = ConcurrentLinkedQueue<String>()
    executor.submit {
      while (!server.isClosed) {
        val socket = runCatching { server.accept() }.getOrNull() ?: break
        socket.use {
          val input = socket.getInputStream().buffered()
          fun line(): String {
            val text = StringBuilder()
            while (true) {
              val c = input.read()
              if (c < 0 || c == 10) break
              if (c != 13) text.append(c.toChar())
            }
            return text.toString()
          }
          val start = line()
          var length = 0
          while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", true))
                length = header.substringAfter(':').trim().toInt()
          }
          val body = input.readNBytes(length).decodeToString()
          val html =
              if (start.startsWith("POST ")) {
                received += body
                "<title>widget-child</title>"
              } else
                  "<title>widget-root</title><button id=blank onclick=\"var w=window.open('');w.document.write('<form method=POST action=$origin/child><input name=payload value=synthetic-widget></form>');w.document.forms[0].submit()\">open</button>"
          val bytes = html.encodeToByteArray()
          socket.getOutputStream().apply {
            write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .encodeToByteArray()
            )
            write(bytes)
            flush()
          }
        }
      }
    }
    val window = MacPaymentWindow { url, _, _ ->
      if (url == "about:blank" || url.startsWith("$origin/")) 1 else 0
    }
    suspend fun title(expected: String) {
      withTimeout(15000) {
        while (window.evaluate("document.title")?.jsonPrimitive?.content != expected) delay(100)
      }
    }
    try {
      assertTrue(window.open("$origin/root", null, emptyList(), ephemeral = false))
      title("widget-root")
      window.evaluate("document.getElementById('blank').click();true")
      title("widget-child")
      assertEquals(listOf("payload=synthetic-widget"), received.toList())
      assertTrue(window.back())
      title("widget-root")
    } finally {
      window.close()
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun `WebKit transports root and popup POSTs blank windows cookies and nested back without replay`() =
      runBlocking {
        assumeTrue(
            System.getenv("BUCC_WEBKIT_MOCK_UI") == "1" &&
                System.getProperty("os.name").startsWith("Mac")
        )
        SwingUtilities.invokeAndWait {}
        assertTrue(macPaymentBrowserAvailable())
        val qrImage =
            checkNotNull(javaClass.getResourceAsStream("/payment-qr/official-fixture.png")).use {
              java.util.Base64.getEncoder().encodeToString(it.readBytes())
            }
        val requests = ConcurrentLinkedQueue<Pair<String, String>>()
        val cookies = ConcurrentLinkedQueue<String>()
        val server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        val executor =
            Executors.newCachedThreadPool { task ->
              Thread(task, "synthetic-payment-http").apply { isDaemon = true }
            }
        val origin = "http://127.0.0.1:${server.localPort}"
        fun html(title: String, body: String) =
            "<html><head><meta charset=utf-8><title>$title</title></head><body>$body</body></html>"
        fun serve(socket: Socket) =
            socket.use {
              val input = socket.getInputStream().buffered()
              fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) {
                  val value = input.read()
                  if (value < 0 || value == 10) break
                  if (value != 13) bytes += value.toByte()
                }
                return bytes.toByteArray().decodeToString()
              }
              val request = line().split(" ")
              if (request.size < 2) return@use
              val headers = mutableMapOf<String, String>()
              while (true) {
                val header = line()
                if (header.isEmpty()) break
                headers[header.substringBefore(":").lowercase()] = header.substringAfter(":").trim()
              }
              val path = java.net.URI(request[1]).path
              val body =
                  input.readNBytes(headers["content-length"]?.toIntOrNull() ?: 0).decodeToString()
              requests += path to body
              cookies += headers["cookie"].orEmpty()
              val response =
                  if (path == "/qr.png") java.util.Base64.getDecoder().decode(qrImage)
                  else
                      when (path) {
                        "/pay/card" ->
                            html(
                                "root-ready",
                                """
          <img id="qr" src="data:image/png;base64,$qrImage">
          <form id="post" action="$origin/child" method="POST" target="_blank"><input name="payload" value="synthetic-popup"></form>
          <button id="open-post" onclick="document.getElementById('post').submit()">open</button>
          <button id="blank" onclick="var w=window.open('');w.document.write('<form method=POST action=$origin/blank-child><input name=payload value=synthetic-blank></form>');w.document.forms[0].submit()">blank</button>
          <a id="deny" target="_blank" href="https://evil.invalid/blocked">deny</a>
          """,
                            )
                        "/child",
                        "/blank-child" ->
                            html(
                                path.drop(1),
                                "<a id=nested href='$origin/nested' target=_blank>nested</a>",
                            )
                        "/nested" -> html("nested-ready", "<p>synthetic</p>")
                        "/cashier" -> html("verified-cashier", "<h1>合成支付页面</h1>")
                        "/error" -> html("error-fixture", "<p>synthetic server error</p>")
                        else -> html("unknown-fixture", "")
                      }.encodeToByteArray()
              socket.getOutputStream().apply {
                write(
                    "HTTP/1.1 ${if (path == "/error") "500 Fixture Error" else "200 OK"}\r\nContent-Type: ${if (path == "/qr.png") "image/png" else "text/html; charset=utf-8"}\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                        .encodeToByteArray()
                )
                write(response)
                flush()
              }
            }
        executor.submit {
          while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            executor.submit { serve(socket) }
          }
        }
        val order = AtomicBoolean()
        val blocked = ConcurrentLinkedQueue<String>()
        val window = MacPaymentWindow { url, method, root ->
          val allowed =
              runCatching { java.net.URI(url) }
                  .getOrNull()
                  ?.let {
                    it.scheme == "http" && it.host == "127.0.0.1" && it.port == server.localPort
                  } == true
          if (url == "about:blank") 1
          else if (!allowed) {
            blocked += "blocked"
            0
          } else if (url == "$origin/pay/card" && method == "POST") {
            if (root && order.compareAndSet(false, true)) 1 else 0
          } else 1
        }
        suspend fun waitTitle(title: String) {
          withTimeout(15000) {
            while ((window.evaluate("document.title") as? JsonPrimitive)?.content != title) delay(
                100
            )
          }
        }
        suspend fun click(id: String) {
          window.evaluate("document.getElementById('$id').click();true")
        }
        try {
          assertTrue(
              window.open(
                  "$origin/pay/card",
                  "payload=synthetic-root".encodeToByteArray(),
                  listOf(
                      PaymentSessionCookie(
                          "private_session",
                          "synthetic-private",
                          "127.0.0.1",
                          "/",
                          false,
                          true,
                          origin,
                      ),
                      PaymentSessionCookie(
                          "visible_session",
                          "synthetic-visible",
                          "127.0.0.1",
                          "/",
                          false,
                          false,
                          origin,
                      ),
                  ),
                  ephemeral = true,
              )
          )
          waitTitle("root-ready")
          val initial =
              withTimeout(5000) {
                var loading: JsonObject
                do {
                  loading = window.events.receive()
                } while (loading["type"]?.jsonPrimitive?.content != "loading")
                loading
              }
          assertEquals(true, initial["recoveryCovered"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(true, initial["recoveryCoverOnTop"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(true, initial["recoveryCoverFillsContent"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(1, requests.count { it.first == "/pay/card" })
          assertEquals("payload=synthetic-root", requests.single { it.first == "/pay/card" }.second)
          assertTrue(cookies.any { it.contains("private_session=synthetic-private") })
          val visible = window.evaluate("document.cookie").toString()
          assertContains(visible, "visible_session")
          assertFalse(visible.contains("private_session"))
          val decoded = window.evaluate(paymentQrDecodeScript())?.jsonPrimitive?.content.orEmpty()
          assertEquals(
              listOf("https://cashier.cc-pay.cn/cashier?order=synthetic-fixture"),
              paymentResponseCandidates(decoded),
          )
          window.coverResponse("正在打开支付页面")
          // A different origin's image taints DOM canvas, but the native renderer remains readable
          // behind the cover.
          window.evaluate(
              "document.getElementById('qr').src='http://localhost:${server.localPort}/qr.png';true"
          )
          withTimeout(5000) {
            while (
                window
                    .evaluate(
                        "document.getElementById('qr').complete && document.getElementById('qr').naturalWidth>0"
                    )
                    ?.jsonPrimitive
                    ?.booleanOrNull != true
            ) delay(100)
          }
          assertContains(
              paymentResponseCandidates(
                  window.evaluate(paymentQrDecodeScript())?.jsonPrimitive?.content.orEmpty()
              ),
              "__BUCC_QR_CROSS_ORIGIN__",
          )
          assertEquals(
              listOf("https://cashier.cc-pay.cn/cashier?order=synthetic-fixture"),
              window.snapshotQr("$origin/pay/card"),
          )
          val recognition =
              withTimeout(5000) {
                var value: JsonObject
                do {
                  value = window.events.receive()
                } while (value["type"]?.jsonPrimitive?.content != "cover")
                value
              }
          assertEquals(true, recognition["recoveryCoverOnTop"]?.jsonPrimitive?.booleanOrNull)
          val decision =
              assertIs<PaymentQrRecoveryDecision.OpenOfficialPayment>(
                  PaymentQrRecoveryAttempt(0, PaymentQrSource.ZFW_PAY_CARD)
                      .decide(1, paymentResponseCandidates(decoded))
              )
          assertEquals("https://cashier.cc-pay.cn/cashier?order=synthetic-fixture", decision.url)
          assertTrue(window.snapshotQr("$origin/wrong-page").isEmpty())
          click("open-post")
          waitTitle("child")
          assertEquals("payload=synthetic-popup", requests.single { it.first == "/child" }.second)
          click("nested")
          waitTitle("nested-ready")
          assertTrue(window.back())
          waitTitle("child")
          assertTrue(window.back())
          waitTitle("root-ready")
          click("blank")
          waitTitle("blank-child")
          assertEquals(
              "payload=synthetic-blank",
              requests.single { it.first == "/blank-child" }.second,
          )
          assertTrue(window.back())
          waitTitle("root-ready")
          click("deny")
          delay(300)
          assertTrue(blocked.isNotEmpty())
          waitTitle("root-ready")
          assertEquals(1, requests.count { it.first == "/pay/card" })
          assertEquals(1, requests.count { it.first == "/child" })
          assertEquals(1, requests.count { it.first == "/blank-child" })
          window.navigate("$origin/error")
          waitTitle("error-fixture")
          withTimeout(5000) {
            while (true) {
              val event = window.events.receive()
              if (
                  event["type"]?.jsonPrimitive?.content == "error" &&
                      event["code"]?.jsonPrimitive?.intOrNull == 500
              )
                  break
            }
          }
          assertTrue(window.back())
          waitTitle("root-ready")
          assertEquals(1, requests.count { it.first == "/pay/card" })
          // Stand-in for the validated HTTPS destination: keep every network request local.
          window.navigate("$origin/cashier")
          waitTitle("verified-cashier")
          window.coverResponse(null)
          val revealed =
              withTimeout(5000) {
                var value: JsonObject
                do {
                  value = window.events.receive()
                } while (
                    value["type"]?.jsonPrimitive?.content != "cover" ||
                        value["covered"]?.jsonPrimitive?.booleanOrNull != false
                )
                value
              }
          assertEquals(false, revealed["recoveryCovered"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(false, revealed["recoveryCoverOnTop"]?.jsonPrimitive?.booleanOrNull)
          assertEquals(1, requests.count { it.first == "/pay/card" })
        } finally {
          window.close()
          server.close()
          executor.shutdownNow()
        }
      }
}
