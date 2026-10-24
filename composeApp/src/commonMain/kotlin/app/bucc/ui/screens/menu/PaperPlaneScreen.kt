package app.bucc.ui.screens.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bucc.api.plane.PaperPlaneApi
import app.bucc.api.plane.PaperPlaneBackend
import app.bucc.api.plane.PaperPlaneOneClickSummary
import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.api.plane.normalizedPaperPlaneTaskName
import kotlinx.coroutines.launch

@Composable
fun PaperPlaneScreen(backend: PaperPlaneBackend? = null) {
  val scope = rememberCoroutineScope()
  val api = remember(backend) { backend ?: PaperPlaneApi() }
  var points by remember { mutableStateOf(PaperPlaneSectionState<PaperPlanePointTasksResponse>()) }
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }

  suspend fun refreshPoints() {
    if (points.refreshing) return
    points = points.beginRefresh()
    api.getTasks()
        .onSuccess { points = points.completeRefresh(it) }
        .onFailure { points = points.failRefresh(it.message ?: "无法加载积分") }
  }

  LaunchedEffect(api) { refreshPoints() }

  val taskResponse = points.value
  val taskError = taskResponse?.let(::paperPlanePointTasksError)
  fun taskCount(name: String): String =
      taskResponse
          ?.takeIf { taskError == null }
          ?.list
          ?.singleOrNull { normalizedPaperPlaneTaskName(it.gzmc) == name }
          ?.num
          ?.trim()
          ?.toIntOrNull()
          ?.toString() ?: "—"

  Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Card(Modifier.fillMaxWidth()) {
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
                paperPlanePointsSummary(points.value),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Filled.MonetizationOn, contentDescription = "积分")
              Text(
                  "积分",
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.SemiBold,
              )
            }
          }
          Text("今日投掷纸飞机次数：${taskCount("投掷课堂纸飞机")}", style = MaterialTheme.typography.bodySmall)
          Text("今日查看纸飞机次数：${taskCount("浏览课堂纸飞机")}", style = MaterialTheme.typography.bodySmall)
          taskError?.let {
            Text(
                "今日次数加载失败：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
          }
          if (points.refreshing && !points.loaded) {
            Text("正在加载积分…", style = MaterialTheme.typography.bodySmall)
          }
          points.error?.let {
            Text(
                "积分加载失败：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        IconButton(onClick = { scope.launch { refreshPoints() } }, enabled = !points.refreshing) {
          Icon(Icons.Default.Refresh, contentDescription = "刷新积分")
        }
      }
    }

    Button(
        onClick = {
          if (!busy) {
            busy = true
            status = "处理中…"
            scope.launch {
              try {
                var shouldRefreshPoints = false
                var completed: PaperPlaneOneClickSummary? = null
                api.completeOneClick()
                    .onSuccess { result ->
                      completed = result
                      result.points?.let { points = points.completeRefresh(it) }
                      status = paperPlaneOneClickStatus(result)
                      shouldRefreshPoints =
                          result.points == null || status?.startsWith("失败：") == true
                    }
                    .onFailure {
                      shouldRefreshPoints = true
                      status = "失败：${it.message ?: "请重试"}"
                    }
                if (shouldRefreshPoints) {
                  refreshPoints()
                  completed
                      ?.takeIf { it.error == null && points.error == null }
                      ?.let { result ->
                        status = paperPlaneOneClickStatus(result.copy(points = points.value))
                      }
                }
              } finally {
                busy = false
              }
            }
          }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text("一键投掷")
    }

    status?.let {
      Text(
          text = it,
          style = MaterialTheme.typography.bodyMedium,
          color =
              if (it.startsWith("失败")) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

internal fun paperPlanePointsSummary(response: PaperPlanePointTasksResponse?): String =
    response?.info?.jf?.trim()?.takeIf { value -> value.toDoubleOrNull()?.isFinite() == true }
        ?: "—"

internal fun paperPlaneOneClickStatus(result: PaperPlaneOneClickSummary): String {
  result.error?.let {
    return "失败：$it（投掷 ${result.sent}/5 次）"
  }
  val tasks =
      result.points?.takeIf { paperPlanePointTasksError(it) == null } ?: return "失败：今日任务次数未确认"
  val throwCount =
      tasks.list.single { normalizedPaperPlaneTaskName(it.gzmc) == "投掷课堂纸飞机" }.num!!.trim().toInt()
  val viewCount =
      tasks.list.single { normalizedPaperPlaneTaskName(it.gzmc) == "浏览课堂纸飞机" }.num!!.trim().toInt()
  if (throwCount < 5 || viewCount < 5) {
    return "失败：今日任务未完成（投掷 $throwCount/5，查看 $viewCount/5）"
  }
  return "已完成：今日投掷 $throwCount/5，查看 $viewCount/5；本次新查看 ${result.browsed} 次"
}

internal data class PaperPlaneSectionState<T>(
    val value: T? = null,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
  fun beginRefresh() = copy(refreshing = true, error = null)

  fun completeRefresh(value: T?) =
      copy(value = value, refreshing = false, loaded = true, error = null)

  fun failRefresh(message: String) = copy(refreshing = false, loaded = true, error = message)
}
