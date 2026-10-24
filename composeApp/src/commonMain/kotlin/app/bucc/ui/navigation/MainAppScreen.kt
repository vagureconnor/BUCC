package app.bucc.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bucc.api.ConnectionMode
import app.bucc.api.plane.PaperPlaneApi
import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.api.storage.BykcCourseFilterStore
import app.bucc.attendance.LocalAttendanceApi
import app.bucc.getPlatform
import app.bucc.homework.*
import app.bucc.model.dto.BykcCourseDto
import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.UserData
import app.bucc.model.dto.UserInfo
import app.bucc.notifications.*
import app.bucc.repository.ScheduleRepository
import app.bucc.repository.datedScheduleClasses
import app.bucc.td.TdHealthCloudCredentialDraft
import app.bucc.td.TdRuntime
import app.bucc.ui.common.components.AppTopBar
import app.bucc.ui.common.components.Sidebar
import app.bucc.ui.common.util.BackHandlerCompat
import app.bucc.ui.common.util.supportsQrCamera
import app.bucc.ui.screens.attendance.AttendanceScreen
import app.bucc.ui.screens.attendance.AttendanceViewModel
import app.bucc.ui.screens.bykc.*
import app.bucc.ui.screens.cgyy.CgyyHomeScreen
import app.bucc.ui.screens.cgyy.CgyyLockCodeScreen
import app.bucc.ui.screens.cgyy.CgyyOrdersScreen
import app.bucc.ui.screens.cgyy.CgyyReserveFormScreen
import app.bucc.ui.screens.cgyy.CgyyReservePickerScreen
import app.bucc.ui.screens.cgyy.CgyyUiState
import app.bucc.ui.screens.cgyy.CgyyViewModel
import app.bucc.ui.screens.classroom.ClassroomQueryScreen
import app.bucc.ui.screens.classroom.ClassroomViewModel
import app.bucc.ui.screens.evaluation.EvaluationScreen
import app.bucc.ui.screens.evaluation.EvaluationViewModel
import app.bucc.ui.screens.exam.ExamScreen
import app.bucc.ui.screens.exam.ExamUiState
import app.bucc.ui.screens.exam.ExamViewModel
import app.bucc.ui.screens.finance.CampusFinanceScreen
import app.bucc.ui.screens.grade.GradeScoreWatchViewModel
import app.bucc.ui.screens.grade.GradeScreen
import app.bucc.ui.screens.grade.GradeViewModel
import app.bucc.ui.screens.homework.*
import app.bucc.ui.screens.ihome.IhomeScreen
import app.bucc.ui.screens.ihome.IhomeViewModel
import app.bucc.ui.screens.libbook.LibBookBookingsScreen
import app.bucc.ui.screens.libbook.LibBookHomeScreen
import app.bucc.ui.screens.libbook.LibBookReserveScreen
import app.bucc.ui.screens.libbook.LibBookScanScreen
import app.bucc.ui.screens.libbook.LibBookSeminarScreen
import app.bucc.ui.screens.libbook.LibBookSeminarViewModel
import app.bucc.ui.screens.libbook.LibBookViewModel
import app.bucc.ui.screens.menu.*
import app.bucc.ui.screens.schedule.CalendarImportDialog
import app.bucc.ui.screens.schedule.CourseDetailScreen
import app.bucc.ui.screens.schedule.ScheduleScreen
import app.bucc.ui.screens.schedule.ScheduleViewModel
import app.bucc.ui.screens.schedule.calendarImportAvailable
import app.bucc.ui.screens.signin.SigninScreen
import app.bucc.ui.screens.signin.SigninViewModel
import app.bucc.ui.screens.td.TdScreen
import app.bucc.ui.screens.ygdk.YgdkClockinFormScreen
import app.bucc.ui.screens.ygdk.YgdkHomeScreen
import app.bucc.ui.screens.ygdk.YgdkUiState
import app.bucc.ui.screens.ygdk.YgdkViewModel
import app.bucc.ui.theme.ThemeMode
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** 应用程序所有的屏幕页面定义。 */
enum class AppScreen {
  HOME,
  HOME_TODO,
  MY,
  SETTINGS,
  ABOUT,
  SCHEDULE,
  EXAM,
  GRADE,
  IHOME,
  COURSE_DETAIL,
  BYKC_HOME,
  BYKC_COURSES,
  BYKC_DETAIL,
  BYKC_CHOSEN,
  BYKC_STATISTICS,
  SIGNIN,
  CGYY_HOME,
  CGYY_RESERVE_PICKER,
  CGYY_RESERVE_FORM,
  CGYY_ORDERS,
  CGYY_LOCK_CODE,
  CLASSROOM_QUERY,
  EVALUATION,
  HOMEWORK,
  ATTENDANCE,
  TD,
  PAPER_PLANE,
  CAMPUS_FINANCE,
  LIBBOOK_HOME,
  LIBBOOK_RESERVE,
  LIBBOOK_BOOKINGS,
  LIBBOOK_SEMINAR,
  LIBBOOK_SCAN,
  YGDK_HOME,
  YGDK_FORM,
}

/** Dispatches the app-level back action. Payment screens may consume it before stack navigation. */
internal fun dispatchNavigationBack(
    currentScreen: AppScreen,
    navController: NavigationController,
    financeBackHandler: (() -> Unit)?,
): Boolean {
  if (currentScreen == AppScreen.CAMPUS_FINANCE && financeBackHandler != null) {
    financeBackHandler()
    return true
  }
  return navController.navigateBack()
}

/**
 * 主界面支架组件。整合侧边栏、顶部栏和各业务模块的屏幕切换，协调 ViewModel 初始化与导航状态分发。
 *
 * @param userData 登录用户的基础数据。
 * @param userInfo 登录用户的详细信息。
 * @param onLogoutClick 注销回调。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun MainAppScreen(
    userData: UserData,
    userInfo: UserInfo?,
    connectionMode: ConnectionMode,
    availableConnectionModes: List<ConnectionMode>,
    onEnsureUserInfo: () -> Unit,
    onConnectionModeSelected: (ConnectionMode) -> Unit,
    onLogoutClick: () -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeSelected: (ThemeMode) -> Unit = {},
    modifier: Modifier = Modifier,
    navController: NavigationController = rememberNavigationController(),
    sessionAvailable: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  val currentScreen = navController.currentScreen
  val cgyyScreens = remember {
    setOf(
        AppScreen.CGYY_HOME,
        AppScreen.CGYY_RESERVE_PICKER,
        AppScreen.CGYY_RESERVE_FORM,
        AppScreen.CGYY_ORDERS,
        AppScreen.CGYY_LOCK_CODE,
    )
  }
  val ygdkScreens = remember { setOf(AppScreen.YGDK_HOME, AppScreen.YGDK_FORM) }
  val libBookScreens = remember {
    setOf(
        AppScreen.LIBBOOK_HOME,
        AppScreen.LIBBOOK_RESERVE,
        AppScreen.LIBBOOK_BOOKINGS,
        AppScreen.LIBBOOK_SEMINAR,
        AppScreen.LIBBOOK_SCAN,
    )
  }

  val accountAvatar = app.bucc.ui.common.components.rememberAccountAvatar(userData.schoolid)
  var showSidebar by remember { mutableStateOf(false) }
  var showNotificationPermissionDialog by remember { mutableStateOf(false) }
  var financeBackHandler by remember { mutableStateOf<(() -> Unit)?>(null) }
  var homeOpenSectionId by remember(userData.schoolid) { mutableStateOf<String?>(null) }
  var featureSections by
      remember(userData.schoolid) { mutableStateOf(workbenchFeatureSections(userData.schoolid)) }
  var showCalendarImport by remember { mutableStateOf(false) }
  var homeManualRefreshPending by remember { mutableStateOf(false) }
  var homeManualRefreshStarted by remember { mutableStateOf(false) }
  val homeSnackbarHostState = remember { SnackbarHostState() }
  val homeBootstrapCoordinator = remember(scope) { HomeBootstrapCoordinator(scope) }
  val homeBootstrapRunning by homeBootstrapCoordinator.isRunning.collectAsState()
  val homeNow by
      produceState(initialValue = Clock.System.now().toLocalDateTime(TimeZone.of("+08:00"))) {
        while (true) {
          delay(60_000)
          value = Clock.System.now().toLocalDateTime(TimeZone.of("+08:00"))
        }
      }
  var hasVisitedCgyy by remember { mutableStateOf(false) }
  var hasVisitedLibBook by remember { mutableStateOf(false) }
  val shouldKeepCgyyViewModel =
      currentScreen == AppScreen.HOME || hasVisitedCgyy || currentScreen in cgyyScreens
  val shouldKeepLibBookViewModel = hasVisitedLibBook || currentScreen in libBookScreens

  // 初始化各模块 ViewModel
  val scheduleViewModel: ScheduleViewModel = viewModel { ScheduleViewModel() }
  val scheduleUiState by scheduleViewModel.uiState.collectAsState()
  val upcomingScheduleState by scheduleViewModel.upcomingState.collectAsState()
  var notificationRevision by remember { mutableStateOf(0) }
  LifecycleResumeEffect(userData.schoolid, sessionAvailable) {
    notificationRevision++
    if (sessionAvailable) scheduleViewModel.loadUpcomingSchedule()
    onPauseOrDispose {}
  }
  LaunchedEffect(sessionAvailable, homeNow) {
    if (!sessionAvailable) return@LaunchedEffect
    scheduleViewModel.loadUpcomingSchedule()
    scheduleViewModel.ensureCurrentWeekLoaded(forceRefresh = true)
    if (currentScreen == AppScreen.SCHEDULE)
        scheduleUiState.selectedTerm?.let(scheduleViewModel::loadWeeks)
  }

  val examViewModel: ExamViewModel? =
      if (currentScreen == AppScreen.EXAM) {
        viewModel(key = "exam") { ExamViewModel() }
      } else {
        null
      }
  val examUiState = examViewModel?.uiState?.collectAsState()?.value ?: ExamUiState()
  var showExamTermMenu by remember { mutableStateOf(false) }
  val gradeViewModel: GradeViewModel? =
      if (currentScreen == AppScreen.GRADE) {
        viewModel(key = "grade") { GradeViewModel() }
      } else {
        null
      }
  val gradeScoreWatchViewModel: GradeScoreWatchViewModel =
      viewModel(key = "grade-score-watch-${userData.schoolid}") {
        GradeScoreWatchViewModel(userKey = userData.schoolid)
      }
  val gradeScoreWatchUiState by gradeScoreWatchViewModel.uiState.collectAsState()

  val signinViewModel: SigninViewModel =
      viewModel(key = "signin-${userData.schoolid}") { SigninViewModel() }
  val signinUiState by signinViewModel.uiState.collectAsState()
  val evaluationViewModel: EvaluationViewModel? =
      if (currentScreen == AppScreen.EVALUATION) {
        viewModel(key = "evaluation") { EvaluationViewModel() }
      } else {
        null
      }
  val cgyyViewModel: CgyyViewModel? =
      if (shouldKeepCgyyViewModel) {
        viewModel(key = "cgyy-${userData.schoolid}") { CgyyViewModel() }
      } else {
        null
      }
  val cgyyUiState = cgyyViewModel?.uiState?.collectAsState()?.value ?: CgyyUiState()
  val libBookSeminarViewModel: LibBookSeminarViewModel? =
      if (shouldKeepLibBookViewModel)
          viewModel(key = "libbook-seminar-${userData.schoolid}-$connectionMode") {
            LibBookSeminarViewModel()
          }
      else null
  val qrCameraSupported = remember { supportsQrCamera() }
  val libBookViewModel: LibBookViewModel? =
      if (shouldKeepLibBookViewModel) {
        viewModel(key = "libbook-${userData.schoolid}") { LibBookViewModel() }
      } else {
        null
      }
  val classroomViewModel: ClassroomViewModel? =
      if (currentScreen == AppScreen.CLASSROOM_QUERY) {
        viewModel(key = "classroom") { ClassroomViewModel() }
      } else {
        null
      }
  val homeworkViewModel =
      remember(userData.schoolid, connectionMode) {
        HomeworkViewModel(
            HomeworkRepository(userData.schoolid, defaultHomeworkAdapters(userData.schoolid))
        )
      }
  DisposableEffect(homeworkViewModel) { onDispose { homeworkViewModel.close() } }
  LaunchedEffect(sessionAvailable) {
    homeworkViewModel.setSessionAvailable(sessionAvailable)
    if (!sessionAvailable) {
      homeBootstrapCoordinator.cancel()
      TdRuntime.service.onAppBackgrounded()
    }
  }
  val homeworkUiState by homeworkViewModel.state.collectAsState()
  val attendanceViewModel: AttendanceViewModel? =
      if (currentScreen == AppScreen.ATTENDANCE) {
        viewModel(key = "attendance-${userData.schoolid}-${connectionMode.storageKey}") {
          AttendanceViewModel(LocalAttendanceApi())
        }
      } else {
        null
      }
  val ihomeViewModel: IhomeViewModel? =
      if (currentScreen == AppScreen.IHOME) {
        viewModel(key = "ihome-${userData.schoolid}") { IhomeViewModel() }
      } else {
        null
      }
  val bykcViewModel: BykcViewModel =
      viewModel(key = "bykc-${userData.schoolid}") { BykcViewModel() }
  val bykcCoursesState by bykcViewModel.coursesState.collectAsState()
  val bykcDetailState by bykcViewModel.courseDetailState.collectAsState()
  val bykcChosenState by bykcViewModel.chosenCoursesState.collectAsState()
  val ygdkViewModel: YgdkViewModel? =
      if (currentScreen == AppScreen.HOME || currentScreen in ygdkScreens) {
        viewModel(key = "ygdk-${userData.schoolid}") { YgdkViewModel(userKey = userData.schoolid) }
      } else {
        null
      }
  val ygdkUiState = ygdkViewModel?.uiState?.collectAsState()?.value ?: YgdkUiState()
  val currentWeek = scheduleUiState.currentWeek
  val tdAvailable = true
  val tdReadOnly = connectionMode != ConnectionMode.DIRECT
  val tdService = remember(connectionMode) { TdRuntime.service }
  DisposableEffect(tdService) { onDispose { tdService.onAppBackgrounded() } }
  val tdUiState by tdService.state.collectAsState()
  LaunchedEffect(sessionAvailable, currentScreen, tdService, connectionMode) {
    if (!sessionAvailable) return@LaunchedEffect
    if (tdAvailable && (currentScreen == AppScreen.HOME || currentScreen == AppScreen.SETTINGS))
        tdService.load()
  }
  var homeTodoPreferences by
      remember(userData.schoolid) {
        mutableStateOf(HomeTodoPreferenceStore.read(userData.schoolid))
      }
  fun updateHomeTodoPreferences(update: HomeTodoPreferences.() -> HomeTodoPreferences) {
    homeTodoPreferences = homeTodoPreferences.update()
    HomeTodoPreferenceStore.write(userData.schoolid, homeTodoPreferences)
  }
  val ygdkWeekReminderKey = currentWeek?.let { "${it.term}:${it.serialNumber}" }
  val ygdkTermReminderKey = currentWeek?.term ?: scheduleUiState.selectedTerm?.itemCode
  val ygdkWeekDone = ygdkWeekReminderKey?.let { ygdkViewModel?.isHomeReminderWeekDone(it) } ?: false
  val ygdkTermDone = ygdkTermReminderKey?.let { ygdkViewModel?.isHomeReminderTermDone(it) } ?: false
  val isYgdkReminderWeek = currentWeek?.serialNumber?.let { it in 11..14 } == true
  val shouldLoadYgdkHomeOverview =
      ygdkViewModel != null &&
          ygdkUiState.homeReminderEnabled &&
          homeTodoPreferences.ygdkVisible &&
          isYgdkReminderWeek &&
          !ygdkWeekDone &&
          !ygdkTermDone

  var selectedCourse by remember { mutableStateOf<CourseClass?>(null) }
  val selectedSigninCourse =
      selectedCourse?.let { course -> findSigninCourse(course, signinUiState.classes) }
  var selectedBykcCourseId by remember { mutableStateOf<Long?>(null) }
  var selectedBykcCourseSnapshot by remember { mutableStateOf<BykcCourseDto?>(null) }
  val bykcCoursesListState = rememberLazyListState()
  val bykcFilterUserKey = userData.schoolid
  val defaultBykcFilters = remember { defaultBykcCourseFilters() }
  var bykcCourseFilters by
      remember(bykcFilterUserKey) {
        mutableStateOf(
            BykcCourseFilterStore.get(bykcFilterUserKey)?.toBykcCourseFilters()
                ?: defaultBykcFilters
        )
      }
  val planeApi = remember(userData.schoolid, connectionMode) { PaperPlaneApi() }
  var planeTasks by
      remember(userData.schoolid, connectionMode) {
        mutableStateOf<PaperPlanePointTasksResponse?>(null)
      }
  var planeLoading by remember { mutableStateOf(false) }
  var planeError by remember { mutableStateOf<String?>(null) }
  suspend fun refreshPlaneTasks() {
    if (planeLoading) return
    planeLoading = true
    try {
      planeApi
          .getTasks()
          .onSuccess {
            planeTasks = it
            planeError = paperPlanePointTasksError(it)
          }
          .onFailure {
            planeTasks = null
            planeError = "暂时无法读取纸飞机任务"
          }
    } finally {
      planeLoading = false
    }
  }
  LaunchedEffect(
      sessionAvailable,
      userData.schoolid,
      connectionMode,
      homeNow.date,
      currentScreen,
      homeTodoPreferences.paperPlaneVisible,
  ) {
    if (!sessionAvailable) return@LaunchedEffect
    if (
        homeTodoPreferences.paperPlaneVisible &&
            currentScreen in listOf(AppScreen.HOME, AppScreen.HOME_TODO, AppScreen.PAPER_PLANE)
    )
        refreshPlaneTasks()
  }
  val notificationStore = remember { NotificationPreferenceStore() }
  var notificationPreferences by
      remember(userData.schoolid) { mutableStateOf(notificationStore.read(userData.schoolid)) }
  var notificationStatus by remember { mutableStateOf<String?>(null) }
  val notificationRepository =
      remember(userData.schoolid, connectionMode) {
        ScheduleRepository(account = { userData.schoolid })
      }
  LaunchedEffect(
      sessionAvailable,
      userData.schoolid,
      connectionMode,
      notificationPreferences.course,
      notificationPreferences.dailyTodo,
      currentWeek?.term,
      homeNow.date,
      notificationRevision,
  ) {
    if (!sessionAvailable) return@LaunchedEffect
    try {
      if (!notificationPreferences.course && !notificationPreferences.dailyTodo) {
        cancelBuccNotifications()
        notificationStatus = "课前与每日待办提醒已关闭"
        return@LaunchedEffect
      }
      val events =
          if (notificationPreferences.course && currentWeek != null) {
            val term = currentWeek!!.term
            val weeks = notificationRepository.loadWeeks(term).getOrThrow()
            val schedules =
                weeks
                    .filter {
                      it.endDate.substringBefore(' ').substringBefore('T') >=
                          homeNow.date.toString()
                    }
                    .map {
                      it to notificationRepository.loadWeekly(term, it.serialNumber).getOrThrow()
                    }
            datedScheduleClasses(schedules, homeNow).mapNotNull { c ->
              c.start?.let {
                NotificationEvent(
                    c.id,
                    it.toInstant(kotlinx.datetime.TimeZone.of("Asia/Shanghai"))
                        .toEpochMilliseconds(),
                    c.course.courseName,
                )
              }
            }
          } else emptyList()
      notificationStatus =
          when (
              scheduleBuccNotifications(
                  events,
                  notificationPreferences.course,
                  notificationPreferences.dailyTodo,
              )
          ) {
            "scheduled",
            "scheduled_exact" -> "已安排通知"
            "scheduled_inexact",
            "scheduled_inexact_delayed",
            "scheduled_approximate" -> "已安排提醒，系统可能延迟；可开启准时提醒权限"
            "partial_capacity" -> "已优先安排近期课程，请定期打开 BUCC 补齐后续提醒"
            "notification_permission_required; exact_alarm_permission_required" ->
                "请在系统设置允许 BUCC 通知并开启准时提醒权限"
            "notification_permission_required",
            "permission_required" -> "请在系统设置允许 BUCC 通知"
            else -> "通知暂不可用，请检查系统通知权限"
          }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      notificationStatus = "课程提醒暂时无法更新，请刷新课表"
    }
  }
  val homeTodoItems =
      remember(
          bykcChosenState.courses,
          connectionMode,
          homeworkUiState.items,
          cgyyUiState.orders.content,
          signinUiState.classes,
          ygdkUiState.overview,
          ygdkUiState.loadError,
          currentWeek,
          ygdkUiState.homeReminderEnabled,
          homeTodoPreferences.ygdkVisible,
          ygdkWeekDone,
          ygdkTermDone,
          tdUiState.counts.semester,
          tdUiState.counts.term,
          planeTasks,
          planeError,
          homeTodoPreferences.paperPlaneVisible,
          tdUiState.error,
          homeTodoPreferences.tdVisible,
          homeNow,
      ) {
        buildHomeTodoItems(
            bykcCourses = bykcChosenState.courses,
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            homeworkItems =
                homeworkUiState.items.filter {
                  isHomeworkSourceAvailable(it.source, connectionMode)
                },
            cgyyOrders = cgyyUiState.orders.content,
            ygdkOverview = ygdkUiState.overview,
            currentWeek = currentWeek,
            ygdkReminderEnabled = ygdkUiState.homeReminderEnabled,
            ygdkWeekDone = ygdkWeekDone,
            ygdkTermDone = ygdkTermDone,
            tdSemesterCount = tdUiState.counts.semester,
            tdTerm = tdUiState.counts.term,
            paperPlaneTasks = planeTasks,
            paperPlaneQueryFailed = planeError != null,
            paperPlaneVisible = homeTodoPreferences.paperPlaneVisible,
            tdQueryFailed = tdUiState.error != null,
            ygdkQueryFailed = ygdkUiState.loadError != null,
            tdVisible = homeTodoPreferences.tdVisible && tdAvailable,
            ygdkVisible = homeTodoPreferences.ygdkVisible,
            now = homeNow,
        )
      }
  val homeTodoLoadingSources = buildList {
    if (bykcChosenState.isLoading) add(HomeTodoSource.BYKC)
    homeworkUiState.sources.forEach { (source, state) ->
      if (state.isLoading) add(source.toHomeTodoSource())
    }
    if (cgyyUiState.isOrdersLoading) add(HomeTodoSource.CGYY)
    if (signinUiState.isLoading) add(HomeTodoSource.SIGNIN)
    if (shouldLoadYgdkHomeOverview && ygdkUiState.isLoading) add(HomeTodoSource.YGDK)
    if (homeTodoPreferences.tdVisible && tdUiState.loading) add(HomeTodoSource.TD)
    if (homeTodoPreferences.paperPlaneVisible && planeLoading) add(HomeTodoSource.PAPER_PLANE)
  }
  val homeTodoLoading = homeTodoLoadingSources.isNotEmpty()
  val homeContentLoading =
      upcomingScheduleState.isLoading || homeTodoLoading || gradeScoreWatchUiState.isLoading
  val homeIsRefreshing =
      homeManualRefreshPending &&
          (homeManualRefreshStarted || homeBootstrapRunning || homeContentLoading)
  val homeTodoFailedSources = buildList {
    if (bykcChosenState.error != null) add(HomeTodoSource.BYKC)
    homeworkUiState.sources.forEach { (source, state) ->
      if (state.error != null) add(source.toHomeTodoSource())
    }
    if (cgyyUiState.ordersError != null) add(HomeTodoSource.CGYY)
    if (signinUiState.error != null) add(HomeTodoSource.SIGNIN)
    if (shouldLoadYgdkHomeOverview && ygdkUiState.loadError != null) add(HomeTodoSource.YGDK)
    if (homeTodoPreferences.tdVisible && tdUiState.error != null) add(HomeTodoSource.TD)
    if (homeTodoPreferences.paperPlaneVisible && planeError != null) add(HomeTodoSource.PAPER_PLANE)
  }

  fun startHomeBootstrap(forceRefresh: Boolean = false) {
    val showLoading =
        forceRefresh ||
            !scheduleViewModel.hasUpcomingLoaded() ||
            !signinViewModel.hasTodayLoaded() ||
            !homeworkViewModel.hasLoaded() ||
            !bykcViewModel.hasChosenCoursesLoaded() ||
            cgyyViewModel?.hasOrdersLoaded() != true ||
            !scheduleViewModel.hasCurrentWeekLoaded() ||
            (shouldLoadYgdkHomeOverview && ygdkViewModel.hasOverviewLoaded() != true) ||
            !gradeScoreWatchViewModel.hasChecked()
    homeBootstrapCoordinator.restart(
        HomeBootstrapActions(
            loadUpcomingSchedule = { force ->
              scheduleViewModel.ensureUpcomingLoaded(forceRefresh = force)
            },
            loadSignin = { force -> signinViewModel.ensureTodayLoaded(forceRefresh = force) },
            loadHomework = { force -> homeworkViewModel.ensureLoaded(forceRefresh = force) },
            loadBykc = { force -> bykcViewModel.ensureChosenCoursesLoaded(forceRefresh = force) },
            loadCgyy = { force -> cgyyViewModel?.ensureOrdersLoaded(forceRefresh = force) },
            loadYgdk = { force ->
              scheduleViewModel.ensureCurrentWeekLoaded(forceRefresh = force)
              if (shouldLoadYgdkHomeOverview) {
                ygdkViewModel.ensureOverviewLoaded(forceRefresh = force)
              }
            },
            checkGradeScores = { force ->
              gradeScoreWatchViewModel.checkForUpdates(forceRefresh = force)
            },
        ),
        forceRefresh = forceRefresh,
        showLoading = showLoading,
    )
  }

  fun refreshHomeData() {
    homeManualRefreshPending = true
    scope.launch { refreshPlaneTasks() }
    startHomeBootstrap(forceRefresh = true)
  }

  fun updateBykcCourseFilters(newFilters: BykcCourseFilters) {
    val shouldReloadCourses =
        bykcCourseFilters.requiresAllCourses() != newFilters.requiresAllCourses()
    bykcCourseFilters = newFilters
    if (newFilters == defaultBykcFilters) {
      BykcCourseFilterStore.clear(bykcFilterUserKey)
    } else {
      BykcCourseFilterStore.save(bykcFilterUserKey, newFilters.toStored())
    }
    if (shouldReloadCourses) {
      bykcViewModel.loadCourses(includeExpired = newFilters.requiresAllCourses())
    }
  }

  /** 跳转至指定页面。 */
  fun navigateTo(screen: AppScreen) {
    navController.navigateTo(screen)
    if (screen !in listOf(AppScreen.MY, AppScreen.SETTINGS, AppScreen.ABOUT)) showSidebar = false
  }

  val notificationDestination by NotificationRouteInbox.destination.collectAsState()
  LaunchedEffect(sessionAvailable, notificationDestination) {
    if (!sessionAvailable) return@LaunchedEffect
    when (notificationDestination) {
      "todo" -> navigateTo(AppScreen.HOME_TODO)
      "schedule" -> navigateTo(AppScreen.SCHEDULE)
      else -> return@LaunchedEffect
    }
    NotificationRouteInbox.consume()
  }

  /** 统一的返回逻辑处理。 */
  fun navigateBack() {
    if (
        currentScreen == AppScreen.LIBBOOK_SEMINAR &&
            libBookSeminarViewModel?.uiState?.value?.detail != null
    ) {
      libBookSeminarViewModel.closeRoom()
      return
    }
    if (dispatchNavigationBack(currentScreen, navController, financeBackHandler)) {
      val top = navController.currentScreen
      if (top in listOf(AppScreen.MY, AppScreen.SETTINGS, AppScreen.ABOUT)) showSidebar = false
    } else {
      showSidebar = false
    }
  }

  fun handleHomeTodoClick(todoItem: HomeTodoItem) {
    when (val action = todoItem.action) {
      is HomeTodoAction.OpenBykcCourse -> {
        selectedBykcCourseId = action.courseId
        selectedBykcCourseSnapshot = null
        bykcViewModel.loadCourseDetail(action.courseId)
        navigateTo(AppScreen.BYKC_DETAIL)
      }
      is HomeTodoAction.OpenHomework -> {
        homeworkViewModel.showDetail(action.id)
        navigateTo(AppScreen.HOMEWORK)
      }
      is HomeTodoAction.OpenSpocAssignment -> {
        homeworkUiState.items
            .firstOrNull {
              it.source == HomeworkSource.SPOC && it.upstreamId == action.assignmentId
            }
            ?.let { homeworkViewModel.showDetail(it.id) }
        navigateTo(AppScreen.HOMEWORK)
      }
      is HomeTodoAction.OpenJudgeAssignment -> {
        homeworkUiState.items
            .firstOrNull {
              it.source == HomeworkSource.JUDGE &&
                  it.courseId == action.courseId &&
                  it.upstreamId == action.assignmentId
            }
            ?.let { homeworkViewModel.showDetail(it.id) }
        navigateTo(AppScreen.HOMEWORK)
      }
      HomeTodoAction.OpenCgyyOrders -> {
        cgyyViewModel?.ensureOrdersLoaded()
        navigateTo(AppScreen.CGYY_ORDERS)
      }
      is HomeTodoAction.SigninCourse -> signinViewModel.performSignin(action.courseId)
      HomeTodoAction.OpenYgdkHome -> navigateTo(AppScreen.YGDK_HOME)
      HomeTodoAction.OpenTd -> if (tdAvailable) navigateTo(AppScreen.TD)
      HomeTodoAction.OpenPaperPlane -> navigateTo(AppScreen.PAPER_PLANE)
    }
  }

  fun openScoresFromHomeNotice() {
    gradeScoreWatchViewModel.consumeNotice()
    navigateTo(AppScreen.GRADE)
  }

  LaunchedEffect(
      sessionAvailable,
      ygdkUiState.overview,
      ygdkWeekReminderKey,
      ygdkTermReminderKey,
      ygdkUiState.homeReminderEnabled,
  ) {
    if (!sessionAvailable) return@LaunchedEffect
    if (!ygdkUiState.homeReminderEnabled) return@LaunchedEffect
    val viewModel = ygdkViewModel ?: return@LaunchedEffect
    val summary = ygdkUiState.overview?.summary ?: return@LaunchedEffect
    ygdkWeekReminderKey?.let { key ->
      if ((summary.weekCount ?: 0) >= 4) {
        viewModel.markHomeReminderWeekDone(key)
      }
    }
    ygdkTermReminderKey?.let { key ->
      if (summary.termCount?.let { it >= 16 } == true) {
        viewModel.markHomeReminderTermDone(key)
      }
    }
  }

  LaunchedEffect(sessionAvailable, currentScreen, currentWeek, shouldLoadYgdkHomeOverview) {
    if (!sessionAvailable) return@LaunchedEffect
    if (currentScreen == AppScreen.HOME && shouldLoadYgdkHomeOverview) {
      ygdkViewModel.ensureOverviewLoaded()
    }
  }

  LaunchedEffect(sessionAvailable, currentScreen) {
    if (!sessionAvailable) return@LaunchedEffect
    if (currentScreen in cgyyScreens) {
      hasVisitedCgyy = true
    }
    if (currentScreen in libBookScreens) {
      hasVisitedLibBook = true
    }
    if (currentScreen != AppScreen.HOME) {
      homeManualRefreshPending = false
      homeManualRefreshStarted = false
    }
    if (currentScreen == AppScreen.MY) {
      onEnsureUserInfo()
    }
  }

  // 连接模式切换后，重置所有 ViewModel 的加载标记与缓存数据，并强制刷新当前页面
  LaunchedEffect(connectionMode, userData.schoolid) {
    if (!sessionAvailable) return@LaunchedEffect
    // 常驻 ViewModel
    scheduleViewModel.resetLoadedState()
    signinViewModel.resetLoadedState()
    bykcViewModel.resetLoadedState()
    // 按需 ViewModel（当前可能为 null，仅在存活时重置）
    cgyyViewModel?.resetLoadedState()
    ygdkViewModel?.resetLoadedState()
    examViewModel?.resetLoadedState()
    gradeViewModel?.resetLoadedState()
    gradeScoreWatchViewModel.resetLoadedState()
    evaluationViewModel?.resetLoadedState()
    libBookViewModel?.resetLoadedState()
    libBookSeminarViewModel?.resetLoadedState()
    // 刷新当前页面数据
    when (currentScreen) {
      AppScreen.HOME -> startHomeBootstrap(forceRefresh = true)
      AppScreen.SCHEDULE -> scheduleViewModel.ensureScheduleLoaded(forceRefresh = true)
      AppScreen.EXAM -> examViewModel?.ensureLoaded(forceRefresh = true)
      AppScreen.GRADE -> gradeViewModel?.ensureLoaded(forceRefresh = true)
      AppScreen.BYKC_COURSES -> {
        bykcViewModel.ensureProfileLoaded(forceRefresh = true)
        bykcViewModel.ensureCoursesLoaded(
            includeExpired = bykcCourseFilters.requiresAllCourses(),
            forceRefresh = true,
        )
      }
      AppScreen.BYKC_CHOSEN -> bykcViewModel.ensureChosenCoursesLoaded(forceRefresh = true)
      AppScreen.BYKC_STATISTICS -> bykcViewModel.ensureStatisticsLoaded(forceRefresh = true)
      AppScreen.SIGNIN -> signinViewModel.ensureTodayLoaded(forceRefresh = true)
      AppScreen.COURSE_DETAIL -> signinViewModel.ensureTodayLoaded(forceRefresh = true)
      AppScreen.CGYY_HOME,
      AppScreen.CGYY_RESERVE_PICKER,
      AppScreen.CGYY_RESERVE_FORM -> cgyyViewModel?.ensureInitialDataLoaded(forceRefresh = true)
      AppScreen.CGYY_ORDERS -> {
        cgyyViewModel?.ensureInitialDataLoaded(forceRefresh = true)
        cgyyViewModel?.ensureOrdersLoaded(forceRefresh = true)
      }
      AppScreen.EVALUATION -> evaluationViewModel?.ensureLoaded(forceRefresh = true)
      AppScreen.HOMEWORK -> homeworkViewModel.ensureLoaded(forceRefresh = true)
      AppScreen.IHOME -> {
        ihomeViewModel?.resetLoadedState()
        ihomeViewModel?.ensureLoaded()
      }
      AppScreen.LIBBOOK_HOME,
      AppScreen.LIBBOOK_RESERVE -> libBookViewModel?.ensureInitialLoaded(forceRefresh = true)
      AppScreen.LIBBOOK_BOOKINGS -> libBookViewModel?.ensureBookingsLoaded(forceRefresh = true)
      AppScreen.YGDK_HOME,
      AppScreen.YGDK_FORM -> ygdkViewModel?.ensureLoaded(forceRefresh = true)
      else -> Unit
    }
  }

  // Don't clear a manual refresh until we've observed real loading at least once.
  LaunchedEffect(
      sessionAvailable,
      homeManualRefreshPending,
      homeManualRefreshStarted,
      homeBootstrapRunning,
      homeContentLoading,
  ) {
    if (!sessionAvailable) return@LaunchedEffect
    if (!homeManualRefreshPending) {
      homeManualRefreshStarted = false
    } else if (homeBootstrapRunning || homeContentLoading) {
      homeManualRefreshStarted = true
    } else if (homeManualRefreshStarted) {
      homeManualRefreshPending = false
      homeManualRefreshStarted = false
    }
  }

  LaunchedEffect(sessionAvailable, currentScreen, shouldKeepCgyyViewModel) {
    if (!sessionAvailable) return@LaunchedEffect
    if (currentScreen != AppScreen.HOME) {
      homeBootstrapCoordinator.cancel()
    }
    when (currentScreen) {
      AppScreen.HOME -> startHomeBootstrap()
      AppScreen.SCHEDULE -> scheduleViewModel.ensureScheduleLoaded()
      AppScreen.EXAM -> examViewModel?.ensureLoaded()
      AppScreen.GRADE -> gradeViewModel?.ensureLoaded()
      AppScreen.BYKC_COURSES -> {
        bykcViewModel.ensureProfileLoaded()
        bykcViewModel.ensureCoursesLoaded(includeExpired = bykcCourseFilters.requiresAllCourses())
      }
      AppScreen.BYKC_CHOSEN -> bykcViewModel.ensureChosenCoursesLoaded()
      AppScreen.BYKC_STATISTICS -> bykcViewModel.ensureStatisticsLoaded()
      AppScreen.SIGNIN -> signinViewModel.ensureTodayLoaded()
      AppScreen.COURSE_DETAIL -> signinViewModel.ensureTodayLoaded()
      AppScreen.CGYY_HOME,
      AppScreen.CGYY_RESERVE_PICKER,
      AppScreen.CGYY_RESERVE_FORM -> {
        cgyyViewModel?.ensureInitialDataLoaded()
      }
      AppScreen.CGYY_ORDERS -> {
        cgyyViewModel?.ensureInitialDataLoaded()
        cgyyViewModel?.ensureOrdersLoaded()
      }
      AppScreen.CGYY_LOCK_CODE -> {
        cgyyViewModel?.ensureInitialDataLoaded()
        cgyyViewModel?.ensureLockCodeLoaded(forceRefresh = true)
      }
      AppScreen.EVALUATION -> evaluationViewModel?.ensureLoaded()
      AppScreen.HOMEWORK -> homeworkViewModel.ensureLoaded()
      AppScreen.LIBBOOK_HOME,
      AppScreen.LIBBOOK_RESERVE -> libBookViewModel?.ensureInitialLoaded()
      AppScreen.LIBBOOK_BOOKINGS -> libBookViewModel?.ensureBookingsLoaded()
      AppScreen.YGDK_HOME,
      AppScreen.YGDK_FORM -> ygdkViewModel?.ensureLoaded()
      else -> Unit
    }
  }

  LaunchedEffect(sessionAvailable, currentScreen, signinUiState.signinResult) {
    if (!sessionAvailable) return@LaunchedEffect
    if (currentScreen == AppScreen.HOME) {
      signinUiState.signinResult?.let { message ->
        homeSnackbarHostState.showSnackbar(message)
        signinViewModel.clearSigninResult()
      }
    }
  }

  val screenTitle =
      when (currentScreen) {
        AppScreen.HOME -> "工作台"
        AppScreen.HOME_TODO -> "待办"
        AppScreen.MY -> "我的"
        AppScreen.SETTINGS -> "设置"
        AppScreen.ABOUT -> "关于"
        AppScreen.SCHEDULE -> "课程表"
        AppScreen.EXAM -> "考试查询"
        AppScreen.GRADE -> "成绩"
        AppScreen.IHOME -> "iHome"
        AppScreen.COURSE_DETAIL -> "课程详情"
        AppScreen.BYKC_HOME -> "博雅课程"
        AppScreen.BYKC_COURSES -> "选择课程"
        AppScreen.BYKC_DETAIL -> "课程详情"
        AppScreen.BYKC_CHOSEN -> "我的课程"
        AppScreen.BYKC_STATISTICS -> "课程统计"
        AppScreen.SIGNIN -> "课程签到"
        AppScreen.CGYY_HOME -> "研讨室预约"
        AppScreen.CGYY_RESERVE_PICKER -> "预约研讨室"
        AppScreen.CGYY_RESERVE_FORM -> "填写预约信息"
        AppScreen.CGYY_ORDERS -> "我的预约"
        AppScreen.CGYY_LOCK_CODE -> "查看密码"
        AppScreen.CLASSROOM_QUERY -> "空教室查询"
        AppScreen.EVALUATION -> "自动评教"
        AppScreen.HOMEWORK -> "作业中心"
        AppScreen.ATTENDANCE -> "签到查询"
        AppScreen.TD -> "TD 线"
        AppScreen.PAPER_PLANE -> "课堂纸飞机"
        AppScreen.CAMPUS_FINANCE -> "校园财务"
        AppScreen.LIBBOOK_HOME -> "图书馆空间预约"
        AppScreen.LIBBOOK_SEMINAR -> "图书馆研讨间预约"
        AppScreen.LIBBOOK_SCAN -> "扫码预约"
        AppScreen.LIBBOOK_RESERVE -> "预约座位"
        AppScreen.LIBBOOK_BOOKINGS -> "我的座位预约"
        AppScreen.YGDK_HOME -> "阳光打卡"
        AppScreen.YGDK_FORM -> "新增打卡"
      }

  Box(modifier = modifier.fillMaxSize()) {
    BackHandlerCompat(enabled = showSidebar || navController.navStack.size > 1) {
      if (showSidebar) showSidebar = false else if (navController.navStack.size > 1) navigateBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
      val isRootScreen = currentScreen == AppScreen.HOME
      val showGlobalTopBar = currentScreen != AppScreen.SCHEDULE
      if (showGlobalTopBar) {
        AppTopBar(
            title = screenTitle,
            canNavigateBack = !isRootScreen,
            onNavigationIconClick = {
              if (isRootScreen) showSidebar = !showSidebar else navigateBack()
            },
            actions = {
              // 特殊页面的顶部栏动作按钮
              if (currentScreen == AppScreen.EXAM) {
                Box {
                  TextButton(onClick = { showExamTermMenu = true }) {
                    Text(examUiState.selectedTerm?.itemName ?: "选择学期")
                    Icon(Icons.Default.ArrowDropDown, null)
                  }
                  DropdownMenu(
                      expanded = showExamTermMenu,
                      onDismissRequest = { showExamTermMenu = false },
                  ) {
                    examUiState.terms.forEach {
                      DropdownMenuItem(
                          text = { Text(it.itemName) },
                          onClick = {
                            examViewModel?.selectTerm(it)
                            showExamTermMenu = false
                          },
                      )
                    }
                  }
                }
              } else if (currentScreen == AppScreen.GRADE) {
                IconButton(onClick = { gradeViewModel?.ensureLoaded(forceRefresh = true) }) {
                  Icon(Icons.Default.Refresh, "刷新成绩")
                }
              }
            },
        )
      }

      Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        when (currentScreen) {
          AppScreen.HOME ->
              HomeScreen(
                  onScanLibraryQr =
                      if (qrCameraSupported) ({ navigateTo(AppScreen.LIBBOOK_SCAN) }) else null,
                  upcomingClasses = upcomingScheduleState.classes,
                  signinClasses = signinUiState.classes,
                  now = homeNow,
                  updatedAt =
                      upcomingScheduleState.updatedAt?.let {
                        if (upcomingScheduleState.fromCache) "$it（缓存）" else it
                      },
                  isLoading = upcomingScheduleState.isLoading,
                  isRefreshing = homeIsRefreshing,
                  error = upcomingScheduleState.error,
                  todayProgressOccurrences = upcomingScheduleState.todayProgressOccurrences,
                  scheduleHasLoaded = upcomingScheduleState.hasLoaded,
                  todoItems = homeTodoItems,
                  todoLoading = homeTodoLoading,
                  todoLoadingSources = homeTodoLoadingSources,
                  todoFailedSources = homeTodoFailedSources,
                  todoSourceErrors =
                      buildMap {
                        homeworkUiState.sources.forEach { (source, state) ->
                          state.error?.let { put(source.toHomeTodoSource(), it) }
                        }
                        cgyyUiState.ordersError?.let { put(HomeTodoSource.CGYY, it) }
                        tdUiState.error?.let { put(HomeTodoSource.TD, it) }
                        planeError?.let { put(HomeTodoSource.PAPER_PLANE, it) }
                        ygdkUiState.loadError?.let { put(HomeTodoSource.YGDK, it) }
                      },
                  signingTodoId =
                      signinUiState.signingInCourseId?.let { courseId -> "signin:$courseId" },
                  scoreUpdateNotice = gradeScoreWatchUiState.notice,
                  onRetrySchedule = { scheduleViewModel.loadUpcomingSchedule(forceRefresh = true) },
                  onRefresh = { refreshHomeData() },
                  onOpenScoresClick = { openScoresFromHomeNotice() },
                  onDismissScoreNotice = { gradeScoreWatchViewModel.consumeNotice() },
                  onTodoClick = { todoItem -> handleHomeTodoClick(todoItem) },
                  onSigninTodoClick = { courseId -> signinViewModel.performSignin(courseId) },
                  onCourseClick = {
                    selectedCourse = it
                    navigateTo(AppScreen.COURSE_DETAIL)
                  },
                  featureSections =
                      featureSections.map { section ->
                        section.copy(
                            features =
                                section.features.map {
                                  if (it.id == "td" && tdReadOnly)
                                      it.copy(description = "仅支持查询健康云次数，不支持打卡")
                                  else it
                                }
                        )
                      },
                  openSectionId = homeOpenSectionId,
                  onOpenSectionChange = { homeOpenSectionId = it },
                  onOpenSchedule = { navigateTo(AppScreen.SCHEDULE) },
                  onOpenTodos = { navigateTo(AppScreen.HOME_TODO) },
                  onFeatureOrderChange = { sectionId, order ->
                    WorkbenchOrderStore.write(userData.schoolid, sectionId, order)
                    featureSections = workbenchFeatureSections(userData.schoolid)
                  },
                  onFeatureClick = { id ->
                    when (id) {
                      "schedule" -> navigateTo(AppScreen.SCHEDULE)
                      "exam" -> navigateTo(AppScreen.EXAM)
                      "grade" -> navigateTo(AppScreen.GRADE)
                      "bykc" -> navigateTo(AppScreen.BYKC_HOME)
                      "classroom" -> navigateTo(AppScreen.CLASSROOM_QUERY)
                      "homework" -> navigateTo(AppScreen.HOMEWORK)
                      "attendance" -> navigateTo(AppScreen.ATTENDANCE)
                      "ihome" -> navigateTo(AppScreen.IHOME)
                      "libbook" -> navigateTo(AppScreen.LIBBOOK_HOME)
                      "td" -> if (tdAvailable) navigateTo(AppScreen.TD)
                      "cgyy" -> navigateTo(AppScreen.CGYY_HOME)
                      "ygdk" -> navigateTo(AppScreen.YGDK_HOME)
                      "evaluation" -> navigateTo(AppScreen.EVALUATION)
                      "paper-plane" -> navigateTo(AppScreen.PAPER_PLANE)
                      "finance" -> navigateTo(AppScreen.CAMPUS_FINANCE)
                    }
                  },
              )
          AppScreen.MY ->
              MyScreen(
                  userInfo = userInfo,
                  accountId = userData.schoolid,
                  avatarState = accountAvatar,
              )
          AppScreen.SETTINGS ->
              SettingsScreen(
                  currentMode = connectionMode,
                  availableModes = availableConnectionModes,
                  onModeSelected = onConnectionModeSelected,
                  onAccodingAccount = {
                    navigateTo(AppScreen.HOMEWORK)
                    homeworkViewModel.showLogin(HomeworkSource.ACCODING)
                  },
                  onPatpatAccount = {
                    if (tdAvailable) {
                      navigateTo(AppScreen.HOMEWORK)
                      homeworkViewModel.showLogin(HomeworkSource.PATPAT)
                    }
                  },
                  onTdAccountSave = { username, password ->
                    scope.launch {
                      tdService.saveHealthCloudCredentials(
                          TdHealthCloudCredentialDraft(username = username, password = password)
                      )
                    }
                  },
                  themeMode = themeMode,
                  onThemeModeSelected = onThemeModeSelected,
                  accodingAccountName =
                      HomeworkAccountNames.get(userData.schoolid, HomeworkSource.ACCODING),
                  patpatAccountName =
                      HomeworkAccountNames.get(userData.schoolid, HomeworkSource.PATPAT),
                  tdAccountName =
                      tdUiState.account
                          ?.takeIf { it.studentId == userData.schoolid }
                          ?.healthCloudUsername ?: userData.schoolid,
                  tdDefaultAccountName = userData.schoolid,
                  tdAccountOverridden =
                      tdUiState.account
                          ?.takeIf { it.studentId == userData.schoolid }
                          ?.healthCloudUsernameOverridden == true,
                  tdPasswordConfigured =
                      tdUiState.account
                          ?.takeIf { it.studentId == userData.schoolid }
                          ?.passwordConfigured == true,
                  tdAccountError = tdUiState.error,
                  tdAccountMessage = tdUiState.message,
                  tdTodoVisible = homeTodoPreferences.tdVisible,
                  ygdkTodoVisible = homeTodoPreferences.ygdkVisible,
                  paperPlaneTodoVisible = homeTodoPreferences.paperPlaneVisible,
                  notificationPreferences = notificationPreferences,
                  notificationStatus = notificationStatus,
                  onNotificationPreferencesChange = {
                    notificationPreferences = it
                    notificationStore.write(userData.schoolid, it)
                  },
                  onNotificationPermissions = {
                    scope.launch {
                      when (
                          notificationPermissionAction(buccNotificationPermissionState()) {
                            requestBuccNotificationPermission()
                          }
                      ) {
                        NotificationPermissionAction.GRANTED -> notificationStatus = "系统通知权限已允许"
                        NotificationPermissionAction.SHOW_SETTINGS_BUTTON ->
                            showNotificationPermissionDialog = true
                      }
                    }
                  },
                  onTdTodoVisibleChange = { enabled ->
                    updateHomeTodoPreferences { copy(tdVisible = enabled) }
                  },
                  onYgdkTodoVisibleChange = { enabled ->
                    updateHomeTodoPreferences { copy(ygdkVisible = enabled) }
                  },
                  onPaperPlaneTodoVisibleChange = { enabled ->
                    updateHomeTodoPreferences { copy(paperPlaneVisible = enabled) }
                  },
              )
          AppScreen.ABOUT -> AboutScreen()
          AppScreen.SCHEDULE ->
              ScheduleScreen(
                  terms = scheduleUiState.terms,
                  weeks = scheduleUiState.weeks,
                  weeklySchedule = scheduleUiState.weeklySchedule,
                  weekSchedules = scheduleUiState.weekSchedules,
                  upcomingClasses = upcomingScheduleState.classes,
                  upcomingIsLoading = upcomingScheduleState.isLoading,
                  upcomingError = upcomingScheduleState.error,
                  isMobile = getPlatform().isMobile,
                  selectedTerm = scheduleUiState.selectedTerm,
                  selectedWeek = scheduleUiState.selectedWeek,
                  isLoading = scheduleUiState.isLoading,
                  error = scheduleUiState.error,
                  isUpdating = scheduleUiState.isUpdating,
                  updatedAt = scheduleUiState.updatedAt,
                  diagnosticResponse = scheduleUiState.diagnosticResponse,
                  onUpdate = { scheduleViewModel.updateSchedule() },
                  onRefresh = {
                    scheduleViewModel.ensureScheduleLoaded(forceRefresh = true)
                    scheduleViewModel.loadUpcomingSchedule(forceRefresh = true)
                  },
                  onImportCurrentTerm = { scheduleViewModel.updateSchedule(currentTerm = true) },
                  onExportCalendar =
                      if (calendarImportAvailable()) ({ showCalendarImport = true }) else null,
                  onTermSelected = { scheduleViewModel.selectTerm(it) },
                  onWeekSelected = { scheduleViewModel.selectWeek(it) },
                  onNavigateBack = { navigateBack() },
                  onCourseClick = {
                    selectedCourse = it
                    navigateTo(AppScreen.COURSE_DETAIL)
                  },
              )
          AppScreen.EXAM -> examViewModel?.let { ExamScreen(viewModel = it) }
          AppScreen.GRADE -> gradeViewModel?.let { GradeScreen(viewModel = it) }
          AppScreen.COURSE_DETAIL ->
              selectedCourse?.let { course ->
                CourseDetailScreen(
                    course = course,
                    signinCourse = selectedSigninCourse,
                    isLoadingSigninClasses = signinUiState.isLoading,
                    isSigningIn = signinUiState.signingInCourseId == selectedSigninCourse?.courseId,
                    signinResult = signinUiState.signinResult,
                    onSigninClick = {
                      selectedSigninCourse?.let { signinViewModel.performSignin(it.courseId) }
                    },
                )
              }
          AppScreen.BYKC_HOME ->
              BykcHomeScreen(
                  onSelectCourseClick = { navigateTo(AppScreen.BYKC_COURSES) },
                  onMyCoursesClick = { navigateTo(AppScreen.BYKC_CHOSEN) },
                  onStatisticsClick = {
                    bykcViewModel.ensureStatisticsLoaded()
                    navigateTo(AppScreen.BYKC_STATISTICS)
                  },
              )
          AppScreen.BYKC_STATISTICS -> BykcStatisticsScreen(viewModel = bykcViewModel)
          AppScreen.SIGNIN -> SigninScreen(viewModel = signinViewModel)
          AppScreen.BYKC_COURSES ->
              BykcCoursesScreen(
                  courses = bykcCoursesState.courses,
                  isLoading = bykcCoursesState.isLoading,
                  isLoadingMore = bykcCoursesState.isLoadingMore,
                  hasMorePages = bykcCoursesState.hasMorePages,
                  filters = bykcCourseFilters,
                  error = bykcCoursesState.error,
                  listState = bykcCoursesListState,
                  onCourseClick = {
                    selectedBykcCourseId = it.id
                    selectedBykcCourseSnapshot = it
                    bykcViewModel.loadCourseDetail(it.id)
                    navigateTo(AppScreen.BYKC_DETAIL)
                  },
                  onFiltersChange = ::updateBykcCourseFilters,
                  onRefresh = {
                    bykcViewModel.loadCourses(
                        includeExpired = bykcCourseFilters.requiresAllCourses()
                    )
                  },
                  onLoadMore = {
                    bykcViewModel.loadMoreCourses(
                        includeExpired = bykcCourseFilters.requiresAllCourses()
                    )
                  },
              )
          AppScreen.BYKC_DETAIL ->
              BykcCourseDetailScreen(
                  course = bykcDetailState.course,
                  listSnapshot = selectedBykcCourseSnapshot,
                  isLoading = bykcDetailState.isLoading,
                  error = bykcDetailState.error,
                  operationInProgress = bykcDetailState.operationInProgress,
                  operationMessage = bykcDetailState.operationMessage,
                  onSelectClick = {
                    selectedBykcCourseId?.let { bykcViewModel.selectCourse(it) { _, _ -> } }
                  },
                  onDeselectClick = {
                    selectedBykcCourseId?.let { bykcViewModel.deselectCourse(it) { _, _ -> } }
                  },
                  onSignInClick = {
                    selectedBykcCourseId?.let {
                      bykcViewModel.signCourse(it, null, null, 1) { _, _ -> }
                    }
                  },
                  onSignOutClick = {
                    selectedBykcCourseId?.let {
                      bykcViewModel.signCourse(it, null, null, 2) { _, _ -> }
                    }
                  },
                  onClearMessage = { bykcViewModel.clearOperationMessage() },
              )
          AppScreen.BYKC_CHOSEN ->
              BykcChosenCoursesScreen(
                  courses = bykcChosenState.courses,
                  isLoading = bykcChosenState.isLoading,
                  error = bykcChosenState.error,
                  onCourseClick = {
                    selectedBykcCourseId = it.courseId
                    selectedBykcCourseSnapshot = null
                    bykcViewModel.loadCourseDetail(it.courseId)
                    navigateTo(AppScreen.BYKC_DETAIL)
                  },
                  onRefresh = { bykcViewModel.loadChosenCourses() },
              )
          AppScreen.CLASSROOM_QUERY ->
              classroomViewModel?.let {
                ClassroomQueryScreen(viewModel = it, onBackClick = { navigateBack() })
              }
          AppScreen.CGYY_HOME ->
              CgyyHomeScreen(
                  onReserveClick = { navigateTo(AppScreen.CGYY_RESERVE_PICKER) },
                  onOrdersClick = {
                    cgyyViewModel?.ensureOrdersLoaded()
                    navigateTo(AppScreen.CGYY_ORDERS)
                  },
                  onLockCodeClick = { navigateTo(AppScreen.CGYY_LOCK_CODE) },
              )
          AppScreen.CGYY_RESERVE_PICKER ->
              cgyyViewModel?.let {
                CgyyReservePickerScreen(
                    viewModel = it,
                    onNext = { navigateTo(AppScreen.CGYY_RESERVE_FORM) },
                )
              }
          AppScreen.CGYY_RESERVE_FORM ->
              cgyyViewModel?.let {
                CgyyReserveFormScreen(
                    viewModel = it,
                    onBackToSelection = { navigateBack() },
                    onSubmitSuccess = { navigateTo(AppScreen.CGYY_ORDERS) },
                )
              }
          AppScreen.CGYY_ORDERS -> cgyyViewModel?.let { CgyyOrdersScreen(viewModel = it) }
          AppScreen.CGYY_LOCK_CODE -> cgyyViewModel?.let { CgyyLockCodeScreen(viewModel = it) }
          AppScreen.EVALUATION -> evaluationViewModel?.let { EvaluationScreen(viewModel = it) }
          AppScreen.YGDK_HOME ->
              ygdkViewModel?.let {
                YgdkHomeScreen(
                    uiState = ygdkUiState,
                    onRefresh = { it.refreshAll() },
                    onLoadMore = { it.loadMoreRecords() },
                    onAddClick = { navigateTo(AppScreen.YGDK_FORM) },
                    onHomeReminderEnabledChange = { enabled -> it.setHomeReminderEnabled(enabled) },
                    onMessageConsumed = { it.clearSubmitMessage() },
                )
              }
          AppScreen.YGDK_FORM ->
              ygdkViewModel?.let { viewModel ->
                YgdkClockinFormScreen(
                    uiState = ygdkUiState,
                    imagePicker =
                        app.bucc.ui.common.util.rememberPlatformImagePicker(
                            onImagePicked = { viewModel.setPhoto(it) },
                            onError = { viewModel.showMessage(it) },
                        ),
                    onItemSelected = { viewModel.updateItemId(it) },
                    onStartTimeChange = { viewModel.updateStartTime(it) },
                    onEndTimeChange = { viewModel.updateEndTime(it) },
                    onPlaceChange = { viewModel.updatePlace(it) },
                    onShareChange = { viewModel.setShareToSquare(it) },
                    onClearPhoto = { viewModel.clearPhoto() },
                    onSubmit = { viewModel.submitClockin { navigateBack() } },
                )
              }
          AppScreen.HOMEWORK -> HomeworkScreen(viewModel = homeworkViewModel)
          AppScreen.ATTENDANCE -> attendanceViewModel?.let { AttendanceScreen(viewModel = it) }
          AppScreen.HOME_TODO ->
              HomeTodoListScreen(
                  items = homeTodoItems,
                  isLoading = homeTodoLoading,
                  signingTodoId = signinUiState.signingInCourseId?.let { "signin:$it" },
                  onTodoClick = { handleHomeTodoClick(it) },
                  onSigninTodoClick = { signinViewModel.performSignin(it) },
              )
          AppScreen.PAPER_PLANE -> PaperPlaneScreen(planeApi)
          AppScreen.CAMPUS_FINANCE ->
              CampusFinanceScreen(onBackHandlerChanged = { financeBackHandler = it })
          AppScreen.IHOME -> ihomeViewModel?.let { IhomeScreen(viewModel = it) }
          AppScreen.TD -> TdScreen(readOnly = tdReadOnly)
          AppScreen.LIBBOOK_HOME ->
              LibBookHomeScreen(
                  onReserveClick = {
                    libBookViewModel?.startNormalReservation()
                    navigateTo(AppScreen.LIBBOOK_RESERVE)
                  },
                  onSeminarClick = { navigateTo(AppScreen.LIBBOOK_SEMINAR) },
                  onScanClick =
                      if (qrCameraSupported) ({ navigateTo(AppScreen.LIBBOOK_SCAN) }) else null,
                  onBookingsClick = {
                    libBookViewModel?.ensureBookingsLoaded()
                    navigateTo(AppScreen.LIBBOOK_BOOKINGS)
                  },
              )
          AppScreen.LIBBOOK_RESERVE ->
              libBookViewModel?.let {
                LibBookReserveScreen(
                    viewModel = it,
                    onSubmitSuccess = { navigateTo(AppScreen.LIBBOOK_BOOKINGS) },
                )
              }
          AppScreen.LIBBOOK_BOOKINGS ->
              libBookViewModel?.let { LibBookBookingsScreen(viewModel = it) }
          AppScreen.LIBBOOK_SEMINAR -> libBookSeminarViewModel?.let { LibBookSeminarScreen(it) }
          AppScreen.LIBBOOK_SCAN ->
              LibBookScanScreen(
                  onSeat = { target ->
                    libBookViewModel?.openQrTarget(target)
                    navigateTo(AppScreen.LIBBOOK_RESERVE)
                  },
                  onBack = { navController.navigateBack() },
              )
        }
      }
    }

    AnimatedVisibility(visible = showSidebar, enter = fadeIn(), exit = fadeOut()) {
      Box(
          Modifier.fillMaxSize().background(Color.Black.copy(0.5f)).clickable {
            showSidebar = false
          }
      )
    }

    AnimatedVisibility(
        visible = showSidebar,
        enter = slideInHorizontally(),
        exit = slideOutHorizontally(targetOffsetX = { -it * 2 }),
    ) {
      Box(Modifier.fillMaxHeight(), Alignment.CenterStart) {
        Sidebar(
            userData = userData,
            avatarState = accountAvatar,
            onLogoutClick = {
              showSidebar = false
              onLogoutClick()
            },
            onMyClick = {
              showSidebar = false
              navigateTo(AppScreen.MY)
            },
            onSettingsClick = {
              showSidebar = false
              navigateTo(AppScreen.SETTINGS)
            },
            onAboutClick = {
              showSidebar = false
              navigateTo(AppScreen.ABOUT)
            },
            modifier = Modifier.align(Alignment.CenterStart),
        )
      }
    }

    if (currentScreen == AppScreen.HOME) {
      SnackbarHost(
          hostState = homeSnackbarHostState,
          modifier =
              Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 16.dp),
      )
    }

    if (showCalendarImport) {
      CalendarImportDialog(
          accountId = userData.schoolid,
          termName = scheduleUiState.selectedTerm?.itemName ?: "当前学期",
          loadWeeks = { scheduleViewModel.loadCalendarWeeks() },
          onDismiss = { showCalendarImport = false },
      )
    }

    if (showNotificationPermissionDialog) {
      AlertDialog(
          onDismissRequest = { showNotificationPermissionDialog = false },
          title = { Text("开启通知权限") },
          text = { Text("BUCC 当前无法发送通知。点击下方按钮打开系统通知设置，完成后返回 BUCC。") },
          confirmButton = {
            TextButton(
                onClick = {
                  showNotificationPermissionDialog = false
                  openBuccNotificationSettings()
                }
            ) {
              Text("打开系统设置")
            }
          },
          dismissButton = {
            TextButton(onClick = { showNotificationPermissionDialog = false }) { Text("稍后") }
          },
      )
    }
  }
}
