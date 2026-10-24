package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime

class TdDirectServiceTest {
  private lateinit var previousModeSettings: Settings

  @BeforeTest
  fun directModeByDefault() {
    previousModeSettings = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
  }

  @AfterTest
  fun restoreMode() {
    ConnectionModeStore.settings = previousModeSettings
    ConnectionRuntime.resolveSelectedMode()
  }

  @Test
  fun backgroundingInterruptsExecutionBeforeTheExitAction() = runTest {
    val store = FakeDirectStore()
    val gateway = RecordingDirectGateway()
    val powerEvents = mutableListOf<Boolean>()
    val service = directService(store, gateway, powerEvents)

    val execution = launch { service.startCheckin(3) }
    testScheduler.runCurrent()
    service.onAppBackgrounded()
    execution.cancelAndJoin()

    assertEquals(listOf("check:2", "photo:2"), gateway.actions)
    assertEquals(TdExecutionStatus.INTERRUPTED, service.state.value.execution.status)
    assertEquals(listOf(true, false), powerEvents)
  }

  @Test
  fun readOnlyModesRejectCheckinBeforeAnyTdGatewayRequest() = runTest {
    val store = FakeDirectStore()
    val gateway = RecordingDirectGateway()
    val service = directService(store, gateway, mutableListOf())
    for (mode in listOf(ConnectionMode.WEBVPN)) {
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      val failure = assertFailsWith<IllegalStateException> { service.startCheckin(1) }
      assertTrue(failure.message.orEmpty().contains("仅支持查询"))
      assertTrue(gateway.actions.isEmpty())
    }
  }

  @Test
  fun switchingToReadOnlyModeCancelsAnActiveCheckinBeforeFurtherTdRequests() = runTest {
    val store = FakeDirectStore()
    val started = CompletableDeferred<Unit>()
    val gateway =
        object : TdGateway {
          val actions = mutableListOf<String>()

          override suspend fun check(
              studentId: String,
              cardId: String,
              machine: TdMachine,
              timestampMillis: Long,
          ): TdCheckResponse {
            actions += "check:${machine.id}"
            started.complete(Unit)
            return TdCheckResponse(success = true, message = "成功")
          }

          override suspend fun uploadPhoto(
              machine: TdMachine,
              photo: ByteArray,
              timestampMillis: Long,
          ) {
            actions += "photo:${machine.id}"
          }
        }
    val service = directService(store, gateway, mutableListOf())
    val execution = launch { service.startCheckin(3) }
    testScheduler.runCurrent()
    started.await()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    testScheduler.runCurrent()
    execution.join()
    assertTrue(gateway.actions.size <= 2)
    assertEquals(TdExecutionStatus.CANCELLED, service.state.value.execution.status)
  }

  @Test
  fun changingCampusForTheSameStudentKeepsTheSavedPassword() = runTest {
    val store = FakeDirectStore()
    val service = directService(store, RecordingDirectGateway(), mutableListOf())

    service.saveAccount(
        TdAccountDraft(
            studentId = "20370001",
            campus = TdCampus.SHAHE,
            healthCloudPassword = "",
        )
    )

    assertEquals("secret", store.password("20370001"))
    assertEquals(TdCampus.SHAHE, store.loadAccount()?.campus)
    assertTrue(store.loadAccount()?.entranceMachineId in setOf(8, 9, 10))
    assertTrue(store.loadAccount()?.exitMachineId in setOf(11, 12, 13))
  }

  @Test
  fun loadingTdFollowsActiveCampusAccountAndRetainsPasswordForColdStart() = runTest {
    val store = FakeDirectStore()
    TdCampusCredentials.setAuthenticated("20370002", "session-only")
    val service = directService(store, RecordingDirectGateway(), mutableListOf())

    try {
      service.load()

      assertEquals("20370002", store.loadAccount()?.studentId)
      assertEquals(TdCampus.XUEYUANLU, store.loadAccount()?.campus)
      assertEquals("session-only", TdCampusCredentials.current()?.password)
      assertEquals("session-only", store.password("20370002"))
      assertEquals(true, service.state.value.account?.passwordConfigured)

      // Simulate a later launch: the campus account is restored, but the in-memory SSO
      // credential is gone. The secure store still supplies the default Health Cloud password.
      TdCampusCredentials.clearSession()
      TdCampusCredentials.setActiveAccount("20370002")
      val restoredService = directService(store, RecordingDirectGateway(), mutableListOf())
      restoredService.load()

      assertEquals("20370002", restoredService.state.value.account?.healthCloudUsername)
      assertEquals(true, restoredService.state.value.account?.passwordConfigured)
    } finally {
      TdCampusCredentials.clearSession()
    }
  }

  @Test
  fun coldRestoredAccountWithoutHealthCloudPasswordPointsBackToSettings() = runTest {
    val store = FakeDirectStore()
    TdCampusCredentials.setActiveAccount("20370002")
    val service = directService(store, RecordingDirectGateway(), mutableListOf())

    try {
      service.load()
      service.refreshCounts()

      assertEquals("20370002", service.state.value.account?.healthCloudUsername)
      assertEquals(false, service.state.value.account?.passwordConfigured)
      assertEquals("健康云凭据不可用，请检查设置中的 TD 健康云账号", service.state.value.error)
    } finally {
      TdCampusCredentials.clearSession()
    }
  }

  @Test
  fun refreshCountsHidesCachedCountsUntilRefreshFinishes() = runTest {
    val cachedCounts =
        TdStoredCounts(
            today = 2,
            semester = 8,
            term = "2026-2027-1",
            dayCounts = mapOf("2026-10-12" to 2),
            updatedAt = "2026-10-12T08:00",
        )
    val requestStarted = CompletableDeferred<Unit>()
    val releaseRequest = CompletableDeferred<Unit>()
    val healthCloud =
        TdHealthCloudClient(
            HttpClient(
                MockEngine {
                  requestStarted.complete(Unit)
                  releaseRequest.await()
                  respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
            ),
            baseUrl = "http://healthcloud.test/",
        )
    val store = FakeDirectStore(initialCounts = cachedCounts)
    val service = directService(store, RecordingDirectGateway(), mutableListOf(), healthCloud)
    service.load()
    assertEquals(2, service.state.value.counts.today)

    val refresh = launch { service.refreshCounts() }
    testScheduler.runCurrent()
    requestStarted.await()

    assertTrue(service.state.value.refreshingCounts)
    assertEquals(null, service.state.value.counts.today)
    assertEquals(null, service.state.value.counts.semester)

    releaseRequest.complete(Unit)
    refresh.join()
  }

  @Test
  fun healthCloudOverrideDoesNotChangeTheTdDeviceIdentityAndCanResetToCampusAccount() = runTest {
    val store = FakeDirectStore()
    TdCampusCredentials.setAuthenticated("20370001", "session-only")
    val service = directService(store, RecordingDirectGateway(), mutableListOf())

    try {
      service.load()
      service.saveHealthCloudCredentials(
          TdHealthCloudCredentialDraft(username = "20370002", password = "healthcloud-secret")
      )

      assertEquals("20370001", store.loadAccount()?.studentId)
      assertEquals("20370002", store.loadAccount()?.healthCloudUsernameOverride)
      assertEquals("healthcloud-secret", store.password("20370002"))
      assertEquals("20370002", service.state.value.account?.healthCloudUsername)
      assertEquals(true, service.state.value.account?.healthCloudUsernameOverridden)

      service.saveHealthCloudCredentials(TdHealthCloudCredentialDraft(username = "20370002"))

      assertEquals("healthcloud-secret", store.password("20370002"))
      assertEquals("20370002", service.state.value.account?.healthCloudUsername)

      service.saveHealthCloudCredentials(TdHealthCloudCredentialDraft(username = null))

      assertEquals("20370001", store.loadAccount()?.studentId)
      assertEquals(null, store.loadAccount()?.healthCloudUsernameOverride)
      assertEquals(null, store.password("20370002"))
      assertEquals("20370001", service.state.value.account?.healthCloudUsername)
      assertEquals(false, service.state.value.account?.healthCloudUsernameOverridden)
      assertEquals(true, service.state.value.account?.passwordConfigured)
    } finally {
      TdCampusCredentials.clearSession()
    }
  }

  @Test
  fun changingToANewHealthCloudAccountRequiresItsPassword() = runTest {
    val store = FakeDirectStore()
    TdCampusCredentials.setAuthenticated("20370001", "session-only")
    val service = directService(store, RecordingDirectGateway(), mutableListOf())

    try {
      service.load()
      service.saveHealthCloudCredentials(TdHealthCloudCredentialDraft(username = "20370002"))

      assertEquals(null, store.loadAccount()?.healthCloudUsernameOverride)
      assertEquals(null, store.password("20370002"))
      assertEquals("新健康云账号需要填写对应密码", service.state.value.error)

      service.saveHealthCloudCredentials(
          TdHealthCloudCredentialDraft(username = "20370002", password = "new-account-secret")
      )

      assertEquals("20370002", store.loadAccount()?.healthCloudUsernameOverride)
      assertEquals("new-account-secret", store.password("20370002"))
      assertEquals(null, service.state.value.error)
    } finally {
      TdCampusCredentials.clearSession()
    }
  }

  @Test
  fun savingHealthCloudCredentialsBeforeLoadUsesTheCurrentCampusAccount() = runTest {
    val store = FakeDirectStore(initialAccount = null)
    TdCampusCredentials.setAuthenticated("20370002", "session-only")
    val service = directService(store, RecordingDirectGateway(), mutableListOf())

    try {
      service.saveHealthCloudCredentials(
          TdHealthCloudCredentialDraft(username = "20370003", password = "healthcloud-secret")
      )

      assertEquals("20370002", store.loadAccount()?.studentId)
      assertEquals("20370003", store.loadAccount()?.healthCloudUsernameOverride)
      assertEquals("healthcloud-secret", store.password("20370003"))
      assertEquals(null, service.state.value.error)
    } finally {
      TdCampusCredentials.clearSession()
    }
  }

  private fun directService(
      store: FakeDirectStore,
      gateway: TdGateway,
      powerEvents: MutableList<Boolean>,
      healthCloud: TdHealthCloudClient? = null,
  ) =
      TdDirectService(
          store = store,
          gateway = gateway,
          healthCloud =
              healthCloud
                  ?: TdHealthCloudClient(
                      HttpClient(
                          MockEngine { error("Health Cloud must not be called by this test") }
                      )
                  ),
          powerController = { powerEvents += it },
          now = { LocalDateTime(2026, 10, 12, 8, 0) },
          machineIndex = { 0 },
      )
}

private class FakeDirectStore(
    initialAccount: TdStoredAccount? =
        TdStoredAccount(
            studentId = "20370001",
            cardId = "136D731",
            campus = TdCampus.XUEYUANLU,
            entranceMachineId = 2,
            exitMachineId = 6,
        ),
    initialCounts: TdStoredCounts? = null,
) : TdDirectStore {
  private var account = initialAccount
  private var counts = initialCounts
  private val passwords = mutableMapOf("20370001" to "secret")

  override fun loadAccount(): TdStoredAccount? = account

  override fun saveAccount(account: TdStoredAccount) {
    this.account = account
  }

  override fun password(studentId: String): String? = passwords[studentId]

  override fun savePassword(studentId: String, password: String) {
    passwords[studentId] = password
  }

  override fun deletePassword(studentId: String) {
    passwords.remove(studentId)
  }

  override fun listImages(): List<TdImage> = listOf(TdImage("mock.jpg", 4))

  override fun saveImage(name: String, bytes: ByteArray) = Unit

  override fun deleteImage(name: String) = Unit

  override fun randomImageBytes(): ByteArray = byteArrayOf(1, 2, 3, 4)

  override fun loadCounts(studentId: String): TdStoredCounts? = counts

  override fun saveCounts(studentId: String, counts: TdStoredCounts) {
    this.counts = counts
  }
}

private class RecordingDirectGateway : TdGateway {
  val actions = mutableListOf<String>()

  override suspend fun check(
      studentId: String,
      cardId: String,
      machine: TdMachine,
      timestampMillis: Long,
  ): TdCheckResponse {
    actions += "check:${machine.id}"
    return TdCheckResponse(success = true, message = "成功")
  }

  override suspend fun uploadPhoto(
      machine: TdMachine,
      photo: ByteArray,
      timestampMillis: Long,
  ) {
    actions += "photo:${machine.id}"
  }
}
