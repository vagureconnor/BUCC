package app.bucc.api.core

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.connection.desktopPhysicalCampusProxy
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** CIO's null proxy still consults macOS ProxySelector; campus transport must be explicit. */
actual fun getDefaultEngine(): HttpClientEngine = createDesktopEngine(campusCardTransport = false)

internal fun createDesktopEngine(
    campusCardTransport: Boolean = false,
    relaxTls: Boolean = false,
    financeDirect: Boolean = false,
): HttpClientEngine {
  val physical = desktopPhysicalCampusProxy.acquire()
  val trustManager = if (relaxTls) relaxedTrustManager() else null
  val sslContext =
      trustManager?.let { manager ->
        SSLContext.getInstance("TLS").apply {
          init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }
      }
  val engine =
      OkHttp.create {
        config {
          proxySelector(
              object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                  if (financeDirect) {
                    // Finance hosts live behind the system proxy/fake-IP resolver; using the
                    // campus physical proxy or NO_PROXY here breaks m.icw.buaa.edu.cn.
                    return ProxySelector.getDefault()?.select(uri)?.takeIf { it.isNotEmpty() }
                        ?: listOf(Proxy.NO_PROXY)
                  }
                  // The card service may select its original HTTPS API before any order is sent.
                  // Keep this exception limited to that client and host; CAS retains its own route.
                  val physicalProxy =
                      if (
                          !financeDirect &&
                              (ConnectionRuntime.currentMode() == ConnectionMode.DIRECT ||
                                  (campusCardTransport &&
                                      uri.host.equals("m.icw.buaa.edu.cn", ignoreCase = true)))
                      )
                          physical.select(uri.host.orEmpty())
                      else null
                  return listOf(physicalProxy ?: Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) =
                    Unit
              }
          )
          retryOnConnectionFailure(false)
          if (relaxTls) {
            sslSocketFactory(requireNotNull(sslContext).socketFactory, requireNotNull(trustManager))
            hostnameVerifier(relaxedHostnameVerifier())
          }
        }
      }
  return object : HttpClientEngine by engine {
    override fun close() {
      try {
        engine.close()
      } finally {
        physical.close()
      }
    }
  }
}

/** Only the library booking host currently presents a chain the desktop trust store rejects. */
internal fun relaxedTrustManager(): X509TrustManager =
    object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit

      override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit

      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

internal fun relaxedHostnameVerifier(): javax.net.ssl.HostnameVerifier =
    javax.net.ssl.HostnameVerifier { _, _ -> true }

internal actual fun getCampusCardEngine(): HttpClientEngine =
    createDesktopEngine(campusCardTransport = true)

internal actual fun getFinanceDirectEngine(): HttpClientEngine =
    createDesktopEngine(financeDirect = true)

internal actual fun getFinanceCardEngine(): HttpClientEngine =
    createDesktopEngine(financeDirect = true, relaxTls = true)
