package app.bucc.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsNotificationLaunchTest {
  @Test
  fun `only exact notification routes are accepted`() {
    assertEquals(
        "todo",
        windowsNotificationRoute(
            arrayOf("--bucc-notification-uri", "bucc-notification://open/todo")
        ),
    )
    assertEquals(
        "schedule",
        windowsNotificationRoute(
            arrayOf("--bucc-notification-uri", "bucc-notification://open/schedule")
        ),
    )
    listOf(
            "https://example.invalid",
            "bucc-notification://open/todo?url=secret",
            "bucc-notification://evil/todo",
            "bucc-notification://open/todo/",
            "bucc-notification://open/signin",
        )
        .forEach { assertNull(windowsNotificationRoute(arrayOf("--bucc-notification-uri", it))) }
    assertNull(
        windowsNotificationRoute(
            arrayOf("--bucc-notification-uri", "bucc-notification://open/todo", "extra")
        )
    )
  }

  @Test
  fun `background helper accepts only the daily todo action`() {
    assertEquals("todo", windowsNotificationFireDestination(arrayOf("--bucc-notify-fire", "todo")))
    assertNull(windowsNotificationFireDestination(arrayOf("--bucc-notify-fire", "schedule")))
    assertNull(windowsNotificationFireDestination(emptyArray()))
    assertNull(windowsNotificationFireDestination(arrayOf("--bucc-notify-fire", "todo", "extra")))
  }
}
