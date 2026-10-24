package app.bucc.api

import app.bucc.api.auth.AuthService
import app.bucc.api.core.DefaultApiFactory
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.storage.AuthTokensStore
import app.bucc.api.storage.CredentialStore
import app.bucc.api.storage.StoredAuthTokens
import app.bucc.model.dto.UserData
import com.russhwolf.settings.MapSettings
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionRuntimeTest {

  @BeforeTest
  fun setup() {
    val modeSettings = MapSettings()
    ConnectionModeStore.settings = modeSettings
    AuthTokensStore.settings = MapSettings()
    CredentialStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    AuthTokensStore.clearAllScopes()
    CredentialStore.clear()
    LocalAuthSessionStore.clearAllScopes()
    LocalCookieStore.clearAllScopes()
    ConnectionRuntime.apiFactoryProvider = { DefaultApiFactory }
  }

  @Test
  fun `switching connection mode clears all scoped sessions and disables auto login`() {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    AuthTokensStore.save(
        StoredAuthTokens(
            accessToken = "relay-access",
            refreshToken = "relay-refresh",
        )
    )

    ConnectionModeStore.save(ConnectionMode.DIRECT)
    AuthTokensStore.save(
        StoredAuthTokens(
            accessToken = "direct-access",
            refreshToken = "direct-refresh",
        )
    )
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
        )
    )

    CredentialStore.saveUsername("22373333")
    CredentialStore.setRememberUsername(true)

    ConnectionRuntime.resolveSelectedMode()
    ConnectionRuntime.switchMode(ConnectionMode.WEBVPN)

    assertEquals(ConnectionMode.WEBVPN, ConnectionRuntime.currentMode())
    assertNull(AuthTokensStore.get())
    assertNull(LocalAuthSessionStore.get())
    assertTrue(CredentialStore.isRememberUsername())
    assertEquals("22373333", CredentialStore.getUsername())
    assertNull(CredentialStore.getPassword())

    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    assertNull(AuthTokensStore.get())
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    assertNull(AuthTokensStore.get())
  }

  @Test
  fun `mode switching preserves isolated gateway cookies for revalidation`() =
      kotlinx.coroutines.test.runTest {
        ConnectionModeStore.save(ConnectionMode.WEBVPN)
        ConnectionRuntime.resolveSelectedMode()
        LocalCookieStore.storage(ConnectionMode.WEBVPN)
            .addCookie(
                io.ktor.http.Url("https://d.buaa.edu.cn/"),
                io.ktor.http.Cookie(
                    "gateway-fixture",
                    "fixture",
                    domain = "d.buaa.edu.cn",
                    path = "/",
                ),
            )
        ConnectionRuntime.switchMode(ConnectionMode.DIRECT)
        assertTrue(
            LocalCookieStore.storage(ConnectionMode.DIRECT)
                .get(io.ktor.http.Url("https://d.buaa.edu.cn/"))
                .isEmpty()
        )
        ConnectionRuntime.switchMode(ConnectionMode.WEBVPN)
        assertEquals(
            1,
            LocalCookieStore.storage(ConnectionMode.WEBVPN)
                .get(io.ktor.http.Url("https://d.buaa.edu.cn/"))
                .size,
        )
      }

  @Test
  fun `only first launch requires choosing a mode and remembered webvpn resolves on restart`() {
    assertNull(ConnectionRuntime.resolveSelectedMode())
    ConnectionRuntime.switchMode(ConnectionMode.WEBVPN)
    assertEquals(ConnectionMode.WEBVPN, ConnectionRuntime.resolveSelectedMode())
    assertEquals(ConnectionMode.WEBVPN, ConnectionModeStore.get())
  }

  @Test
  fun `direct mode auth service uses local persisted session contract by default`() {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    LocalAuthSessionStore.save(
        LocalAuthSession(
            username = "22373333",
            user = UserData(name = "Test User", schoolid = "22373333"),
            authenticatedAt = "2026-04-20T08:00:00Z",
            lastActivity = "2026-04-20T08:30:00Z",
        )
    )

    assertTrue(AuthService().hasPersistedSession())

    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    assertFalse(AuthService().hasPersistedSession())
  }
}
