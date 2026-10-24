package app.bucc.ui.screens.libbook

import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

class LibBookMapTrackpadTest {
  @Test
  fun nativeMacGestureRegistrationAndRemovalUseTheJdkBridge() {
    assumeTrue(System.getProperty("os.name", "").contains("Mac", ignoreCase = true))
    // A Swing component needs no window/camera/account, and works in headless JVM regression.
    // This also proves the test JVM export flag permits actual Apple gesture API access.
    SwingUtilities.invokeAndWait {
      val registration = registerLibBookMacMagnification(listOf(JPanel())) {}
      assertNotNull(registration, "Mac gesture registration requires the java.desktop EAWT export")
      registration.close()
    }
  }
}
