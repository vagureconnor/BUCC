package app.bucc.ui.screens.grade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.bucc.gpa.*
import app.bucc.repository.ScheduleStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GpaDashboard(viewModel: GradeViewModel) {
  val state by viewModel.uiState.collectAsState()
  val account = ScheduleStore.account().orEmpty()
  val store = remember(account) { GpaCollegeStore(account) }
  var settings by remember(account) { mutableStateOf(store.read()) }
  var recommendation by remember { mutableStateOf(false) }
  var collegeMenu by remember { mutableStateOf(false) }
  var editCourses by remember { mutableStateOf(false) }
  var addCollege by remember { mutableStateOf(false) }
  var deleteCollege by remember { mutableStateOf<String?>(null) }
  var courseTerm by remember { mutableStateOf<String?>(null) }
  var termMenu by remember { mutableStateOf(false) }
  val profile =
      settings.profiles.firstOrNull { it.name == settings.selected } ?: GpaCollegeProfile("软件学院")
  val grades =
      remember(state.termGrades, state.terms) {
        state.termGrades.flatMap { (code, data) ->
          data.grades.map { grade ->
            grade.copy(
                termCode = grade.termCode ?: code,
                termName =
                    state.terms.firstOrNull { it.itemCode == code }?.itemName
                        ?: grade.termName
                        ?: code,
            )
          }
        }
      }
  val allRows = remember(grades) { gpaCourses(grades) }
  val candidates =
      remember(grades, profile.name, profile.mathPlan) {
        if (profile.name == "软件学院") recommendationCourses(grades, profile.mathPlan)
        else
            gpaCourses(grades)
                .sortedByDescending { it.grade.termCode.orEmpty() }
                .distinctBy { it.key }
      }
  val rows = if (recommendation) candidates.filter { profile.includes(it) } else allRows
  val summary = summarizeGpa(rows)
  val termRows =
      rows
          .filter { courseTerm == null || it.grade.termCode == courseTerm }
          .sortedByDescending { it.grade.termCode.orEmpty() }
  fun save(value: GpaCollegeSettings) {
    settings = value
    store.write(value)
  }
  fun saveProfile(value: GpaCollegeProfile) {
    save(
        settings.copy(profiles = settings.profiles.map { if (it.name == value.name) value else it })
    )
  }

  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("gpa-dashboard"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!recommendation, { recommendation = false }, label = { Text("所有课程") })
        FilterChip(recommendation, { recommendation = true }, label = { Text("推免课程") })
        if (recommendation) {
          Box {
            OutlinedButton({ collegeMenu = true }) { Text(profile.name) }
            DropdownMenu(collegeMenu, { collegeMenu = false }) {
              settings.profiles.forEach { college ->
                DropdownMenuItem(
                    text = { Text(college.name) },
                    onClick = {
                      save(settings.copy(selected = college.name))
                      collegeMenu = false
                    },
                )
                if (college.name != "软件学院") {
                  DropdownMenuItem(
                      text = {
                        Text("删除 ${college.name}", color = MaterialTheme.colorScheme.error)
                      },
                      onClick = {
                        deleteCollege = college.name
                        collegeMenu = false
                      },
                  )
                }
              }
              DropdownMenuItem(
                  text = { Text("新增自定义学院") },
                  onClick = {
                    collegeMenu = false
                    addCollege = true
                  },
              )
            }
          }
          OutlinedButton({ editCourses = true }) { Text("选择推免课程") }
        }
      }
      if (recommendation && profile.name == "软件学院") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("A", "B").forEach { plan ->
            FilterChip(
                profile.mathPlan == plan,
                { saveProfile(profile.copy(mathPlan = plan)) },
                label = { Text("基础课程 $plan 方案") },
            )
          }
        }
        Text(
            "默认课程来自提供的软件学院 2024 级方案，可自行调整。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else if (recommendation) {
        Text("此学院使用自行选择的课程，学分取成绩记录中的学分。", style = MaterialTheme.typography.bodySmall)
      }
    }
    if (state.error != null)
        item {
          Text("${state.error}", color = MaterialTheme.colorScheme.error)
          TextButton({ viewModel.ensureLoaded(true) }) { Text("重试") }
        }
    if (state.isLoading || state.isSummaryLoading || state.isRefreshing)
        item {
          LinearProgressIndicator(Modifier.fillMaxWidth())
          Text("正在查询成绩…", style = MaterialTheme.typography.bodySmall)
        }
    item {
      Card(
          colors =
              CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
      ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
              if (recommendation) "${profile.name} · 推免统计" else "全部学期 · 成绩统计",
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              summary.gpa?.let { "GPA ${gpaNumber(it)}" } ?: "GPA —",
              style = MaterialTheme.typography.headlineMedium,
          )
          FlowRow(
              horizontalArrangement = Arrangement.spacedBy(24.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            GpaMetric("加权平均分", summary.weightedScore)
            GpaMetric("算术平均分", summary.arithmeticScore)
            GpaMetric("计入学分", summary.credits)
            Column {
              Text(summary.count.toString(), style = MaterialTheme.typography.titleLarge)
              Text("计入课程", style = MaterialTheme.typography.bodySmall)
            }
          }
          Text("百分制与五级制按所提供项目的规则换算；通过制不计入 GPA。", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
    item { GpaTrend(cumulativeGpa(rows)) }
    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("课程成绩 · ${termRows.size} 门", style = MaterialTheme.typography.titleMedium)
        Box {
          OutlinedButton({ termMenu = true }) {
            Text(state.terms.firstOrNull { it.itemCode == courseTerm }?.itemName ?: "全部学期")
          }
          DropdownMenu(termMenu, { termMenu = false }) {
            DropdownMenuItem(
                text = { Text("全部学期") },
                onClick = {
                  courseTerm = null
                  termMenu = false
                },
            )
            state.terms.forEach { term ->
              DropdownMenuItem(
                  text = { Text(term.itemName) },
                  onClick = {
                    courseTerm = term.itemCode
                    termMenu = false
                  },
              )
            }
          }
        }
      }
      if (termRows.isEmpty() && !state.isLoading && !state.isSummaryLoading)
          Text("所选范围暂无已发布成绩，可切换学期查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(termRows) { course ->
      OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(course.grade.courseName.orEmpty(), style = MaterialTheme.typography.titleMedium)
          Text(
              "${course.grade.score} · ${course.credit} 学分 · GPA ${course.points?.let(::gpaNumber) ?: "不计入"}"
          )
          Text(
              "${course.grade.termName.orEmpty()} · ${course.grade.courseCode.orEmpty()}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
  if (editCourses)
      GpaCourseSelection(
          profile,
          candidates,
          onDismiss = { editCourses = false },
          onSave = {
            saveProfile(it)
            editCourses = false
          },
      )
  if (addCollege) {
    var name by remember { mutableStateOf("") }
    val cleaned = name.trim()
    AlertDialog(
        onDismissRequest = { addCollege = false },
        title = { Text("新增自定义学院") },
        text = {
          OutlinedTextField(name, { name = it }, label = { Text("学院名称") }, singleLine = true)
        },
        confirmButton = {
          TextButton(
              enabled = cleaned.isNotEmpty() && settings.profiles.none { it.name == cleaned },
              onClick = {
                save(
                    settings.copy(
                        selected = cleaned,
                        profiles = settings.profiles + GpaCollegeProfile(cleaned),
                    )
                )
                addCollege = false
                editCourses = true
              },
          ) {
            Text("创建并选课")
          }
        },
        dismissButton = { TextButton({ addCollege = false }) { Text("取消") } },
    )
  }
  deleteCollege?.let { name ->
    AlertDialog(
        onDismissRequest = { deleteCollege = null },
        title = { Text("删除学院方案") },
        text = { Text("确定删除“$name”方案吗？此操作只会删除本账号保存的方案。") },
        confirmButton = {
          TextButton(
              onClick = {
                val remaining = settings.profiles.filterNot { it.name == name }
                save(
                    settings.copy(
                        selected = if (settings.selected == name) "软件学院" else settings.selected,
                        profiles = remaining,
                    )
                )
                deleteCollege = null
              }
          ) {
            Text("删除", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = { TextButton(onClick = { deleteCollege = null }) { Text("取消") } },
    )
  }
}

private fun gpaNumber(value: Double): String {
  val parts = (kotlin.math.round(value * 1000) / 1000).toString().split('.')
  return parts.first() + "." + parts.getOrElse(1) { "" }.padEnd(3, '0').take(3)
}

@Composable
private fun GpaMetric(label: String, value: Double?) {
  Column {
    Text(value?.let(::gpaNumber) ?: "—", style = MaterialTheme.typography.titleLarge)
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun GpaTrend(points: List<GpaTermPoint>) {
  val colors =
      listOf(
          MaterialTheme.colorScheme.primary,
          MaterialTheme.colorScheme.tertiary,
          MaterialTheme.colorScheme.secondary,
      )
  val grid = MaterialTheme.colorScheme.outlineVariant
  val plotted = points.filter { it.summary.gpa != null }
  val gpaAxis = gradeTrendAxis(plotted.mapNotNull { it.summary.gpa }, 4.0, 0.4)
  val scoreAxis =
      gradeTrendAxis(
          plotted.flatMap { listOfNotNull(it.summary.arithmeticScore, it.summary.weightedScore) },
          100.0,
          10.0,
      )
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("累计成绩趋势", style = MaterialTheme.typography.titleMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("GPA（左轴）", "算术平均分（右轴）", "加权平均分（右轴）").forEachIndexed { index, label ->
          Text("● $label", color = colors[index], style = MaterialTheme.typography.bodySmall)
        }
      }
      if (plotted.isEmpty()) {
        Text("暂无可统计成绩", style = MaterialTheme.typography.bodySmall)
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.height(176.dp), verticalArrangement = Arrangement.SpaceBetween) {
            (0..4).forEach {
              Text(
                  gpaNumber(gpaAxis.second - (gpaAxis.second - gpaAxis.first) * it / 4),
                  style = MaterialTheme.typography.labelSmall,
              )
            }
          }
          Canvas(Modifier.weight(1f).height(176.dp).padding(vertical = 6.dp)) {
            for (level in 0..4) {
              val y = size.height * level / 4
              drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val series =
                listOf(
                    plotted.map { it.summary.gpa },
                    plotted.map { it.summary.arithmeticScore },
                    plotted.map { it.summary.weightedScore },
                )
            series.forEachIndexed { seriesIndex, values ->
              val axis = if (seriesIndex == 0) gpaAxis else scoreAxis
              val offsets =
                  values.mapIndexed { index, value ->
                    value?.let {
                      Offset(
                          if (plotted.size == 1) size.width / 2
                          else size.width * index / (plotted.size - 1),
                          (size.height * (axis.second - it) / (axis.second - axis.first)).toFloat(),
                      )
                    }
                  }
              offsets.zipWithNext().forEach { (a, b) ->
                if (a != null && b != null) drawLine(colors[seriesIndex], a, b, 2.dp.toPx())
              }
              offsets.filterNotNull().forEach { drawCircle(colors[seriesIndex], 3.dp.toPx(), it) }
            }
          }
          Column(Modifier.height(176.dp), verticalArrangement = Arrangement.SpaceBetween) {
            (0..4).forEach {
              Text(
                  gpaNumber(scoreAxis.second - (scoreAxis.second - scoreAxis.first) * it / 4),
                  style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
        plotted.forEach { point ->
          Text(
              "${point.label} · GPA ${gpaNumber(point.summary.gpa!!)} · 算术 ${point.summary.arithmeticScore?.let(::gpaNumber) ?: "—"} · 加权 ${point.summary.weightedScore?.let(::gpaNumber) ?: "—"}",
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

internal fun gradeTrendAxis(
    values: List<Double>,
    maximum: Double,
    singleSpan: Double,
): Pair<Double, Double> {
  val valid = values.filter { it.isFinite() }.map { it.coerceIn(0.0, maximum) }
  if (valid.isEmpty()) return 0.0 to maximum
  val minimum = valid.min()
  val maxValue = valid.max()
  val spread = maxValue - minimum
  val padding = if (spread > 0) maxOf(spread * 0.15, 0.002) else singleSpan / 2
  return maxOf(0.0, minimum - padding) to minOf(maximum, maxValue + padding)
}

@Composable
private fun GpaCourseSelection(
    profile: GpaCollegeProfile,
    courses: List<GpaCourse>,
    onDismiss: () -> Unit,
    onSave: (GpaCollegeProfile) -> Unit,
) {
  var selected by remember(profile) { mutableStateOf(profile.overrides) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("${profile.name} · 推免课程") },
      text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
          items(courses, key = { it.key }) { course ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
              Checkbox(
                  selected[course.key] ?: profile.includes(course),
                  { checked -> selected = selected + (course.key to checked) },
              )
              Column(Modifier.weight(1f)) {
                Text(course.grade.courseName.orEmpty())
                Text(
                    "${course.grade.termName} · ${course.credit} 学分",
                    style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      },
      confirmButton = { TextButton({ onSave(profile.copy(overrides = selected)) }) { Text("保存") } },
      dismissButton = { TextButton(onDismiss) { Text("取消") } },
  )
}
