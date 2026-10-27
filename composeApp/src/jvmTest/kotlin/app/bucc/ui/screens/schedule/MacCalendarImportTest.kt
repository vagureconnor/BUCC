package app.bucc.ui.screens.schedule

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class MacCalendarImportTest {
  @Test
  fun `packaged mac bridge exposes calendar import without requesting permission`() {
    assumeTrue(System.getProperty("os.name").startsWith("Mac"))
    assertTrue(calendarImportAvailable(), "macOS should expose the native calendar bridge")
  }
}
