package app.bucc.ui

import app.bucc.repository.ScheduleStore
import app.bucc.ui.screens.menu.HomeTodoPreferenceStore
import app.bucc.ui.screens.menu.HomeTodoPreferences
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTodoPreferenceStoreTest {
  @Test
  fun `home todo visibility defaults on and is isolated by account`() {
    ScheduleStore.settings = MapSettings()

    assertEquals(HomeTodoPreferences(), HomeTodoPreferenceStore.read("student-a"))
    HomeTodoPreferenceStore.write(
        "student-a",
        HomeTodoPreferences(tdVisible = false, ygdkVisible = true, paperPlaneVisible = false),
    )

    assertEquals(
        HomeTodoPreferences(tdVisible = false, ygdkVisible = true, paperPlaneVisible = false),
        HomeTodoPreferenceStore.read("student-a"),
    )
    assertEquals(HomeTodoPreferences(), HomeTodoPreferenceStore.read("student-b"))
  }
}
