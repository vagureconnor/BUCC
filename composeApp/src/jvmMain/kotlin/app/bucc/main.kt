package app.bucc

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import bucc.composeapp.generated.resources.Res
import bucc.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

fun main(args: Array<String>) {
  if (System.getProperty("os.name").startsWith("Windows", true)) {
    app.bucc.notifications.windowsNotificationFireDestination(args)?.let { destination ->
      kotlinx.coroutines.runBlocking {
        app.bucc.notifications.windowsNotificationBridge?.show(destination)
      }
      return
    }
    app.bucc.notifications
        .windowsNotificationRoute(args)
        ?.let(app.bucc.notifications.NotificationRouteInbox::request)
  }
  application {
    val icon = painterResource(Res.drawable.app_icon)
    Window(
        onCloseRequest = ::exitApplication,
        title = "BUCC",
        icon = icon,
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
      App()
    }
  }
}
