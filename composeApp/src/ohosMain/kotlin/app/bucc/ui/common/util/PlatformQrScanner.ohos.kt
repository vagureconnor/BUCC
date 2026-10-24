@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.ui.common.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.napi.asString
import androidx.compose.ui.unit.dp
import app.bucc.platform.HarmonyHost
import app.bucc.platform.status
import kotlinx.coroutines.CancellationException

actual fun supportsQrCamera(): Boolean = true

@Composable
actual fun PlatformQrScanner(onDecoded: (String) -> Unit, onCancel: () -> Unit) {
  val decoded by rememberUpdatedState(onDecoded)
  val cancelled by rememberUpdatedState(onCancel)
  var attempt by remember { mutableStateOf(0) }
  var failed by remember { mutableStateOf(false) }
  var delivered by remember { mutableStateOf(false) }
  LaunchedEffect(attempt) {
    failed = false
    try {
      val result = HarmonyHost.request("scanLibraryQr")
      when (result.status) {
        "success" -> {
          val value = result["value"].asString().orEmpty()
          if (!delivered && value.isNotBlank() && value.length <= 8192) {
            delivered = true
            decoded(value)
          } else failed = true
        }
        "cancelled" -> cancelled()
        else -> failed = true
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      failed = true
    }
  }
  Column(
      Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(if (failed) "无法使用扫码，请检查相机或系统扫码服务后重试" else "正在打开扫码")
    if (failed) Button(onClick = { attempt++ }) { Text("重试") }
    Button(onClick = onCancel) { Text("返回") }
  }
}
