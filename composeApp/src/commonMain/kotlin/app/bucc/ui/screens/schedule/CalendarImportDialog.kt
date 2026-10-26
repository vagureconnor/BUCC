package app.bucc.ui.screens.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bucc.model.dto.Week
import app.bucc.model.dto.WeeklySchedule
import kotlinx.coroutines.launch

@Composable
fun CalendarImportDialog(
    accountId: String,
    termName: String,
    loadWeeks: suspend () -> Result<List<Pair<Week, WeeklySchedule>>>,
    onDismiss: () -> Unit,
) {
  var preview by remember { mutableStateOf<CalendarExportPreview?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var result by remember { mutableStateOf<String?>(null) }
  var reminder by remember { mutableStateOf<Int?>(10) }
  var loading by remember { mutableStateOf(true) }
  var importing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) {
    loadWeeks().onSuccess { preview = calendarCourseEvents(it) }.onFailure { error = it.message }
    loading = false
  }
  AlertDialog(
      onDismissRequest = { if (!importing) onDismiss() },
      title = { Text("导入系统日历") },
      text = {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text("$termName · 整学期课程")
          Text("创建独立的 BUCC 日历；重复导入会更新已有日程并移除已删课程。时间冲突的课程也会一并导入。")
          if (loading || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
          preview?.let {
            Text(
                "共 ${it.events.size} 个日程" +
                    if (it.skipped > 0) "，${it.skipped} 项缺少有效日期或时间。请修复后再导入，以免移除已有日程。" else ""
            )
          }
          Text("一次设置全部课程提醒")
          listOf(null, 0, 5, 10, 15, 30, 60).forEach { minutes ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
              RadioButton(
                  selected = reminder == minutes,
                  onClick = { reminder = minutes },
                  enabled = !importing,
              )
              Text(
                  when (minutes) {
                    null -> "不提醒"
                    0 -> "课程开始时"
                    else -> "提前 $minutes 分钟"
                  }
              )
            }
          }
          error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
      },
      confirmButton = {
        TextButton(
            enabled =
                !loading &&
                    !importing &&
                    preview != null &&
                    preview!!.skipped == 0 &&
                    result == null,
            onClick = {
              importing = true
              error = null
              scope.launch {
                importCourseCalendar(accountId, termName, preview!!.events, reminder)
                    .onSuccess { result = "已导入 ${it.eventCount} 个日程。${it.notificationStatus}" }
                    .onFailure { error = it.message ?: "日历导入失败" }
                importing = false
              }
            },
        ) {
          Text("一键导入")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss, enabled = !importing) { Text("关闭") } },
  )
}
