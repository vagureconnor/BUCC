package app.bucc.ui.screens.homework

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.bucc.homework.*
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private enum class HomeworkFilter(val label: String) {
  UNFINISHED("未完成"),
  COMPLETED("已完成"),
  EXPIRED("过期"),
  ALL("全部"),
}

@Composable
fun HomeworkScreen(viewModel: HomeworkViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsState()
  val detail by viewModel.detail.collectAsState()
  val login by viewModel.login.collectAsState()
  var source by remember { mutableStateOf<HomeworkSource?>(null) }
  var filter by remember { mutableStateOf(HomeworkFilter.UNFINISHED) }
  var now by remember { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.of("+08:00"))) }
  val uriHandler = LocalUriHandler.current
  LaunchedEffect(Unit) {
    viewModel.ensureLoaded()
    while (true) {
      delay(60_000)
      now = Clock.System.now().toLocalDateTime(TimeZone.of("+08:00"))
    }
  }
  val filtered =
      state.items.filter { item ->
        (source == null || item.source == source) &&
            when (filter) {
              HomeworkFilter.UNFINISHED -> !item.completed && !item.expired(now)
              HomeworkFilter.COMPLETED -> item.completed
              HomeworkFilter.EXPIRED -> !item.completed && item.expired(now)
              HomeworkFilter.ALL -> true
            }
      }
  val visible =
      if (source == HomeworkSource.BYXT) {
        filtered.sortedWith { left, right ->
          compareHomeworkTitlesNaturally(left.title, right.title)
        }
      } else {
        filtered.sortedWith(
            compareBy<HomeworkItem> { it.effectiveDueAt == null }
                .thenBy { it.effectiveDueAt }
                .thenBy { it.courseName }
                .thenBy { it.title }
        )
      }
  val selectedSourceState = source?.let { state.sources[it] }

  LazyColumn(
      modifier.fillMaxSize().imePadding(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("作业中心", style = MaterialTheme.typography.headlineSmall)
        if (state.isLoading) TextButton(onClick = viewModel::cancelRefresh) { Text("取消刷新") }
        else TextButton(onClick = { viewModel.refresh() }) { Text("刷新全部") }
      }
      Text(
          "当前学期作业 · Accoding 显示当前学期比赛和作业（不含教程）",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    item {
      Row(
          Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(selected = source == null, onClick = { source = null }, label = { Text("所有来源") })
        HomeworkSource.entries.forEach { value ->
          FilterChip(
              selected = source == value,
              enabled = isHomeworkSourceAvailable(value),
              onClick = { source = value },
              label = { Text(value.label) },
          )
        }
      }
      val selectedSource = source
      if (
          selectedSource != null &&
              (selectedSource == HomeworkSource.ACCODING || selectedSource == HomeworkSource.PATPAT)
      ) {
        TextButton(
            enabled = isHomeworkSourceAvailable(selectedSource),
            onClick = { viewModel.showLogin(selectedSource) },
        ) {
          Text("登录/修改 ${selectedSource.label} 账号")
        }
      }
      selectedSource?.let { value ->
        val sourceState = selectedSourceState ?: return@let
        if (sourceState.isLoading) {
          Text("正在查询 ${value.label} 作业…", style = MaterialTheme.typography.bodySmall)
        }
        sourceState.error?.let { error ->
          val message =
              if (sourceState.items.isNotEmpty()) {
                "${value.label} 查询失败，已保留上次成功的 ${sourceState.items.size} 条作业：$error"
              } else {
                "${value.label} 查询失败：$error"
              }
          Text(
              message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Row(
          Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        HomeworkFilter.entries.forEach { value ->
          FilterChip(
              selected = filter == value,
              onClick = { filter = value },
              label = { Text(value.label) },
          )
        }
      }
    }
    if (visible.isEmpty() && selectedSourceState?.error == null) {
      item {
        val loading = selectedSourceState?.isLoading ?: state.isLoading
        Text(if (loading) "正在查询作业…" else "暂无符合条件的作业", style = MaterialTheme.typography.bodyLarge)
      }
    }
    items(visible, key = { it.id }) { item ->
      Card(
          enabled = isHomeworkSourceAvailable(item.source),
          onClick = { viewModel.showDetail(item.id) },
          colors =
              CardDefaults.cardColors(
                  containerColor =
                      if (!item.completed && item.overdue(now))
                          MaterialTheme.colorScheme.error.copy(alpha = .12f)
                      else MaterialTheme.colorScheme.surfaceContainerLow
              ),
      ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.courseName.isNotBlank())
                Text(
                    item.courseName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(homeworkStatus(item), style = MaterialTheme.typography.bodySmall)
            item.submissionHint?.let {
              Text(
                  it,
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall,
              )
            }
            Text(
                item.dueAt?.let { "正常截止：${homeworkDate(it)}" } ?: "截止时间未知",
                style = MaterialTheme.typography.bodySmall,
            )
            item.makeupDueAt?.let {
              Text("补交截止：${homeworkDate(it)}", style = MaterialTheme.typography.bodySmall)
            }
            item.reports.forEach { report ->
              Text(
                  "第 ${report.number} 次报告：${if (report.uploaded) "已上传" else "未上传"}",
                  style = MaterialTheme.typography.bodySmall,
              )
            }
            if (item.url.isNotBlank()) {
              FilledTonalButton(
                  enabled = isHomeworkSourceAvailable(item.source),
                  onClick = { uriHandler.openUri(item.url) },
              ) {
                Text("打开作业并提交")
              }
            }
          }
          val overdue = !item.completed && item.overdue(now)
          val statusLabel =
              when {
                item.completed -> "作业已完成"
                overdue -> "作业已过期"
                else -> "未完成"
              }
          val statusTint =
              when {
                item.completed -> Color(0xFF2E7D32)
                overdue -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
              }
          Box(
              Modifier.size(48.dp).semantics { contentDescription = statusLabel },
              contentAlignment = Alignment.Center,
          ) {
            Box(
                Modifier.size(20.dp).border(1.5.dp, statusTint, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
              when {
                item.completed ->
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = statusTint,
                        modifier = Modifier.size(16.dp),
                    )
                overdue ->
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = statusTint,
                        modifier = Modifier.size(16.dp),
                    )
              }
            }
          }
        }
      }
    }
  }
  detail.item?.let { item ->
    AlertDialog(
        onDismissRequest = viewModel::dismissDetail,
        title = {
          Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(item.title, modifier = Modifier.weight(1f))
            if (item.canMarkComplete) {
              TextButton(onClick = { viewModel.markCompleted(item, !item.locallyCompleted) }) {
                Text(if (item.locallyCompleted) "撤销标记完成" else "标记完成")
              }
            }
          }
        },
        text = {
          Column(
              Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (item.courseName.isNotBlank()) Text(item.courseName)
            Text(homeworkStatus(item))
            item.submissionHint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            item.startAt?.let { Text("开始：${homeworkDate(it)}") }
            Text(item.dueAt?.let { "正常截止：${homeworkDate(it)}" } ?: "截止时间未知")
            item.makeupDueAt?.let { Text("补交截止：${homeworkDate(it)}") }
            item.experimentAt?.let { Text("实验时间：${homeworkDate(it)}") }
            item.reports.forEach { report ->
              Text("第 ${report.number} 次报告：${if (report.uploaded) "已上传" else "未上传"}")
              report.filename?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
              report.startAt?.let {
                Text("开放：${homeworkDate(it)}", style = MaterialTheme.typography.bodySmall)
              }
              Text(
                  report.dueAt?.let { "截止：${homeworkDate(it)}" } ?: "截止时间未知",
                  style = MaterialTheme.typography.bodySmall,
              )
            }
            if (detail.isLoading) CircularProgressIndicator(Modifier.size(24.dp))
            detail.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (item.content.isNotBlank()) Text(item.content)
            if (item.url.isNotBlank())
                TextButton(onClick = { uriHandler.openUri(item.url) }) { Text("打开来源网页") }
          }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissDetail) { Text("关闭") } },
    )
  }
  login.source?.let { value ->
    var password by remember(value) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!login.isLoading) viewModel.dismissLogin() },
        title = {
          Text(if (login.savedUsername != null) "修改 ${value.label}账号" else "登录 ${value.label}")
        },
        text = {
          Column(
              Modifier.imePadding().verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
                if (login.savedUsername != null) {
                  "已填入上次保存的账号。修改后需重新登录；只有登录成功后才会更新账号。平台账号密码会加密保存在本设备，用于连接过期时自动登录。"
                } else {
                  "使用此平台独立账号与密码，不会自动使用校园统一认证账号。BUCC 会将账号密码加密保存在本设备，用于连接过期时自动登录。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                login.username,
                viewModel::updateLoginUsername,
                label = { Text(if (value == HomeworkSource.ACCODING) "邮箱" else "平台账号/学号") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (value == HomeworkSource.ACCODING) KeyboardType.Email
                            else KeyboardType.Text,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("密码") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                visualTransformation = PasswordVisualTransformation(),
            )
            login.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (login.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        },
        confirmButton = {
          TextButton(
              onClick = { viewModel.login(login.username, password) },
              enabled = !login.isLoading && login.username.isNotBlank() && password.isNotBlank(),
          ) {
            Text("保存并登录")
          }
        },
        dismissButton = {
          TextButton(onClick = viewModel::dismissLogin, enabled = !login.isLoading) { Text("取消") }
        },
    )
  }
}

private fun homeworkDate(value: kotlinx.datetime.LocalDateTime) =
    value.toString().take(16).replace('T', ' ')

private fun homeworkStatus(item: HomeworkItem) =
    if (item.locallyCompleted) "已在本地标记完成"
    else
        when (item.submission) {
          HomeworkSubmission.SUBMITTED -> "已完成"
          HomeworkSubmission.PARTIAL -> "部分提交"
          HomeworkSubmission.UNSUBMITTED -> "未完成"
          HomeworkSubmission.UNKNOWN -> "个人提交状态未知"
        }
