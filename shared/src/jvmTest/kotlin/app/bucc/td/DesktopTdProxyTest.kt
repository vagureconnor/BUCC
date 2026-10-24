package app.bucc.td

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTdProxyTest {
  @Test
  fun onlyRegisteredLocalPortsBypassExistingProxyAndRemovalRestoresRouting() {
    val previous = ProxySelector.getDefault()
    val configured = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))
    ProxySelector.setDefault(
        object : ProxySelector() {
          override fun select(uri: URI) = listOf(configured)

          override fun connectFailed(
              uri: URI,
              address: java.net.SocketAddress,
              error: java.io.IOException,
          ) = Unit
        }
    )
    try {
      DesktopTdProxy.allow(12345)
      assertEquals(
          listOf(Proxy.NO_PROXY),
          ProxySelector.getDefault().select(URI("http://127.0.0.1:12345/api/state")),
      )
      assertEquals(
          listOf(configured),
          ProxySelector.getDefault().select(URI("https://spoc.buaa.edu.cn/")),
      )
      assertEquals(
          listOf(configured),
          ProxySelector.getDefault().select(URI("http://127.0.0.1:12346/")),
      )
      DesktopTdProxy.remove(12345)
      assertEquals(
          listOf(configured),
          ProxySelector.getDefault().select(URI("http://127.0.0.1:12345/")),
      )
    } finally {
      DesktopTdProxy.remove(12345)
      ProxySelector.setDefault(previous)
    }
  }
}
