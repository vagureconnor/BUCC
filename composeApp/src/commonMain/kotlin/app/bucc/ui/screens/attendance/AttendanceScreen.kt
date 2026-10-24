package app.bucc.ui.screens.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bucc.attendance.AttendanceRecord
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

private enum class AttendanceViewMode(val label: String) {
  DATE("按日期"),
  COURSE("按课程"),
}

private enum class AttendanceDateMode(val label: String) {
  DAY("日视图"),
  WEEK("周视图"),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
fun AttendanceScreen(viewModel: AttendanceViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsState()
  var viewMode by remember { mutableStateOf(AttendanceViewMode.DATE) }
  var dateMode by remember { mutableStateOf(AttendanceDateMode.DAY) }
  var selectedDate by remember {
    mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  var showDatePicker by remember { mutableStateOf(false) }
  var termMenuExpanded by remember { mutableStateOf(false) }
  var courseMenuExpanded by remember { mutableStateOf(false) }

  LaunchedEffect(viewModel) { viewModel.ensureLoaded() }

  LaunchedEffect(state.selectedTerm?.itemCode) {
    if (state.selectedTerm != null) {
      snapshotFlow { state.isLoadingSemester }.first { !it }
      selectedDate =
          state.records
              .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
              .maxOrNull()
              ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }
  }

  val selectedCourse = state.courses.firstOrNull { it.id == state.selectedCourseId }
  val visibleRecords =
      state.records
          .filter {
            viewMode == AttendanceViewMode.DATE ||
                state.selectedCourseId == null ||
                it.courseId == state.selectedCourseId
          }
          .sortedWith(compareBy<AttendanceRecord> { it.date }.thenBy { it.time }.thenBy { it.id })

  Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            "学期",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Box {
          TextButton(onClick = { termMenuExpanded = true }, enabled = state.terms.isNotEmpty()) {
            Text(state.selectedTerm?.itemName ?: if (state.isLoadingTerms) "正在加载学期…" else "选择学期")
          }
          DropdownMenu(
              expanded = termMenuExpanded,
              onDismissRequest = { termMenuExpanded = false },
          ) {
            state.terms.forEach { term ->
              DropdownMenuItem(
                  text = { Text(term.itemName) },
                  onClick = {
                    termMenuExpanded = false
                    viewModel.selectTerm(term)
                  },
              )
            }
          }
        }
      }
      OutlinedButton(
          onClick = viewModel::refresh,
          enabled = !state.isLoadingTerms && !state.isLoadingSemester,
      ) {
        Icon(Icons.Default.Refresh, contentDescription = "刷新")
        Text("刷新")
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AttendanceViewMode.entries.forEach { mode ->
        FilterChip(
            selected = viewMode == mode,
            onClick = { viewMode = mode },
            label = { Text(mode.label) },
        )
      }
    }

    if (viewMode == AttendanceViewMode.DATE) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        TextButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.weight(1f, fill = false),
        ) {
          Text("日期：${selectedDate}")
        }
        AttendanceDateMode.entries.forEach { mode ->
          FilterChip(
              selected = dateMode == mode,
              onClick = { dateMode = mode },
              label = { Text(mode.label) },
          )
        }
      }
      if (dateMode == AttendanceDateMode.WEEK) {
        val monday = selectedDate.minus(DatePeriod(days = selectedDate.dayOfWeek.ordinal))
        Text(
            "${monday} 至 ${monday.plus(DatePeriod(days = 6))}（周一至周日）",
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (viewMode == AttendanceViewMode.COURSE) {
      Spacer(Modifier.height(4.dp))
      Text(
          "课程筛选",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      androidx.compose.foundation.layout.Box {
        TextButton(onClick = { courseMenuExpanded = true }, enabled = state.courses.isNotEmpty()) {
          Text(selectedCourse?.name ?: "全部课程")
        }
        DropdownMenu(
            expanded = courseMenuExpanded,
            onDismissRequest = { courseMenuExpanded = false },
        ) {
          DropdownMenuItem(
              text = { Text("全部课程") },
              onClick = {
                courseMenuExpanded = false
                viewModel.selectCourse(null)
              },
          )
          state.courses.forEach { course ->
            DropdownMenuItem(
                text = { Text(course.name) },
                onClick = {
                  courseMenuExpanded = false
                  viewModel.selectCourse(course.id)
                },
            )
          }
        }
      }
    }

    if (showDatePicker) {
      val datePickerState =
          androidx.compose.material3.rememberDatePickerState(
              initialSelectedDateMillis = selectedDate.toEpochDays() * 86_400_000L,
          )
      DatePickerDialog(
          onDismissRequest = { showDatePicker = false },
          confirmButton = {
            TextButton(
                onClick = {
                  datePickerState.selectedDateMillis?.let { millis ->
                    selectedDate =
                        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                  }
                  showDatePicker = false
                }
            ) {
              Text("确定")
            }
          },
          dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
      ) {
        DatePicker(datePickerState, modifier = Modifier.testTag("attendance-date-picker"))
      }
    }

    Spacer(Modifier.height(8.dp))
    if (state.isLoadingTerms || state.isLoadingSemester) {
      Row(
          Modifier.fillMaxWidth().padding(vertical = 20.dp),
          horizontalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator()
      }
    }
    state.error?.let { message ->
      Card(
          colors =
              CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
      ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
          TextButton(
              onClick = {
                if (state.terms.isEmpty()) viewModel.refreshTerms() else viewModel.refresh()
              }
          ) {
            Text("重试")
          }
        }
      }
      Spacer(Modifier.height(8.dp))
    }

    if (!state.isLoadingTerms && !state.isLoadingSemester && state.error == null) {
      if (state.selectedTerm == null) {
        EmptyAttendanceMessage("暂无可查询学期")
      } else if (state.courses.isEmpty()) {
        EmptyAttendanceMessage("该学期暂无课程出勤记录")
      } else if (visibleRecords.isEmpty()) {
        EmptyAttendanceMessage(
            if (viewMode == AttendanceViewMode.DATE || state.selectedCourseId == null) "该学期暂无出勤记录"
            else "该课程暂无出勤记录"
        )
      } else {
        when (viewMode) {
          AttendanceViewMode.DATE -> {
            val monday = selectedDate.minus(DatePeriod(days = selectedDate.dayOfWeek.ordinal))
            val visibleDateRecords =
                visibleRecords.filter { record ->
                  val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
                  when (dateMode) {
                    AttendanceDateMode.DAY -> date == selectedDate
                    AttendanceDateMode.WEEK ->
                        date != null && date >= monday && date <= monday.plus(DatePeriod(days = 6))
                  }
                }
            if (visibleDateRecords.isEmpty()) {
              EmptyAttendanceMessage(
                  if (dateMode == AttendanceDateMode.DAY) "该日期暂无出勤记录" else "该周暂无出勤记录"
              )
            }
            val groups = visibleDateRecords.groupBy { it.date }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              groups.forEach { (date, records) ->
                item(key = "date:$date") {
                  Text(
                      date,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(top = 6.dp),
                  )
                }
                items(records, key = { "record:${it.id}" }) { record ->
                  val course = state.courses.firstOrNull { it.id == record.courseId }
                  AttendanceRecordCard(record, course?.name ?: "未知课程")
                }
              }
            }
          }
          AttendanceViewMode.COURSE -> {
            val groups = visibleRecords.groupBy { it.courseId }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              state.courses
                  .filter { state.selectedCourseId == null || it.id == state.selectedCourseId }
                  .forEach { course ->
                    item(key = "course:${course.id}") {
                      Text(
                          course.name,
                          style = MaterialTheme.typography.titleSmall,
                          fontWeight = FontWeight.Bold,
                          modifier = Modifier.padding(top = 6.dp),
                      )
                    }
                    if (groups[course.id].isNullOrEmpty()) {
                      item(key = "empty:${course.id}") {
                        Text("暂无出勤记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                      }
                    }
                    items(groups[course.id].orEmpty(), key = { "record:${it.id}" }) { record ->
                      AttendanceRecordCard(record, null)
                    }
                  }
            }
          }
        }
      }
    } else {
      Spacer(Modifier.weight(1f))
    }
  }
}

@Composable
private fun AttendanceRecordCard(record: AttendanceRecord, courseName: String?) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        if (courseName != null)
            Text(
                courseName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        Text(
            buildString {
              append(record.date)
              if (record.time.isNotBlank()) append("  ").append(record.time)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          record.status.ifBlank { "状态未知" },
          style = MaterialTheme.typography.labelLarge,
          color =
              when (record.status) {
                "缺勤" -> MaterialTheme.colorScheme.error
                "出勤" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
  }
}

@Composable
private fun EmptyAttendanceMessage(message: String) {
  Column(
      Modifier.fillMaxWidth().padding(vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
