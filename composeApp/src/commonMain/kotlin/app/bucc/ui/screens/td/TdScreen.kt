package app.bucc.ui.screens.td

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bucc.td.TdAccount
import app.bucc.td.TdCounts
import app.bucc.td.TdExecutionPhase
import app.bucc.td.TdExecutionState
import app.bucc.td.TdExecutionStatus
import app.bucc.td.TdRuntime
import app.bucc.td.TdState
import kotlinx.coroutines.launch

@Composable
fun TdScreen(
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
  val service = remember { TdRuntime.service }
  val state by service.state.collectAsState()
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  var noticeIsError by remember { mutableStateOf(false) }
  LaunchedEffect(service, readOnly) { service.load() }
  LaunchedEffect(state.message, state.error) {
    val notice = state.error ?: state.message ?: return@LaunchedEffect
    noticeIsError = state.error != null
    snackbarHostState.showSnackbar(notice)
    service.clearNotice()
  }
  Scaffold(
      modifier = modifier,
      contentWindowInsets =
          WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
      snackbarHost = {
        SnackbarHost(snackbarHostState) { data ->
          Snackbar(
              snackbarData = data,
              containerColor =
                  if (noticeIsError) MaterialTheme.colorScheme.errorContainer
                  else Color(0xFF176B48),
              contentColor =
                  if (noticeIsError) MaterialTheme.colorScheme.onErrorContainer else Color.White,
          )
        }
      },
  ) { padding ->
    when {
      state.loading && !state.available ->
          TdLoading(modifier = Modifier.fillMaxSize().padding(padding))
      !state.available ->
          TdUnavailable(
              message = state.availabilityMessage ?: "当前平台不支持 TD",
              modifier = Modifier.fillMaxSize().padding(padding),
          )
      else ->
          TdContent(
              state = state,
              readOnly = readOnly,
              onRefreshCounts = { scope.launch { service.refreshCounts() } },
              onStartCheckin = { rounds -> scope.launch { service.startCheckin(rounds) } },
              onCancelCheckin = service::cancelCheckin,
              modifier = Modifier.fillMaxSize().padding(padding),
          )
    }
  }
}

@Composable
private fun TdContent(
    state: TdState,
    readOnly: Boolean,
    onRefreshCounts: () -> Unit,
    onStartCheckin: (Int) -> Unit,
    onCancelCheckin: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val executionActive = state.execution.isActive
  val checkinReady =
      state.account != null && state.images.size >= 3 && !executionActive && !state.loading

  LazyColumn(
      modifier = modifier,
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      TdExecutionCard(
          execution = state.execution,
          enabled = checkinReady && !readOnly,
          readOnly = readOnly,
          onStart = onStartCheckin,
          onCancel = onCancelCheckin,
      )
    }
    item {
      TdAccountCard(
          account = state.account,
      )
    }
    item {
      TdCountsCard(
          counts = state.counts,
          refreshing = state.refreshingCounts,
          enabled = state.account != null && !state.loading && !executionActive,
          onRefresh = onRefreshCounts,
      )
    }
    item { Spacer(Modifier.height(8.dp)) }
  }
}

@Composable
private fun TdAccountCard(account: TdAccount?) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      if (account == null) {
        Text("健康云账号默认跟随统一认证账号，可在设置中修改。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        TdValueRow("学号", account.studentId)
        TdValueRow("校区", account.campus.displayName)
        TdValueRow("健康云账号", account.healthCloudUsername)
        Text(
            if (account.healthCloudUsernameOverridden) "使用设置中保存的独立账号" else "默认复用当前统一认证账号",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!account.passwordConfigured) {
          Text(
              "读取次数失败时，请在设置中检查健康云账号配置。",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

@Composable
internal fun TdCountsCard(
    counts: TdCounts,
    enabled: Boolean,
    refreshing: Boolean = false,
    onRefresh: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("健康云次数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onRefresh, enabled = enabled && !refreshing) {
          if (refreshing) {
            CircularProgressIndicator(
                Modifier.size(16.dp).testTag("td-counts-refresh-progress"),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
          }
          Text(if (refreshing) "正在刷新" else "刷新")
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TdCountValue("今日有效", counts.today, Modifier.weight(1f))
        TdCountValue("本学期", counts.semester, Modifier.weight(1f))
      }
      counts.term?.let { Text("学期：$it", style = MaterialTheme.typography.bodySmall) }
      counts.updatedAt?.let {
        Text(
            "更新时间：$it${if (counts.cached) "（缓存）" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          "次数以健康云有效出口流水为准。刷新只查询健康云，不会向 TD 设备发送请求。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun TdCountValue(label: String, value: Int?, modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium)
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun TdExecutionCard(
    execution: TdExecutionState,
    enabled: Boolean,
    readOnly: Boolean,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("手动打卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      if (readOnly && !execution.isActive) {
        Text("当前模式仅支持次数查询；打卡和图片上传仅限直连模式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else if (execution.isActive) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(execution.progressText(), style = MaterialTheme.typography.bodyMedium)
        execution.remainingSeconds?.let {
          Text("剩余约 ${it} 秒", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消后续动作") }
      } else {
        if (execution.status != TdExecutionStatus.IDLE) {
          Text(execution.summaryText(), color = execution.summaryColor())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(onClick = { onStart(1) }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("打卡 1 次")
          }
          Button(onClick = { onStart(3) }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("打卡 3 次")
          }
        }
      }
      Text(
          "仅在 07:30–10:00、11:30–14:00、15:30–20:00 可执行。每轮依次发送入口、图片、等待、出口和图片。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun TdExecutionState.summaryColor() =
    if (status == TdExecutionStatus.FAILED) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun TdValueRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun TdLoading(modifier: Modifier = Modifier) {
  Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun TdUnavailable(message: String, modifier: Modifier = Modifier) {
  Box(modifier, contentAlignment = Alignment.Center) {
    Text(
        message,
        modifier = Modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun TdExecutionState.progressText(): String =
    message
        ?: buildString {
          if (currentRound > 0) append("第 $currentRound/$requestedRounds 轮 · ")
          append(phase.label())
        }

private fun TdExecutionState.summaryText(): String =
    message
        ?: when (status) {
          TdExecutionStatus.COMPLETED -> "已完成 $completedRounds/$requestedRounds 轮"
          TdExecutionStatus.CANCELLED -> "已取消，未发送后续动作"
          TdExecutionStatus.INTERRUPTED -> "应用进入后台，已停止后续动作"
          TdExecutionStatus.FAILED -> "TD 打卡失败"
          else -> ""
        }

private fun TdExecutionPhase?.label(): String =
    when (this) {
      TdExecutionPhase.PREPARING,
      null -> "正在准备"
      TdExecutionPhase.ENTRANCE -> "正在发送入口请求"
      TdExecutionPhase.ENTRANCE_PHOTO -> "正在上传入口图片"
      TdExecutionPhase.ENTRANCE_DELAY -> "入口后等待"
      TdExecutionPhase.EXIT -> "正在发送出口请求"
      TdExecutionPhase.EXIT_PHOTO -> "正在上传出口图片"
      TdExecutionPhase.ROUND_DELAY -> "下一轮前等待"
    }
