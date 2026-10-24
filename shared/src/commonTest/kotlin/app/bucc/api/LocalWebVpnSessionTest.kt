package app.bucc.api

import app.bucc.api.auth.ApiCallException
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.ensureLocalWebVpnSession
import app.bucc.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class LocalWebVpnSessionTest {
  @BeforeTest
  fun setup() = runTest {
    localConnectionTestMutex.lock()
    ConnectionModeStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    LocalAuthSessionStore.save(
        LocalAuthSession("fixture", UserData("Fixture", "fixture"), "fixture", "fixture")
    )
    LocalUpstreamClientProvider.reset()
  }

  @AfterTest
  fun restore() {
    LocalUpstreamClientProvider.reset()
    ConnectionRuntime.clearSelectedMode()
    localConnectionTestMutex.unlock()
  }

  private fun install(engine: MockEngine) {
    LocalUpstreamClientProvider.clientFactory = { HttpClient(engine) { followRedirects = false } }
  }

  @Test
  fun `credentials challenge stops without password requests or retries`() = runTest {
    var calls = 0
    install(
        MockEngine { request ->
          calls++
          assertEquals("GET", request.method.value)
          if (request.url.encodedPath == "/")
              respond(
                  "",
                  HttpStatusCode.Found,
                  headersOf(
                      HttpHeaders.Location,
                      LocalWebVpnSupport.toWebVpnUrl(
                          "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fd.buaa.edu.cn%2Flogin%3Fcas_login%3Dtrue"
                      ),
                  ),
              )
          else respond("<form><input name='password'></form>", HttpStatusCode.OK)
        }
    )
    val failure = assertFailsWith<ApiCallException> { ensureLocalWebVpnSession() }
    assertEquals("webvpn_session_required", failure.code)
    assertEquals(2, calls)
  }

  @Test
  fun `foreign gateway redirects are rejected before another request`() = runTest {
    var calls = 0
    install(
        MockEngine {
          calls++
          respond(
              "",
              HttpStatusCode.Found,
              headersOf(HttpHeaders.Location, "https://unrelated.invalid/login"),
          )
        }
    )
    assertFailsWith<ApiCallException> { ensureLocalWebVpnSession() }
    assertEquals(1, calls)
  }

  @Test
  fun `gateway redirect loop is bounded and never posts credentials`() = runTest {
    var calls = 0
    install(
        MockEngine { request ->
          calls++
          assertEquals("GET", request.method.value)
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/login"))
        }
    )
    assertFailsWith<ApiCallException> { ensureLocalWebVpnSession() }
    assertEquals(12, calls)
  }

  @Test
  fun `concurrent readers complete only one gateway callback`() = runTest {
    var authorized = false
    var callbacks = 0
    install(
        MockEngine { request ->
          if (request.url.encodedPath == "/") {
            if (authorized) respond("portal", HttpStatusCode.OK)
            else
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "/login?ticket=fixture"),
                )
          } else {
            callbacks++
            authorized = true
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
          }
        }
    )
    val first = async { ensureLocalWebVpnSession() }
    val second = async { ensureLocalWebVpnSession() }
    first.await()
    second.await()
    assertEquals(1, callbacks)
  }
}
