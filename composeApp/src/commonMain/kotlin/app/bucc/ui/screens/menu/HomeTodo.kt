package app.bucc.ui.screens.menu

import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.api.plane.normalizedPaperPlaneTaskName
import app.bucc.homework.*
import app.bucc.model.dto.BykcChosenCourseDto
import app.bucc.model.dto.CgyyOrderDto
import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.JudgeAssignmentSummaryDto
import app.bucc.model.dto.JudgeSubmissionStatus
import app.bucc.model.dto.SigninClassDto
import app.bucc.model.dto.SpocAssignmentSummaryDto
import app.bucc.model.dto.SpocSubmissionStatus
import app.bucc.model.dto.Week
import app.bucc.model.dto.YgdkOverviewResponse
import app.bucc.repository.ScheduleStore
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

internal enum class HomeTodoSource(val label: String) {
  BYKC("博雅"),
  SPOC("SPOC"),
  JUDGE("希冀"),
  ACCODING("Accoding"),
  PATPAT("Patpat"),
  BYXT("实验报告"),
  CGYY("研讨室"),
  SIGNIN("签到"),
  YGDK("阳光打卡"),
  TD("TD 健康云"),
  PAPER_PLANE("纸飞机"),
}

internal data class HomeTodoPreferences(
    val tdVisible: Boolean = true,
    val ygdkVisible: Boolean = true,
    val paperPlaneVisible: Boolean = true,
)

internal object HomeTodoPreferenceStore {
  private const val KEY_PREFIX = "home_todo_visibility"

  fun read(accountId: String): HomeTodoPreferences {
    if (accountId.isBlank()) return HomeTodoPreferences()
    val settings = ScheduleStore.settings
    fun read(key: String) = settings.getBoolean("$KEY_PREFIX:$accountId:$key", true)
    return HomeTodoPreferences(
        tdVisible = read("td"),
        ygdkVisible = read("ygdk"),
        paperPlaneVisible = read("paper_plane"),
    )
  }

  fun write(accountId: String, preferences: HomeTodoPreferences) {
    if (accountId.isBlank()) return
    val settings = ScheduleStore.settings
    settings.putBoolean("$KEY_PREFIX:$accountId:td", preferences.tdVisible)
    settings.putBoolean("$KEY_PREFIX:$accountId:ygdk", preferences.ygdkVisible)
    settings.putBoolean("$KEY_PREFIX:$accountId:paper_plane", preferences.paperPlaneVisible)
  }
}

internal sealed interface HomeTodoAction {
  data class OpenHomework(val id: String) : HomeTodoAction

  data class OpenBykcCourse(val courseId: Long) : HomeTodoAction

  data class OpenSpocAssignment(val assignmentId: String) : HomeTodoAction

  data class OpenJudgeAssignment(val courseId: String, val assignmentId: String) : HomeTodoAction

  data object OpenCgyyOrders : HomeTodoAction

  data class SigninCourse(val courseId: String) : HomeTodoAction

  data object OpenYgdkHome : HomeTodoAction

  data object OpenTd : HomeTodoAction

  data object OpenPaperPlane : HomeTodoAction
}

internal data class HomeTodoItem(
    val id: String,
    val source: HomeTodoSource,
    val title: String,
    val subtitle: String,
    val statusLabel: String,
    val timeLabel: String,
    val sortTime: LocalDateTime?,
    val action: HomeTodoAction,
) {
  val actionLabel: String?
    get() =
        when (action) {
          is HomeTodoAction.SigninCourse -> "签到"
          else -> null
        }
}

internal val homeTodoComparator: Comparator<HomeTodoItem> =
    compareBy<HomeTodoItem> {
          if (
              it.source in
                  setOf(HomeTodoSource.PAPER_PLANE, HomeTodoSource.TD, HomeTodoSource.SIGNIN)
          )
              0
          else 1
        }
        .thenBy { it.sortTime == null }
        .thenBy { it.sortTime }

internal fun formatHomeTodoFailure(
    failedSources: List<HomeTodoSource>,
    sourceErrors: Map<HomeTodoSource, String>,
): String =
    if (failedSources.isEmpty()) {
      ""
    } else {
      "部分来源暂未更新：" +
          failedSources.joinToString("；") { source ->
            sourceErrors[source]?.takeIf { it.isNotBlank() }?.let { "${source.label}：$it" }
                ?: source.label
          }
    }

internal fun buildHomeTodoItems(
    bykcCourses: List<BykcChosenCourseDto>,
    spocAssignments: List<SpocAssignmentSummaryDto>,
    judgeAssignments: List<JudgeAssignmentSummaryDto>,
    cgyyOrders: List<CgyyOrderDto>,
    homeworkItems: List<HomeworkItem> = emptyList(),
    ygdkOverview: YgdkOverviewResponse? = null,
    currentWeek: Week? = null,
    ygdkReminderEnabled: Boolean = true,
    ygdkWeekDone: Boolean = false,
    ygdkTermDone: Boolean = false,
    tdSemesterCount: Int? = null,
    tdTerm: String? = null,
    tdQueryFailed: Boolean = false,
    ygdkQueryFailed: Boolean = false,
    tdVisible: Boolean = true,
    ygdkVisible: Boolean = true,
    paperPlaneTasks: PaperPlanePointTasksResponse? = null,
    paperPlaneQueryFailed: Boolean = false,
    paperPlaneVisible: Boolean = true,
    now: LocalDateTime,
): List<HomeTodoItem> {
  val visibleHomeworkItems = homeworkItems.filter { it.showOnHome(now) }
  return buildList {
        addAll(buildBykcTodoItems(bykcCourses, now))
        addAll(
            visibleHomeworkItems.map { item ->
              val dueAt = homeworkDueAtForHome(item, now)
              HomeTodoItem(
                  item.id,
                  item.source.toHomeTodoSource(),
                  item.title,
                  item.courseName,
                  if (item.source == HomeworkSource.BYXT && item.startAt?.let { it > now } == true)
                      "尚未开放"
                  else if (item.submission == HomeworkSubmission.PARTIAL) "待完成" else "待提交",
                  dueAt?.let { "截止 ${formatHomeDateTime(it)}" } ?: "截止时间未知",
                  dueAt,
                  HomeTodoAction.OpenHomework(item.id),
              )
            }
        )
        addAll(buildSpocTodoItems(spocAssignments, now))
        addAll(buildJudgeTodoItems(judgeAssignments, now))
        addAll(buildCgyyTodoItems(cgyyOrders, now))
        buildTdTodoItem(tdSemesterCount, tdTerm, tdVisible, now, queryFailed = tdQueryFailed)
            ?.let(::add)
        buildPaperPlaneTodoItem(paperPlaneTasks, paperPlaneVisible, paperPlaneQueryFailed, now)
            ?.let(::add)
        buildYgdkTodoItem(
                overview = ygdkOverview,
                currentWeek = currentWeek,
                reminderEnabled = ygdkReminderEnabled && ygdkVisible,
                weekDone = ygdkWeekDone,
                termDone = ygdkTermDone,
                queryFailed = ygdkQueryFailed,
                now = now,
            )
            ?.let(::add)
      }
      .distinctBy { it.id }
      .sortedWith(homeTodoComparator)
}

private fun homeworkDueAtForHome(item: HomeworkItem, now: LocalDateTime): LocalDateTime? =
    if (item.source == HomeworkSource.BYXT && item.reports.isNotEmpty()) {
      item.reports
          .asSequence()
          .filterNot { it.uploaded }
          .mapNotNull { it.dueAt }
          .filter { it > now }
          .minOrNull()
    } else item.effectiveDueAt

internal fun buildTdTodoItem(
    semesterCount: Int?,
    term: String?,
    visible: Boolean,
    now: LocalDateTime,
    queryFailed: Boolean = false,
): HomeTodoItem? {
  if (
      !visible ||
          queryFailed ||
          semesterCount == null ||
          term.isNullOrBlank() ||
          semesterCount >= 32
  )
      return null
  return HomeTodoItem(
      id = "td:$term",
      source = HomeTodoSource.TD,
      title = "TD 学期次数未达标",
      subtitle = "本学期有效次数 $semesterCount / 32 次",
      statusLabel = "待完成",
      timeLabel = "本学期目标",
      sortTime = LocalDateTime(now.date, kotlinx.datetime.LocalTime(23, 59, 59)),
      action = HomeTodoAction.OpenTd,
  )
}

private const val PAPER_PLANE_THROW_TASK = "投掷课堂纸飞机"
private const val PAPER_PLANE_BROWSE_TASK = "浏览课堂纸飞机"

internal fun paperPlanePointTasksError(response: PaperPlanePointTasksResponse): String? {
  response.integralError?.let {
    return it
  }
  val counts = mutableMapOf<String, Int>()
  for (task in response.list) {
    val name = normalizedPaperPlaneTaskName(task.gzmc)
    if (name != PAPER_PLANE_THROW_TASK && name != PAPER_PLANE_BROWSE_TASK) continue
    if (name in counts) return "纸飞机积分任务重复"
    val count = task.num?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return "纸飞机积分任务次数格式无效"
    counts[name] = count
  }
  if (PAPER_PLANE_THROW_TASK !in counts || PAPER_PLANE_BROWSE_TASK !in counts) {
    return "纸飞机积分任务缺失"
  }
  return null
}

internal fun buildPaperPlaneTodoItem(
    response: PaperPlanePointTasksResponse?,
    visible: Boolean,
    queryFailed: Boolean,
    now: LocalDateTime,
): HomeTodoItem? {
  if (!visible || queryFailed || response == null || paperPlanePointTasksError(response) != null)
      return null
  val counts =
      response.list
          .filter {
            normalizedPaperPlaneTaskName(it.gzmc) in
                setOf(PAPER_PLANE_THROW_TASK, PAPER_PLANE_BROWSE_TASK)
          }
          .associate { normalizedPaperPlaneTaskName(it.gzmc)!! to it.num!!.trim().toInt() }
  val throwCount = counts.getValue(PAPER_PLANE_THROW_TASK)
  val browseCount = counts.getValue(PAPER_PLANE_BROWSE_TASK)
  if (throwCount >= 5 && browseCount >= 5) return null
  val throwing = throwCount < 5
  return HomeTodoItem(
      id = "paper-plane:${now.date}",
      source = HomeTodoSource.PAPER_PLANE,
      title = if (throwing) PAPER_PLANE_THROW_TASK else PAPER_PLANE_BROWSE_TASK,
      subtitle = if (throwing) "投掷 $throwCount / 5 次" else "浏览 $browseCount / 5 次",
      statusLabel = "待完成",
      timeLabel = "今日目标",
      sortTime = null,
      action = HomeTodoAction.OpenPaperPlane,
  )
}

internal fun parseHomeDateTime(value: String?, today: LocalDate? = null): LocalDateTime? {
  val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  if ('-' in normalized && (' ' in normalized || 'T' in normalized)) {
    return runCatching { LocalDateTime.parse(normalized.replace(" ", "T")) }.getOrNull()
  }

  val time = parseClockTime(normalized) ?: return null
  val targetDate = today ?: return null
  return LocalDateTime(
      year = targetDate.year,
      month = targetDate.month,
      day = targetDate.day,
      hour = time.hour,
      minute = time.minute,
      second = time.second,
      nanosecond = time.nanosecond,
  )
}

internal fun formatHomeDateTime(value: LocalDateTime?): String =
    value?.let {
      "${it.month.ordinal + 1}月${it.day}日 ${it.hour.toPaddedString()}:${it.minute.toPaddedString()}"
    } ?: "时间待定"

private fun buildBykcTodoItems(
    courses: List<BykcChosenCourseDto>,
    now: LocalDateTime,
): List<HomeTodoItem> =
    courses.mapNotNull { course ->
      val start = course.courseStartDate
      val end = course.courseEndDate
      val hasEnded = end?.let { it < now } == true
      if (hasEnded || (start == null && end == null)) {
        return@mapNotNull null
      }

      val isOngoing = start?.let { it <= now } == true && (end == null || now <= end)
      val subtitle =
          listOfNotNull(course.courseTeacher?.takeIf { it.isNotBlank() }, course.coursePosition)
              .joinToString(" · ")
              .ifBlank { "我的课程" }

      HomeTodoItem(
          id = "bykc:${course.courseId}",
          source = HomeTodoSource.BYKC,
          title = course.courseName,
          subtitle = subtitle,
          statusLabel = if (isOngoing) "进行中" else "即将开始",
          timeLabel =
              if (start != null && end != null) {
                "${formatHomeDateTime(start)} - ${end.hour.toPaddedString()}:${end.minute.toPaddedString()}"
              } else {
                "开始 ${formatHomeDateTime(start)}"
              },
          sortTime = start ?: end,
          action = HomeTodoAction.OpenBykcCourse(course.courseId),
      )
    }

private fun buildSpocTodoItems(
    assignments: List<SpocAssignmentSummaryDto>,
    now: LocalDateTime,
): List<HomeTodoItem> =
    assignments.mapNotNull { assignment ->
      val startTime = parseHomeDateTime(assignment.startTime)
      val dueTime = parseHomeDateTime(assignment.dueTime)
      if (
          assignment.submissionStatus != SpocSubmissionStatus.UNSUBMITTED ||
              startTime?.let { it > now } == true ||
              dueTime == null ||
              dueTime <= now
      ) {
        return@mapNotNull null
      }

      val subtitle =
          listOfNotNull(
                  assignment.courseName.takeIf { it.isNotBlank() },
                  assignment.teacherName?.takeIf { it.isNotBlank() },
              )
              .joinToString(" · ")
              .ifBlank { "SPOC 作业" }

      HomeTodoItem(
          id = "spoc:${assignment.assignmentId}",
          source = HomeTodoSource.SPOC,
          title = assignment.title,
          subtitle = subtitle,
          statusLabel = "待提交",
          timeLabel = "截止 ${formatHomeDateTime(dueTime)}",
          sortTime = dueTime,
          action = HomeTodoAction.OpenSpocAssignment(assignment.assignmentId),
      )
    }

private fun buildJudgeTodoItems(
    assignments: List<JudgeAssignmentSummaryDto>,
    now: LocalDateTime,
): List<HomeTodoItem> =
    assignments.mapNotNull { assignment ->
      val dueTime = parseHomeDateTime(assignment.dueTime)
      val unfinished =
          assignment.submissionStatus == JudgeSubmissionStatus.UNSUBMITTED ||
              assignment.submissionStatus == JudgeSubmissionStatus.PARTIAL
      if (!unfinished || dueTime == null || dueTime <= now) {
        return@mapNotNull null
      }

      val subtitle = assignment.courseName.takeIf { it.isNotBlank() } ?: "希冀作业"

      HomeTodoItem(
          id = "judge:${assignment.courseId}:${assignment.assignmentId}",
          source = HomeTodoSource.JUDGE,
          title = assignment.title,
          subtitle = subtitle,
          statusLabel =
              if (assignment.submissionStatus == JudgeSubmissionStatus.PARTIAL) {
                "待完成"
              } else {
                "待提交"
              },
          timeLabel = "截止 ${formatHomeDateTime(dueTime)}",
          sortTime = dueTime,
          action = HomeTodoAction.OpenJudgeAssignment(assignment.courseId, assignment.assignmentId),
      )
    }

private fun buildCgyyTodoItems(
    orders: List<CgyyOrderDto>,
    now: LocalDateTime,
): List<HomeTodoItem> =
    orders.mapNotNull { order ->
      val start = parseHomeDateTime(order.reservationStartDate)
      val end = parseHomeDateTime(order.reservationEndDate)
      val isRejected = (order.checkStatus ?: 0) < 0
      val isCanceled = order.orderStatus == 2
      val hasEnded = end?.let { it <= now } == true
      if (isRejected || isCanceled || hasEnded || (start == null && end == null)) {
        return@mapNotNull null
      }

      val title =
          listOfNotNull(
                  order.venueName?.takeIf { it.isNotBlank() },
                  (order.venueSpaceName ?: order.siteName)?.takeIf { it.isNotBlank() },
              )
              .joinToString(" / ")
              .ifBlank { "研讨室预约" }
      val subtitle =
          listOfNotNull(
                  order.theme?.takeIf { it.isNotBlank() },
                  order.purposeTypeName?.takeIf { it.isNotBlank() },
              )
              .joinToString(" · ")
              .ifBlank { "我的预约" }
      val isOngoing = start?.let { it <= now } == true && (end == null || now < end)

      HomeTodoItem(
          id = "cgyy:${order.id}",
          source = HomeTodoSource.CGYY,
          title = title,
          subtitle = subtitle,
          statusLabel = if (isOngoing) "使用中" else "待使用",
          timeLabel =
              if (start != null && end != null) {
                "${formatHomeDateTime(start)} - ${end.hour.toPaddedString()}:${end.minute.toPaddedString()}"
              } else {
                "开始 ${formatHomeDateTime(start)}"
              },
          sortTime = start ?: end,
          action = HomeTodoAction.OpenCgyyOrders,
      )
    }

internal fun buildSigninTodoItems(
    classes: List<SigninClassDto>,
    now: LocalDateTime,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<HomeTodoItem> =
    classes.mapNotNull { signinClass ->
      val (start, end) = signinWindow(signinClass, now, timeZone) ?: return@mapNotNull null
      val isUpcoming = now < start

      HomeTodoItem(
          id = "signin:${signinClass.courseId}",
          source = HomeTodoSource.SIGNIN,
          title = signinClass.courseName,
          subtitle = "课程签到",
          statusLabel = if (isUpcoming) "即将签到" else "签到中",
          timeLabel =
              "${start.hour.toPaddedString()}:${start.minute.toPaddedString()} - ${end.hour.toPaddedString()}:${end.minute.toPaddedString()}",
          sortTime = start,
          action = HomeTodoAction.SigninCourse(signinClass.courseId),
      )
    }

internal fun isSigninActionable(
    signinClass: SigninClassDto,
    now: LocalDateTime,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean = signinWindow(signinClass, now, timeZone) != null

private fun signinWindow(
    signinClass: SigninClassDto,
    now: LocalDateTime,
    timeZone: TimeZone,
): Pair<LocalDateTime, LocalDateTime>? {
  if (signinClass.signStatus != 0) return null
  val start = parseHomeDateTime(signinClass.classBeginTime, now.date) ?: return null
  val end = parseHomeDateTime(signinClass.classEndTime, now.date) ?: return null
  if (now >= end) return null
  val isUpcoming = now < start
  val withinUpcomingWindow = now.toInstant(timeZone) >= start.toInstant(timeZone) - 10.minutes
  if (isUpcoming && !withinUpcomingWindow) return null
  return start to end
}

internal fun findSigninCourse(
    course: CourseClass,
    signinClasses: List<SigninClassDto>,
    classDate: LocalDate? = null,
): SigninClassDto? {
  val beginTime = course.beginTime?.let(::signinTimeParts) ?: return null
  val endTime = course.endTime?.let(::signinTimeParts) ?: return null
  return signinClasses
      .filter { signinClass ->
        if (signinClass.courseName.trim() != course.courseName.trim()) return@filter false
        val signinBegin = signinTimeParts(signinClass.classBeginTime) ?: return@filter false
        val signinEnd = signinTimeParts(signinClass.classEndTime) ?: return@filter false
        (classDate == null ||
            listOfNotNull(beginTime.first, endTime.first, signinBegin.first, signinEnd.first)
                .all { it == classDate }) &&
            beginTime.second == signinBegin.second && endTime.second == signinEnd.second
      }
      .singleOrNull()
}

private fun signinTimeParts(value: String): Pair<LocalDate?, LocalTime>? {
  val normalized = value.trim()
  if ('-' in normalized && (' ' in normalized || 'T' in normalized)) {
    val dateTime = runCatching { LocalDateTime.parse(normalized.replace(" ", "T")) }.getOrNull()
        ?: return null
    return dateTime.date to dateTime.time
  }
  val time = parseClockTime(normalized) ?: return null
  return null to time
}

internal fun buildYgdkTodoItem(
    overview: YgdkOverviewResponse?,
    currentWeek: Week?,
    reminderEnabled: Boolean,
    weekDone: Boolean,
    termDone: Boolean,
    queryFailed: Boolean = false,
    now: LocalDateTime,
): HomeTodoItem? {
  if (!reminderEnabled || queryFailed || weekDone || termDone) return null
  val week = currentWeek ?: return null
  val weekNumber = week.serialNumber
  if (weekNumber !in 11..14) return null
  val summary = overview?.summary ?: return null
  val weekCount = summary.weekCount ?: return null
  val termCount = summary.termCount
  if (termCount == null || weekCount >= 4 || termCount >= 16) return null

  val dueTime = now.currentWeekSundayEnd()
  return HomeTodoItem(
      id = "ygdk:${week.term}:$weekNumber",
      source = HomeTodoSource.YGDK,
      title = "本周阳光打卡未达标",
      subtitle = "本周已打卡 $weekCount / 4 次",
      statusLabel = "待打卡",
      timeLabel = "截止 ${formatHomeDateTime(dueTime)}",
      sortTime = dueTime,
      action = HomeTodoAction.OpenYgdkHome,
  )
}

private fun parseClockTime(value: String): LocalTime? {
  val parts = value.split(":")
  if (parts.size !in 2..3) return null
  val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
  val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
  val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
  return runCatching { LocalTime(hour, minute, second) }.getOrNull()
}

private fun LocalDateTime.currentWeekSundayEnd(): LocalDateTime =
    LocalDateTime(
        date = date.plus(DatePeriod(days = date.daysUntilSunday())),
        time = LocalTime(hour = 23, minute = 59, second = 59),
    )

private fun LocalDate.daysUntilSunday(): Int =
    when (dayOfWeek) {
      DayOfWeek.MONDAY -> 6
      DayOfWeek.TUESDAY -> 5
      DayOfWeek.WEDNESDAY -> 4
      DayOfWeek.THURSDAY -> 3
      DayOfWeek.FRIDAY -> 2
      DayOfWeek.SATURDAY -> 1
      DayOfWeek.SUNDAY -> 0
    }

private fun Int.toPaddedString(): String = toString().padStart(2, '0')

internal fun HomeworkSource.toHomeTodoSource() =
    when (this) {
      HomeworkSource.SPOC -> HomeTodoSource.SPOC
      HomeworkSource.JUDGE -> HomeTodoSource.JUDGE
      HomeworkSource.ACCODING -> HomeTodoSource.ACCODING
      HomeworkSource.PATPAT -> HomeTodoSource.PATPAT
      HomeworkSource.BYXT -> HomeTodoSource.BYXT
    }
