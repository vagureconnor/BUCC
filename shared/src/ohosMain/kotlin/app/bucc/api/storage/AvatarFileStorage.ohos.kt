@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.bucc.api.storage

import app.bucc.platform.HarmonyAppDirectories
import kotlin.random.Random
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.openat
import platform.posix.read
import platform.posix.renameat
import platform.posix.stat
import platform.posix.unlinkat
import platform.posix.write

internal actual object PlatformAvatarFiles : AvatarFileStorage {
  private var directoryOverride: String? = null
  private val directory: String
    get() = directoryOverride ?: "${HarmonyAppDirectories.filesDir}/bucc-avatars"

  private val fileName = "custom-avatar.bin"

  actual fun initialize(directory: String?) {
    directoryOverride = directory?.takeIf(String::isNotBlank)
  }

  override fun read(): ByteArray? {
    val root = openDirectory(create = false) ?: return null
    try {
      val descriptor = openat(root, fileName, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
      if (descriptor < 0) {
        if (errno == ENOENT) return null
        failIo()
      }
      try {
        return memScoped {
          val info = alloc<stat>()
          if (fstat(descriptor, info.ptr) != 0) failIo()
          if (
              (info.st_mode and S_IFMT.toUInt()) != S_IFREG.toUInt() ||
                  info.st_size !in 1..(MAX_AVATAR_BYTES + 8 * 1024).toLong()
          )
              return@memScoped null
          val bytes = ByteArray(info.st_size.toInt())
          var offset = 0
          bytes.usePinned { pinned ->
            while (offset < bytes.size) {
              val count =
                  read(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert())
              if (count == 0L) break
              if (count < 0L) {
                if (errno == EINTR) continue
                failIo()
              }
              offset += count.toInt()
            }
          }
          if (offset == bytes.size) bytes else bytes.copyOf(offset)
        }
      } finally {
        close(descriptor)
      }
    } finally {
      close(root)
    }
  }

  override fun write(bytes: ByteArray): Boolean =
      runCatching {
            val root = openDirectory(create = true) ?: failIo()
            try {
              val temporary = ".avatar-${getpid()}-${Random.nextLong().toULong().toString(16)}.tmp"
              val descriptor =
                  openat(root, temporary, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384u)
              if (descriptor < 0) failIo()
              try {
                if (fchmod(descriptor, 384u) != 0) failIo()
                bytes.usePinned { pinned ->
                  var offset = 0
                  while (offset < bytes.size) {
                    val count =
                        write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert())
                    if (count < 0L) {
                      if (errno == EINTR) continue
                      failIo()
                    }
                    if (count == 0L) failIo()
                    offset += count.toInt()
                  }
                }
                if (fsync(descriptor) != 0) failIo()
              } catch (error: Throwable) {
                unlinkat(root, temporary, 0)
                throw error
              } finally {
                close(descriptor)
              }
              if (renameat(root, temporary, root, fileName) != 0) {
                unlinkat(root, temporary, 0)
                failIo()
              }
              true
            } finally {
              close(root)
            }
          }
          .getOrDefault(false)

  override fun clear() {
    val root = openDirectory(create = false) ?: return
    try {
      if (unlinkat(root, fileName, 0) != 0 && errno != ENOENT) failIo()
    } finally {
      close(root)
    }
  }

  private fun openDirectory(create: Boolean): Int? {
    val path = directory
    if (create && mkdir(path, 448u) != 0 && errno != EEXIST) failIo()
    val descriptor = open(path, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
    if (descriptor >= 0) {
      if (fchmod(descriptor, 448u) != 0) {
        close(descriptor)
        failIo()
      }
      return descriptor
    }
    if (!create && errno == ENOENT) return null
    failIo()
  }

  private fun failIo(): Nothing = error("Unable to access local avatar storage")
}
