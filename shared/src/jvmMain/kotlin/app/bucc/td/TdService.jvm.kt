package app.bucc.td

actual fun createTdService(): TdService =
    if (
        System.getProperty("os.name").orEmpty().let {
          it.startsWith("Mac", ignoreCase = true) || it.startsWith("Windows", ignoreCase = true)
        }
    ) {
      DesktopTdService()
    } else {
      UnavailableTdService("TD 桌面集成目前仅支持 macOS 和 Windows")
    }
