@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.notifications

import androidx.compose.ui.napi.asString
import app.bucc.platform.HarmonyHost
import app.bucc.platform.status
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual suspend fun scheduleBuccNotifications(
    events: List<NotificationEvent>,
    courseEnabled: Boolean,
    dailyTodoEnabled: Boolean,
): String {
  val plan =
      if (courseEnabled) buildCourseReminderPlan(events, Clock.System.now().toEpochMilliseconds())
      else emptyList()
  val result =
      HarmonyHost.request(
          "scheduleNotifications",
          buildJsonObject {
            put(
                "plan",
                JsonArray(
                    plan.map { item ->
                      buildJsonObject {
                        put("eventId", item.eventId)
                        put("triggerAtMillis", item.triggerAtMillis)
                      }
                    }
                ),
            )
            put("dailyTodo", dailyTodoEnabled)
          },
      )
  return if (result.status == "success") result["code"].asString() ?: "unavailable"
  else "unavailable"
}

actual suspend fun cancelBuccNotifications() {
  check(HarmonyHost.request("cancelNotifications").status == "success") { "无法取消系统提醒，请稍后重试" }
}

actual suspend fun buccNotificationPermissionState(): NotificationPermissionState {
  val result = HarmonyHost.request("notificationPermissionState")
  return if (result.status != "success") NotificationPermissionState.UNSUPPORTED
  else notificationPermissionState(result["code"].asString())
}

actual suspend fun requestBuccNotificationPermission(): NotificationPermissionState {
  val result = HarmonyHost.request("requestNotificationPermission")
  return if (result.status != "success") NotificationPermissionState.UNSUPPORTED
  else notificationPermissionState(result["code"].asString())
}

private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

actual fun openBuccNotificationSettings() {
  settingsScope.launch { runCatching { HarmonyHost.request("openNotificationSettings") } }
}

private fun notificationPermissionState(code: String?): NotificationPermissionState =
    when (code) {
      "granted" -> NotificationPermissionState.GRANTED
      "denied" -> NotificationPermissionState.DENIED
      "not_determined" -> NotificationPermissionState.NOT_DETERMINED
      else -> NotificationPermissionState.UNSUPPORTED
    }
