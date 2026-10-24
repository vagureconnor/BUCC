@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package app.bucc.diagnostics

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

private const val MAX_DIAGNOSTIC_FILE_BYTES = 2 * 1024 * 1024
private const val DIAGNOSTIC_DIRECTORY = "bucc-diagnostics"
private const val DISABLED_MARKER = "disabled"

internal actual object PlatformDiagnosticFiles : DiagnosticFiles {
  private val directoryPath: String
    get() = "${HarmonyAppDirectories.cacheDir}/$DIAGNOSTIC_DIRECTORY"

  private fun fileName(slot: Int): String {
    require(slot in 0..2) { "Invalid diagnostics slot" }
    return "events-$slot.jsonl"
  }

  override var enabled: Boolean
    get() {
      val directory = openDirectory(create = false) ?: return true
      try {
        val marker = openat(directory, DISABLED_MARKER, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
        if (marker >= 0) {
          close(marker)
          return false
        }
        if (errno == ENOENT) return true
        failIo()
      } finally {
        close(directory)
      }
    }
    set(value) {
      val directory = openDirectory(create = true) ?: failIo()
      try {
        if (value) {
          if (unlinkat(directory, DISABLED_MARKER, 0) != 0 && errno != ENOENT) failIo()
        } else {
          val marker =
              openat(directory, DISABLED_MARKER, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384u)
          if (marker >= 0) close(marker) else if (errno != EEXIST) failIo()
        }
      } finally {
        close(directory)
      }
    }

  override fun read(slot: Int): String? {
    val name = fileName(slot)
    val directory = openDirectory(create = false) ?: return null
    try {
      val descriptor = openat(directory, name, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
      if (descriptor < 0) {
        if (errno == ENOENT) return null
        failIo()
      }
      try {
        memScoped {
          val info = alloc<stat>()
          if (fstat(descriptor, info.ptr) != 0) failIo()
          if (
              (info.st_mode and S_IFMT.toUInt()) != S_IFREG.toUInt() ||
                  info.st_size < 0 ||
                  info.st_size > MAX_DIAGNOSTIC_FILE_BYTES.toLong()
          )
              failIo()
          val expectedSize = info.st_size.toInt()
          val bytes = ByteArray(expectedSize)
          var offset = 0
          bytes.usePinned { pinned ->
            while (offset < expectedSize) {
              val count =
                  read(descriptor, pinned.addressOf(offset), (expectedSize - offset).convert())
              if (count == 0L) break
              if (count < 0L) {
                if (errno == EINTR) continue
                failIo()
              }
              offset += count.toInt()
            }
          }
          return if (offset == bytes.size) bytes.decodeToString()
          else bytes.copyOf(offset).decodeToString()
        }
      } finally {
        close(descriptor)
      }
    } finally {
      close(directory)
    }
  }

  override fun write(slot: Int, value: String) {
    val name = fileName(slot)
    val bytes = value.encodeToByteArray()
    check(bytes.size <= MAX_DIAGNOSTIC_FILE_BYTES) { "Diagnostic file exceeds the supported size" }
    val directory = openDirectory(create = true) ?: failIo()
    try {
      val temporary = "${name}.${getpid()}.${Random.nextLong().toULong().toString(16)}.tmp"
      val descriptor =
          openat(directory, temporary, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384u)
      if (descriptor < 0) failIo()
      try {
        if (fchmod(descriptor, 384u) != 0) failIo()
        bytes.usePinned { pinned ->
          var offset = 0
          while (offset < bytes.size) {
            val count = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert())
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
        unlinkat(directory, temporary, 0)
        throw error
      } finally {
        close(descriptor)
      }
      if (renameat(directory, temporary, directory, name) != 0) {
        unlinkat(directory, temporary, 0)
        failIo()
      }
    } finally {
      close(directory)
    }
  }

  override fun delete(slot: Int) {
    val directory = openDirectory(create = false) ?: return
    try {
      if (unlinkat(directory, fileName(slot), 0) != 0 && errno != ENOENT) failIo()
    } finally {
      close(directory)
    }
  }

  override fun modifiedAt(slot: Int): Long? {
    val directory = openDirectory(create = false) ?: return null
    try {
      val descriptor = openat(directory, fileName(slot), O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
      if (descriptor < 0) {
        if (errno == ENOENT) return null
        failIo()
      }
      try {
        memScoped {
          val info = alloc<stat>()
          if (fstat(descriptor, info.ptr) != 0) failIo()
          if ((info.st_mode and S_IFMT.toUInt()) != S_IFREG.toUInt()) failIo()
          return info.st_mtim.tv_sec * 1000L + info.st_mtim.tv_nsec / 1_000_000L
        }
      } finally {
        close(descriptor)
      }
    } finally {
      close(directory)
    }
  }

  private fun openDirectory(create: Boolean): Int? {
    val path = directoryPath
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

  private fun failIo(): Nothing = error("Unable to access local diagnostics storage")
}

internal actual fun diagnosticStackFrames(error: Throwable): List<String> =
    error.getStackTrace().toList()
