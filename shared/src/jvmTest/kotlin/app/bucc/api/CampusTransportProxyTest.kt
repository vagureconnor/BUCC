package app.bucc.api

import app.bucc.api.core.getDefaultEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class CampusTransportProxyTest {
  @Test
  fun `campus direct requests bypass an unrelated system HTTP proxy`() = runBlocking {
    val previous = ProxySelector.getDefault()
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val responder =
        thread(isDaemon = true) {
          runCatching {
            server.accept().use { socket ->
              val reader = socket.getInputStream().bufferedReader()
              while (!reader.readLine().isNullOrEmpty()) Unit
              socket
                  .getOutputStream()
                  .write(
                      "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\ndirect"
                          .toByteArray()
                  )
            }
          }
        }
    ProxySelector.setDefault(
        object : ProxySelector() {
          override fun select(uri: URI): List<Proxy> =
              listOf(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 9)))

          override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
        }
    )
    val client =
        HttpClient(getDefaultEngine()) { install(HttpTimeout) { requestTimeoutMillis = 2000 } }
    try {
      assertEquals("direct", client.get("http://127.0.0.1:${server.localPort}/").bodyAsText())
    } finally {
      client.close()
      ProxySelector.setDefault(previous)
      server.close()
      responder.join(100)
    }
  }
}
