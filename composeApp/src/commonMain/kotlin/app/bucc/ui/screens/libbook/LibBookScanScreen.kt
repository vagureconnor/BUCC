package app.bucc.ui.screens.libbook

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bucc.model.dto.*
import app.bucc.ui.common.util.PlatformQrScanner
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalTime::class)
@Composable
fun LibBookScanScreen(onSeat: (LibBookQrTarget) -> Unit, onBack: () -> Unit) {
  var result by remember { mutableStateOf<LibBookQrResult?>(null) }
  var attempt by remember { mutableIntStateOf(0) }
  if (result == null) {
    key(attempt) {
      PlatformQrScanner(
          onDecoded = { payload ->
            if (result == null)
                result =
                    parseLibBookReservationQr(
                        payload,
                        Clock.System.now()
                            .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
                            .date
                            .toString(),
                    )
          },
          onCancel = onBack,
      )
    }
  } else {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when (val value = result) {
        is LibBookQrResult.Seat -> {
          Text("已识别图书馆座位", style = MaterialTheme.typography.titleLarge)
          Text("接下来读取分区和座位信息，选择时段后确认预约。扫码不会自动预约、签到或退座。")
          Button(onClick = { onSeat(value.target) }) { Text("查看座位并选择时段") }
        }
        LibBookQrResult.Expired -> Text("该二维码指定的预约日期已过期，请扫描有效预约码。")
        LibBookQrResult.Invalid -> Text("二维码内容损坏或缺少座位信息，请重新扫描。")
        else -> Text("暂不支持此二维码。仅支持已验证的图书馆座位预约码，不处理签到、退座、支付或不明签名链接。")
      }
      TextButton(
          onClick = {
            result = null
            attempt++
          }
      ) {
        Text("重新扫码")
      }
      TextButton(onClick = onBack) { Text("返回") }
    }
  }
}
