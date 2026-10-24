package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkItem

/** macOS polls while the application process is alive. Other platforms return zero. */
expect fun homeworkReminderPollIntervalMillis(): Long

/** Displays local operating-system notifications for newly detected assignments. */
expect fun notifyNewHomework(items: List<HomeworkItem>)

interface HomeworkBackgroundTaskCompletion {
  fun finish(status: String)
}

interface HomeworkBackgroundTaskHandler {
  fun refresh(completion: HomeworkBackgroundTaskCompletion)
}

/** Installs the in-memory refresh action used by the iOS BGAppRefreshTask bridge. */
expect fun installHomeworkBackgroundTaskHandler(handler: HomeworkBackgroundTaskHandler)
