package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkItem

interface HomeworkNotificationPlatform {
  fun showHomeworkNotification(title: String, body: String, identifier: String)
}

object HomeworkNotificationBridge {
  private var platform: HomeworkNotificationPlatform? = null

  fun install(platform: HomeworkNotificationPlatform) {
    this.platform = platform
  }

  fun show(title: String, body: String, identifier: String) {
    platform?.showHomeworkNotification(title, body, identifier)
  }
}

object HomeworkBackgroundTaskBridge {
  private var handler: HomeworkBackgroundTaskHandler? = null

  fun install(handler: HomeworkBackgroundTaskHandler): HomeworkBackgroundTaskRegistration {
    this.handler = handler
    return HomeworkBackgroundTaskRegistration { if (this.handler === handler) this.handler = null }
  }

  fun run(completion: HomeworkBackgroundTaskCompletion) {
    val current = handler
    if (current == null) completion.finish("unavailable") else current.refresh(completion)
  }
}

actual fun homeworkReminderPollIntervalMillis(): Long = 0L

actual fun installHomeworkBackgroundTaskHandler(
    handler: HomeworkBackgroundTaskHandler
): HomeworkBackgroundTaskRegistration = HomeworkBackgroundTaskBridge.install(handler)

actual fun notifyNewHomework(items: List<HomeworkItem>) {
  items.forEach { item ->
    HomeworkNotificationBridge.show("有新作业", item.title, "bucc.homework.${item.id.hashCode()}")
  }
}
