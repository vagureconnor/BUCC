package app.bucc.notifications

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WindowsNotificationBridgeTest {
  @Test
  fun `only future trigger times are kept and capacity is explicit`() {
    val plan =
        listOf(
            CourseReminderPlanItem("private-event-a", 900L),
            CourseReminderPlanItem("private-event-b", 1_100L),
            CourseReminderPlanItem("private-event-c", 1_100L),
            CourseReminderPlanItem("private-event-d", 2_000L),
        )

    val eligible = windowsNotificationTimes(plan, nowMillis = 1_000L)
    assertEquals(listOf(1_100L, 2_000L), eligible)
    val oversizedPlan = (0L..99L).map { CourseReminderPlanItem("event-$it", 1_000L + it + 1) }
    val futureTimes = windowsNotificationTimes(oversizedPlan, nowMillis = 1_000L)
    assertEquals(100, futureTimes.size)
    assertEquals(MAX_SCHEDULED_TOASTS, futureTimes.take(MAX_SCHEDULED_TOASTS).size)
    assertEquals("partial_capacity", windowsNotificationCapacityStatus(futureTimes.size))
    assertEquals("scheduled", windowsNotificationCapacityStatus(MAX_SCHEDULED_TOASTS))
  }

  @Test
  fun `encoded command contains no raw path quotes and payload omits event data`() {
    val launcher = "C:\\Users\\O'Brien\\BUCC \"test\"\\BUCC.exe"
    assertFalse(isSafeWindowsNotificationLauncher(launcher, isAbsolute = true))
    assertTrue(isSafeWindowsNotificationLauncher("C:\\Users\\Connor\\BUCC.exe", isAbsolute = true))
    val json =
        buildWindowsNotificationData(
            operation = "schedule",
            launcher = launcher,
            appId = "fixture.app",
            scheme = "fixture-bucc",
            triggerAtMillis =
                windowsNotificationTimes(
                    listOf(CourseReminderPlanItem("event-secret", 42L)),
                    nowMillis = 0L,
                ),
        )
    assertFalse("eventId" in json)
    assertFalse("event-secret" in json)
    assertFalse("title" in json)

    val command = encodeWindowsPowerShellCommand(json)
    assertTrue(command != null)
    val script = String(Base64.getDecoder().decode(command), StandardCharsets.UTF_16LE)
    assertFalse(launcher in script)
    assertTrue("ConvertFrom-Json" in script)
    val payloadB64 =
        Regex("FromBase64String\\('([A-Za-z0-9+/=]+)'\\)").find(script)?.groupValues?.get(1)
    assertTrue(payloadB64 != null)
    val decodedJson = String(Base64.getDecoder().decode(payloadB64), StandardCharsets.UTF_8)
    assertEquals(
        launcher,
        Json.parseToJsonElement(decodedJson).jsonObject["launcher"]?.jsonPrimitive?.content,
    )
    assertEquals(
        listOf(42L),
        Json.parseToJsonElement(decodedJson).jsonObject["triggerAtMillis"]?.jsonArray?.map {
          it.jsonPrimitive.content.toLong()
        },
    )
  }

  @Test
  fun `only supported destinations are accepted and scheduled task key is stable per user`() {
    assertEquals(setOf("todo", "schedule"), NOTIFICATION_DESTINATIONS)
    assertTrue(isSupportedNotificationDestination("todo"))
    assertTrue(isSupportedNotificationDestination("schedule"))
    assertFalse(isSupportedNotificationDestination("https://example.invalid"))
    assertNotEquals(
        windowsNotificationTaskName("app", "S-1-5-21-a"),
        windowsNotificationTaskName("app", "S-1-5-21-b"),
    )
    assertEquals(
        windowsNotificationTaskName("app", "S-1-5-21-a"),
        windowsNotificationTaskName("app", "S-1-5-21-a"),
    )
    assertTrue(windowsNotificationTaskName("app", "S-1-5-21-a").startsWith("BUCC-DailyTodo-"))
  }

  @Test
  fun `notification script uses fixed PowerShell path and bounded WinRT permission bootstrap`() {
    assertEquals(null, windowsPowerShellExecutable(null))
    assertEquals(null, windowsPowerShellExecutable("relative-system-root"))
    assertEquals(null, windowsPowerShellExecutable("/missing-system-root"))

    val command =
        encodeWindowsPowerShellCommand(
            buildWindowsNotificationData(
                operation = "show",
                launcher = "C:\\BUCC\\BUCC.exe",
                appId = "fixture.app",
                scheme = "fixture-bucc",
                destination = "todo",
            )
        ) ?: error("small notification script must fit the encoded command limit")
    val script = String(Base64.getDecoder().decode(command), StandardCharsets.UTF_16LE)

    assertTrue("ProgressPreference = 'SilentlyContinue'" in script)
    assertTrue("GetProperty('Setting')" in script)
    assertTrue("-2147023728" in script)
    assertTrue("SuppressPopup" in script)
    assertTrue("<audio silent=\"true\"/>" in script)
    assertTrue("'bucc-init'" in script)
    assertTrue("'registration'" in script)
    assertTrue("-lt 5" in script)
    assertTrue("New-ScheduledTaskTrigger -Daily -At ([DateTime]::Today.AddHours(9))" in script)
    assertFalse("powershell.exe\"" in script)
  }
}
