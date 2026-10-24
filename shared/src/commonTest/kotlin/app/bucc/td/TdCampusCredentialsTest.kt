package app.bucc.td

import app.bucc.api.storage.CredentialStore
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TdCampusCredentialsTest {
  @BeforeTest
  fun setUp() {
    CredentialStore.settings = MapSettings()
    CredentialStore.clear()
    TdCampusCredentials.clearSession()
  }

  @AfterTest
  fun tearDown() {
    TdCampusCredentials.clearSession()
  }

  @Test
  fun `TD can read the active campus login without saving it`() {
    TdCampusCredentials.setAuthenticated("20370001", "session-only")

    assertEquals(TdCampusCredential("20370001", "session-only"), TdCampusCredentials.current())
    assertNull(CredentialStore.getPassword())
  }

  @Test
  fun `remembered account name does not restore a campus password`() {
    CredentialStore.saveUsername("20370001")

    assertNull(TdCampusCredentials.current())
    CredentialStore.setRememberUsername(true)
    TdCampusCredentials.setActiveAccount("20370001")
    assertNull(TdCampusCredentials.current())
  }

  @Test
  fun `TD never reuses a remembered password for another active campus account`() {
    CredentialStore.saveUsername("20370001")
    CredentialStore.setRememberUsername(true)
    TdCampusCredentials.setActiveAccount("20370002")

    assertNull(TdCampusCredentials.current())
  }

  @Test
  fun `clearing the active session discards its in-memory campus password`() {
    TdCampusCredentials.setAuthenticated("20370001", "session-only")

    TdCampusCredentials.clearSession()

    assertNull(TdCampusCredentials.current())
  }
}
