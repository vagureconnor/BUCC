package app.bucc.api.storage

import java.io.File

internal actual object PlatformAvatarFiles : AvatarFileStorage {
  private var directory: File? = null
  private val file: File?
    get() = directory?.resolve("custom-avatar.bin")

  actual fun initialize(directory: String?) {
    this.directory = directory?.takeIf(String::isNotBlank)?.let(::File)
  }

  override fun read(): ByteArray? =
      runCatching {
            val source = file?.takeIf { it.isFile } ?: return null
            if (source.length() !in 1..(MAX_AVATAR_BYTES + 8 * 1024).toLong()) return null
            source.readBytes()
          }
          .getOrNull()

  override fun write(bytes: ByteArray): Boolean =
      runCatching {
            val root = directory ?: return false
            if (!root.exists() && !root.mkdirs()) return false
            val temporary = File.createTempFile("avatar-", ".tmp", root)
            try {
              check(temporary.setReadable(false, false) && temporary.setReadable(true, true))
              temporary.writeBytes(bytes)
              val target = root.resolve("custom-avatar.bin")
              if (target.exists() && !target.delete()) return false
              check(temporary.renameTo(target))
              check(target.setReadable(false, false) && target.setReadable(true, true))
              true
            } finally {
              temporary.delete()
            }
          }
          .getOrDefault(false)

  override fun clear() {
    runCatching { file?.delete() }
  }
}
