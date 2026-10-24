package app.bucc.notifications

data class NotificationEvent(
    val id: String,
    val startMillis: Long,
    val title: String,
)

data class CourseReminderPlanItem(
    val eventId: String,
    val triggerAtMillis: Long,
)

fun buildCourseReminderPlan(
    events: List<NotificationEvent>,
    nowMillis: Long,
): List<CourseReminderPlanItem> {
  val earliestById = linkedMapOf<String, CourseReminderPlanItem>()
  events.forEach { event ->
    val triggerAt = event.startMillis - COURSE_LEAD_TIME_MILLIS
    if (event.id.isBlank() || triggerAt <= nowMillis) return@forEach
    val current = earliestById[event.id]
    if (current == null || triggerAt < current.triggerAtMillis) {
      earliestById[event.id] = CourseReminderPlanItem(event.id, triggerAt)
    }
  }
  return earliestById.values.sortedWith(
      compareBy<CourseReminderPlanItem> { it.triggerAtMillis }.thenBy { it.eventId }
  )
}

private const val COURSE_LEAD_TIME_MILLIS = 10 * 60 * 1000L
