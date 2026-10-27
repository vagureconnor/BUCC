package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkItem
import app.bucc.notifications.showAndroidNewHomeworkNotification

actual fun homeworkReminderPollIntervalMillis(): Long = 0L

actual fun notifyNewHomework(items: List<HomeworkItem>) {
  if (items.isNotEmpty()) showAndroidNewHomeworkNotification()
}

actual fun installHomeworkBackgroundTaskHandler(
    handler: HomeworkBackgroundTaskHandler
): HomeworkBackgroundTaskRegistration = HomeworkBackgroundTaskRegistration {}
