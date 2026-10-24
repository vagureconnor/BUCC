package app.bucc.notifications

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPlannerTest {
  @Test
  fun `notification defaults preserve the three independent choices`() {
    assertEquals(
        NotificationPreferences(course = true, dailyTodo = false, newHomework = true),
        NotificationPreferences(),
    )
  }

  @Test
  fun `notification preferences are isolated by account`() {
    val store = NotificationPreferenceStore(settings = MapSettings())
    val first = NotificationPreferences(course = true, dailyTodo = false, newHomework = true)
    val second = NotificationPreferences(course = false, dailyTodo = true, newHomework = false)

    store.write("student-a", first)
    store.write("student-b", second)

    assertEquals(first, store.read("student-a"))
    assertEquals(second, store.read("student-b"))
  }

  @Test
  fun `course reminder plan keeps future reminders deduplicated and sorted`() {
    val now = 1_000_000L
    val events =
        listOf(
            NotificationEvent("later", now + 40 * MINUTE, "虚构课程 B"),
            NotificationEvent("earlier", now + 20 * MINUTE, "虚构课程 A"),
            NotificationEvent("earlier", now + 20 * MINUTE, "虚构课程 A"),
            NotificationEvent("reminder-past", now + 5 * MINUTE, "虚构课程已过提醒时间"),
            NotificationEvent("started", now - MINUTE, "已开始的虚构课程"),
        )

    val plan = buildCourseReminderPlan(events, now)

    assertEquals(listOf("earlier", "later"), plan.map { it.eventId })
    assertEquals(listOf(now + 10 * MINUTE, now + 30 * MINUTE), plan.map { it.triggerAtMillis })
  }

  private companion object {
    const val MINUTE = 60_000L
  }
}
