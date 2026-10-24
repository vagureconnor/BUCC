package app.bucc.homework

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import com.russhwolf.settings.MapSettings
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class WebVpnServiceGateTest {
  @Test
  fun `WebVPN skips PatPat refresh and login while direct mode still calls both`() = runTest {
    val previous = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    try {
      var loads = 0
      var logins = 0
      val adapter =
          object : HomeworkAdapter {
            override val source = HomeworkSource.PATPAT

            override suspend fun load(): List<HomeworkItem> {
              loads++
              return emptyList()
            }

            override suspend fun login(username: String, password: String) {
              logins++
            }
          }
      val repo = HomeworkRepository("synthetic", listOf(adapter), MapSettings()) { true }
      ConnectionModeStore.save(ConnectionMode.WEBVPN)
      ConnectionRuntime.resolveSelectedMode()
      repo.refresh()
      runCatching { repo.login(HomeworkSource.PATPAT, "synthetic", "fixture") }
      assertEquals(0, loads)
      assertEquals(0, logins)
      ConnectionModeStore.save(ConnectionMode.DIRECT)
      ConnectionRuntime.resolveSelectedMode()
      repo.refresh()
      repo.login(HomeworkSource.PATPAT, "synthetic", "fixture")
      assertEquals(2, loads)
      assertEquals(1, logins)
    } finally {
      ConnectionModeStore.settings = previous
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `TD runtime uses the same service in both modes so WebVPN can query counts`() {
    val previous = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    try {
      ConnectionModeStore.save(ConnectionMode.WEBVPN)
      ConnectionRuntime.resolveSelectedMode()
      val webVpnService = app.bucc.td.TdRuntime.service
      ConnectionModeStore.save(ConnectionMode.DIRECT)
      ConnectionRuntime.resolveSelectedMode()
      assertSame(webVpnService, app.bucc.td.TdRuntime.service)
    } finally {
      ConnectionModeStore.settings = previous
      ConnectionRuntime.resolveSelectedMode()
    }
  }
}
