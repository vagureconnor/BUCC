@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package app.bucc.diagnostics

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.memcpy

internal actual object PlatformDiagnosticFiles : DiagnosticFiles {
  private val manager
    get() = NSFileManager.defaultManager

  private val directory: String
    get() =
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first()
            as String) + "/BUCC/diagnostics"

  private fun file(slot: Int) = "$directory/events-$slot.jsonl"

  private fun prepare() {
    manager.createDirectoryAtPath(directory, true, mapOf(NSFilePosixPermissions to 448), null)
  }

  override var enabled: Boolean
    get() = !manager.fileExistsAtPath("$directory/disabled")
    set(value) {
      prepare()
      if (value) manager.removeItemAtPath("$directory/disabled", null)
      else manager.createFileAtPath("$directory/disabled", null, null)
    }

  override fun read(slot: Int): String? {
    val data = NSData.dataWithContentsOfFile(file(slot)) ?: return null
    return ByteArray(data.length.toInt())
        .also { bytes ->
          if (bytes.isNotEmpty())
              bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
        .decodeToString()
  }

  override fun write(slot: Int, value: String) {
    prepare()
    val bytes = value.encodeToByteArray()
    bytes.usePinned { pinned ->
      check(
          NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
              .writeToFile(file(slot), true)
      )
    }
    manager.setAttributes(mapOf(NSFilePosixPermissions to 384), file(slot), null)
  }

  override fun delete(slot: Int) {
    manager.removeItemAtPath(file(slot), null)
  }

  override fun modifiedAt(slot: Int): Long? =
      (manager.attributesOfItemAtPath(file(slot), null)?.get(NSFileModificationDate) as? NSDate)
          ?.let { (it.timeIntervalSince1970 * 1000).toLong() }
}

internal actual fun diagnosticStackFrames(error: Throwable): List<String> =
    error.getStackTrace().toList()
