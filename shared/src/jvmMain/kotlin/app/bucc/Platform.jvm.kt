package app.bucc

class JVMPlatform : Platform {
  override val name: String = "Java ${System.getProperty("java.version")}"
  override val isMobile: Boolean = false
  override val isIOS: Boolean = false
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun supportsLocalConnectionModes(): Boolean = true
