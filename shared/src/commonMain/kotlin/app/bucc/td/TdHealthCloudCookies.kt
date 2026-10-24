package app.bucc.td

import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalWebVpnSupport
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Health-cloud sessions stay independent; only WebVPN's root transport identity is shared. */
internal class TdHealthCloudCookies : CookiesStorage {
  private val mutex = Mutex()
  private var scope = ConnectionRuntime.currentMode() to LocalAuthSessionStore.get()?.username
  private var own = AcceptAllCookiesStorage()

  private fun updateScope() {
    val current = ConnectionRuntime.currentMode() to LocalAuthSessionStore.get()?.username
    if (scope != current) {
      own.close()
      own = AcceptAllCookiesStorage()
      scope = current
    }
  }

  override suspend fun addCookie(requestUrl: Url, cookie: Cookie) =
      mutex.withLock {
        updateScope()
        own.addCookie(requestUrl, cookie)
      }

  override suspend fun get(requestUrl: Url): List<Cookie> =
      mutex.withLock {
        updateScope()
        val local = own.get(requestUrl)
        val host =
            runCatching { Url(LocalWebVpnSupport.fromWebVpnUrl(requestUrl.toString())).host }
                .getOrNull()
        val gateway =
            if (
                scope.second != null &&
                    requestUrl.host == "d.buaa.edu.cn" &&
                    host in setOf("10.212.28.38", "sso.buaa.edu.cn")
            ) {
              LocalCookieStore.storage().get(Url("https://d.buaa.edu.cn/")).filter {
                it.domain.orEmpty().trimStart('.').equals("d.buaa.edu.cn", true) && it.path == "/"
              }
            } else emptyList()
        val names = gateway.map { it.name }.toSet()
        gateway + local.filterNot { it.name in names }
      }

  override fun close() = own.close()
}
