package app.bucc.ui.screens.homework

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
class HomeworkAccountEditTest {
  private val account = "homework-account-edit-test"
  private val settings = MapSettings()

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `edit form preloads saved username and cancel keeps stored value`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    ScheduleStore.settings = settings
    HomeworkAccountNames.set(account, HomeworkSource.ACCODING, "old@example.test", settings)
    val viewModel = viewModel(FakeAccountAdapter())
    try {
      viewModel.showLogin(HomeworkSource.ACCODING)
      assertEquals("old@example.test", viewModel.login.value.username)
      assertEquals("old@example.test", viewModel.login.value.savedUsername)

      viewModel.updateLoginUsername("draft@example.test")
      assertEquals(
          "old@example.test",
          HomeworkAccountNames.get(account, HomeworkSource.ACCODING, settings),
      )
      viewModel.dismissLogin()
      viewModel.showLogin(HomeworkSource.ACCODING)

      assertEquals("old@example.test", viewModel.login.value.username)
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `patpat edit form preloads saved username and cancel keeps stored value`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    ScheduleStore.settings = settings
    HomeworkAccountNames.set(account, HomeworkSource.PATPAT, "old-patpat", settings)
    val viewModel = viewModel(FakeAccountAdapter(source = HomeworkSource.PATPAT))
    try {
      viewModel.showLogin(HomeworkSource.PATPAT)
      assertEquals("old-patpat", viewModel.login.value.username)
      assertEquals("old-patpat", viewModel.login.value.savedUsername)

      viewModel.updateLoginUsername("draft-patpat")
      assertEquals("old-patpat", HomeworkAccountNames.get(account, HomeworkSource.PATPAT, settings))
      viewModel.dismissLogin()
      viewModel.showLogin(HomeworkSource.PATPAT)

      assertEquals("old-patpat", viewModel.login.value.username)
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `failed save keeps previous username and edited draft`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    ScheduleStore.settings = settings
    HomeworkAccountNames.set(account, HomeworkSource.ACCODING, "old@example.test", settings)
    val adapter = FakeAccountAdapter(failure = "invalid credentials")
    val viewModel = viewModel(adapter)
    try {
      viewModel.showLogin(HomeworkSource.ACCODING)
      viewModel.updateLoginUsername("new@example.test")
      viewModel.login(viewModel.login.value.username, "test-password")
      runCurrent()

      assertEquals(
          "old@example.test",
          HomeworkAccountNames.get(account, HomeworkSource.ACCODING, settings),
      )
      assertEquals("new@example.test", viewModel.login.value.username)
      assertEquals("invalid credentials", viewModel.login.value.error)
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `successful save commits edited username after login succeeds`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    ScheduleStore.settings = settings
    HomeworkAccountNames.set(account, HomeworkSource.PATPAT, "old-patpat", settings)
    val adapter = FakeAccountAdapter(source = HomeworkSource.PATPAT)
    val viewModel = viewModel(adapter)
    try {
      viewModel.showLogin(HomeworkSource.PATPAT)
      viewModel.updateLoginUsername("new-patpat")
      viewModel.login(viewModel.login.value.username, "test-password")
      runCurrent()

      assertEquals("new-patpat", HomeworkAccountNames.get(account, HomeworkSource.PATPAT, settings))
      assertEquals("new-patpat", adapter.loggedInUsername)
      assertNull(viewModel.login.value.source)
    } finally {
      viewModel.close()
    }
  }

  private fun viewModel(adapter: FakeAccountAdapter) =
      HomeworkViewModel(HomeworkRepository(account, listOf(adapter), settings) { true })

  private class FakeAccountAdapter(
      override val source: HomeworkSource = HomeworkSource.ACCODING,
      private val failure: String? = null,
  ) : HomeworkAdapter {
    override val requiresSeparateLogin = true
    var loggedInUsername: String? = null

    override suspend fun load(): List<HomeworkItem> = emptyList()

    override suspend fun login(username: String, password: String) {
      if (failure != null) error(failure)
      loggedInUsername = username
    }
  }
}
