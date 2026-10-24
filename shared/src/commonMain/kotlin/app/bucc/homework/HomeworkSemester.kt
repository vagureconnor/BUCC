package app.bucc.homework

import app.bucc.repository.ScheduleRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Semester boundaries come from the campus teaching calendar, not guessed month cutoffs. */
data class HomeworkSemester(val code: String, val start: LocalDate, val end: LocalDate) {
  fun contains(startAt: LocalDateTime?, dueAt: LocalDateTime?): Boolean =
      (startAt ?: dueAt)?.date?.let { it >= start && it <= end } == true
}

class HomeworkSemesterProvider(private val schedules: ScheduleRepository = ScheduleRepository()) {
  private val mutex = Mutex()
  private var saved: HomeworkSemester? = null
  private var loadedAt: Instant? = null

  suspend fun load(): HomeworkSemester =
      mutex.withLock {
        val now = Clock.System.now()
        saved
            ?.takeIf { loadedAt?.let { now - it < 1.minutes } == true }
            ?.let {
              return@withLock it
            }
        schedules.invalidateQueries()
        val terms = schedules.loadTerms().getOrThrow()
        val term = terms.firstOrNull { it.selected } ?: error("校园系统未返回当前学期")
        val weeks = schedules.loadWeeks(term.itemCode).getOrThrow()
        val starts =
            weeks.mapNotNull { runCatching { LocalDate.parse(it.startDate.take(10)) }.getOrNull() }
        val ends =
            weeks.mapNotNull { runCatching { LocalDate.parse(it.endDate.take(10)) }.getOrNull() }
        check(starts.isNotEmpty() && ends.isNotEmpty()) { "当前学期教学日历缺失" }
        HomeworkSemester(term.itemCode, starts.min(), ends.max()).also {
          saved = it
          loadedAt = now
        }
      }
}
