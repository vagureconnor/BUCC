package app.bucc.ui.screens.finance

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PaymentBrowserRuntimeConfigTest {
  @Test
  fun `browser runtime uses a pinned release and cannot reuse the legacy latest cache`() {
    val root = Files.createTempDirectory("bucc-runtime-policy").toFile()
    try {
      assertEquals("jbr-release-17.0.10b1207.14", PAYMENT_BROWSER_RELEASE)
      assertNotEquals(root.resolve("kcef"), paymentBrowserInstallDirectory(root))
      assertFalse(paymentBrowserRuntimeIsCompatible(root, "aarch64"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `installed marker and similarly shaped but incompatible native library are rejected`() {
    val root = Files.createTempDirectory("bucc-runtime-invalid").toFile()
    try {
      root.resolve("install.lock").writeText("")
      root.resolve("libjcef.dylib").writeText("incompatible-native-fixture")
      root
          .resolve("Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework")
          .apply {
            parentFile.mkdirs()
            writeText("fixture")
          }
      assertFalse(paymentBrowserRuntimeIsCompatible(root, "aarch64"))
    } finally {
      root.deleteRecursively()
    }
  }
}
