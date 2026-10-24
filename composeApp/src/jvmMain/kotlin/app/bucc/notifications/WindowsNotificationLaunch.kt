package app.bucc.notifications

import java.io.File

internal fun windowsNotificationRoute(args: Array<String>): String? {
  if (args.size != 2 || args[0] != "--bucc-notification-uri") return null
  return when (args[1]) {
    "bucc-notification://open/todo" -> "todo"
    "bucc-notification://open/schedule" -> "schedule"
    else -> null
  }
}

internal fun windowsNotificationFireDestination(args: Array<String>): String? =
    "todo".takeIf { args.contentEquals(arrayOf("--bucc-notify-fire", "todo")) }

internal val windowsNotificationBridge: WindowsNotificationBridge? by lazy {
  if (!System.getProperty("os.name").startsWith("Windows", true)) null
  else
      System.getProperty("jpackage.app-path")
          ?.let(::File)
          ?.takeIf { it.isFile && it.name.equals("BUCC.exe", true) }
          ?.let(::WindowsNotificationBridge)
}
