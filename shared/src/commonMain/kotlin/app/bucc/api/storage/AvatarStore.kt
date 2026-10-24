package app.bucc.api.storage

const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
const val MAX_AVATAR_INPUT_BYTES = 20 * 1024 * 1024
private const val MAX_AVATAR_FILE_BYTES = MAX_AVATAR_BYTES + 8 * 1024
private val AVATAR_MAGIC = "BUCCAV1".encodeToByteArray()

fun isValidAvatarInput(bytes: ByteArray, mimeType: String): Boolean =
    bytes.isNotEmpty() && bytes.size <= MAX_AVATAR_INPUT_BYTES && mimeType.startsWith("image/")

data class StoredAvatar(val bytes: ByteArray, val mimeType: String)

interface AvatarFileStorage {
  fun read(): ByteArray?

  fun write(bytes: ByteArray): Boolean

  fun clear()
}

internal expect object PlatformAvatarFiles : AvatarFileStorage {
  fun initialize(directory: String?)
}

object AvatarStore {
  private var _fileStorage: AvatarFileStorage = PlatformAvatarFiles
  var fileStorage: AvatarFileStorage
    get() = _fileStorage
    set(value) {
      _fileStorage = value
    }

  fun initialize(directory: String) {
    PlatformAvatarFiles.initialize(directory)
    _fileStorage = PlatformAvatarFiles
  }

  fun save(accountId: String, bytes: ByteArray, mimeType: String): Boolean {
    if (
        accountId.isBlank() ||
            bytes.isEmpty() ||
            bytes.size > MAX_AVATAR_BYTES ||
            !mimeType.startsWith("image/")
    )
        return false
    val record = encodeRecord(accountId, mimeType, bytes) ?: return false
    return runCatching { fileStorage.write(record) }.getOrDefault(false)
  }

  fun get(accountId: String): StoredAvatar? {
    if (accountId.isBlank()) return null
    val record = fileStorage.read() ?: return null
    val decoded =
        decodeRecord(record)
            ?: run {
              fileStorage.clear()
              return null
            }
    if (decoded.accountId != accountId) {
      fileStorage.clear()
      return null
    }
    return StoredAvatar(decoded.bytes, decoded.mimeType)
  }

  fun clear(accountId: String) {
    if (accountId.isBlank()) return
    val decoded = fileStorage.read()?.let(::decodeRecord)
    if (decoded == null || decoded.accountId == accountId) fileStorage.clear()
  }

  private fun encodeRecord(accountId: String, mimeType: String, bytes: ByteArray): ByteArray? {
    val account = accountId.encodeToByteArray()
    val mime = mimeType.encodeToByteArray()
    if (account.size !in 1..4096 || mime.size !in 1..256) return null
    val result = ByteArray(AVATAR_MAGIC.size + 8 + account.size + mime.size + bytes.size)
    AVATAR_MAGIC.copyInto(result)
    writeLength(result, AVATAR_MAGIC.size, account.size)
    writeLength(result, AVATAR_MAGIC.size + 4, mime.size)
    var offset = AVATAR_MAGIC.size + 8
    account.copyInto(result, offset)
    offset += account.size
    mime.copyInto(result, offset)
    offset += mime.size
    bytes.copyInto(result, offset)
    return result.takeIf { it.size <= MAX_AVATAR_FILE_BYTES }
  }

  private fun decodeRecord(bytes: ByteArray): AvatarRecord? {
    if (bytes.size !in (AVATAR_MAGIC.size + 8 + 2)..MAX_AVATAR_FILE_BYTES) return null
    if (!AVATAR_MAGIC.indices.all { bytes[it] == AVATAR_MAGIC[it] }) return null
    val accountLength = readLength(bytes, AVATAR_MAGIC.size)
    val mimeLength = readLength(bytes, AVATAR_MAGIC.size + 4)
    val contentOffset = AVATAR_MAGIC.size + 8 + accountLength + mimeLength
    if (accountLength !in 1..4096 || mimeLength !in 1..256 || contentOffset >= bytes.size)
        return null
    val accountStart = AVATAR_MAGIC.size + 8
    val mimeStart = accountStart + accountLength
    val accountId =
        runCatching {
              bytes
                  .copyOfRange(accountStart, mimeStart)
                  .decodeToString(throwOnInvalidSequence = true)
            }
            .getOrNull() ?: return null
    val mimeType =
        runCatching {
              bytes
                  .copyOfRange(mimeStart, contentOffset)
                  .decodeToString(throwOnInvalidSequence = true)
            }
            .getOrNull() ?: return null
    if (!mimeType.startsWith("image/")) return null
    return AvatarRecord(accountId, mimeType, bytes.copyOfRange(contentOffset, bytes.size))
  }

  private fun writeLength(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
  }

  private fun readLength(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)

  private data class AvatarRecord(val accountId: String, val mimeType: String, val bytes: ByteArray)
}
