package app.bucc.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPermissionFlowTest {
  @Test
  fun `first permission request invokes the native prompt`() =
      kotlinx.coroutines.test.runTest {
        var requests = 0

        val action =
            notificationPermissionAction(NotificationPermissionState.NOT_DETERMINED) {
              requests++
              NotificationPermissionState.GRANTED
            }

        assertEquals(NotificationPermissionAction.GRANTED, action)
        assertEquals(1, requests)
      }

  @Test
  fun `previously denied permission shows a settings button without retrying`() =
      kotlinx.coroutines.test.runTest {
        var requests = 0

        val action =
            notificationPermissionAction(NotificationPermissionState.DENIED) {
              requests++
              NotificationPermissionState.GRANTED
            }

        assertEquals(NotificationPermissionAction.SHOW_SETTINGS_BUTTON, action)
        assertEquals(0, requests)
      }
}
