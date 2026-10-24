package app.bucc.api.core

import app.bucc.api.local.getLibBookHttpClientEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopHttpEngineTest {
  @Test
  fun `LibBook engine uses the scoped physical helper and dedicated relaxed TLS checks`() =
      runBlocking {
        val oldModes = app.bucc.api.ConnectionModeStore.settings
        val oldPool = app.bucc.connection.desktopPhysicalCampusProxy
        val oldSelector = ProxySelector.getDefault()
        app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
        app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.DIRECT)
        app.bucc.api.ConnectionRuntime.resolveSelectedMode()
        FixtureServer("library-physical-route").use { physical ->
          FixtureServer("inherited-library-route").use { inherited ->
            app.bucc.connection.desktopPhysicalCampusProxy =
                app.bucc.connection.PhysicalCampusProxyPool(
                    {
                      object : app.bucc.connection.PhysicalCampusHelper {
                        override suspend fun exchange(request: String) =
                            "{\"status\":\"READY\",\"port\":${(physical.proxy.address() as InetSocketAddress).port}}"

                        override suspend fun close() = Unit
                      }
                    },
                    { true },
                )
            ProxySelector.setDefault(
                object : ProxySelector() {
                  override fun select(uri: URI) = listOf(inherited.proxy)

                  override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) =
                      Unit
                }
            )
            try {
              HttpClient(getLibBookHttpClientEngine()).use { client ->
                assertEquals(
                    "library-physical-route",
                    client.get("http://booking.lib.buaa.edu.cn/library").bodyAsText(),
                )
              }
              assertEquals(1, physical.requests.get())
              assertEquals(0, inherited.requests.get())
              assertTrue(
                  runCatching { relaxedTrustManager().checkServerTrusted(emptyArray(), "test") }
                      .isSuccess
              )
              assertTrue(relaxedHostnameVerifier().verify("untrusted.invalid", null))
            } finally {
              ProxySelector.setDefault(oldSelector)
              app.bucc.connection.desktopPhysicalCampusProxy = oldPool
              app.bucc.api.ConnectionModeStore.settings = oldModes
              app.bucc.api.ConnectionRuntime.resolveSelectedMode()
            }
          }
        }
      }

  @Test
  fun `campus card client bypasses an inherited rejecting proxy without changing system selection`() =
      runBlocking {
        FixtureServer("inherited-proxy").use { inherited ->
          FixtureServer("campus-direct").use { campus ->
            val original = ProxySelector.getDefault()
            val selector =
                object : ProxySelector() {
                  override fun select(uri: URI): List<Proxy> = listOf(inherited.proxy)

                  override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) =
                      Unit
                }
            ProxySelector.setDefault(selector)
            try {
              HttpClient(getCampusCardEngine()).use { client ->
                assertEquals("campus-direct", client.get(campus.url).bodyAsText())
              }
              assertEquals(0, inherited.requests.get())
              assertEquals(1, campus.requests.get())
              assertTrue(ProxySelector.getDefault() === selector)
            } finally {
              ProxySelector.setDefault(original)
            }
          }
        }
      }

  @Test
  fun `DIRECT routes campus hosts and card API through the physical helper`() = runBlocking {
    val oldModes = app.bucc.api.ConnectionModeStore.settings
    val oldPool = app.bucc.connection.desktopPhysicalCampusProxy
    val oldSelector = ProxySelector.getDefault()
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.DIRECT)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    FixtureServer("physical-route").use { physical ->
      FixtureServer("ordinary-route").use { ordinary ->
        app.bucc.connection.desktopPhysicalCampusProxy =
            app.bucc.connection.PhysicalCampusProxyPool(
                {
                  object : app.bucc.connection.PhysicalCampusHelper {
                    override suspend fun exchange(request: String) =
                        "{\"status\":\"READY\",\"port\":${(physical.proxy.address() as InetSocketAddress).port}}"

                    override suspend fun close() = Unit
                  }
                },
                { true },
            )
        val rejecting =
            object : ProxySelector() {
              override fun select(uri: URI) = listOf(ordinary.proxy)

              override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) =
                  Unit
            }
        ProxySelector.setDefault(rejecting)
        val cardEngine = getCampusCardEngine()
        val normalEngine = getDefaultEngine()
        try {
          HttpClient(cardEngine).use { client ->
            assertEquals(
                "physical-route",
                client.get("http://m.icw.buaa.edu.cn/card/home/openHomePageApp").bodyAsText(),
            )
          }
          HttpClient(normalEngine).use { client ->
            assertEquals("physical-route", client.get("http://sso.buaa.edu.cn/login").bodyAsText())
          }
          assertEquals(2, physical.requests.get())
          assertEquals(0, ordinary.requests.get())
          assertTrue(ProxySelector.getDefault() === rejecting)
        } finally {
          cardEngine.close()
          normalEngine.close()
          ProxySelector.setDefault(oldSelector)
          app.bucc.connection.desktopPhysicalCampusProxy = oldPool
          app.bucc.api.ConnectionModeStore.settings = oldModes
          app.bucc.api.ConnectionRuntime.resolveSelectedMode()
        }
      }
    }
  }

  @Test
  fun `WebVPN card transport still reaches campus through the physical helper`() = runBlocking {
    val oldModes = app.bucc.api.ConnectionModeStore.settings
    val oldPool = app.bucc.connection.desktopPhysicalCampusProxy
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
    app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    FixtureServer("card-campus-route").use { physical ->
      app.bucc.connection.desktopPhysicalCampusProxy =
          app.bucc.connection.PhysicalCampusProxyPool(
              {
                object : app.bucc.connection.PhysicalCampusHelper {
                  override suspend fun exchange(request: String) =
                      "{\"status\":\"READY\",\"port\":${(physical.proxy.address() as InetSocketAddress).port}}"

                  override suspend fun close() = Unit
                }
              },
              { true },
          )
      val cardEngine = getCampusCardEngine()
      try {
        HttpClient(cardEngine).use { client ->
          assertEquals(
              "card-campus-route",
              client.get("http://m.icw.buaa.edu.cn/card/home/openHomePageApp").bodyAsText(),
          )
        }
        assertEquals(1, physical.requests.get())
      } finally {
        cardEngine.close()
        app.bucc.connection.desktopPhysicalCampusProxy = oldPool
        app.bucc.api.ConnectionModeStore.settings = oldModes
        app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      }
    }
  }

  @Test
  fun `connection loss after POST does not automatically replay a submission`() = runBlocking {
    for (engine in listOf(::getDefaultEngine, ::getCampusCardEngine)) {
      FixtureServer(null).use { server ->
        HttpClient(engine()) { install(HttpTimeout) { requestTimeoutMillis = 10_000 } }
            .use { client ->
              assertTrue(
                  runCatching { client.post(server.url) { setBody("synthetic-submission") } }
                      .isFailure
              )
              assertEquals(1, server.requests.get())
            }
      }
    }
  }

  private class FixtureServer(private val response: String?) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor =
        Executors.newSingleThreadExecutor { task ->
          Thread(task, "desktop-network-fixture").apply { isDaemon = true }
        }
    val requests = AtomicInteger()
    val proxy
      get() = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.localPort))

    val url
      get() = "http://127.0.0.1:${server.localPort}/submit"

    init {
      executor.submit {
        while (!server.isClosed) {
          val socket = runCatching { server.accept() }.getOrNull() ?: break
          socket.use {
            socket.soTimeout = 3000
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
            if (line().isNotBlank()) {
              var length = 0
              while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true))
                    length = header.substringAfter(':').trim().toInt()
              }
              input.readNBytes(length)
              requests.incrementAndGet()
              response?.let { body ->
                val bytes = body.encodeToByteArray()
                socket.getOutputStream().apply {
                  write(
                      "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                          .encodeToByteArray()
                  )
                  write(bytes)
                  flush()
                }
              }
            }
          }
        }
      }
    }

    override fun close() {
      server.close()
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }
}
