package app.bucc.api

import app.bucc.api.auth.ApiCallException
import app.bucc.api.local.*
import app.bucc.api.storage.CredentialStore
import app.bucc.security.CampusSecureCredentials
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * Explicit local diagnostic. No response, identity, cookie or credential is printed or persisted.
 */
object LoginConnectivityProbe {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val saved = Settings()
    val memory = MapSettings()
    saved.keys.forEach { key -> saved.getStringOrNull(key)?.let { memory.putString(key, it) } }
    val mode =
        args
            .firstOrNull { it.startsWith("--mode=") }
            ?.substringAfter('=')
            ?.let { ConnectionMode.valueOf(it) } ?: ConnectionMode.DIRECT
    ConnectionModeStore.settings = memory
    LocalAuthSessionStore.settings = memory
    LocalCookieStore.settings = memory
    ConnectionModeStore.save(mode)
    ConnectionRuntime.resolveSelectedMode()
    if ("--clean" in args) {
      LocalAuthSessionStore.clearAllScopes()
      LocalCookieStore.clearAllScopes()
    }
    println("PROBE mode=$mode")
    var credentialPosts = 0
    LocalUpstreamClientProvider.reset()
    val factory =
        if ("--direct-transport" in args) {
          { redirects: Boolean ->
            HttpClient(
                OkHttp.create {
                  config {
                    proxy(Proxy.NO_PROXY)
                    retryOnConnectionFailure(false)
                  }
                }
            ) {
              followRedirects = redirects
              install(HttpCookies) { storage = LocalCookieStore.storage(mode) }
            }
          }
        } else LocalUpstreamClientProvider.clientFactory
    LocalUpstreamClientProvider.clientFactory = { redirects ->
      factory(redirects).apply {
        plugin(HttpSend).intercept { request ->
          val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.buildString()))
          check(upstream.host in setOf("d.buaa.edu.cn", "uc.buaa.edu.cn", "sso.buaa.edu.cn"))
          check(
              request.method.value == "GET" ||
                  ("--restore-login" in args &&
                      request.method.value == "POST" &&
                      upstream.host == "sso.buaa.edu.cn" &&
                      upstream.encodedPath == "/login")
          )
          if (request.method.value == "POST") {
            credentialPosts++
            check(credentialPosts == 1) { "Credential replay blocked" }
          }
          val label =
              "${request.method.value} gateway=${request.url.host == "d.buaa.edu.cn"} ${upstream.host}${upstream.encodedPath}"
          try {
            val call = execute(request)
            println(
                "HTTP $label status=${call.response.status.value} service=${upstream.parameters["service"]?.let { runCatching { Url(it).host }.getOrNull() } ?: "none"}"
            )
            if (request.method.value == "POST") {
              val stored = LocalCookieStore.load(mode)
              println(
                  "COOKIE_SHAPE stored=${stored.size} ssoDomain=${stored.count { it.cookie.domain?.contains("sso.buaa.edu.cn") == true }} gatewayDomain=${stored.count { it.cookie.domain == "d.buaa.edu.cn" }} casNames=${stored.count { it.cookie.name in setOf("TGC", "CASTGC") }}"
              )
            }
            call.response.headers["Location"]?.let { location ->
              if (location.startsWith("https://") || location.startsWith("http://")) {
                val destination = Url(LocalWebVpnSupport.fromWebVpnUrl(location))
                println(
                    "REDIRECT host=${destination.host} path=${destination.encodedPath} gateway=${Url(location).host == "d.buaa.edu.cn"}"
                )
              } else println("REDIRECT relative=true")
            }
            call
          } catch (e: Exception) {
            println(
                "HTTP $label error=${generateSequence(e as Throwable) { it.cause }.take(4).map { it::class.simpleName }.joinToString("/")}"
            )
            throw e
          }
        }
      }
    }
    try {
      for (original in
          listOf("https://uc.buaa.edu.cn/api/uc/status", "https://sso.buaa.edu.cn/login")) {
        for (gateway in listOf(false, true)) {
          val client = LocalUpstreamClientProvider.newNoRedirectClient()
          try {
            withTimeout(7000) {
              val response =
                  client.get(if (gateway) LocalWebVpnSupport.toWebVpnUrl(original) else original)
              val text = response.bodyAsText()
              if (original.contains("/api/uc/status") && text.trimStart().startsWith("{")) {
                val payload = Json.parseToJsonElement(text).jsonObject
                val code = payload["code"]?.jsonPrimitive?.intOrNull
                println(
                    "UC code=${if (code in setOf(0, 10600)) code else "other"} hasData=${payload["data"] is JsonObject}"
                )
              }
              println(
                  "PAGE host=${Url(original).host} gateway=$gateway status=${response.status.value} execution=${LocalCasParser.extractExecution(text).isNotBlank()} json=${text.trimStart().startsWith("{")}"
              )
            }
          } catch (e: Exception) {
            println("PAGE host=${Url(original).host} gateway=$gateway error=${e::class.simpleName}")
          } finally {
            client.close()
          }
        }
      }
      if ("--transport-only" in args) return@runBlocking
      val backend = LocalAuthServiceBackend()
      val preload = withTimeout(60000) { backend.preloadLoginState() }
      println(
          "PRELOAD success=${preload.isSuccess} restored=${preload.getOrNull()?.userData != null} challenge=${!preload.getOrNull()?.execution.isNullOrBlank()} captcha=${preload.getOrNull()?.captchaRequired} code=${(preload.exceptionOrNull() as? ApiCallException)?.code} error=${preload.exceptionOrNull()?.let { it::class.simpleName }}"
      )
      if (
          "--restore-login" in args &&
              preload.isSuccess &&
              preload.getOrNull()?.userData == null &&
              preload.getOrNull()?.captchaRequired != true
      ) {
        val username = CredentialStore.getUsername()
        val credential = username?.let { CampusSecureCredentials.load(it) }
        if (credential == null) println("LOGIN availableCredential=false")
        else {
          val result =
              withTimeout(120000) {
                backend.login(
                    credential.username,
                    credential.password,
                    null,
                    preload.getOrNull()?.execution,
                )
              }
          println(
              "LOGIN success=${result.isSuccess} posts=$credentialPosts code=${(result.exceptionOrNull() as? ApiCallException)?.code} error=${result.exceptionOrNull()?.let { it::class.simpleName }}"
          )
          val direct = LocalUpstreamClientProvider.newNoRedirectClient()
          try {
            val response =
                direct.get(
                    "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fuc.buaa.edu.cn%2Fapi%2Flogin%2Fcas"
                )
            println(
                "DIRECT_CAS status=${response.status.value} challenge=${LocalCasParser.extractExecution(response.bodyAsText()).isNotBlank()}"
            )
          } catch (e: Exception) {
            println("DIRECT_CAS error=${e::class.simpleName}")
          } finally {
            direct.close()
          }
          if (result.isSuccess) {
            val verified = backend.getAuthStatus()
            println("VERIFIED success=${verified.isSuccess}")
          }
        }
      }
    } finally {
      LocalUpstreamClientProvider.reset()
    }
  }
}
