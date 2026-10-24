package app.bucc.connection

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Campus DIRECT and explicitly selected card API requests use the scoped helper; no system proxy,
 * DNS or route is changed.
 */
internal class PhysicalCampusProxyPool(
    private val hostFactory: () -> PhysicalCampusHelper = { DesktopPhysicalCampusHelper() },
    private val supported: () -> Boolean = { physicalCampusProxySupported },
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val mutex = Mutex()
  private val references = AtomicInteger()
  private var host: PhysicalCampusHelper? = null

  fun acquire(): Lease {
    references.incrementAndGet()
    return Lease()
  }

  inner class Lease internal constructor() : AutoCloseable {
    private val closed = AtomicBoolean()

    fun select(hostname: String): Proxy? {
      val name = hostname.lowercase().trimEnd('.')
      if (closed.get() || !supported() || !name.endsWith(".buaa.edu.cn")) return null
      // ProxySelector is invoked by OkHttp's blocking network worker, never the UI thread.
      return runBlocking {
        mutex.withLock {
          if (closed.get()) return@withLock null
          try {
            val active = host ?: hostFactory().also { host = it }
            val reply = Json.parseToJsonElement(active.exchange("{\"op\":\"endpoint\"}")).jsonObject
            val port = reply["port"]?.jsonPrimitive?.intOrNull
            if (
                reply["status"]?.jsonPrimitive?.content == "READY" &&
                    port != null &&
                    port in 1..65535
            ) {
              Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            } else null // No password has been sent; preserve the ordinary route off campus.
          } catch (_: Exception) {
            runCatching { host?.close() }
            host = null
            null
          }
        }
      }
    }

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      if (references.decrementAndGet() == 0)
          cleanupScope.launch {
            mutex.withLock {
              if (references.get() == 0) {
                try {
                  host?.close()
                } finally {
                  host = null
                }
              }
            }
          }
    }
  }
}

internal var desktopPhysicalCampusProxy = PhysicalCampusProxyPool()
