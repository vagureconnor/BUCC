package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal data class TdStoredAccount(
    val studentId: String,
    val cardId: String,
    val campus: TdCampus,
    val entranceMachineId: Int,
    val exitMachineId: Int,
    val healthCloudUsernameOverride: String? = null,
)

internal data class TdStoredCounts(
    val today: Int,
    val semester: Int,
    val term: String,
    val dayCounts: Map<String, Int>,
    val updatedAt: String,
)

internal interface TdDirectStore {
  fun loadAccount(): TdStoredAccount?

  fun saveAccount(account: TdStoredAccount)

  fun password(studentId: String): String?

  fun savePassword(studentId: String, password: String)

  fun deletePassword(studentId: String)

  fun listImages(): List<TdImage>

  fun saveImage(name: String, bytes: ByteArray)

  fun deleteImage(name: String)

  fun randomImageBytes(): ByteArray

  fun loadCounts(studentId: String): TdStoredCounts?

  fun saveCounts(studentId: String, counts: TdStoredCounts)
}

internal fun interface TdExecutionPowerController {
  fun setExecutionActive(active: Boolean)
}

internal class TdDirectService(
    private val store: TdDirectStore,
    private val gateway: TdGateway,
    private val healthCloud: TdHealthCloudClient,
    private val powerController: TdExecutionPowerController,
    private val now: () -> LocalDateTime = {
      Clock.System.now().toLocalDateTime(TimeZone.of("+08:00"))
    },
    private val machineIndex: (Int) -> Int = { size -> Random.nextInt(size) },
) : TdService {
  private val mutableState = MutableStateFlow(TdState())
  override val state: StateFlow<TdState> = mutableState.asStateFlow()
  private val operationMutex = Mutex()
  private var executionJob: Job? = null
  private var stopReason = StopReason.CANCELLED

  override suspend fun load() {
    setLoading(true)
    runCatching {
          val previous = store.loadAccount()
          val stored = syncCampusAccount(previous)
          if (stored != null && stored != previous) store.saveAccount(stored)
          val account = stored?.toPublicAccount()
          val counts =
              stored
                  ?.let { store.loadCounts(it.healthCloudUsernameOverride ?: it.studentId) }
                  ?.toPublicCounts() ?: TdCounts()
          mutableState.value =
              mutableState.value.copy(
                  loading = false,
                  account = account,
                  counts = counts,
                  images = store.listImages(),
                  error = null,
              )
        }
        .onFailure(::showError)
  }

  override suspend fun saveAccount(account: TdAccountDraft) {
    operationMutex.withLock {
      setLoading(true)
      runCatching {
            require(account.studentId.matches(Regex("[0-9]+"))) { "学号必须为数字" }
            require(store.listImages().isNotEmpty()) { "请先添加至少一张打卡图片" }
            val previous = store.loadAccount()
            val sameStudent = previous?.studentId == account.studentId
            val sameCampus = sameStudent && previous.campus == account.campus
            val entrances = tdMachines.filter { it.campus == account.campus && it.entrance }
            val exits = tdMachines.filter { it.campus == account.campus && !it.entrance }
            val stored =
                TdStoredAccount(
                    studentId = account.studentId,
                    cardId =
                        if (sameStudent) previous.cardId
                        else account.studentId.toLong().toString(16).uppercase(),
                    campus = account.campus,
                    entranceMachineId =
                        if (sameCampus) previous.entranceMachineId
                        else entrances[machineIndex(entrances.size)].id,
                    exitMachineId =
                        if (sameCampus) previous.exitMachineId
                        else exits[machineIndex(exits.size)].id,
                )
            if (!sameStudent && previous != null) {
              store.deletePassword(previous.studentId)
              previous.healthCloudUsernameOverride?.let(store::deletePassword)
            }
            val healthCloudUsernameOverride =
                if (sameStudent) previous.healthCloudUsernameOverride else null
            if (account.healthCloudPassword.isNotBlank()) {
              store.savePassword(
                  healthCloudUsernameOverride ?: account.studentId,
                  account.healthCloudPassword,
              )
            }
            val configured = stored.copy(healthCloudUsernameOverride = healthCloudUsernameOverride)
            store.saveAccount(configured)
            mutableState.value =
                mutableState.value.copy(
                    loading = false,
                    account = configured.toPublicAccount(),
                    counts =
                        store
                            .loadCounts(
                                configured.healthCloudUsernameOverride ?: configured.studentId
                            )
                            ?.toPublicCounts() ?: TdCounts(),
                    message = "TD 账号已保存",
                    error = null,
                )
          }
          .onFailure(::showError)
    }
  }

  override suspend fun saveHealthCloudCredentials(account: TdHealthCloudCredentialDraft) {
    operationMutex.withLock {
      setLoading(true)
      runCatching {
            val storedAccount = store.loadAccount()
            val previous = syncCampusAccount(storedAccount) ?: error("请先登录统一认证账号")
            if (previous != storedAccount) store.saveAccount(previous)
            val activeUsername =
                TdCampusCredentials.activeUsername()?.takeIf { it.matches(Regex("[0-9]+")) }
            val defaultUsername = activeUsername ?: previous.studentId
            val requestedUsername = account.username?.trim().orEmpty()
            require(requestedUsername.isEmpty() || requestedUsername.matches(Regex("[0-9]+"))) {
              "健康云账号必须为数字"
            }
            val usernameOverride =
                requestedUsername.takeIf { it.isNotEmpty() && it != defaultUsername }
            val previousUsername = previous.healthCloudUsernameOverride
            require(
                usernameOverride == null ||
                    usernameOverride == previousUsername ||
                    account.password.isNotBlank()
            ) {
              "新健康云账号需要填写对应密码"
            }
            if (account.password.isNotBlank()) {
              store.savePassword(usernameOverride ?: defaultUsername, account.password)
            }
            if (previousUsername != null && previousUsername != usernameOverride) {
              store.deletePassword(previousUsername)
            }
            val updated = previous.copy(healthCloudUsernameOverride = usernameOverride)
            store.saveAccount(updated)
            val healthCloudUsername = usernameOverride ?: defaultUsername
            mutableState.value =
                mutableState.value.copy(
                    loading = false,
                    account = updated.toPublicAccount(defaultUsername),
                    counts = store.loadCounts(healthCloudUsername)?.toPublicCounts() ?: TdCounts(),
                    message = "健康云账号设置已保存",
                    error = null,
                )
          }
          .onFailure(::showError)
    }
  }

  override suspend fun refreshCounts() {
    operationMutex.withLock {
      setLoading(true)
      mutableState.value = mutableState.value.copy(refreshingCounts = true, counts = TdCounts())
      try {
        runCatching {
              val account = store.loadAccount() ?: error("TD 账号尚未加载，请检查设置")
              val healthCloudUsername = healthCloudUsername(account)
              val password = passwordFor(account) ?: error("健康云凭据不可用，请检查设置中的 TD 健康云账号")
              val cached = store.loadCounts(healthCloudUsername)
              val current = now()
              val counts =
                  healthCloud.queryCounts(
                      studentId = healthCloudUsername,
                      password = password,
                      throughDay = current.date,
                      cachedTerm = cached?.term,
                      cachedDayCounts = cached?.dayCounts.orEmpty(),
                  )
              val stored =
                  TdStoredCounts(
                      today = counts.todayCount,
                      semester = counts.semesterCount,
                      term = counts.term,
                      dayCounts = counts.dayCounts,
                      updatedAt = current.toString(),
                  )
              store.saveCounts(healthCloudUsername, stored)
              mutableState.value =
                  mutableState.value.copy(
                      loading = false,
                      counts = stored.toPublicCounts(),
                      message = "健康云次数已刷新",
                      error = null,
                  )
            }
            .onFailure {
              if (it is CancellationException) throw it
              showError(it)
            }
      } finally {
        mutableState.value = mutableState.value.copy(loading = false, refreshingCounts = false)
      }
    }
  }

  override suspend fun addImage(name: String, bytes: ByteArray) {
    operationMutex.withLock {
      setLoading(true)
      runCatching {
            require(bytes.isNotEmpty()) { "图片内容不能为空" }
            store.saveImage(name, bytes)
            mutableState.value =
                mutableState.value.copy(
                    loading = false,
                    images = store.listImages(),
                    message = "图片已添加",
                    error = null,
                )
          }
          .onFailure(::showError)
    }
  }

  override suspend fun deleteImage(name: String) {
    operationMutex.withLock {
      setLoading(true)
      runCatching {
            store.deleteImage(name)
            mutableState.value =
                mutableState.value.copy(
                    loading = false,
                    images = store.listImages(),
                    message = "图片已删除",
                    error = null,
                )
          }
          .onFailure(::showError)
    }
  }

  override suspend fun startCheckin(rounds: Int) {
    check(ConnectionRuntime.currentMode() == ConnectionMode.DIRECT) {
      "当前连接模式仅支持查询 TD 健康云次数，打卡功能仅限直连模式"
    }
    require(rounds in setOf(1, 3)) { "打卡次数只能为 1 或 3" }
    if (!operationMutex.tryLock()) {
      showError(IllegalStateException("已有 TD 操作正在执行"))
      return
    }
    var modeWatcher: Job? = null
    try {
      val current = now()
      if (!inCheckinWindow(current.time)) {
        showError(IllegalStateException("当前不在 TD 打卡时段内"))
        return
      }
      val account =
          store.loadAccount()
              ?: run {
                showError(IllegalStateException("请先保存 TD 账号"))
                return
              }
      if (store.listImages().isEmpty()) {
        showError(IllegalStateException("请先添加至少一张打卡图片"))
        return
      }
      val entrance = tdMachines.first { it.id == account.entranceMachineId }
      val exit = tdMachines.first { it.id == account.exitMachineId }
      stopReason = StopReason.CANCELLED
      val ownerJob = currentCoroutineContext()[Job]
      executionJob = ownerJob
      modeWatcher =
          CoroutineScope(currentCoroutineContext()).launch {
            ConnectionRuntime.currentModeFlow.collect { mode ->
              if (mode != ConnectionMode.DIRECT) {
                stopReason = StopReason.CANCELLED
                ownerJob?.cancel(
                    CancellationException("TD check-in stopped after connection mode changed")
                )
              }
            }
          }
      powerController.setExecutionActive(true)
      mutableState.value =
          mutableState.value.copy(
              execution =
                  TdExecutionState(
                      status = TdExecutionStatus.RUNNING,
                      requestedRounds = rounds,
                      phase = TdExecutionPhase.PREPARING,
                  ),
              message = null,
              error = null,
          )
      val runner = TdExecutionRunner(gateway)
      val completed =
          runner.run(
              TdExecutionPlan(
                  studentId = account.studentId,
                  cardId = account.cardId,
                  entrance = entrance,
                  exit = exit,
                  rounds = rounds,
                  imageProvider = { store.randomImageBytes() },
              )
          ) { progress ->
            mutableState.value = mutableState.value.copy(execution = progress)
          }
      mutableState.value =
          mutableState.value.copy(
              execution =
                  mutableState.value.execution.copy(
                      status = TdExecutionStatus.COMPLETED,
                      completedRounds = completed,
                      remainingSeconds = null,
                      message = "已完成 $completed/$rounds 轮",
                  ),
              message = "TD 打卡流程已完成，请稍后刷新健康云流水",
          )
    } catch (_: CancellationException) {
      val interrupted = stopReason == StopReason.BACKGROUNDED
      mutableState.value =
          mutableState.value.copy(
              execution =
                  mutableState.value.execution.copy(
                      status =
                          if (interrupted) TdExecutionStatus.INTERRUPTED
                          else TdExecutionStatus.CANCELLED,
                      remainingSeconds = null,
                      message = if (interrupted) "应用进入后台，已停止后续动作" else "已取消，未发送后续动作",
                  ),
              message = null,
          )
    } catch (cause: Throwable) {
      mutableState.value =
          mutableState.value.copy(
              execution =
                  mutableState.value.execution.copy(
                      status = TdExecutionStatus.FAILED,
                      remainingSeconds = null,
                      message = cause.message ?: "TD 打卡失败",
                  ),
              error = cause.message ?: "TD 打卡失败",
          )
    } finally {
      modeWatcher?.cancel()
      executionJob = null
      powerController.setExecutionActive(false)
      operationMutex.unlock()
    }
  }

  override fun cancelCheckin() {
    stopReason = StopReason.CANCELLED
    executionJob?.cancel(CancellationException("TD check-in cancelled"))
  }

  override fun onAppBackgrounded() {
    if (executionJob?.isActive == true) {
      stopReason = StopReason.BACKGROUNDED
      executionJob?.cancel(CancellationException("TD interrupted because app entered background"))
    }
  }

  override fun clearNotice() {
    mutableState.value = mutableState.value.copy(message = null, error = null)
  }

  override fun close() {
    cancelCheckin()
  }

  private fun TdStoredAccount.toPublicAccount(defaultUsername: String = studentId) =
      TdAccount(
          studentId = studentId,
          campus = campus,
          passwordConfigured = passwordFor(this) != null,
          healthCloudUsername = healthCloudUsernameOverride ?: defaultUsername,
          healthCloudUsernameOverridden = healthCloudUsernameOverride != null,
      )

  private fun passwordFor(account: TdStoredAccount): String? {
    val username = healthCloudUsername(account)
    val campus = TdCampusCredentials.current()
    return campus?.takeIf { it.username == username }?.password
        ?: store.password(username)?.takeIf(String::isNotBlank)
  }

  private fun healthCloudUsername(account: TdStoredAccount): String =
      account.healthCloudUsernameOverride
          ?: TdCampusCredentials.activeUsername()?.takeIf { it.matches(Regex("[0-9]+")) }
          ?: account.studentId

  private fun syncCampusAccount(previous: TdStoredAccount?): TdStoredAccount? {
    val username =
        TdCampusCredentials.activeUsername()?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: return previous
    val sessionCredential = TdCampusCredentials.current()?.takeIf { it.username == username }
    if (previous?.studentId == username) {
      // The iOS store backs this with Keychain. Retain the default Health Cloud credential
      // there while the authenticated session still has the campus password, so restoring
      // the campus account on a later launch does not require another TD login prompt.
      sessionCredential?.password?.let { store.savePassword(username, it) }
      return previous
    }

    previous?.studentId?.let(store::deletePassword)
    previous?.healthCloudUsernameOverride?.let(store::deletePassword)
    sessionCredential?.password?.let { store.savePassword(username, it) }

    val campus = previous?.campus ?: TdCampus.XUEYUANLU
    val entrances = tdMachines.filter { it.campus == campus && it.entrance }
    val exits = tdMachines.filter { it.campus == campus && !it.entrance }
    return TdStoredAccount(
        studentId = username,
        cardId = username.toLong().toString(16).uppercase(),
        campus = campus,
        entranceMachineId = entrances[machineIndex(entrances.size)].id,
        exitMachineId = exits[machineIndex(exits.size)].id,
        healthCloudUsernameOverride = null,
    )
  }

  private fun TdStoredCounts.toPublicCounts() =
      TdCounts(
          today = today,
          semester = semester,
          term = term,
          updatedAt = updatedAt,
          cached = true,
      )

  private fun setLoading(loading: Boolean) {
    mutableState.value = mutableState.value.copy(loading = loading, error = null)
  }

  private fun showError(cause: Throwable) {
    mutableState.value =
        mutableState.value.copy(
            loading = false,
            error = cause.message ?: "TD 操作失败",
        )
  }

  private enum class StopReason {
    CANCELLED,
    BACKGROUNDED,
  }
}

private fun inCheckinWindow(time: LocalTime): Boolean =
    listOf(
            LocalTime(7, 30) to LocalTime(10, 0),
            LocalTime(11, 30) to LocalTime(14, 0),
            LocalTime(15, 30) to LocalTime(20, 0),
        )
        .any { (start, end) -> time >= start && time <= end }
