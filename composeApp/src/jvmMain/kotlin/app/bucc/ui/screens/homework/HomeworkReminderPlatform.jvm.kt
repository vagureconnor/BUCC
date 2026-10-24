package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkItem
import java.awt.AWTException
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

private val desktopNotifications =
    System.getProperty("os.name").let {
      it.startsWith("Mac", ignoreCase = true) || it.startsWith("Windows", ignoreCase = true)
    }
private var trayIcon: TrayIcon? = null

actual fun homeworkReminderPollIntervalMillis(): Long =
    if (desktopNotifications) 30 * 60 * 1000L else 0L

actual fun installHomeworkBackgroundTaskHandler(handler: HomeworkBackgroundTaskHandler) = Unit

actual fun notifyNewHomework(items: List<HomeworkItem>) {
  if (!desktopNotifications || items.isEmpty() || GraphicsEnvironment.isHeadless()) return
  SwingUtilities.invokeLater {
    try {
      if (!SystemTray.isSupported()) return@invokeLater
      val icon =
          trayIcon
              ?: run {
                val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                graphics.color = Color(34, 118, 232)
                graphics.fillOval(1, 1, 14, 14)
                graphics.dispose()
                TrayIcon(image, "BUCC").also {
                  SystemTray.getSystemTray().add(it)
                  trayIcon = it
                }
              }
      val first = items.take(3).joinToString("、") { it.title }
      icon.displayMessage(
          "有新作业",
          first + if (items.size > 3) "等 ${items.size} 项" else "",
          TrayIcon.MessageType.INFO,
      )
    } catch (_: AWTException) {
      // Notifications are optional when the desktop has no tray service.
    }
  }
}
