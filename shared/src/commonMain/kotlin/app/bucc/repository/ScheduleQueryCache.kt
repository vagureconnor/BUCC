package app.bucc.repository

import kotlinx.serialization.json.Json

/** Incremental online snapshots are separate from complete manual semester imports. */
internal object ScheduleQueryCache {
  private val json = Json { ignoreUnknownKeys = true }

  private fun storage() = ScheduleSnapshotStorage(ScheduleStore.settings, "schedule_query")

  fun read(account: String): List<SemesterSchedule> =
      runCatching {
            storage().read(account)?.let { json.decodeFromString<List<SemesterSchedule>>(it) }
          }
          .getOrNull()
          .orEmpty()

  fun write(account: String, snapshots: List<SemesterSchedule>) {
    storage().write(account, json.encodeToString(snapshots))
  }
}
