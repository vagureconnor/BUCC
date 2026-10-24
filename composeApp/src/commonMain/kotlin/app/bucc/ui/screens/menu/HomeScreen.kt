package app.bucc.ui.screens.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.SigninClassDto
import app.bucc.repository.UpcomingClass
import app.bucc.ui.icons.FeatureIcon
import app.bucc.ui.screens.grade.GradeScoreUpdateNotice
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalTime::class, ExperimentalMaterialApi::class)
@Composable
internal fun HomeScreen(
    upcomingClasses: List<UpcomingClass>,
    signinClasses: List<SigninClassDto>,
    now: LocalDateTime,
    updatedAt: String?,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    todayProgressOccurrences: List<UpcomingClass>,
    scheduleHasLoaded: Boolean,
    todoItems: List<HomeTodoItem>,
    todoLoading: Boolean,
    todoLoadingSources: List<HomeTodoSource>,
    todoFailedSources: List<HomeTodoSource>,
    todoSourceErrors: Map<HomeTodoSource, String>,
    signingTodoId: String?,
    scoreUpdateNotice: GradeScoreUpdateNotice?,
    onRetrySchedule: () -> Unit,
    onRefresh: () -> Unit,
    onOpenScoresClick: () -> Unit,
    onDismissScoreNotice: () -> Unit,
    onTodoClick: (HomeTodoItem) -> Unit,
    onSigninTodoClick: (String) -> Unit,
    featureSections: List<WorkbenchFeatureSection>,
    openSectionId: String?,
    onOpenSectionChange: (String?) -> Unit,
    onFeatureClick: (String) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenTodos: () -> Unit,
    onFeatureOrderChange: (String, List<String>) -> Unit,
    onCourseClick: (CourseClass) -> Unit,
    onScanLibraryQr: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
  val pullRefreshState = rememberPullRefreshState(refreshing = isRefreshing, onRefresh = onRefresh)
  var editingOrder by remember { mutableStateOf(false) }
  val openSection = featureSections.firstOrNull { it.id == openSectionId }
  val visibleClasses =
      remember(upcomingClasses) { upcomingClasses.sortedWith(compareBy({ it.date }, { it.start })) }
  val visibleTodos = remember(todoItems) { todoItems.sortedWith(homeTodoComparator) }
  val actionableSigninClasses =
      remember(signinClasses, now) {
        val timeZone = TimeZone.currentSystemDefault()
        signinClasses.filter { isSigninActionable(it, now, timeZone) }
      }
  val progressOccurrences =
      remember(todayProgressOccurrences, visibleClasses, now.date) {
        homeScheduleProgressOccurrences(todayProgressOccurrences, visibleClasses, now.date)
      }
  val progress =
      remember(progressOccurrences, now) { todayCourseProgress(progressOccurrences, now) }
  val scheduleContent: LazyListScope.() -> Unit = {
    item { SectionHeading("课表", "今天无课或已全部结束时显示下一有课日") }
    if (error != null) item { HomeErrorCard(error, onRetrySchedule) }
    if (visibleClasses.isEmpty()) {
      item {
        if (isLoading) HomeLoadingCard("正在同步课程", "正在查找当前及后续教学周的安排")
        else if (error == null) HomeEmptyCard("暂无后续课程", "可进入课表查看其他学期的安排")
      }
    } else {
      items(visibleClasses, key = { it.id }) { occurrence ->
        UpcomingClassCard(
            occurrence,
            now,
            if (occurrence.date == now.date)
                findSigninCourse(occurrence.course, actionableSigninClasses)
            else null,
            signingTodoId,
            onSigninTodoClick,
            { onCourseClick(occurrence.course) },
        )
      }
    }
    item { TodayCourseProgress(progress, now, isLoading, error, scheduleHasLoaded) }
  }
  val todoContent: LazyListScope.() -> Unit = {
    item { SectionHeading("待办", if (todoLoading) "同步中" else "${todoItems.size} 项待处理") }
    if (scoreUpdateNotice != null)
        item { HomeScoreUpdateBanner(scoreUpdateNotice, onOpenScoresClick, onDismissScoreNotice) }
    if (todoLoadingSources.isNotEmpty())
        item { HomeSectionLoadingChip("正在同步 ${todoLoadingSources.joinToString("、") { it.label }}") }
    if (todoFailedSources.isNotEmpty())
        item { HomeInfoBanner(formatHomeTodoFailure(todoFailedSources, todoSourceErrors)) }
    if (todoItems.isEmpty())
        item {
          if (todoLoading) HomeLoadingCard("正在同步待办", "各来源的结果会陆续显示")
          else HomeEmptyCard("当前没有待办", "新的课程与任务会显示在这里")
        }
    items(visibleTodos, key = { "todo:${it.id}" }) { item ->
      HomeTodoCard(item, signingTodoId == item.id, { onTodoClick(item) }, onSigninTodoClick)
    }
  }
  BoxWithConstraints(modifier = modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
    val wide = maxWidth >= 840.dp
    val header: @Composable () -> Unit = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
                "${now.date.year}年${now.date.month.ordinal + 1}月${now.date.day}日 · " +
                    if (isRefreshing) "正在刷新" else updatedAt?.let { "更新于 $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          onScanLibraryQr?.let { scan ->
            OutlinedButton(onClick = scan, modifier = Modifier.padding(start = 8.dp)) {
              Icon(
                  Icons.Default.QrCodeScanner,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text("扫码预约")
            }
          }
          if (wide)
              featureSections.forEach { section ->
                OutlinedButton(
                    onClick = { onOpenSectionChange(section.id) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                  Text(section.title, style = MaterialTheme.typography.titleSmall)
                }
              }
          TextButton(onClick = onRefresh, enabled = !isRefreshing) { Text("刷新") }
        }
        if (!wide)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              featureSections.forEach { section ->
                OutlinedButton(
                    onClick = { onOpenSectionChange(section.id) },
                    modifier = Modifier.weight(1f),
                ) {
                  Text(section.title, style = MaterialTheme.typography.titleSmall)
                }
              }
            }
      }
    }
    if (wide) {
      Column(
          Modifier.fillMaxSize().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        header()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
          LazyColumn(
              Modifier.weight(0.55f).fillMaxHeight(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              content = scheduleContent,
          )
          LazyColumn(
              Modifier.weight(0.45f).fillMaxHeight(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              content = todoContent,
          )
        }
      }
    } else {
      LazyColumn(
          Modifier.fillMaxSize().testTag("home-screen-scroll"),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item { header() }
        item { SectionHeading("课表", "今天无课或已全部结束时显示下一有课日") }
        if (error != null) item { HomeErrorCard(error, onRetrySchedule) }
        if (visibleClasses.isEmpty())
            item {
              if (isLoading) HomeLoadingCard("正在同步课程", "正在查找当前及后续教学周的安排")
              else if (error == null) HomeEmptyCard("暂无后续课程", "可进入课表查看其他学期的安排")
            }
        else
            items(visibleClasses, key = { "mobile-course:${it.id}" }) { occurrence ->
              UpcomingClassCard(
                  occurrence,
                  now,
                  if (occurrence.date == now.date)
                      findSigninCourse(occurrence.course, actionableSigninClasses)
                  else null,
                  signingTodoId,
                  onSigninTodoClick,
                  { onCourseClick(occurrence.course) },
              )
            }
        item { TodayCourseProgress(progress, now, isLoading, error, scheduleHasLoaded) }
        item { TextButton(onClick = onOpenSchedule) { Text("查看完整课表") } }
        item { SectionHeading("待办", if (todoLoading) "同步中" else "${todoItems.size} 项待处理") }
        if (scoreUpdateNotice != null)
            item {
              HomeScoreUpdateBanner(scoreUpdateNotice, onOpenScoresClick, onDismissScoreNotice)
            }
        if (todoLoadingSources.isNotEmpty())
            item {
              HomeSectionLoadingChip("正在同步 ${todoLoadingSources.joinToString("、") { it.label }}")
            }
        if (todoFailedSources.isNotEmpty())
            item { HomeInfoBanner(formatHomeTodoFailure(todoFailedSources, todoSourceErrors)) }
        item {
          if (visibleTodos.isEmpty()) {
            Box(Modifier.fillMaxWidth().testTag("home-todo-viewport")) {
              if (todoLoading) HomeLoadingCard("正在同步待办", "各来源的结果会陆续显示")
              else HomeEmptyCard("当前没有待办", "新的课程与任务会显示在这里")
            }
          } else {
            val visibleCount = visibleTodos.size.coerceAtMost(3)
            val viewportHeight = 168.dp * visibleCount + 12.dp * (visibleCount - 1)
            LazyColumn(
                modifier =
                    Modifier.fillMaxWidth().height(viewportHeight).testTag("home-todo-viewport"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(visibleTodos, key = { "home-todo:${it.id}" }) { todo ->
                HomeTodoCard(
                    todo,
                    signingTodoId == todo.id,
                    { onTodoClick(todo) },
                    onSigninTodoClick,
                    modifier = Modifier.height(168.dp),
                )
              }
            }
          }
        }
        item { TextButton(onClick = onOpenTodos) { Text("查看全部待办") } }
        item { Spacer(Modifier.height(12.dp)) }
      }
    }
    if (isRefreshing) {
      PullRefreshIndicator(
          isRefreshing,
          pullRefreshState,
          Modifier.align(Alignment.TopCenter).testTag("home-pull-refresh-indicator"),
      )
    }
  }
  openSection?.let { section ->
    AlertDialog(
        onDismissRequest = { onOpenSectionChange(null) },
        title = { Text(section.title) },
        text = {
          BoxWithConstraints {
            val listHeight = (maxHeight - 56.dp).coerceAtLeast(0.dp).coerceAtMost(460.dp)
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              TextButton(onClick = { editingOrder = !editingOrder }) {
                Text(if (editingOrder) "完成排序" else "调整顺序")
              }
              LazyColumn(
                  Modifier.fillMaxWidth().height(listHeight).testTag("home-section-feature-list"),
                  contentPadding = PaddingValues(bottom = 24.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                items(section.features, key = { it.id }) { feature ->
                  if (editingOrder) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(feature.title, Modifier.weight(1f))
                      TextButton(
                          enabled = section.features.firstOrNull()?.id != feature.id,
                          onClick = {
                            val ids = section.features.map { it.id }.toMutableList()
                            val index = ids.indexOf(feature.id)
                            ids.add(index - 1, ids.removeAt(index))
                            onFeatureOrderChange(section.id, ids)
                          },
                      ) {
                        Text("上移")
                      }
                      TextButton(
                          enabled = section.features.lastOrNull()?.id != feature.id,
                          onClick = {
                            val ids = section.features.map { it.id }.toMutableList()
                            val index = ids.indexOf(feature.id)
                            ids.add(index + 1, ids.removeAt(index))
                            onFeatureOrderChange(section.id, ids)
                          },
                      ) {
                        Text("下移")
                      }
                    }
                  } else WorkbenchFeatureCard(feature, { onFeatureClick(feature.id) })
                }
              }
            }
          }
        },
        confirmButton = { TextButton(onClick = { onOpenSectionChange(null) }) { Text("关闭") } },
    )
  }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
        title,
        Modifier.weight(1f),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

internal data class HomeScheduleProgress(val date: LocalDate, val fraction: Float?)

internal fun homeScheduleProgressOccurrences(
    todayProgressOccurrences: List<UpcomingClass>,
    visibleClasses: List<UpcomingClass>,
    today: LocalDate,
): List<UpcomingClass> =
    (todayProgressOccurrences + visibleClasses.filter { it.date == today }).distinctBy { it.id }

/** Uses all occurrences dated today from the same week data as the filtered home schedule list. */
internal fun todayCourseProgress(
    classes: List<UpcomingClass>,
    now: LocalDateTime,
): HomeScheduleProgress? {
  val todayClasses = classes.filter { it.date == now.date }
  if (todayClasses.isEmpty()) return null
  var totalMinutes = 0
  var elapsedMinutes = 0
  for (occurrence in todayClasses) {
    val start = occurrence.start ?: return HomeScheduleProgress(now.date, null)
    val end = occurrence.end ?: return HomeScheduleProgress(now.date, null)
    val startMinute = start.hour * 60 + start.minute
    val endMinute = end.hour * 60 + end.minute
    if (endMinute <= startMinute) return HomeScheduleProgress(now.date, null)
    totalMinutes += endMinute - startMinute
    val currentMinute = now.hour * 60 + now.minute
    elapsedMinutes += (currentMinute - startMinute).coerceIn(0, endMinute - startMinute)
  }
  return HomeScheduleProgress(
      now.date,
      if (totalMinutes == 0) null else (elapsedMinutes.toFloat() / totalMinutes).coerceIn(0f, 1f),
  )
}

@Composable
private fun TodayCourseProgress(
    progress: HomeScheduleProgress?,
    now: LocalDateTime,
    isLoading: Boolean,
    error: String?,
    scheduleHasLoaded: Boolean,
) {
  Column(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
        homeScheduleProgressLabel(progress, now, isLoading, error, scheduleHasLoaded),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    progress?.fraction?.let {
      LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
    }
  }
}

internal fun homeScheduleProgressLabel(
    progress: HomeScheduleProgress?,
    now: LocalDateTime,
    isLoading: Boolean = false,
    error: String? = null,
    scheduleHasLoaded: Boolean = true,
): String =
    when {
      progress == null && isLoading -> "正在读取今日课表"
      progress == null && error != null -> "今日课程数据暂不可用"
      progress == null && !scheduleHasLoaded -> "正在读取今日课表"
      progress == null -> "今日无课"
      progress.date == now.date ->
          progress.fraction?.let { "今日课程进度 ${kotlin.math.round(it * 100).toInt()}%" }
              ?: "今日有课 · 进度未知"
      else -> "今日无课"
    }

@Composable
internal fun HomeTodoListScreen(
    items: List<HomeTodoItem>,
    isLoading: Boolean,
    signingTodoId: String?,
    onTodoClick: (HomeTodoItem) -> Unit,
    onSigninTodoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  LazyColumn(
      modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { SectionHeading("全部待办", "${items.size} 项") }
    if (isLoading && items.isEmpty()) item { HomeLoadingCard("正在同步待办", "各来源的结果会陆续显示") }
    if (!isLoading && items.isEmpty()) item { HomeEmptyCard("当前没有待办", "新的课程与任务会显示在这里") }
    items(items.sortedWith(homeTodoComparator), key = { "todo:${it.id}" }) { item ->
      HomeTodoCard(item, signingTodoId == item.id, { onTodoClick(item) }, onSigninTodoClick)
    }
  }
}

@Composable
private fun HomeSectionLoadingChip(text: String) {
  Surface(
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      shape = MaterialTheme.shapes.small,
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
      Text(text = text, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun HomeLoadingCard(title: String, subtitle: String) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun UpcomingClassCard(
    occurrence: UpcomingClass,
    now: LocalDateTime,
    signinClass: SigninClassDto?,
    signingTodoId: String?,
    onSigninClick: (String) -> Unit,
    onClick: () -> Unit,
) {
  val ongoing = occurrence.isInProgress(now)
  Card(
      modifier =
          Modifier.fillMaxWidth()
              .clickable(onClick = onClick)
              .testTag("home-course-card-${occurrence.id}"),
      colors =
          CardDefaults.cardColors(
              containerColor =
                  if (ongoing) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceVariant
          ),
  ) {
    Column(
        Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
            occurrence.course.courseName,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (ongoing)
            TodoChip("上课中", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        else if (occurrence.end == null)
            TodoChip(
                "结束时间未知",
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.onSecondary,
            )
      }
      val date = occurrence.date
      val time =
          listOfNotNull(
                  occurrence.start?.time?.toString()?.take(5),
                  occurrence.end?.time?.toString()?.take(5),
              )
              .joinToString("–")
              .ifEmpty { "时间待定" }
      Text(
          "${date.month.ordinal + 1}月${date.day}日 · $time",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.primary,
      )
      Text(
          occurrence.course.placeName?.takeIf { it.isNotBlank() } ?: "地点待定",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (signinClass != null) {
        Button(
            onClick = { onSigninClick(signinClass.courseId) },
            enabled = signingTodoId != "signin:${signinClass.courseId}",
            modifier = Modifier.testTag("home-course-signin-${signinClass.courseId}"),
        ) {
          if (signingTodoId == "signin:${signinClass.courseId}") {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
          } else {
            Text("签到")
          }
        }
      }
    }
  }
}

@Composable
internal fun HomeTodoCard(
    item: HomeTodoItem,
    isSigning: Boolean,
    onClick: () -> Unit,
    onSigninClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val isSigninItem = item.action is HomeTodoAction.SigninCourse
  Card(
      modifier =
          modifier
              .fillMaxWidth()
              .testTag("home-todo-card-${item.id}")
              .then(if (isSigninItem) Modifier else Modifier.clickable(onClick = onClick)),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          color = sourceContainerColor(item.source),
          contentColor = sourceContentColor(item.source),
          shape = MaterialTheme.shapes.medium,
      ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              imageVector = sourceIcon(item.source),
              contentDescription = item.source.label,
              modifier = Modifier.size(22.dp),
          )
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          TodoChip(
              text = item.source.label,
              color = sourceContainerColor(item.source),
              contentColor = sourceContentColor(item.source),
          )
          TodoChip(
              text = item.statusLabel,
              color = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.timeLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
      }

      if (item.action is HomeTodoAction.SigninCourse) {
        Button(
            onClick = { onSigninClick(item.action.courseId) },
            enabled = !isSigning,
        ) {
          if (isSigning) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
          } else {
            Text("签到")
          }
        }
      } else {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun HomeErrorCard(message: String, onRetry: () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = "课表加载失败",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Spacer(modifier = Modifier.height(10.dp))
      TextButton(onClick = onRetry) { Text("重试") }
    }
  }
}

@Composable
private fun HomeEmptyCard(title: String, subtitle: String) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
          imageVector = Icons.Default.Schedule,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HomeInfoBanner(message: String) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.tertiaryContainer,
      contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
      shape = MaterialTheme.shapes.medium,
  ) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

@Composable
private fun HomeScoreUpdateBanner(
    notice: GradeScoreUpdateNotice,
    onOpenScoresClick: () -> Unit,
    onDismiss: () -> Unit,
) {
  val title =
      if (notice.changedCount == 1) {
        "${notice.changedScores.first().courseName} 成绩已更新"
      } else {
        "${notice.termName} 有 ${notice.changedCount} 门成绩更新"
      }
  Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      shape = MaterialTheme.shapes.medium,
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "前往成绩查询查看最新分数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      TextButton(onClick = onOpenScoresClick) { Text("查看") }
      TextButton(onClick = onDismiss) { Text("忽略") }
    }
  }
}

@Composable
private fun TodoChip(text: String, color: Color, contentColor: Color) {
  Surface(color = color, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun sourceContainerColor(source: HomeTodoSource): Color =
    when (source) {
      HomeTodoSource.BYKC -> MaterialTheme.colorScheme.primaryContainer
      HomeTodoSource.SPOC -> MaterialTheme.colorScheme.tertiaryContainer
      HomeTodoSource.JUDGE,
      HomeTodoSource.ACCODING -> MaterialTheme.colorScheme.primaryContainer
      HomeTodoSource.PATPAT,
      HomeTodoSource.BYXT -> MaterialTheme.colorScheme.secondaryContainer
      HomeTodoSource.CGYY -> MaterialTheme.colorScheme.secondaryContainer
      HomeTodoSource.SIGNIN -> MaterialTheme.colorScheme.primaryContainer
      HomeTodoSource.YGDK -> MaterialTheme.colorScheme.tertiaryContainer
      HomeTodoSource.TD -> MaterialTheme.colorScheme.secondaryContainer
      HomeTodoSource.PAPER_PLANE -> MaterialTheme.colorScheme.primaryContainer
    }

@Composable
private fun sourceContentColor(source: HomeTodoSource): Color =
    when (source) {
      HomeTodoSource.BYKC -> MaterialTheme.colorScheme.onPrimaryContainer
      HomeTodoSource.SPOC -> MaterialTheme.colorScheme.onTertiaryContainer
      HomeTodoSource.JUDGE,
      HomeTodoSource.ACCODING -> MaterialTheme.colorScheme.onPrimaryContainer
      HomeTodoSource.PATPAT,
      HomeTodoSource.BYXT -> MaterialTheme.colorScheme.onSecondaryContainer
      HomeTodoSource.CGYY -> MaterialTheme.colorScheme.onSecondaryContainer
      HomeTodoSource.SIGNIN -> MaterialTheme.colorScheme.onPrimaryContainer
      HomeTodoSource.YGDK -> MaterialTheme.colorScheme.onTertiaryContainer
      HomeTodoSource.TD -> MaterialTheme.colorScheme.onSecondaryContainer
      HomeTodoSource.PAPER_PLANE -> MaterialTheme.colorScheme.onPrimaryContainer
    }

private fun sourceIcon(source: HomeTodoSource): ImageVector =
    when (source) {
      HomeTodoSource.BYKC -> FeatureIcon.TODO_BYKC.vector
      HomeTodoSource.SPOC -> FeatureIcon.HOMEWORK.vector
      HomeTodoSource.JUDGE -> FeatureIcon.TODO_JUDGE.vector
      HomeTodoSource.ACCODING -> FeatureIcon.TODO_ACCODING.vector
      HomeTodoSource.PATPAT -> FeatureIcon.TODO_PATPAT.vector
      HomeTodoSource.BYXT -> FeatureIcon.TODO_BYXT.vector
      HomeTodoSource.CGYY -> FeatureIcon.CGYY.vector
      HomeTodoSource.SIGNIN -> FeatureIcon.TODO_SIGNIN.vector
      HomeTodoSource.YGDK -> FeatureIcon.YGDK.vector
      HomeTodoSource.TD -> FeatureIcon.TD.vector
      HomeTodoSource.PAPER_PLANE -> FeatureIcon.PAPER_PLANE.vector
    }
