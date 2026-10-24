@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.api.storage

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

internal actual object PlatformAvatarFiles : AvatarFileStorage {
  private var directoryOverride: String? = null
  private val directory: String
    get() =
        directoryOverride
            ?: "${NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String}/BUCC/avatars"

  private val file: String
    get() = "$directory/custom-avatar.bin"

  private val manager
    get() = NSFileManager.defaultManager

  actual fun initialize(directory: String?) {
    directoryOverride = directory?.takeIf(String::isNotBlank)
  }

  override fun read(): ByteArray? {
    val data = NSData.dataWithContentsOfFile(file) ?: return null
    if (data.length.toLong() !in 1..(MAX_AVATAR_BYTES + 8 * 1024).toLong()) return null
    return ByteArray(data.length.toInt()).also { bytes ->
      if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    }
  }

  override fun write(bytes: ByteArray): Boolean =
      runCatching {
            manager.createDirectoryAtPath(
                directory,
                true,
                mapOf(NSFilePosixPermissions to 448),
                null,
            )
            val data =
                bytes.usePinned {
                  NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
                }
            check(data.writeToFile(file, true))
            manager.setAttributes(mapOf(NSFilePosixPermissions to 384), file, null)
            true
          }
          .getOrDefault(false)

  override fun clear() {
    manager.removeItemAtPath(file, null)
  }
}
