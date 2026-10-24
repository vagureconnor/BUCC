package app.bucc

interface Platform {
  val name: String
  val isMobile: Boolean
  val isIOS: Boolean
}

expect fun getPlatform(): Platform

expect fun supportsLocalConnectionModes(): Boolean
