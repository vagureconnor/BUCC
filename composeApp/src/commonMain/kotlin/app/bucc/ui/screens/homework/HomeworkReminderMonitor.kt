package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkItem
import app.bucc.homework.HomeworkRepository
import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Finds newly published assignments after a successful local refresh. */
class HomeworkReminderMonitor(
    private val account: String,
    private val repository: HomeworkRepository,
    private val settings: Settings = ScheduleStore.settings,
) {
  private val key = "homework_reminder_seen_$account"
  private val json = Json
  private val mutex = Mutex()
  private var knownIds =
      settings.getStringOrNull(key)?.let {
        runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
      }

  suspend fun check(): List<HomeworkItem> =
      mutex.withLock {
        repository.refresh()
        val current = repository.state.value.items
        val currentIds = current.mapTo(mutableSetOf()) { it.id }
        val previous = knownIds
        settings.putString(key, json.encodeToString(currentIds))
        knownIds = currentIds
        if (previous == null) emptyList() else current.filter { it.id !in previous }
      }
}
