@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

class IosCampusCredentialVaultTest {
  @Test
  fun roundTripsUpdatesDeletesAndSupportsEmptyPasswords() {
    withVault { vault, directory ->
      vault.save(CampusCredential("student-a", "first-password"))
      vault.save(CampusCredential("student-b", "other-password"))
      assertEquals(CampusCredential("student-a", "first-password"), vault.load("student-a"))

      vault.save(CampusCredential("student-a", "updated-password"))
      assertEquals(CampusCredential("student-a", "updated-password"), vault.load("student-a"))
      assertEquals(CampusCredential("student-b", "other-password"), vault.load("student-b"))

      vault.save(CampusCredential("empty-password", ""))
      assertEquals(CampusCredential("empty-password", ""), vault.load("empty-password"))

      vault.delete("student-a")
      assertNull(vault.load("student-a"))
      assertEquals(CampusCredential("student-b", "other-password"), vault.load("student-b"))
      check(NSFileManager.defaultManager.fileExistsAtPath("$directory/key-v1"))
    }
  }

  @Test
  fun copiedRecordCannotBeReadAsAnotherAccount() {
    withVault { vault, directory ->
      vault.save(CampusCredential("student-a", "password"))
      val source = "$directory/${IosCampusCredentialVault.recordFileName("student-a")}"
      val destination = "$directory/${IosCampusCredentialVault.recordFileName("student-b")}"
      check(NSFileManager.defaultManager.copyItemAtPath(source, destination, null))

      assertFails { vault.load("student-b") }
    }
  }

  @Test
  fun tamperedRecordFailsAuthentication() {
    withVault { vault, directory ->
      vault.save(CampusCredential("student-a", "password"))
      val path = "$directory/${IosCampusCredentialVault.recordFileName("student-a")}"
      val bytes = requireNotNull(NSData.dataWithContentsOfFile(path)).toByteArray()
      bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
      bytes.toNSData().writeToFile(path, true)

      assertFails { vault.load("student-a") }
    }
  }

  @Test
  fun missingKeyWithExistingRecordFailsClosed() {
    withVault { vault, directory ->
      vault.save(CampusCredential("student-a", "password"))
      check(NSFileManager.defaultManager.removeItemAtPath("$directory/key-v1", null))

      assertFails { vault.load("student-a") }
      assertFails { vault.save(CampusCredential("student-b", "password")) }
    }
  }

  private fun withVault(block: (IosCampusCredentialVault, String) -> Unit) {
    val directory = NSTemporaryDirectory() + "bucc-credentials-${NSUUID.UUID().UUIDString}"
    try {
      block(IosCampusCredentialVault(directory), directory)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }
  }

  private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    return ByteArray(size).also { result ->
      if (size > 0) result.usePinned { memcpy(it.addressOf(0), bytes, size.convert()) }
    }
  }

  private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(it.addressOf(0), size.toULong())
  }
}
