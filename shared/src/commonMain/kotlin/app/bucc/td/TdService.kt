package app.bucc.td

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TdService {
  val state: StateFlow<TdState>

  suspend fun load()

  suspend fun saveAccount(account: TdAccountDraft)

  suspend fun saveHealthCloudCredentials(account: TdHealthCloudCredentialDraft)

  suspend fun refreshCounts()

  suspend fun addImage(name: String, bytes: ByteArray)

  suspend fun deleteImage(name: String)

  suspend fun startCheckin(rounds: Int)

  fun cancelCheckin()

  fun onAppBackgrounded()

  fun clearNotice()

  fun close()
}

expect fun createTdService(): TdService

object TdRuntime {
  private val directService: TdService by lazy(::createTdService)
  val service: TdService
    get() = directService

  fun notifyAppBackgrounded() {
    service.onAppBackgrounded()
  }
}

class UnavailableTdService(message: String) : TdService {
  private val mutableState =
      MutableStateFlow(TdState(available = false, availabilityMessage = message))
  override val state: StateFlow<TdState> = mutableState.asStateFlow()

  override suspend fun load() = Unit

  override suspend fun saveAccount(account: TdAccountDraft) = unavailable()

  override suspend fun saveHealthCloudCredentials(account: TdHealthCloudCredentialDraft) =
      unavailable()

  override suspend fun refreshCounts() = unavailable()

  override suspend fun addImage(name: String, bytes: ByteArray) = unavailable()

  override suspend fun deleteImage(name: String) = unavailable()

  override suspend fun startCheckin(rounds: Int) = unavailable()

  override fun cancelCheckin() = Unit

  override fun onAppBackgrounded() = Unit

  override fun clearNotice() {
    mutableState.value = mutableState.value.copy(message = null, error = null)
  }

  override fun close() = Unit

  private fun unavailable(): Nothing =
      error(mutableState.value.availabilityMessage ?: "TD is unavailable on this platform")
}
