package app.bucc

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import app.bucc.repository.ScheduleStore
import app.bucc.ui.screens.schedule.LocalScheduleResponseExporter
import app.bucc.widget.ScheduleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 使用系统文件保存窗口导出完整响应，不经过输入法剪贴板。 */
@Composable
fun AndroidApp() {
  val context = LocalContext.current
  val notificationPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.bucc.notifications.completeAndroidNotificationPermissionRequest(granted)
      }
  remember(context) {
    app.bucc.security.initializeCampusCredentialStorage(context.applicationContext)
    app.bucc.api.storage.AvatarStore.initialize(
        context.applicationContext.filesDir.resolve("avatars").absolutePath
    )
    app.bucc.notifications.installNotificationAndroidContext(context.applicationContext)
    true
  }
  SideEffect {
    app.bucc.notifications.installNotificationPermissionLauncher {
      notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) {
    ScheduleStore.changes.collect { ScheduleWidgetProvider.requestRefresh(context) }
  }
  var pendingResponse by rememberSaveable { mutableStateOf<String?>(null) }
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri
        ->
        val response = pendingResponse
        pendingResponse = null
        if (uri != null && response != null) {
          scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                  runCatching {
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开文件" }
                        .use { it.write(bytes) }
                    // 读取刚保存的文件核对全文，成功提示不依赖文件名或剪贴板长度。
                    val saved =
                        requireNotNull(context.contentResolver.openInputStream(uri)).use {
                          it.readBytes()
                        }
                    check(saved.contentEquals(bytes)) { "文件内容不完整" }
                  }
                }
            Toast.makeText(
                    context,
                    if (result.isSuccess) "已保存完整响应（${response.length} 字符），请发送 TXT 文件"
                    else "导出失败，请重新选择手机上的保存位置",
                    Toast.LENGTH_LONG,
                )
                .show()
          }
        }
      }
  CompositionLocalProvider(
      LocalScheduleResponseExporter provides
          { response ->
            pendingResponse = response
            launcher.launch("BUCC-课表响应.txt")
          }
  ) {
    App()
  }
}
