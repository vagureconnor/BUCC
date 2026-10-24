package app.bucc.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class CampusSecureCredentialsTest {
  @Test
  fun mockStorageSupportsSaveLoadDeleteAndUsernameIsolation() = runTest {
    val storage = MockCampusCredentialStorage()

    CampusSecureCredentials.withStorageForTests(storage) {
      CampusSecureCredentials.save(CampusCredential("student-a", "synthetic-password-a"))
      CampusSecureCredentials.save(CampusCredential("student-b", "synthetic-password-b"))

      assertEquals(
          CampusCredential("student-a", "synthetic-password-a"),
          CampusSecureCredentials.load("student-a"),
      )
      assertEquals(
          CampusCredential("student-b", "synthetic-password-b"),
          CampusSecureCredentials.load("student-b"),
      )

      CampusSecureCredentials.delete("student-a")

      assertNull(CampusSecureCredentials.load("student-a"))
      assertEquals(
          CampusCredential("student-b", "synthetic-password-b"),
          CampusSecureCredentials.load("student-b"),
      )
    }
  }

  @Test
  fun credentialToStringRedactsUsernameAndPassword() {
    val credential = CampusCredential("synthetic-username", "synthetic-password")

    assertFalse("synthetic-username" in credential.toString())
    assertFalse("synthetic-password" in credential.toString())
  }

  @Test
  fun restoringNestedMockReturnsToPriorStorage() = runTest {
    val outer = MockCampusCredentialStorage()
    val inner = MockCampusCredentialStorage()

    CampusSecureCredentials.withStorageForTests(outer) {
      CampusSecureCredentials.save(CampusCredential("mock-user", "mock-password"))
      CampusSecureCredentials.withStorageForTests(inner) {
        CampusSecureCredentials.save(CampusCredential("nested-user", "nested-password"))
      }
      assertEquals(
          CampusCredential("mock-user", "mock-password"),
          CampusSecureCredentials.load("mock-user"),
      )
    }

    assertNull(inner.load("mock-user"))
    assertNull(outer.load("nested-user"))
  }

  private class MockCampusCredentialStorage : CampusCredentialStorage {
    private val entries = mutableMapOf<String, CampusCredential>()

    override suspend fun save(credential: CampusCredential) {
      entries[credential.username] = credential
    }

    override suspend fun load(username: String): CampusCredential? = entries[username]

    override suspend fun delete(username: String) {
      entries.remove(username)
    }
  }
}
