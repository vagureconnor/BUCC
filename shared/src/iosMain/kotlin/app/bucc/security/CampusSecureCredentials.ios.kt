@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.security

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual fun createPlatformCampusCredentialStorage(): CampusCredentialStorage {
  val supportDirectory =
      NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
          .firstOrNull() as? String ?: error("校园安全凭据存储不可用")
  return IosCampusCredentialStorage(IosCampusCredentialVault("$supportDirectory/BUCC/credentials"))
}

private class IosCampusCredentialStorage(private val vault: IosCampusCredentialVault) :
    CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential) = vault.save(credential)

  override suspend fun load(username: String): CampusCredential? = vault.load(username)

  override suspend fun delete(username: String) = vault.delete(username)
}

internal val iosCampusCredentialStorageLock = NSRecursiveLock()

internal inline fun <T> withIosCampusCredentialStorageLock(block: () -> T): T {
  iosCampusCredentialStorageLock.lock()
  try {
    return block()
  } finally {
    iosCampusCredentialStorageLock.unlock()
  }
}
