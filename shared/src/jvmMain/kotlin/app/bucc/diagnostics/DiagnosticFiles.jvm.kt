package app.bucc.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal actual object PlatformDiagnosticFiles : DiagnosticFiles {
  private val directory: Path
    get() = Path.of(System.getProperty("user.home"), "Library", "Logs", "BUCC", "diagnostics")

  private fun file(slot: Int) = directory.resolve("events-$slot.jsonl")

  private fun prepare() {
    Files.createDirectories(directory)
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
  }

  override var enabled: Boolean
    get() = !Files.exists(directory.resolve("disabled"))
    set(value) {
      prepare()
      if (value) Files.deleteIfExists(directory.resolve("disabled"))
      else Files.writeString(directory.resolve("disabled"), "")
    }

  override fun read(slot: Int): String? = file(slot).takeIf(Files::exists)?.let(Files::readString)

  override fun write(slot: Int, value: String) {
    prepare()
    val temp = Files.createTempFile(directory, ".events-", ".tmp")
    try {
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
      Files.writeString(temp, value)
      Files.move(
          temp,
          file(slot),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  override fun delete(slot: Int) {
    Files.deleteIfExists(file(slot))
  }

  override fun modifiedAt(slot: Int): Long? =
      file(slot).takeIf(Files::exists)?.let { Files.getLastModifiedTime(it).toMillis() }
}

internal actual fun diagnosticStackFrames(error: Throwable): List<String> =
    error.stackTrace.map { "${it.className}.${it.methodName}" }
