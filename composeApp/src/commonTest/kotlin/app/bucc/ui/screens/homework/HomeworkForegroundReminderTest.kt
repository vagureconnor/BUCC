package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkAdapter
import app.bucc.homework.HomeworkItem
import app.bucc.homework.HomeworkRepository
import app.bucc.homework.HomeworkSource
import app.bucc.notifications.NotificationPreferenceStore
import app.bucc.notifications.NotificationPreferences
import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkForegroundReminderTest {
  private val originalSettings = ScheduleStore.settings
  private val account = "homework-foreground-reminder-test"

  @AfterTest
  fun tearDown() {
    ScheduleStore.settings = originalSettings
    Dispatchers.resetMain()
  }

  @Test
  fun `foreground checks seed current work and track later assignments`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val settings = MapSettings()
    ScheduleStore.settings = settings
    NotificationPreferenceStore(settings)
        .write(
            account,
            NotificationPreferences(newHomework = false),
        )
    val adapter = FixtureHomeworkAdapter(mutableListOf(homework("existing")))
    val viewModel = viewModel(adapter, settings)
    try {
      viewModel.onAppForegrounded()
      runCurrent()

      assertEquals(1, adapter.loadCount)
      assertEquals(setOf("existing"), seenIds(settings))

      adapter.items += homework("new")
      viewModel.onAppForegrounded()
      runCurrent()

      assertEquals(2, adapter.loadCount)
      assertEquals(setOf("existing", "new"), seenIds(settings))
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `foreground check does not refresh while session is unavailable`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val settings = MapSettings()
    ScheduleStore.settings = settings
    NotificationPreferenceStore(settings)
        .write(
            account,
            NotificationPreferences(newHomework = false),
        )
    val adapter = FixtureHomeworkAdapter(mutableListOf(homework("existing")))
    val viewModel = viewModel(adapter, settings)
    try {
      viewModel.setSessionAvailable(false)
      viewModel.onAppForegrounded()
      runCurrent()

      assertEquals(0, adapter.loadCount)
      assertEquals(emptySet(), seenIds(settings))
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `overlapping foreground checks perform one refresh`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val settings = MapSettings()
    ScheduleStore.settings = settings
    NotificationPreferenceStore(settings)
        .write(
            account,
            NotificationPreferences(newHomework = false),
        )
    val gate = CompletableDeferred<Unit>()
    val adapter = FixtureHomeworkAdapter(mutableListOf(homework("existing")), gate)
    val viewModel = viewModel(adapter, settings)
    try {
      viewModel.onAppForegrounded()
      viewModel.onAppForegrounded()
      runCurrent()

      assertEquals(1, adapter.loadCount)
      gate.complete(Unit)
      runCurrent()
      assertEquals(setOf("existing"), seenIds(settings))
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `session becoming unavailable cancels an in flight foreground check`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val settings = MapSettings()
    ScheduleStore.settings = settings
    NotificationPreferenceStore(settings)
        .write(
            account,
            NotificationPreferences(newHomework = false),
        )
    val gate = CompletableDeferred<Unit>()
    val adapter = FixtureHomeworkAdapter(mutableListOf(homework("old-session")), gate)
    val viewModel = viewModel(adapter, settings)
    try {
      viewModel.onAppForegrounded()
      runCurrent()
      assertEquals(1, adapter.loadCount)

      viewModel.setSessionAvailable(false)
      gate.complete(Unit)
      runCurrent()

      assertEquals(emptySet(), seenIds(settings))
    } finally {
      viewModel.close()
    }
  }

  @Test
  fun `closing the session unregisters its background refresh handler once`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val settings = MapSettings()
    val repository =
        HomeworkRepository(
            account,
            listOf(FixtureHomeworkAdapter(mutableListOf())),
            settings,
        ) { true }
    var installedHandler: HomeworkBackgroundTaskHandler? = null
    var disposeCalls = 0
    val viewModel =
        HomeworkViewModel(repository) { handler ->
          installedHandler = handler
          HomeworkBackgroundTaskRegistration {
            disposeCalls++
            if (installedHandler === handler) installedHandler = null
          }
        }

    viewModel.close()
    viewModel.close()

    assertEquals(1, disposeCalls)
    assertNull(installedHandler)
  }

  @Test
  fun `session becoming unavailable cancels an in flight background check`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val settings = MapSettings()
    ScheduleStore.settings = settings
    val gate = CompletableDeferred<Unit>()
    val adapter = FixtureHomeworkAdapter(mutableListOf(homework("old-session")), gate)
    var installedHandler: HomeworkBackgroundTaskHandler? = null
    val viewModel =
        HomeworkViewModel(
            HomeworkRepository(account, listOf(adapter), settings) { true },
        ) { handler ->
          installedHandler = handler
          HomeworkBackgroundTaskRegistration {
            if (installedHandler === handler) installedHandler = null
          }
        }
    val completion = CompletableDeferred<String>()
    try {
      installedHandler!!.refresh(
          object : HomeworkBackgroundTaskCompletion {
            override fun finish(status: String) {
              completion.complete(status)
            }
          }
      )
      runCurrent()
      assertEquals(1, adapter.loadCount)

      viewModel.setSessionAvailable(false)
      gate.complete(Unit)
      runCurrent()

      assertEquals("cancelled", completion.await())
      assertEquals(emptySet(), seenIds(settings))
    } finally {
      viewModel.close()
    }
  }

  private fun viewModel(adapter: FixtureHomeworkAdapter, settings: Settings) =
      HomeworkViewModel(
          HomeworkRepository(account, listOf(adapter), settings) { true },
      )

  private fun seenIds(settings: MapSettings): Set<String> =
      settings
          .getStringOrNull("homework_reminder_seen_$account")
          ?.let { Json.decodeFromString<Set<String>>(it) }
          .orEmpty()

  private fun homework(id: String) =
      HomeworkItem(
          id = id,
          source = HomeworkSource.SPOC,
          upstreamId = id,
          title = id,
      )

  private class FixtureHomeworkAdapter(
      val items: MutableList<HomeworkItem>,
      private val loadGate: CompletableDeferred<Unit>? = null,
  ) : HomeworkAdapter {
    override val source = HomeworkSource.SPOC
    var loadCount = 0
      private set

    override suspend fun load(): List<HomeworkItem> {
      loadCount++
      loadGate?.await()
      return items.toList()
    }
  }
}
