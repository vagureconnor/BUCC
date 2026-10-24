package app.bucc.td

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createTdService(): TdService {
  val httpClient =
      HttpClient(Curl) {
        followRedirects = false
        install(HttpCookies) { storage = TdHealthCloudCookies() }
        install(HttpTimeout) {
          requestTimeoutMillis = 15_000
          connectTimeoutMillis = 10_000
          socketTimeoutMillis = 15_000
        }
      }
  val protocol = TdProtocolClient(socketFactory = TdSocketFactory(::HarmonyTdSocket))
  return TdDirectService(
      store = HarmonyTdStore(),
      gateway = TdProtocolGateway(protocol),
      healthCloud = TdHealthCloudClient(httpClient),
      powerController = TdExecutionPowerController(HarmonyTdRuntime::setExecutionActive),
  )
}

/** The Ability host observes this state to control its own keep-screen-on flag. */
object HarmonyTdRuntime {
  private val active = MutableStateFlow(false)
  val executionActive: StateFlow<Boolean> = active.asStateFlow()

  internal fun setExecutionActive(value: Boolean) {
    active.value = value
  }

  fun notifyAppBackgrounded() {
    TdRuntime.notifyAppBackgrounded()
    active.value = false
  }
}
