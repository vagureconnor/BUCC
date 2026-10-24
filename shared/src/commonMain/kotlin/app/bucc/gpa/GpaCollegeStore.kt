package app.bucc.gpa

import app.bucc.repository.ScheduleSnapshotStorage
import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GpaCollegeProfile(
    val name: String,
    val mathPlan: String = "A",
    val overrides: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class GpaCollegeSettings(
    val selected: String = "软件学院",
    val profiles: List<GpaCollegeProfile> = listOf(GpaCollegeProfile("软件学院")),
)

class GpaCollegeStore(private val account: String, settings: Settings = ScheduleStore.settings) {
  private val storage = ScheduleSnapshotStorage(settings, "gpa_colleges")
  private val json = Json { ignoreUnknownKeys = true }

  fun read(): GpaCollegeSettings =
      runCatching { storage.read(account)?.let { json.decodeFromString<GpaCollegeSettings>(it) } }
          .getOrNull() ?: GpaCollegeSettings()

  fun write(value: GpaCollegeSettings) = storage.write(account, json.encodeToString(value))
}

fun GpaCollegeProfile.includes(course: GpaCourse): Boolean =
    overrides[course.key] ?: (name == "软件学院" && isSoftwareRecommendation(course))
