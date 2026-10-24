package app.bucc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.bucc.notifications.NotificationRouteInbox

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    app.bucc.td.initializeAndroidTd(this)
    submitNotificationDestination(intent)

    setContent { AndroidApp() }
  }

  override fun onStop() {
    app.bucc.td.TdRuntime.notifyAppBackgrounded()
    super.onStop()
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    submitNotificationDestination(intent)
  }

  private fun submitNotificationDestination(intent: android.content.Intent?) {
    intent?.getStringExtra("bucc_destination")?.let(NotificationRouteInbox::request)
  }
}

@Preview
@Composable
fun AppAndroidPreview() {
  App()
}
