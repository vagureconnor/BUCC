package app.bucc.connection

import app.bucc.api.ConnectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AuthenticationStage {
  MODE_SWITCH,
  CREDENTIAL_LOAD,
  PRELOAD,
  CREDENTIAL_SUBMIT,
  UC_ACTIVATION,
  IDENTITY_CHECK,
  CHALLENGE,
}

enum class AuthenticationPath {
  DIRECT,
  OFFICIAL_GATEWAY,
}

enum class AuthenticationChallenge {
  CAPTCHA,
  SERVICE,
  MFA,
}

data class AuthenticationDiagnostic(
    val mode: ConnectionMode?,
    val path: AuthenticationPath,
    val stage: AuthenticationStage,
    val httpStatus: Int? = null,
    val challenge: AuthenticationChallenge? = null,
    val credentialSubmissions: Int = 0,
)

/** Bounded memory only. No free-form fields, URLs, identities, cookies, tickets or secrets. */
object AuthenticationDiagnostics {
  private val mutableEvents = MutableStateFlow<List<AuthenticationDiagnostic>>(emptyList())
  val events = mutableEvents.asStateFlow()

  fun record(event: AuthenticationDiagnostic) {
    require(event.httpStatus == null || event.httpStatus in 100..599)
    require(event.credentialSubmissions in 0..1)
    mutableEvents.update { (it + event).takeLast(48) }
  }

  fun clear() {
    mutableEvents.value = emptyList()
  }
}
