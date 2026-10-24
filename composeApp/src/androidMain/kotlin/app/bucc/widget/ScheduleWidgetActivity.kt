package app.bucc.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.bucc.repository.ScheduleStore
import app.bucc.ui.screens.schedule.OfflineScheduleScreen
import app.bucc.ui.theme.BUCCTheme

/** 小组件点击直接打开相同周次，断网时也无需等待登录。 */
class ScheduleWidgetActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val sameAccount = intent.getStringExtra("owner") == ScheduleStore.account()
    setContent {
      BUCCTheme {
        OfflineScheduleScreen(
            onBack = { finish() },
            initialTermCode = if (sameAccount) intent.getStringExtra("term") else null,
            initialWeek = if (sameAccount) intent.getIntExtra("week", -1) else null,
        )
      }
    }
  }
}
