package app.bucc.api

import app.bucc.api.local.*
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Explicit read-only finance diagnostic; clones sessions and prints only fixed shapes. */
object FinanceReadProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking {
      val memory = MapSettings()
      val saved = Settings()
      saved.keys.forEach { key -> saved.getStringOrNull(key)?.let { memory.putString(key, it) } }
      ConnectionModeStore.settings = memory
      LocalAuthSessionStore.settings = memory
      LocalCookieStore.settings = memory
      val mode = ConnectionMode.valueOf(args.firstOrNull() ?: "DIRECT")
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      LocalUpstreamClientProvider.reset()
      println("FINANCE mode=$mode session=${LocalAuthSessionStore.get() != null}")
      val factory = LocalUpstreamClientProvider.isolatedClientFactory
      LocalUpstreamClientProvider.isolatedClientFactory = { redirects, cookies ->
        factory(redirects, cookies).apply {
          plugin(HttpSend).intercept { request ->
            check(request.method.value == "GET") { "Read-only probe" }
            val original = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.buildString()))
            check(
                original.host in
                    setOf(
                        "m.icw.buaa.edu.cn",
                        "it.buaa.edu.cn",
                        "zfw.buaa.edu.cn",
                        "sso.buaa.edu.cn",
                        "uc.buaa.edu.cn",
                        "d.buaa.edu.cn",
                    )
            )
            val path = original.encodedPath.takeIf { !it.contains(Regex("\\d{5}")) } ?: "[redacted]"
            try {
              val call = execute(request)
              println(
                  "GET host=${original.host} path=$path status=${call.response.status.value} gateway=${request.url.host == "d.buaa.edu.cn"}"
              )
              call.response.headers["Location"]?.let { location ->
                val target =
                    runCatching { Url(LocalWebVpnSupport.fromWebVpnUrl(location)) }.getOrNull()
                println(
                    "REDIRECT scheme=${target?.protocol?.name} host=${target?.host} relative=${location.startsWith('/')}"
                )
              }
              call
            } catch (e: Exception) {
              println(
                  "GET host=${original.host} path=$path error=${generateSequence(e as Throwable) { it.cause }.take(4).map { it::class.simpleName }.joinToString("/")}"
              )
              throw e
            }
          }
        }
      }
      try {
        val pageClient = LocalUpstreamClientProvider.newClient(LocalCookieStore.storage(mode))
        try {
          val page = pageClient.get(localUpstreamUrl("https://zfw.buaa.edu.cn/"))
          val html = page.bodyAsText()
          val forms = Regex("(?is)<form\\b([^>]*)>(.*?)</form>").findAll(html).toList()
          println(
              "ZFW_PAGE status=${page.status.value} parsed=${app.bucc.api.finance.parseLogin(html) != null} forms=${forms.size}"
          )
          forms.forEach { form ->
            val a = app.bucc.api.finance.formAttributes(form.groupValues[1])
            println(
                "ZFW_FORM method=${a["method"]} actionPath=${a["action"]?.substringBefore('?')} hasUsername=${form.value.contains("LoginForm[username]")} hasPassword=${form.value.contains("LoginForm[password]")} hasSms=${form.value.contains("LoginForm[smsCode]")} hasVerify=${form.value.contains("LoginForm[verifyCode]")}"
            )
          }
        } finally {
          pageClient.close()
        }
        val sessionCoordinator =
            app.bucc.api.finance.ZfwSessionCoordinator(
                clientFactory = {
                  LocalUpstreamClientProvider.newClient(LocalCookieStore.storage(mode))
                },
                credentials = {
                  app.bucc.security.CampusCredential("probe-not-submitted", "probe-not-submitted")
                },
            )
        try {
          val result =
              withTimeout(60000) { sessionCoordinator.ensureSession(forceVerification = true) }
          println(
              "ZFW state=${result::class.simpleName} reason=${(result as? app.bucc.api.finance.ZfwSessionState.Failed)?.reason}"
          )
        } finally {
          sessionCoordinator.close()
        }
        val balances =
            withTimeout(150000) {
              LocalCampusFinanceApi().loadBalances(LocalAuthSessionStore.get()?.username)
            }
        println(
            "BALANCES card=${balances.campusCard.value != null} cardError=${balances.campusCard.error} fee=${balances.internetFee.value != null} feeError=${balances.internetFee.error} traffic=${balances.internetTraffic.used != null} trafficError=${balances.internetTraffic.error}"
        )
      } finally {
        LocalUpstreamClientProvider.reset()
      }
    }
    kotlin.system.exitProcess(0)
  }
}
