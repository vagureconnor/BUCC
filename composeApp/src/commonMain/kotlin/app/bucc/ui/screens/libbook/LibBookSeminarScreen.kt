package app.bucc.ui.screens.libbook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.bucc.model.dto.*
import app.bucc.ui.common.util.rememberLibraryAttachmentPicker
import kotlinx.coroutines.delay

@Composable
fun LibBookSeminarScreen(viewModel: LibBookSeminarViewModel) {
  val state by viewModel.uiState.collectAsState()
  val owner = LocalLifecycleOwner.current
  LaunchedEffect(viewModel, owner) {
    viewModel.ensureLoaded()
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(30_000)
        viewModel.refresh()
      }
    }
  }
  val pickAttachment =
      rememberLibraryAttachmentPicker(
          onPicked = { file ->
            viewModel.uploadAttachment(file.fileName, file.mimeType, file.bytes)
          },
          onError = viewModel::attachmentError,
      )
  val detail = state.detail
  LazyColumn(
      Modifier.fillMaxSize().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(bottom = 28.dp),
  ) {
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("图书馆研讨间预约", style = MaterialTheme.typography.titleLarge)
        TextButton(
            onClick = { viewModel.refresh() },
            enabled = !state.loading && !state.submitting,
        ) {
          Text("刷新")
        }
      }
    }
    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    if (state.stale) item { Text("可用状态可能已过期，请刷新后再申请", color = MaterialTheme.colorScheme.error) }
    state.message?.let { message ->
      item {
        Card(Modifier.fillMaxWidth()) {
          Row(Modifier.padding(12.dp)) {
            Text(message, Modifier.weight(1f))
            TextButton(onClick = viewModel::dismissMessage) { Text("关闭") }
          }
        }
      }
    }
    item { Text("校区", style = MaterialTheme.typography.titleSmall) }
    item {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.filters.campuses, key = { it.id }) { campus ->
          FilterChip(
              selected = state.campusId == campus.id,
              onClick = { viewModel.selectCampus(campus.id) },
              label = { Text(campus.name) },
              enabled = !state.submitting && !state.uploading,
          )
        }
      }
    }
    val buildings =
        state.filters.campuses.firstOrNull { it.id == state.campusId }?.buildings.orEmpty()
    item { Text("楼馆", style = MaterialTheme.typography.titleSmall) }
    item {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(buildings, key = { it.id }) { building ->
          FilterChip(
              selected = state.buildingId == building.id,
              onClick = { viewModel.selectBuilding(building.id) },
              label = { Text(building.name) },
              enabled = !state.submitting && !state.uploading,
          )
        }
      }
    }
    val floors = buildings.firstOrNull { it.id == state.buildingId }?.floors.orEmpty()
    item { Text("楼层", style = MaterialTheme.typography.titleSmall) }
    if (floors.isEmpty()) item { Text("该楼馆未提供楼层信息", style = MaterialTheme.typography.bodySmall) }
    else
        item {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(floors, key = { it.id }) { floor ->
              FilterChip(
                  selected = state.floorId == floor.id,
                  onClick = { viewModel.selectFloor(floor.id) },
                  label = { Text(floor.name) },
                  enabled = !state.submitting && !state.uploading,
              )
            }
          }
        }
    item {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.dates, key = { it }) { day ->
          FilterChip(
              selected = state.date == day,
              onClick = { viewModel.selectDate(day) },
              label = { Text(day) },
              enabled = !state.submitting && !state.uploading,
          )
        }
      }
    }
    if (detail == null) {
      if (!state.loading && state.rooms.isEmpty()) item { Text("暂无可预约的研讨间") }
      items(state.rooms, key = { it.id }) { room ->
        ElevatedCard(
            onClick = { viewModel.openRoom(room.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(room.name, style = MaterialTheme.typography.titleMedium)
            if (room.capacity.isNotBlank()) Text("人数上限：${room.capacity}")
            if (room.description.isNotBlank()) Text(room.description, maxLines = 4)
            Text("选择时间并申请", color = MaterialTheme.colorScheme.primary)
          }
        }
      }
    } else {
      item {
        TextButton(
            onClick = viewModel::closeRoom,
            enabled = !state.submitting && !state.uploading,
        ) {
          Text("返回房间列表")
        }
        Text(detail.room.name, style = MaterialTheme.typography.titleLarge)
      }
      val day = detail.days.firstOrNull { it.date == state.date }
      if (day != null) {
        item {
          SeminarTimeline(day, state.begin, state.finish, viewModel.notBefore(), detail.categories)
        }
        if (detail.earlierPeriods == "3") {
          item { Text("选择预约时段") }
          item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              items(detail.categories, key = { it.id }) { category ->
                val candidate =
                    LibBookSeminarRequest(
                        detail.room.id,
                        state.date,
                        category.start,
                        category.end,
                        "fixture",
                        content = "fixture",
                        mobile = "13000000000",
                        categoryId = category.id,
                    )
                val available =
                    day.permits(category.start, category.end, viewModel.notBefore()) &&
                        !(day.status == "-2" && category.id in setOf("1", "3")) &&
                        !(day.status == "-3" && category.id in setOf("2", "3"))
                FilterChip(
                    selected = state.categoryId == category.id,
                    onClick = { viewModel.selectCategory(category) },
                    enabled = available && !state.submitting,
                    label = {
                      Text(
                          "${category.name} ${seminarTime(candidate.begin)}–${seminarTime(candidate.finish)}"
                      )
                    },
                )
              }
            }
          }
        } else {
          item { Text("开始时间") }
          item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              items(day.starts(viewModel.notBefore())) { minute ->
                FilterChip(
                    selected = state.begin == minute,
                    onClick = { viewModel.selectBegin(minute) },
                    label = { Text(seminarTime(minute)) },
                    enabled = !state.submitting && !state.uploading,
                )
              }
            }
          }
          state.begin?.let { begin ->
            item { Text("结束时间 · 时长 ${day.minimum}–${day.maximum} 分钟") }
            item {
              LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(day.ends(begin, viewModel.notBefore())) { minute ->
                  FilterChip(
                      selected = state.finish == minute,
                      onClick = { viewModel.selectFinish(minute) },
                      label = { Text(seminarTime(minute)) },
                      enabled = !state.submitting && !state.uploading,
                  )
                }
              }
            }
          }
        }
      } else item { Text("该日期暂无开放时段", color = MaterialTheme.colorScheme.error) }
      item { Text("人数：${maxOf(detail.minPeople,day?.minPeople?:1)}–${detail.maxPeople} 人（含申请人）") }
      if (detail.maxPeople > 1) {
        item {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.memberInput,
                onValueChange = viewModel::editMember,
                label = { Text("参与人学号") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = !state.submitting && !state.uploading,
            )
            TextButton(
                onClick = viewModel::addMember,
                enabled = !state.addingMember && !state.submitting,
            ) {
              Text(if (state.addingMember) "验证中" else "添加")
            }
          }
        }
        items(state.members, key = { it.id }) { member ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(member.name)
            TextButton(
                onClick = { viewModel.removeMember(member.id) },
                enabled = !state.submitting && !state.uploading,
            ) {
              Text("移除")
            }
          }
        }
      }
      if (detail.requiresTitleChoice) {
        item { Text("申请主题") }
        item {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(detail.titles, key = { it.first }) { title ->
              FilterChip(
                  selected = state.titleId == title.first,
                  onClick = { viewModel.selectTitle(title.first) },
                  label = { Text(title.second) },
                  enabled = !state.submitting && !state.uploading,
              )
            }
          }
        }
      } else
          item {
            OutlinedTextField(
                state.title,
                viewModel::editTitle,
                label = { Text("申请主题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting && !state.uploading,
            )
          }
      item {
        OutlinedTextField(
            state.content,
            viewModel::editContent,
            label = { Text("申请内容（最多 200 字）") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.submitting && !state.uploading,
        )
      }
      item {
        OutlinedTextField(
            state.mobile,
            viewModel::editMobile,
            label = { Text("联系电话") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.submitting && !state.uploading,
        )
      }
      item {
        Row {
          Checkbox(
              state.isPublic,
              viewModel::setPublic,
              enabled = !state.submitting && !state.uploading,
          )
          Text("公开本次活动")
        }
      }
      if (detail.requiresAttachment)
          item {
            Text("申请附件（JPG、PNG、BMP 或 PDF，最大 10 MiB）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(
                  onClick = pickAttachment,
                  enabled = !state.uploading && !state.submitting,
              ) {
                Text(if (state.uploading) "正在上传" else "选择并上传附件")
              }
              if (state.attachment != null)
                  TextButton(onClick = viewModel::removeAttachment, enabled = !state.submitting) {
                    Text("移除")
                  }
            }
            if (state.attachmentName.isNotBlank()) Text(state.attachmentName)
          }
      item {
        Text("预约规则", style = MaterialTheme.typography.titleMedium)
        Text(
            detail.rules.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").ifBlank {
              "请遵守图书馆空间使用规则"
            }
        )
      }
      item {
        Button(
            onClick = viewModel::prepareConfirmation,
            enabled =
                !state.loading &&
                    !state.submitting &&
                    !state.stale &&
                    !state.uploading &&
                    (!detail.requiresAttachment || state.attachment != null) &&
                    state.begin != null &&
                    state.finish != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (state.submitting) "正在提交" else "核验时间并确认预约")
        }
      }
    }
  }
  state.confirmation?.let { request ->
    AlertDialog(
        onDismissRequest = viewModel::dismissConfirmation,
        title = { Text("确认研讨间预约") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail?.room?.name.orEmpty())
            Text("${request.date} ${seminarTime(request.begin)}–${seminarTime(request.finish)}")
            Text(
                "主题：${if(detail?.requiresTitleChoice==true) detail.titles.firstOrNull { it.first==request.titleId }?.second.orEmpty() else request.title}"
            )
            Text("参与人数：${request.members.size+1}")
            Text("确认表示已阅读并同意上方预约规则。")
          }
        },
        confirmButton = { TextButton(onClick = viewModel::submitConfirmed) { Text("同意规则并提交") } },
        dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("返回修改") } },
    )
  }
}

@Composable
private fun SeminarTimeline(
    day: LibBookSeminarDay,
    begin: Int?,
    finish: Int?,
    notBefore: Int,
    categories: List<LibBookSeminarCategory>,
) {
  val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val unavailable = if (dark) Color(0xFF39434D) else Color(0xFFE5E7EB)
  val available = if (dark) Color(0xFF356B54) else Color(0xFFB7DFC9)
  val occupied = if (dark) Color(0xFF876431) else Color(0xFFF0D39F)
  val selected = MaterialTheme.colorScheme.primary
  val busy =
      day.occupied +
          categories
              .filter {
                (day.status == "-2" && it.id in setOf("1", "3")) ||
                    (day.status == "-3" && it.id in setOf("2", "3"))
              }
              .map { LibBookSeminarInterval(it.start, it.end) }
  val occupiedLabel =
      busy.joinToString("、") { "${seminarTime(it.start)}–${seminarTime(it.end)}" }.ifBlank { "无" }
  val selectedLabel =
      if (begin != null && finish != null) "${seminarTime(begin)}–${seminarTime(finish)}" else "未选择"
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        "全天可用时间 · 开放 ${seminarTime(day.start)}–${seminarTime(day.end)}",
        style = MaterialTheme.typography.titleSmall,
    )
    Canvas(
        Modifier.fillMaxWidth().height(32.dp).semantics {
          contentDescription =
              "开放 ${seminarTime(day.start)}至${seminarTime(day.end)}；占用 $occupiedLabel；已选 $selectedLabel"
        }
    ) {
      fun x(minute: Int) = minute.coerceIn(0, 1440) / 1440f * size.width
      fun segment(start: Int, end: Int, color: Color) {
        if (end > start) drawRect(color, Offset(x(start), 0f), Size(x(end) - x(start), size.height))
      }
      segment(0, 1440, unavailable)
      if (day.status in setOf("1", "-2", "-3"))
          segment(maxOf(day.start, notBefore), day.end, available)
      busy.forEach { segment(it.start, it.end, occupied) }
      if (begin != null && finish != null) {
        segment(begin, finish, selected.copy(alpha = .75f))
        drawRect(
            selected,
            Offset(x(begin), 0f),
            Size(x(finish) - x(begin), size.height),
            style = Stroke(3.dp.toPx()),
        )
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("00:00")
      Text("06:00")
      Text("12:00")
      Text("18:00")
      Text("24:00")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      listOf(available to "可预约", occupied to "已占用", unavailable to "不可预约", selected to "已选")
          .forEach { (color, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Box(Modifier.size(12.dp).background(color))
              Text(label, style = MaterialTheme.typography.bodySmall)
            }
          }
    }
    Text("已占用：$occupiedLabel", style = MaterialTheme.typography.bodySmall)
    Text("当前已选：$selectedLabel", style = MaterialTheme.typography.bodySmall)
  }
}
