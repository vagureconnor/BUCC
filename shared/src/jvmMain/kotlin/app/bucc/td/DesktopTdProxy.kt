package app.bucc.td

import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Only BUCC's dynamically allocated local backend ports bypass the system proxy. */
internal object DesktopTdProxy {
  private val ports = ConcurrentHashMap.newKeySet<Int>()

  @Synchronized
  fun allow(port: Int) {
    ports.add(port)
    if (ProxySelector.getDefault() !is LocalSelector) {
      ProxySelector.setDefault(LocalSelector(ProxySelector.getDefault()))
    }
  }

  fun remove(port: Int) {
    ports.remove(port)
  }

  private class LocalSelector(private val delegate: ProxySelector?) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        if (uri.host == "127.0.0.1" && uri.port in ports) listOf(Proxy.NO_PROXY)
        else delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)

    override fun connectFailed(
        uri: URI,
        address: java.net.SocketAddress,
        error: java.io.IOException,
    ) {
      delegate?.connectFailed(uri, address, error)
    }
  }
}
