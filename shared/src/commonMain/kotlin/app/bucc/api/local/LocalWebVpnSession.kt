package app.bucc.api.local

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.ApiCallException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.takeFrom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** These services need a gateway session independently of the UC login route. */
internal fun localGatewayServiceUrl(url: String): String =
    if (ConnectionRuntime.currentMode() == ConnectionMode.WEBVPN) {
      LocalWebVpnSupport.toWebVpnUrl(url)
    } else url

/** Reuse the SSO session established by primary login, even for a gateway business service. */
internal fun localServiceSsoUrl(url: String): String = localGatewayServiceUrl(url)

private val gatewaySessionMutex = Mutex()

/** Mirror the official gateway's document.cookie bridge; upstream cookies live on its server. */
internal suspend fun localGatewayCookie(upstreamUrl: String, name: String): String? {
  if (ConnectionRuntime.currentMode() != ConnectionMode.WEBVPN) return null
  val upstream = Url(upstreamUrl)
  require(
      upstream.protocol.name == "https" &&
          upstream.host == "cgyy.buaa.edu.cn" &&
          upstream.port == 443
  )
  val response =
      LocalUpstreamClientProvider.shared().get("https://d.buaa.edu.cn/wengine-vpn/cookie") {
        parameter("method", "get")
        parameter("host", upstream.host)
        parameter("scheme", upstream.protocol.name)
        parameter("path", upstream.encodedPath)
      }
  check(response.status == HttpStatusCode.OK) { "无法接续研讨室网关会话，请刷新后重试" }
  val cookies = response.bodyAsText()
  return cookies.split(';').firstNotNullOfOrNull { entry ->
    entry
        .trim()
        .takeIf { it.substringBefore('=') == name && '=' in it }
        ?.substringAfter('=')
        ?.decodeURLQueryComponent(plusIsSpace = false)
  }
}

/** Complete the official CAS service callback using existing SSO cookies, never passwords. */
internal suspend fun ensureLocalWebVpnSession() {
  val mode = ConnectionRuntime.currentMode()
  if (mode != ConnectionMode.WEBVPN) return
  val session = LocalAuthSessionStore.get() ?: throw gatewaySessionRequired()
  gatewaySessionMutex.withLock {
    val client = LocalUpstreamClientProvider.newNoRedirectClient()
    try {
      var target = "https://d.buaa.edu.cn/"
      repeat(12) {
        check(
            ConnectionRuntime.currentMode() == mode &&
                LocalAuthSessionStore.get()?.username == session.username
        ) {
          "连接模式或账号已变化，请重新打开服务"
        }
        val response = client.get(target)
        check(
            ConnectionRuntime.currentMode() == mode &&
                LocalAuthSessionStore.get()?.username == session.username
        ) {
          "连接模式或账号已变化，请重新打开服务"
        }
        val actual = response.call.request.url
        if (actual.toString() == "https://d.buaa.edu.cn/" && response.status == HttpStatusCode.OK)
            return
        if (response.status.value !in 300..399) throw gatewaySessionRequired()
        val location = response.headers[HttpHeaders.Location] ?: throw gatewaySessionRequired()
        val resolved =
            URLBuilder(actual)
                .apply {
                  parameters.clear()
                  fragment = ""
                  takeFrom(location)
                }
                .buildString()
        val parsed = Url(resolved)
        val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(resolved))
        if (
            parsed.protocol.name != "https" ||
                parsed.port != 443 ||
                !parsed.user.isNullOrEmpty() ||
                !parsed.password.isNullOrEmpty() ||
                parsed.host !in setOf("d.buaa.edu.cn", "sso.buaa.edu.cn") ||
                (parsed.host == "d.buaa.edu.cn" &&
                    upstream.host !in setOf("d.buaa.edu.cn", "sso.buaa.edu.cn"))
        ) {
          throw gatewaySessionRequired()
        }
        // Reuse the SSO identity already established by login. Obtain a ticket
        // for the gateway service without asking for or re-submitting credentials.
        target =
            if (upstream.host == "sso.buaa.edu.cn") {
              val service = upstream.parameters["service"]
              if (
                  upstream.encodedPath != "/login" ||
                      service != "https://d.buaa.edu.cn/login?cas_login=true"
              ) {
                throw gatewaySessionRequired()
              }
              localServiceSsoUrl(upstream.toString())
            } else resolved
      }
      throw gatewaySessionRequired()
    } finally {
      client.close()
    }
  }
}

private fun gatewaySessionRequired() =
    ApiCallException(
        "WebVPN 网关认证未接续，请重新登录 BUCC 后手动刷新；如需验证码，请切换 WebVPN 模式完成登录。",
        code = "webvpn_session_required",
    )
