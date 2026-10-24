package app.bucc.ui.screens.homework

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.storage.AuthTokensStore
import app.bucc.api.storage.GradeScoreCacheStore
import app.bucc.homework.HomeworkAccountNames
import app.bucc.homework.HomeworkAdapter
import app.bucc.homework.HomeworkItem
import app.bucc.homework.HomeworkRepository
import app.bucc.homework.HomeworkSource
import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class WebVpnHomeworkLoginTest {
  private val account = "webvpn-homework-login-test"

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `PatPat login stays closed and does not call adapter in WebVPN`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    withIsolatedMode(ConnectionMode.WEBVPN) { settings ->
      HomeworkAccountNames.set(account, HomeworkSource.PATPAT, "saved-patpat", settings)
      val adapter = FakePatPatAdapter()
      val viewModel = createViewModel(adapter, settings)
      try {
        viewModel.showLogin(HomeworkSource.PATPAT)
        viewModel.login("attempted-patpat", "synthetic-password")
        runCurrent()

        assertNull(viewModel.login.value.source)
        assertEquals(
            "saved-patpat",
            HomeworkAccountNames.get(account, HomeworkSource.PATPAT, settings),
        )
        assertEquals(0, adapter.loginCalls)
        assertNull(adapter.loggedInUsername)
      } finally {
        viewModel.close()
      }
    }
  }

  @Test
  fun `PatPat login remains available in Direct and preserves account management save`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    withIsolatedMode(ConnectionMode.DIRECT) { settings ->
      HomeworkAccountNames.set(account, HomeworkSource.PATPAT, "saved-patpat", settings)
      val adapter = FakePatPatAdapter()
      val viewModel = createViewModel(adapter, settings)
      try {
        viewModel.showLogin(HomeworkSource.PATPAT)

        assertEquals(HomeworkSource.PATPAT, viewModel.login.value.source)
        assertEquals("saved-patpat", viewModel.login.value.username)
        viewModel.updateLoginUsername("updated-patpat")
        viewModel.login(viewModel.login.value.username, "synthetic-password")
        runCurrent()

        assertEquals(1, adapter.loginCalls)
        assertEquals("updated-patpat", adapter.loggedInUsername)
        assertEquals(
            "updated-patpat",
            HomeworkAccountNames.get(account, HomeworkSource.PATPAT, settings),
        )
        assertNull(viewModel.login.value.source)
      } finally {
        viewModel.close()
      }
    }
  }

  private fun createViewModel(adapter: FakePatPatAdapter, settings: MapSettings) =
      HomeworkViewModel(HomeworkRepository(account, listOf(adapter), settings) { true })

  /**
   * Mode/session globals use test-only stores so mode switching cannot touch persistent
   * credentials.
   */
  private suspend fun withIsolatedMode(mode: ConnectionMode, block: suspend (MapSettings) -> Unit) {
    val previousMode = ConnectionRuntime.currentMode()
    val previousModeSettings = ConnectionModeStore.settings
    val previousScheduleSettings = ScheduleStore.settings
    val previousTokenSettings = AuthTokensStore.settings
    val previousLocalSessionSettings = LocalAuthSessionStore.settings
    val previousGradeCacheSettings = GradeScoreCacheStore.settings
    val testSettings = MapSettings()

    ConnectionModeStore.settings = testSettings
    ScheduleStore.settings = testSettings
    AuthTokensStore.settings = testSettings
    LocalAuthSessionStore.settings = testSettings
    GradeScoreCacheStore.settings = testSettings
    try {
      ConnectionRuntime.switchMode(mode)
      block(testSettings)
    } finally {
      if (previousMode == null) ConnectionRuntime.clearSelectedMode()
      else ConnectionRuntime.switchMode(previousMode)
      ConnectionModeStore.settings = previousModeSettings
      ScheduleStore.settings = previousScheduleSettings
      AuthTokensStore.settings = previousTokenSettings
      LocalAuthSessionStore.settings = previousLocalSessionSettings
      GradeScoreCacheStore.settings = previousGradeCacheSettings
    }
  }

  private class FakePatPatAdapter : HomeworkAdapter {
    override val source = HomeworkSource.PATPAT
    override val requiresSeparateLogin = true
    var loginCalls = 0
    var loggedInUsername: String? = null

    override suspend fun load(): List<HomeworkItem> = emptyList()

    override suspend fun login(username: String, password: String) {
      loginCalls += 1
      loggedInUsername = username
    }
  }
}
