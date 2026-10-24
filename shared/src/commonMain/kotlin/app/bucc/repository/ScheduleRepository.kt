package app.bucc.repository

import app.bucc.api.feature.ScheduleApi
import app.bucc.model.dto.*
import com.russhwolf.settings.Settings
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一次完整导入的学期；所有周次成功后才替换本地版本。 */
@Serializable
data class SemesterSchedule(
    val terms: List<Term>,
    val termCode: String,
    val weeks: List<Week>,
    val schedules: Map<Int, WeeklySchedule>,
    val updatedAt: String = "",
)

object ScheduleStore {
  private val revision = MutableStateFlow(0L)
  val changes = revision.asStateFlow()
  private var backing: Settings? = null
  var settings: Settings
    get() = backing ?: Settings().also { backing = it }
    set(value) {
      backing = value
    }

  private val json = Json { ignoreUnknownKeys = true }
  private const val ACCOUNT = "schedule_account"

  fun account(): String? = settings.getStringOrNull(ACCOUNT)

  fun useAccount(account: String) {
    require(account.isNotBlank())
    settings.putString(ACCOUNT, account)
    revision.update { it + 1 }
  }

  fun forgetAccount() {
    settings.remove(ACCOUNT)
    revision.update { it + 1 }
  }

  fun read(account: String): List<SemesterSchedule> =
      ScheduleSnapshotStorage(settings).read(account)?.let {
        json.decodeFromString<List<SemesterSchedule>>(it)
      } ?: emptyList()

  fun write(account: String, semesters: List<SemesterSchedule>) {
    ScheduleSnapshotStorage(settings).write(account, json.encodeToString(semesters))
    revision.update { it + 1 }
  }

  fun hasSavedSchedule(): Boolean =
      runCatching { account()?.let { read(it).isNotEmpty() } == true }.getOrDefault(false)
}

/** 默认在线查询，失败时回退到已手动本地化的课表；仅 update 写入持久存储。 */
class ScheduleRepository(
    private val api: ScheduleApi = ScheduleApi(),
    private val account: () -> String? = ScheduleStore::account,
    private val read: (String) -> List<SemesterSchedule> = ScheduleStore::read,
    private val write: (String, List<SemesterSchedule>) -> Unit = ScheduleStore::write,
    private val today: () -> LocalDate = {
      Clock.System.now().toLocalDateTime(TimeZone.of("+08:00")).date
    },
) {
  private val updateMutex = Mutex()
  private val queryMutex = Mutex()
  private var queryOwner: String? = null
  private var queryTerms: List<Term>? = null
  private val queryWeeks = mutableMapOf<String, List<Week>>()
  private val querySchedules = mutableMapOf<Pair<String, Int>, WeeklySchedule>()
  private val queryTimestamps = mutableMapOf<Pair<String, Int>, String>()
  private var queryUsedFallback = false
  var upcomingUpdatedAt: String? = null
    private set

  var upcomingFromCache: Boolean = false
    private set

  var todayProgressOccurrences: List<UpcomingClass> = emptyList()
    private set

  fun invalidateQueries() {
    queryTerms = null
    queryWeeks.clear()
    querySchedules.clear()
    queryTimestamps.clear()
  }

  private fun checkQueryOwner() {
    if (queryOwner != account()) {
      invalidateQueries()
      queryOwner = account()
    }
  }

  suspend fun loadTerms(offlineOnly: Boolean = false): Result<List<Term>> =
      queryMutex.withLock {
        checkQueryOwner()
        queryTerms
            ?.takeUnless { offlineOnly }
            ?.let {
              return@withLock Result.success(it)
            }
        onlineOrSaved(
                offlineOnly,
                { api.getTerms().onFailure { queryUsedFallback = true } },
                ::terms,
            )
            .onSuccess { queryTerms = it }
      }

  suspend fun loadWeeks(code: String, offlineOnly: Boolean = false): Result<List<Week>> =
      queryMutex.withLock {
        checkQueryOwner()
        queryWeeks[code]
            ?.takeUnless { offlineOnly }
            ?.let {
              return@withLock Result.success(it)
            }
        onlineOrSaved(
                offlineOnly,
                {
                  api.getWeeks(code).mapCatching(::normalizeWeeks).onFailure {
                    queryUsedFallback = true
                  }
                },
                { weeks(code) },
            )
            .onSuccess { queryWeeks[code] = it }
      }

  suspend fun loadWeekly(
      code: String,
      week: Int,
      offlineOnly: Boolean = false,
  ): Result<WeeklySchedule> =
      queryMutex.withLock {
        checkQueryOwner()
        querySchedules[code to week]
            ?.takeUnless { offlineOnly }
            ?.let {
              return@withLock Result.success(it)
            }
        var fetched = false
        onlineOrSaved(
                offlineOnly,
                {
                  api.getWeeklySchedule(code, week)
                      .mapCatching(::normalizeWeeklySchedule)
                      .onSuccess { fetched = true }
                      .onFailure { queryUsedFallback = true }
                },
                { weekly(code, week) },
            )
            .onSuccess {
              querySchedules[code to week] = it
              queryTimestamps[code to week] =
                  if (fetched)
                      Clock.System.now()
                          .toLocalDateTime(TimeZone.of("+08:00"))
                          .toString()
                          .take(16)
                          .replace('T', ' ')
                  else updatedAt(code).orEmpty()
            }
      }

  suspend fun loadUpcomingClasses(
      now: LocalDateTime,
      offlineOnly: Boolean = false,
  ): Result<List<UpcomingClass>> {
    val owner = account()
    val cached = owner?.let(ScheduleQueryCache::read).orEmpty()
    queryUsedFallback = false
    upcomingFromCache = offlineOnly
    upcomingUpdatedAt = null
    todayProgressOccurrences = emptyList()
    val loadedTimestamps = mutableListOf<String>()
    fun localWeeks(): List<Pair<Week, WeeklySchedule>> {
      val snapshots =
          (runCatching { owner?.let(read) }.getOrNull().orEmpty() + cached)
              .groupBy { it.termCode }
              .map { (_, entries) ->
                entries
                    .first()
                    .copy(
                        schedules =
                            entries.reversed().fold(emptyMap()) { acc, entry ->
                              acc + entry.schedules
                            }
                    )
              }
      upcomingUpdatedAt = snapshots.map { it.updatedAt }.filter { it.isNotBlank() }.minOrNull()
      return snapshots.flatMap { snapshot ->
        snapshot.weeks.mapNotNull { week ->
          snapshot.schedules[week.serialNumber]?.let { week to it }
        }
      }
    }
    fun local(): List<UpcomingClass> {
      upcomingFromCache = true
      val weeks = localWeeks()
      todayProgressOccurrences = datedScheduleClasses(weeks, now).filter { it.date == now.date }
      return upcomingClasses(weeks, now)
    }
    if (offlineOnly) return Result.success(local())
    try {
      val terms = loadTerms().getOrThrow()
      val selected = terms.firstOrNull { it.selected } ?: terms.firstOrNull()
      val candidates =
          terms
              .filter { it == selected || selected == null || it.itemCode > selected.itemCode }
              .sortedWith(compareBy<Term> { it != selected }.thenBy { it.itemCode })
      val loaded = mutableListOf<Pair<Week, WeeklySchedule>>()
      var snapshots = cached
      var firstError: Throwable? = null
      for (term in candidates) {
        val weeksResult = loadWeeks(term.itemCode)
        val weeks = weeksResult.getOrNull()
        if (weeks == null) {
          firstError = firstError ?: weeksResult.exceptionOrNull()
          continue
        }
        for (week in weeks.filter { it.endDate >= now.date.toString() }.sortedBy { it.startDate }) {
          val scheduleResult = loadWeekly(term.itemCode, week.serialNumber)
          val schedule = scheduleResult.getOrNull()
          if (schedule == null) {
            firstError = firstError ?: scheduleResult.exceptionOrNull()
            continue
          }
          loaded.add(week to schedule)
          todayProgressOccurrences =
              datedScheduleClasses(loaded, now).filter { it.date == now.date }
          queryTimestamps[term.itemCode to week.serialNumber]
              ?.takeIf { it.isNotBlank() }
              ?.let(loadedTimestamps::add)
          upcomingUpdatedAt = loadedTimestamps.minOrNull()
          upcomingFromCache = queryUsedFallback
          currentCoroutineContext().ensureActive()
          check(account() == owner) { "账号已切换，请重新加载课表" }
          if (owner != null) {
            val old = snapshots.firstOrNull { it.termCode == term.itemCode }
            val snapshot =
                SemesterSchedule(
                    terms,
                    term.itemCode,
                    weeks,
                    old?.schedules.orEmpty() + (week.serialNumber to schedule),
                    queryTimestamps[term.itemCode to week.serialNumber] ?: old?.updatedAt.orEmpty(),
                )
            snapshots = listOf(snapshot) + snapshots.filterNot { it.termCode == term.itemCode }
            ScheduleQueryCache.write(owner, snapshots)
          }
          val upcoming = upcomingClasses(loaded, now)
          if (upcoming.isNotEmpty()) return Result.success(upcoming)
        }
      }
      val upcoming = upcomingClasses(loaded, now)
      if (firstError != null) {
        upcomingFromCache = true
        val merged = upcomingClasses(loaded + localWeeks(), now)
        todayProgressOccurrences =
            datedScheduleClasses(loaded + localWeeks(), now).filter { it.date == now.date }
        if (merged.isNotEmpty()) return Result.success(merged)
        return Result.failure(firstError)
      }
      todayProgressOccurrences = datedScheduleClasses(loaded, now).filter { it.date == now.date }
      return Result.success(upcoming)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (account() != owner) return Result.failure(e)
      val saved = local()
      return if (saved.isNotEmpty()) Result.success(saved) else Result.failure(e)
    }
  }

  suspend fun loadTodayClasses(offlineOnly: Boolean = false): Result<List<TodayClass>> =
      onlineOrSaved(offlineOnly, { api.getTodaySchedule() }, ::todayClasses)

  private suspend fun <T> onlineOrSaved(
      offlineOnly: Boolean,
      online: suspend () -> Result<T>,
      local: () -> Result<T>,
  ): Result<T> {
    if (offlineOnly) return local()
    val owner = account()
    val result =
        try {
          online()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
    currentCoroutineContext().ensureActive()
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    if (account() != owner) return Result.failure(IllegalStateException("账号已切换，请重新加载课表"))
    // 无本地数据时保留真正的网络错误，不用“尚未导入”掩盖它。
    return if (result.isSuccess) result else local().takeIf { it.isSuccess } ?: result
  }

  private fun saved(): List<SemesterSchedule> {
    val owner = account() ?: error("请先登录并导入课表")
    return (read(owner) + ScheduleQueryCache.read(owner))
        .groupBy { it.termCode }
        .map { (_, entries) ->
          val latest = entries.maxBy { it.updatedAt }
          latest.copy(
              schedules =
                  entries
                      .sortedBy { it.updatedAt }
                      .fold(emptyMap()) { acc, entry -> acc + entry.schedules }
          )
        }
  }

  private fun normalizeWeeks(weeks: List<Week>): List<Week> =
      weeks.map {
        it.copy(startDate = normalizeDate(it.startDate), endDate = normalizeDate(it.endDate))
      }

  private fun normalizeDate(raw: String): String {
    // 本科接口返回 yyyy-MM-dd HH:mm:ss；缓存统一存日期，保证校验及离线范围比较正确。
    val date = raw.trim().substringBefore(' ').substringBefore('T')
    val match = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").matchEntire(date)
    requireNotNull(match) { "课表日期格式无效，未覆盖本地数据" }
    val (year, month, day) = match.destructured
    return LocalDate(year.toInt(), month.toInt(), day.toInt()).toString()
  }

  private fun semester(code: String) =
      saved().firstOrNull { it.termCode == code } ?: error("此学期尚未导入，请点击课表本地化")

  fun terms(): Result<List<Term>> = runCatching {
    val snapshots = saved()
    check(snapshots.isNotEmpty()) { "尚未导入课表，请进入课表页点击课表本地化" }
    val current = snapshots.first().terms.firstOrNull { it.selected }?.itemCode
    snapshots
        .flatMap { it.terms }
        .distinctBy { it.itemCode }
        .map { it.copy(selected = it.itemCode == current) }
  }

  fun weeks(code: String): Result<List<Week>> = runCatching {
    val date = today().toString()
    semester(code).weeks.map { it.copy(curWeek = date >= it.startDate && date <= it.endDate) }
  }

  fun weekly(code: String, week: Int): Result<WeeklySchedule> = runCatching {
    semester(code).schedules[week]?.let(::normalizeWeeklySchedule) ?: error("本地课表缺少此周，请点击课表本地化")
  }

  fun schedules(code: String): Result<Map<Int, WeeklySchedule>> = runCatching {
    semester(code).schedules.mapValues { normalizeWeeklySchedule(it.value) }
  }

  fun updatedAt(code: String?): String? =
      runCatching { saved().firstOrNull { it.termCode == code }?.updatedAt }.getOrNull()

  fun todayClasses(): Result<List<TodayClass>> = runCatching {
    val snapshots = saved()
    check(snapshots.isNotEmpty()) { "尚未导入课表，请进入课表页点击课表本地化" }
    val date = today()
    // 位图的尾部空周可能跨到下一学期，优先使用开学日期更晚的已保存学期。
    val snapshot =
        snapshots
            .filter {
              it.weeks.any { week ->
                date.toString() >= week.startDate && date.toString() <= week.endDate
              }
            }
            .maxByOrNull { it.weeks.minOf { week -> week.startDate } }
    val week =
        snapshot?.weeks?.firstOrNull {
          date.toString() >= it.startDate && date.toString() <= it.endDate
        }
    snapshot
        ?.schedules
        ?.get(week?.serialNumber)
        ?.let { normalizeCourseClasses(it.arrangedList, it.sectionTimes) }
        ?.filter { it.dayOfWeek == date.dayOfWeek.ordinal + 1 }
        .orEmpty()
        .sortedBy { it.beginTime ?: "" }
        .map {
          TodayClass(
              it.courseName,
              it.placeName,
              listOfNotNull(it.beginTime, it.endTime).joinToString("-"),
              it.courseName,
          )
        }
  }

  suspend fun update(code: String? = null): Result<SemesterSchedule> =
      updateMutex.withLock {
        try {
          val owner = account() ?: error("请先登录后更新课表")
          val imported = api.importSemester(code).getOrThrow()
          val snapshot =
              imported.copy(
                  weeks = normalizeWeeks(imported.weeks),
                  schedules = imported.schedules.mapValues { normalizeWeeklySchedule(it.value) },
              )
          currentCoroutineContext().ensureActive()
          check(account() == owner) { "账号已切换，请重新本地化课表" }
          check(code == null || snapshot.termCode == code) { "返回的课表学期与所选学期不一致，未覆盖本地数据" }
          check(snapshot.terms.any { it.itemCode == snapshot.termCode }) { "课表缺少学期信息" }
          check(snapshot.weeks.map { it.serialNumber }.toSet() == snapshot.schedules.keys) {
            "课表周次不完整，未覆盖本地数据"
          }
          // 本科 WeeklySchedule.code 实际为学号；研究生适配才填学期代码，不能统一比较。
          // 学期归属由请求参数、snapshot.termCode 和下面的 Week.term 校验。
          check(snapshot.weeks.distinctBy { it.serialNumber }.size == snapshot.weeks.size) {
            "课表周次重复，未覆盖本地数据"
          }
          snapshot.weeks.forEach {
            check(
                it.term == snapshot.termCode &&
                    it.serialNumber > 0 &&
                    LocalDate.parse(it.startDate) <= LocalDate.parse(it.endDate)
            ) {
              "课表日期无效，未覆盖本地数据"
            }
          }
          val updated =
              snapshot.copy(
                  updatedAt =
                      Clock.System.now()
                          .toLocalDateTime(TimeZone.of("+08:00"))
                          .toString()
                          .take(16)
                          .replace('T', ' ')
              )
          write(owner, listOf(updated) + read(owner).filterNot { it.termCode == updated.termCode })
          Result.success(updated)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }
}
