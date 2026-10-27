@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkItem
import app.bucc.platform.HarmonyHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Refresh on foreground entry; mobile background execution has no polling guarantee.
actual fun homeworkReminderPollIntervalMillis(): Long = 0L

private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var foregroundHandler: HomeworkBackgroundTaskHandler? = null

actual fun installHomeworkBackgroundTaskHandler(
    handler: HomeworkBackgroundTaskHandler
): HomeworkBackgroundTaskRegistration {
  foregroundHandler = handler
  return HomeworkBackgroundTaskRegistration {
    if (foregroundHandler === handler) foregroundHandler = null
  }
}

actual fun notifyNewHomework(items: List<HomeworkItem>) {
  if (items.isEmpty()) return
  notificationScope.launch { runCatching { HarmonyHost.request("notifyNewHomework") } }
}

@kotlin.native.CName("bucc_refresh_homework_on_foreground")
fun refreshHomeworkOnForeground() {
  foregroundHandler?.refresh(
      object : HomeworkBackgroundTaskCompletion {
        override fun finish(status: String) = Unit
      }
  )
}
