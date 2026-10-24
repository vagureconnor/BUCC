package app.bucc.notifications

import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.Settings

data class NotificationPreferences(
    val course: Boolean = true,
    val dailyTodo: Boolean = false,
    val newHomework: Boolean = true,
)

class NotificationPreferenceStore(
    private val settings: Settings = ScheduleStore.settings,
) {
  fun read(accountId: String): NotificationPreferences {
    if (accountId.isBlank()) return NotificationPreferences()
    val prefix = "$KEY_PREFIX:$accountId"
    return NotificationPreferences(
        course = settings.getBoolean("$prefix:course", true),
        dailyTodo = settings.getBoolean("$prefix:daily_todo", false),
        newHomework = settings.getBoolean("$prefix:new_homework", true),
    )
  }

  fun write(accountId: String, preferences: NotificationPreferences) {
    if (accountId.isBlank()) return
    val prefix = "$KEY_PREFIX:$accountId"
    settings.putBoolean("$prefix:course", preferences.course)
    settings.putBoolean("$prefix:daily_todo", preferences.dailyTodo)
    settings.putBoolean("$prefix:new_homework", preferences.newHomework)
  }

  private companion object {
    const val KEY_PREFIX = "bucc_notification_preferences"
  }
}
