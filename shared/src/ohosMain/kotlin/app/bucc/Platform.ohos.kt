package app.bucc

class HarmonyOSPlatform : Platform {
  override val name: String = "HarmonyOS"
  override val isMobile: Boolean = true
  override val isIOS: Boolean = false
}

actual fun getPlatform(): Platform = HarmonyOSPlatform()

actual fun supportsLocalConnectionModes(): Boolean = true
