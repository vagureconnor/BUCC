package app.bucc.api.storage

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal actual object PlatformAvatarFiles : AvatarFileStorage {
  private var directoryOverride: Path? = null
  private val directory: Path
    get() = directoryOverride ?: defaultDirectory()

  private val file: Path
    get() = directory.resolve("custom-avatar.bin")

  actual fun initialize(directory: String?) {
    directoryOverride = directory?.let { Path.of(it) }
  }

  override fun read(): ByteArray? =
      runCatching {
            file
                .takeIf { Files.isRegularFile(it) }
                ?.let { path ->
                  val size = Files.size(path)
                  if (size !in 1..(MAX_AVATAR_BYTES + 8 * 1024).toLong()) null
                  else Files.readAllBytes(path)
                }
          }
          .getOrNull()

  override fun write(bytes: ByteArray): Boolean =
      runCatching {
            Files.createDirectories(directory)
            restrictDirectory(directory)
            val temporary = Files.createTempFile(directory, ".avatar-", ".tmp")
            try {
              restrictFile(temporary)
              Files.write(temporary, bytes)
              try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
              } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
              }
              restrictFile(file)
              true
            } finally {
              Files.deleteIfExists(temporary)
            }
          }
          .getOrDefault(false)

  override fun clear() {
    runCatching { Files.deleteIfExists(file) }
  }

  private fun restrictDirectory(path: Path) = runCatching {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
  }

  private fun restrictFile(path: Path) = runCatching {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
  }

  private fun defaultDirectory(): Path {
    val home = Path.of(System.getProperty("user.home"))
    return when {
      System.getProperty("os.name").startsWith("Mac", true) ->
          home.resolve("Library/Application Support/BUCC/avatars")
      System.getProperty("os.name").startsWith("Windows", true) ->
          (System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
                  ?: home.resolve("AppData/Local"))
              .resolve("BUCC/avatars")
      else ->
          (System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
                  ?: home.resolve(".local/share"))
              .resolve("BUCC/avatars")
    }
  }
}
