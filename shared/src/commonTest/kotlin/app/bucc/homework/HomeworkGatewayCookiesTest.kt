package app.bucc.homework

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSession
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.localUpstreamUrl
import app.bucc.model.dto.UserData
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class HomeworkGatewayCookiesTest {
  private lateinit var oldMode: Settings
  private lateinit var oldAuth: Settings
  private lateinit var oldCookies: Settings

  @BeforeTest
  fun setup() {
    oldMode = ConnectionModeStore.settings
    oldAuth = LocalAuthSessionStore.settings
    oldCookies = LocalCookieStore.settings
    ConnectionModeStore.settings = MapSettings()
    LocalAuthSessionStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    useMode(ConnectionMode.WEBVPN)
  }

  @AfterTest
  fun restore() {
    ConnectionModeStore.settings = oldMode
    LocalAuthSessionStore.settings = oldAuth
    LocalCookieStore.settings = oldCookies
    ConnectionRuntime.resolveSelectedMode()
  }

  private fun useMode(mode: ConnectionMode) {
    ConnectionModeStore.save(mode)
    ConnectionRuntime.resolveSelectedMode()
    LocalAuthSessionStore.save(
        LocalAuthSession(
            "fixture-account",
            UserData(name = "Fixture", schoolid = "fixture-account"),
            "fixture-time",
            "fixture-time",
        )
    )
  }

  @Test
  fun `Accoding gets gateway authentication without other services or accounts`() = runTest {
    val main = LocalCookieStore.storage()
    main.addCookie(
        Url("https://d.buaa.edu.cn/"),
        Cookie("gateway-ticket", "fixture", path = "/", secure = true),
    )
    val other = Url(localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/"))
    main.addCookie(other, Cookie("other-service", "fixture", path = other.encodedPath))
    val target = Url(localUpstreamUrl("https://accoding.buaa.edu.cn/api/users/me"))
    val isolated = HomeworkCookies("fixture-account", HomeworkSource.ACCODING, MapSettings())
    isolated.addCookie(
        target,
        Cookie("accoding-session", "fixture", path = target.encodedPath.substringBeforeLast('/')),
    )
    assertEquals(
        setOf("gateway-ticket", "accoding-session"),
        isolated.get(target).map { it.name }.toSet(),
    )
    assertTrue(isolated.get(Url("https://accoding.buaa.edu.cn/api/users/me")).isEmpty())
    assertTrue(
        HomeworkCookies("another-account", HomeworkSource.ACCODING, MapSettings())
            .get(target)
            .isEmpty()
    )
  }

  @Test
  fun `Accoding service sessions cannot cross connection modes or in flight mode switches`() =
      runTest {
        val settings = MapSettings()
        val target = Url(localUpstreamUrl("https://accoding.buaa.edu.cn/user/login"))
        val webVpn = HomeworkCookies("fixture-account", HomeworkSource.ACCODING, settings)
        webVpn.addCookie(target, Cookie("service-session", "fixture", path = "/"))
        assertTrue(homeworkHasSavedSession("fixture-account", HomeworkSource.ACCODING, settings))
        useMode(ConnectionMode.DIRECT)
        assertTrue(webVpn.get(target).isEmpty())
        assertTrue(
            HomeworkCookies("fixture-account", HomeworkSource.ACCODING, settings)
                .get(target)
                .isEmpty()
        )
        assertFalse(homeworkHasSavedSession("fixture-account", HomeworkSource.ACCODING, settings))
      }

  @Test
  fun `health cloud shares only root gateway identity and clears private cookies on mode change`() =
      runTest {
        val main = LocalCookieStore.storage()
        main.addCookie(
            Url("https://d.buaa.edu.cn/"),
            Cookie("gateway-ticket", "fixture", path = "/", secure = true),
        )
        val library = Url(localUpstreamUrl("https://booking.lib.buaa.edu.cn/v4/"))
        main.addCookie(library, Cookie("library-only", "fixture", path = library.encodedPath))
        val isolated = app.bucc.td.TdHealthCloudCookies()
        val direct = Url("http://10.212.28.38/")
        isolated.addCookie(direct, Cookie("health-only", "fixture", path = "/"))
        val wrapped = Url(app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl(direct.toString()))
        assertEquals(listOf("gateway-ticket"), isolated.get(wrapped).map { it.name })
        assertEquals(listOf("health-only"), isolated.get(direct).map { it.name })
        assertTrue(isolated.get(library).isEmpty())
        useMode(ConnectionMode.DIRECT)
        assertTrue(isolated.get(direct).isEmpty())
      }
}
