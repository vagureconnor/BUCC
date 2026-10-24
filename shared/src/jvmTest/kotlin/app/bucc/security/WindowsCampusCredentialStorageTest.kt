package app.bucc.security

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class WindowsCampusCredentialStorageTest {
  @Test
  fun syntheticCredentialRoundTripsUpdatesAndDeletesInWindowsVault() = runBlocking {
    assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
    val storage = createPlatformCampusCredentialStorage()
    val username = "bucc-fixture-${UUID.randomUUID()}"
    try {
      assertNull(storage.load(username))
      storage.save(CampusCredential(username, "测试-fixture-密码🔒"))
      assertEquals(CampusCredential(username, "测试-fixture-密码🔒"), storage.load(username))
      storage.save(CampusCredential(username, ""))
      assertEquals(CampusCredential(username, ""), storage.load(username))
      storage.delete(username)
      assertNull(storage.load(username))
      storage.delete(username)
    } finally {
      storage.delete(username)
    }
  }
}
