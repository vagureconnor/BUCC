package app.bucc.ui.screens.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.feature.GraduateScheduleLoadException
import app.bucc.api.feature.ScheduleApi
import app.bucc.model.dto.*
import app.bucc.repository.ScheduleRepository
import app.bucc.repository.UpcomingClass
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 默认在线浏览；只有用户手动本地化才保存整学期。离线入口显式禁止联网。 */
class ScheduleViewModel(
    scheduleApi: ScheduleApi = ScheduleApi(),
    private val repository: ScheduleRepository = ScheduleRepository(scheduleApi),
    private val offlineOnly: Boolean = false,
) : ViewModel() {
  private var todayLoadedOnce = false
  private var scheduleLoadedOnce = false
  private var currentWeekLoadedOnce = false
  private var todayJob: Job? = null
  private var currentWeekJob: Job? = null
  private var scheduleJob: Job? = null
  private var weekJob: Job? = null
  private var updateJob: Job? = null
  private var upcomingJob: Job? = null
  private var upcomingLoadedOnce = false
  private val _upcomingState = MutableStateFlow(UpcomingScheduleState())
  val upcomingState = _upcomingState.asStateFlow()
  private var generation = 0

  internal fun hasUpcomingLoaded(): Boolean = upcomingLoadedOnce

  fun ensureUpcomingLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && (upcomingLoadedOnce || upcomingJob?.isActive == true)) return
    loadUpcomingSchedule(forceRefresh)
  }

  fun loadUpcomingSchedule(forceRefresh: Boolean = false) {
    upcomingJob?.cancel()
    if (forceRefresh) repository.invalidateQueries()
    val request = generation
    _upcomingState.value = _upcomingState.value.copy(isLoading = true, error = null)
    upcomingJob =
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          val now = Clock.System.now().toLocalDateTime(TimeZone.of("+08:00"))
          val result = repository.loadUpcomingClasses(now, offlineOnly = offlineOnly)
          if (request != generation) return@launch
          upcomingLoadedOnce = result.isSuccess
          result
              .onSuccess {
                _upcomingState.value =
                    UpcomingScheduleState(
                        classes = it,
                        todayProgressOccurrences = repository.todayProgressOccurrences,
                        updatedAt = repository.upcomingUpdatedAt,
                        fromCache = repository.upcomingFromCache,
                        hasLoaded = true,
                    )
              }
              .onFailure {
                _upcomingState.value =
                    _upcomingState.value.copy(isLoading = false, error = it.message ?: "课表查询失败")
              }
        }
  }

  private val _uiState = MutableStateFlow(ScheduleUiState())
  val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()
  private val _todayScheduleState = MutableStateFlow(TodayScheduleState())
  val todayScheduleState: StateFlow<TodayScheduleState> = _todayScheduleState.asStateFlow()

  fun ensureTodayLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && (todayLoadedOnce || todayJob?.isActive == true)) return
    loadTodaySchedule()
  }

  internal fun hasTodayLoaded(): Boolean = todayLoadedOnce

  internal fun hasCurrentWeekLoaded(): Boolean = currentWeekLoadedOnce

  fun ensureCurrentWeekLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && (currentWeekLoadedOnce || currentWeekJob?.isActive == true)) return
    currentWeekJob?.cancel()
    val request = generation
    currentWeekJob =
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          repository.loadTerms(offlineOnly).onSuccess { terms ->
            val term = terms.firstOrNull { it.selected } ?: terms.firstOrNull() ?: return@onSuccess
            val weeks =
                repository.loadWeeks(term.itemCode, offlineOnly).getOrNull() ?: return@onSuccess
            if (generation != request) return@onSuccess
            _uiState.value = _uiState.value.copy(currentWeek = weeks.firstOrNull { it.curWeek })
            currentWeekLoadedOnce = true
          }
        }
  }

  fun ensureScheduleLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && (scheduleLoadedOnce || scheduleJob?.isActive == true)) return
    loadTerms(forceRefresh)
  }

  suspend fun loadCalendarWeeks(): Result<List<Pair<Week, WeeklySchedule>>> {
    val term = _uiState.value.selectedTerm ?: return Result.failure(IllegalStateException("请先选择学期"))
    return try {
      val weeks = repository.loadWeeks(term.itemCode, offlineOnly).getOrThrow()
      val schedules = mutableListOf<Pair<Week, WeeklySchedule>>()
      for (week in weeks) {
        schedules +=
            week to
                repository.loadWeekly(term.itemCode, week.serialNumber, offlineOnly).getOrThrow()
      }
      Result.success(schedules)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  fun resetLoadedState() {
    generation++
    listOf(todayJob, currentWeekJob, scheduleJob, weekJob, updateJob, upcomingJob).forEach {
      it?.cancel()
    }
    todayLoadedOnce = false
    upcomingLoadedOnce = false
    repository.invalidateQueries()
    _upcomingState.value = UpcomingScheduleState()
    scheduleLoadedOnce = false
    currentWeekLoadedOnce = false
    _uiState.value = ScheduleUiState()
    _todayScheduleState.value = TodayScheduleState()
  }

  fun loadTodaySchedule() {
    todayJob?.cancel()
    val request = generation
    _todayScheduleState.value = _todayScheduleState.value.copy(isLoading = true, error = null)
    todayJob =
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          val result = repository.loadTodayClasses(offlineOnly)
          if (generation != request) return@launch
          todayLoadedOnce = result.isSuccess
          result
              .onSuccess { _todayScheduleState.value = TodayScheduleState(todayClasses = it) }
              .onFailure { _todayScheduleState.value = TodayScheduleState(error = it.message) }
        }
  }

  fun loadTerms(forceRefresh: Boolean = false) {
    if (forceRefresh) repository.invalidateQueries()
    scheduleJob?.cancel()
    weekJob?.cancel()
    val request = generation
    _uiState.value = _uiState.value.copy(isLoading = true, error = null, diagnosticResponse = null)
    scheduleJob =
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          val result = repository.loadTerms(offlineOnly)
          if (generation != request) return@launch
          result
              .onSuccess { terms ->
                val selected =
                    terms.firstOrNull { it.itemCode == _uiState.value.selectedTerm?.itemCode }
                        ?: terms.firstOrNull { it.selected }
                        ?: terms.firstOrNull()
                val changedTerm = selected?.itemCode != _uiState.value.selectedTerm?.itemCode
                _uiState.value =
                    _uiState.value.copy(
                        terms = terms,
                        selectedTerm = selected,
                        weeks = if (changedTerm) emptyList() else _uiState.value.weeks,
                        selectedWeek = if (changedTerm) null else _uiState.value.selectedWeek,
                        weeklySchedule = if (changedTerm) null else _uiState.value.weeklySchedule,
                        weekSchedules =
                            if (changedTerm) emptyMap() else _uiState.value.weekSchedules,
                    )
                if (selected != null) fetchWeeks(selected, forceRefresh)
                else {
                  scheduleLoadedOnce = true
                  _uiState.value =
                      _uiState.value.copy(
                          isLoading = false,
                          weeks = emptyList(),
                          weekSchedules = emptyMap(),
                          selectedWeek = null,
                          weeklySchedule = null,
                          updatedAt = null,
                      )
                }
              }
              .onFailure { showLoadError(it) }
        }
  }

  fun selectTerm(term: Term) {
    if (_uiState.value.isUpdating) return
    _uiState.value =
        _uiState.value.copy(
            selectedTerm = term,
            selectedWeek = null,
            weeklySchedule = null,
            weeks = emptyList(),
            weekSchedules = emptyMap(),
            diagnosticResponse = null,
            updatedAt = repository.updatedAt(term.itemCode),
        )
    loadWeeks(term)
  }

  fun loadWeeks(term: Term) {
    scheduleJob?.cancel()
    weekJob?.cancel()
    _uiState.value = _uiState.value.copy(isLoading = true, error = null)
    scheduleJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { fetchWeeks(term) }
  }

  private suspend fun fetchWeeks(term: Term, forceRefresh: Boolean = false) {
    val request = generation
    val result = repository.loadWeeks(term.itemCode, offlineOnly)
    if (generation != request || _uiState.value.selectedTerm?.itemCode != term.itemCode) return
    result
        .onSuccess { weeks ->
          val selected =
              weeks.firstOrNull {
                it.serialNumber == _uiState.value.selectedWeek?.serialNumber &&
                    it.term == _uiState.value.selectedWeek?.term
              } ?: weeks.firstOrNull { it.curWeek } ?: weeks.firstOrNull()
          _uiState.value =
              _uiState.value.copy(
                  weeks = weeks,
                  weekSchedules =
                      if (offlineOnly) repository.schedules(term.itemCode).getOrDefault(emptyMap())
                      else if (forceRefresh) emptyMap() else _uiState.value.weekSchedules,
                  selectedWeek = selected,
                  weeklySchedule = null,
                  updatedAt = repository.updatedAt(term.itemCode),
                  error = null,
                  isLoading = false,
              )
          scheduleLoadedOnce = true
          selected?.let { loadWeeklySchedule(term, it) }
        }
        .onFailure { showLoadError(it) }
  }

  fun selectWeek(week: Week) {
    _uiState.value = _uiState.value.copy(selectedWeek = week)
    _uiState.value.selectedTerm?.let { loadWeeklySchedule(it, week) }
  }

  fun loadWeeklySchedule(term: Term, week: Week) {
    weekJob?.cancel()
    val cached = _uiState.value.weekSchedules[week.serialNumber]
    _uiState.value =
        _uiState.value.copy(weeklySchedule = cached, isLoading = cached == null, error = null)
    if (cached != null) return
    val request = generation
    weekJob =
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          val result = repository.loadWeekly(term.itemCode, week.serialNumber, offlineOnly)
          if (
              generation != request ||
                  _uiState.value.selectedTerm?.itemCode != term.itemCode ||
                  _uiState.value.selectedWeek?.serialNumber != week.serialNumber
          )
              return@launch
          result
              .onSuccess {
                scheduleLoadedOnce = true
                _uiState.value =
                    _uiState.value.copy(
                        weeklySchedule = it,
                        weekSchedules = _uiState.value.weekSchedules + (week.serialNumber to it),
                        isLoading = false,
                        error = null,
                    )
              }
              .onFailure { showLoadError(it) }
        }
  }

  private fun showLoadError(error: Throwable) {
    scheduleLoadedOnce = false
    _uiState.value =
        _uiState.value.copy(
            isLoading = false,
            error = error.message,
            diagnosticResponse = (error.cause as? GraduateScheduleLoadException)?.responseBody,
        )
  }

  fun updateSchedule(currentTerm: Boolean = false) {
    if (offlineOnly || _uiState.value.isUpdating) return
    scheduleJob?.cancel()
    weekJob?.cancel()
    val code = if (currentTerm) null else _uiState.value.selectedTerm?.itemCode
    val request = generation
    _uiState.value =
        _uiState.value.copy(
            isUpdating = true,
            isLoading = false,
            error = null,
            diagnosticResponse = null,
        )
    updateJob =
        viewModelScope.launch {
          try {
            val result = repository.update(code)
            if (generation != request) return@launch
            result
                .onSuccess { snapshot ->
                  val selectedWeek =
                      snapshot.weeks.firstOrNull {
                        it.term == _uiState.value.selectedWeek?.term &&
                            it.serialNumber == _uiState.value.selectedWeek?.serialNumber
                      } ?: snapshot.weeks.firstOrNull { it.curWeek } ?: snapshot.weeks.firstOrNull()
                  _uiState.value =
                      _uiState.value.copy(
                          terms = snapshot.terms,
                          selectedTerm = snapshot.terms.first { it.itemCode == snapshot.termCode },
                          weeks = snapshot.weeks,
                          selectedWeek = selectedWeek,
                          weekSchedules = snapshot.schedules,
                          weeklySchedule = snapshot.schedules[selectedWeek?.serialNumber],
                          updatedAt = snapshot.updatedAt,
                      )
                  scheduleLoadedOnce = true
                  loadTodaySchedule()
                  loadUpcomingSchedule(forceRefresh = true)
                  ensureCurrentWeekLoaded(forceRefresh = true)
                }
                .onFailure {
                  _uiState.value =
                      _uiState.value.copy(
                          diagnosticResponse =
                              (it.cause as? GraduateScheduleLoadException)?.responseBody,
                          error =
                              "本地化失败：${it.message}" +
                                  if (_uiState.value.updatedAt != null) "。已保存的课表仍可查看。" else "",
                      )
                }
          } finally {
            if (generation == request) _uiState.value = _uiState.value.copy(isUpdating = false)
          }
        }
  }

  fun clearError() {
    _uiState.value = _uiState.value.copy(error = null, diagnosticResponse = null)
    _todayScheduleState.value = _todayScheduleState.value.copy(error = null)
  }
}

/** 周课表界面 UI 状态。 */
data class ScheduleUiState(
    val isLoading: Boolean = false,
    val isUpdating: Boolean = false,
    val updatedAt: String? = null,
    val terms: List<Term> = emptyList(),
    val weeks: List<Week> = emptyList(),
    val currentWeek: Week? = null,
    val selectedTerm: Term? = null,
    val selectedWeek: Week? = null,
    val weeklySchedule: WeeklySchedule? = null,
    val weekSchedules: Map<Int, WeeklySchedule> = emptyMap(),
    val error: String? = null,
    val diagnosticResponse: String? = null,
)

/** 今日摘要界面 UI 状态。 */
data class TodayScheduleState(
    val isLoading: Boolean = false,
    val todayClasses: List<TodayClass> = emptyList(),
    val error: String? = null,
)

/** Dated upcoming occurrences shared by the workbench and weekly schedule repository. */
data class UpcomingScheduleState(
    val isLoading: Boolean = false,
    val classes: List<UpcomingClass> = emptyList(),
    val todayProgressOccurrences: List<UpcomingClass> = emptyList(),
    val updatedAt: String? = null,
    val fromCache: Boolean = false,
    val error: String? = null,
    val hasLoaded: Boolean = false,
)

/**
 * Course timetable labels: prefer upstream section/course times, fill any missing periods locally.
 */
internal fun scheduleDisplayTimes(schedules: Collection<WeeklySchedule>): List<SectionTime> {
  val mapped =
      listOf(
          "08:00" to "08:45",
          "08:50" to "09:35",
          "09:50" to "10:35",
          "10:40" to "11:25",
          "11:30" to "12:15",
          "14:00" to "14:45",
          "14:50" to "15:35",
          "15:50" to "16:35",
          "16:40" to "17:25",
          "17:30" to "18:15",
          "19:00" to "19:45",
          "19:50" to "20:35",
          "20:40" to "21:25",
          "21:30" to "22:15",
      )
  val actual = scheduleSectionTimes(schedules).associateBy { it.section }
  return mapped.mapIndexed { index, (start, end) ->
    val section = index + 1
    val time = actual[section]
    SectionTime(section, time?.start ?: start, time?.end ?: end)
  }
}
