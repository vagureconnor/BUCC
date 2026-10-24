package app.bucc.connection

import app.bucc.api.ConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticationDiagnosticsTest {
  @Test
  fun diagnosticsAreBoundedAndRejectReplayCounts() {
    AuthenticationDiagnostics.clear()
    val event =
        AuthenticationDiagnostic(
            ConnectionMode.WEBVPN,
            AuthenticationPath.OFFICIAL_GATEWAY,
            AuthenticationStage.PRELOAD,
        )
    repeat(60) { AuthenticationDiagnostics.record(event) }
    assertEquals(48, AuthenticationDiagnostics.events.value.size)
    assertFailsWith<IllegalArgumentException> {
      AuthenticationDiagnostics.record(event.copy(credentialSubmissions = 2))
    }
    AuthenticationDiagnostics.clear()
  }
}
