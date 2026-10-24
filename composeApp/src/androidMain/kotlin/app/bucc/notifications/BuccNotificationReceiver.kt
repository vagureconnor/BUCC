package app.bucc.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BuccNotificationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      "app.bucc.notifications.DAILY" -> {
        NotificationAlarmScheduler.show(context, daily = true, eventId = "daily-todo", title = "")
        NotificationAlarmScheduler.scheduleNextDaily(context)
      }
      "app.bucc.notifications.COURSE" ->
          NotificationAlarmScheduler.show(
              context,
              daily = false,
              eventId = intent.getStringExtra("event_id").orEmpty(),
              title = intent.getStringExtra("title").orEmpty(),
          )
    }
  }
}

class BuccNotificationBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (
        intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      NotificationAlarmScheduler.rescheduleAfterBoot(context)
    }
  }
}
