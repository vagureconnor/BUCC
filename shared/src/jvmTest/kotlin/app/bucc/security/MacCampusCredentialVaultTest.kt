package app.bucc.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue

class MacCampusCredentialVaultTest {
  @Test
  fun credentialsSurviveRestartUpdateAndDeleteByAccount() {
    withVault { directory ->
      val first = MacCampusCredentialVault(directory)
      first.save(CampusCredential("alice", "alice-secret"))

      val restarted = MacCampusCredentialVault(directory)
      assertEquals(CampusCredential("alice", "alice-secret"), restarted.load("alice"))
      restarted.save(CampusCredential("alice", "updated-secret"))
      assertEquals(
          CampusCredential("alice", "updated-secret"),
          MacCampusCredentialVault(directory).load("alice"),
      )

      restarted.delete("alice")
      assertNull(restarted.load("alice"))
    }
  }

  @Test
  fun accountsHaveSeparateRecordsAndCiphertextDoesNotContainPassword() {
    withVault { directory ->
      val vault = MacCampusCredentialVault(directory)
      vault.save(CampusCredential("alice", "distinctive-secret-alice"))
      vault.save(CampusCredential("bob", "distinctive-secret-bob"))

      assertEquals("distinctive-secret-alice", vault.load("alice")?.password)
      assertEquals("distinctive-secret-bob", vault.load("bob")?.password)
      Files.list(directory).use { records ->
        records
            .filter { it.fileName.toString() != "key-v1" }
            .forEach { record ->
              val bytes = Files.readAllBytes(record)
              assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("distinctive-secret-alice"))
              assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("distinctive-secret-bob"))
            }
      }
    }
  }

  @Test
  fun tamperingAndWrongAccountAssociatedDataFailClosed() {
    withVault { directory ->
      val vault = MacCampusCredentialVault(directory)
      vault.save(CampusCredential("alice", "private-value"))
      val aliceRecord = recordPath(directory, "alice")
      val tampered =
          Files.readAllBytes(aliceRecord).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
          }
      Files.write(aliceRecord, tampered)
      assertFails { vault.load("alice") }

      vault.save(CampusCredential("alice", "private-value"))
      Files.copy(aliceRecord, recordPath(directory, "bob"))
      assertFails { vault.load("bob") }
    }
  }

  @Test
  fun vaultFilesAndDirectoryHavePrivatePosixPermissions() {
    withVault { directory ->
      MacCampusCredentialVault(directory).save(CampusCredential("alice", "private-value"))
      assertEquals(
          setOf(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
          ),
          Files.getPosixFilePermissions(directory),
      )
      Files.list(directory).use { files ->
        files.forEach { path ->
          assertEquals(
              setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
              Files.getPosixFilePermissions(path),
          )
        }
      }
    }
  }

  @Test
  fun existingRecordsWithMissingKeyDoNotCreateReplacementKey() {
    withVault { directory ->
      val vault = MacCampusCredentialVault(directory)
      vault.save(CampusCredential("alice", "private-value"))
      Files.delete(directory.resolve("key-v1"))

      assertFails { vault.load("alice") }
      assertFails { vault.save(CampusCredential("bob", "other-value")) }
      assertFalse(Files.exists(directory.resolve("key-v1")))
    }
  }

  @Test
  fun simultaneousFirstSavesFromSeparateVaultInstancesShareOneKey() {
    withVault { directory ->
      val barrier = CyclicBarrier(2)
      val executor = Executors.newFixedThreadPool(2)
      try {
        val first =
            executor.submit {
              barrier.await()
              MacCampusCredentialVault(directory)
                  .save(CampusCredential("alice", "alice-parallel-secret"))
            }
        val second =
            executor.submit {
              barrier.await()
              MacCampusCredentialVault(directory)
                  .save(CampusCredential("bob", "bob-parallel-secret"))
            }
        first.get()
        second.get()
      } finally {
        executor.shutdownNow()
      }

      val restarted = MacCampusCredentialVault(directory)
      assertEquals("alice-parallel-secret", restarted.load("alice")?.password)
      assertEquals("bob-parallel-secret", restarted.load("bob")?.password)
    }
  }

  private fun withVault(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("bucc-campus-vault-test-")
    try {
      assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
      block(directory)
    } finally {
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }

  private fun recordPath(directory: Path, username: String): Path =
      directory.resolve(MacCampusCredentialVault.recordFileName(username))
}
