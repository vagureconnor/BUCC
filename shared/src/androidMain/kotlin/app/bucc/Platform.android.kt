package app.bucc

import android.os.Build

class AndroidPlatform : Platform {
  override val name: String = "Android ${Build.VERSION.SDK_INT}"
  override val isMobile: Boolean = true
  override val isIOS: Boolean = false
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun supportsLocalConnectionModes(): Boolean = true
