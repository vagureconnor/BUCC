package app.bucc.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

internal object NotificationAlarmScheduler {
  private const val PREFS = "bucc_notifications"
  private const val KEY_EVENTS = "course_events"
  private const val KEY_COURSE = "course_enabled"
  private const val KEY_DAILY = "daily_enabled"
  private const val ACTION_COURSE = "app.bucc.notifications.COURSE"
  private const val ACTION_DAILY = "app.bucc.notifications.DAILY"
  private const val CHANNEL = "bucc_reminders"
  private const val DAILY_ALARM_ID = "daily-todo"

  fun schedule(
      context: Context,
      events: List<NotificationEvent>,
      courseEnabled: Boolean,
      dailyEnabled: Boolean,
  ): String {
    val app = context.applicationContext
    ensureChannel(app)
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    cancelCourse(app, readEvents(prefs))
    cancelDaily(app)
    val plan =
        if (courseEnabled) buildCourseReminderPlan(events, System.currentTimeMillis())
        else emptyList()
    val courseById = events.associateBy { it.id }
    plan.forEach { item ->
      val event = courseById[item.eventId] ?: return@forEach
      scheduleAlarm(app, item.eventId, item.triggerAtMillis, ACTION_COURSE, event.title)
    }
    if (dailyEnabled) scheduleAlarm(app, DAILY_ALARM_ID, nextBeijingTen(), ACTION_DAILY, "")
    prefs
        .edit()
        .putString(
            KEY_EVENTS,
            JSONArray()
                .apply {
                  if (courseEnabled)
                      plan.forEach { item ->
                        val event = courseById[item.eventId] ?: return@forEach
                        put(
                            JSONObject()
                                .put("id", event.id)
                                .put("start", event.startMillis)
                                .put("title", event.title)
                        )
                      }
                }
                .toString(),
        )
        .putBoolean(KEY_COURSE, courseEnabled)
        .putBoolean(KEY_DAILY, dailyEnabled)
        .apply()
    val exact =
        Build.VERSION.SDK_INT < 31 ||
            (app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)
    val postAllowed =
        Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    return when {
      !postAllowed && !exact -> "notification_permission_required; exact_alarm_permission_required"
      !postAllowed -> "notification_permission_required"
      !exact -> "scheduled_inexact_delayed"
      else -> "scheduled_exact"
    }
  }

  fun cancel(context: Context) {
    val app = context.applicationContext
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    cancelCourse(app, readEvents(prefs))
    cancelDaily(app)
    prefs
        .edit()
        .remove(KEY_EVENTS)
        .putBoolean(KEY_COURSE, false)
        .putBoolean(KEY_DAILY, false)
        .apply()
  }

  fun rescheduleAfterBoot(context: Context) {
    val app = context.applicationContext
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val events = readEvents(prefs)
    schedule(app, events, prefs.getBoolean(KEY_COURSE, true), prefs.getBoolean(KEY_DAILY, false))
  }

  fun show(context: Context, daily: Boolean, eventId: String, title: String) {
    if (
        Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    )
        return
    ensureChannel(context)
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else Notification.Builder(context)
    val notification =
        builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(if (daily) "待办提醒" else "上课提醒")
            .setContentText(if (daily) "查看今天的待办事项" else "还有 10 分钟上课，记得打卡")
            .setContentIntent(openAppIntent(context, if (daily) "todo" else "schedule"))
            .setAutoCancel(true)
            .apply { if (Build.VERSION.SDK_INT < 26) setPriority(Notification.PRIORITY_DEFAULT) }
            .build()
    manager.notify(if (daily) DAILY_ALARM_ID.hashCode() else eventId.hashCode(), notification)
  }

  fun showNewHomework(context: Context) {
    if (
        Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    )
        return
    ensureChannel(context)
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val notification =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
            else Notification.Builder(context))
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("有新作业")
            .setContentText("打开 BUCC 查看作业列表")
            .setContentIntent(openAppIntent(context, "todo"))
            .setAutoCancel(true)
            .apply { if (Build.VERSION.SDK_INT < 26) setPriority(Notification.PRIORITY_DEFAULT) }
            .build()
    manager.notify("bucc.homework.notification".hashCode(), notification)
  }

  private fun readEvents(prefs: android.content.SharedPreferences): List<NotificationEvent> =
      runCatching {
            val array = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
            (0 until array.length()).map { index ->
              val item = array.getJSONObject(index)
              NotificationEvent(
                  item.getString("id"),
                  item.getLong("start"),
                  item.optString("title"),
              )
            }
          }
          .getOrDefault(emptyList())

  private fun cancelCourse(context: Context, events: List<NotificationEvent>) {
    events.forEach { cancelAlarm(context, it.id, ACTION_COURSE) }
  }

  private fun cancelDaily(context: Context) = cancelAlarm(context, DAILY_ALARM_ID, ACTION_DAILY)

  private fun scheduleAlarm(
      context: Context,
      id: String,
      atMillis: Long,
      action: String,
      title: String,
  ) {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    val intent = alarmIntent(context, id, action, title)
    val pending =
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
      if (Build.VERSION.SDK_INT >= 23)
          manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
      else manager.set(AlarmManager.RTC_WAKEUP, atMillis, pending)
    } else if (Build.VERSION.SDK_INT >= 23) {
      manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    } else {
      manager.setExact(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }
  }

  private fun cancelAlarm(context: Context, id: String, action: String) {
    val intent = alarmIntent(context, id, action, "")
    val pending =
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
    context.getSystemService(AlarmManager::class.java)?.cancel(pending)
    pending.cancel()
  }

  private fun alarmIntent(context: Context, id: String, action: String, title: String): Intent =
      Intent(context, BuccNotificationReceiver::class.java)
          .setAction(action)
          .setData(
              Uri.parse(
                  "bucc-notification://${if (action == ACTION_DAILY) "daily" else "course"}/${Base64.encodeToString(id.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}"
              )
          )
          .putExtra("daily", action == ACTION_DAILY)
          .putExtra("event_id", id)
          .putExtra("title", title)
          .putExtra("bucc_destination", if (action == ACTION_DAILY) "todo" else "schedule")

  private fun openAppIntent(context: Context, destination: String): PendingIntent {
    val intent =
        Intent()
            .setClassName(context.packageName, "app.bucc.MainActivity")
            .setAction("app.bucc.notifications.OPEN")
            .setData(Uri.parse("bucc-notification://open/$destination"))
            .putExtra("bucc_destination", destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      manager.createNotificationChannel(
          NotificationChannel(CHANNEL, "课程与待办提醒", NotificationManager.IMPORTANCE_DEFAULT)
      )
    }
  }

  private fun nextBeijingTen(): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
    calendar.set(Calendar.HOUR_OF_DAY, 10)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    if (calendar.timeInMillis <= System.currentTimeMillis()) calendar.add(Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
  }

  fun scheduleNextDaily(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_DAILY, false))
        scheduleAlarm(context, DAILY_ALARM_ID, nextBeijingTen(), ACTION_DAILY, "")
  }
}
