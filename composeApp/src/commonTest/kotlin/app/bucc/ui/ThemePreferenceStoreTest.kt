package app.bucc.ui

import app.bucc.repository.ScheduleStore
import app.bucc.ui.theme.ThemeMode
import app.bucc.ui.theme.ThemePreferenceStore
import com.russhwolf.settings.MapSettings
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferenceStoreTest {
  @BeforeTest
  fun setup() {
    ScheduleStore.settings = MapSettings()
  }

  @Test
  fun `主题默认跟随系统`() {
    assertEquals(ThemeMode.SYSTEM, ThemePreferenceStore.read("student-a"))
  }

  @Test
  fun `三种主题模式均可保存并读取`() {
    ThemeMode.entries.forEach { mode ->
      ThemePreferenceStore.write("student-a", mode)
      assertEquals(mode, ThemePreferenceStore.read("student-a"))
    }
  }

  @Test
  fun `非法主题偏好回退到跟随系统`() {
    ScheduleStore.settings.putString("theme_preference:student-a", "UNKNOWN")

    assertEquals(ThemeMode.SYSTEM, ThemePreferenceStore.read("student-a"))
  }

  @Test
  fun `主题偏好按账号隔离`() {
    ThemePreferenceStore.write("student-a", ThemeMode.DARK)
    ThemePreferenceStore.write("student-b", ThemeMode.LIGHT)

    assertEquals(ThemeMode.DARK, ThemePreferenceStore.read("student-a"))
    assertEquals(ThemeMode.LIGHT, ThemePreferenceStore.read("student-b"))
    assertEquals(ThemeMode.SYSTEM, ThemePreferenceStore.read("student-c"))
  }
}
