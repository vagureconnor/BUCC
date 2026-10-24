package app.bucc

import platform.UIKit.UIDevice

class IOSPlatform : Platform {
  override val name: String =
      UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
  override val isMobile: Boolean = true
  override val isIOS: Boolean = true
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun supportsLocalConnectionModes(): Boolean = true
