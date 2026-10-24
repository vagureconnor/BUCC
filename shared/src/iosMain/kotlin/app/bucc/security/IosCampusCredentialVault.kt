@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.security

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

/** iOS app-private encrypted credential files. This deliberately does not use SecItem/Keychain. */
internal class IosCampusCredentialVault(private val directory: String) {
  fun save(credential: CampusCredential) = withIosCampusCredentialStorageLock {
    saveLocked(credential)
  }

  private fun saveLocked(credential: CampusCredential) {
    val username = credential.username.encodeToByteArray()
    val password = credential.password.encodeToByteArray()
    require(username.size <= MAX_USERNAME_BYTES && password.size <= MAX_PASSWORD_BYTES) {
      "校园凭据超出安全存储长度限制"
    }
    prepareDirectory()
    val key = readOrCreateKey()
    try {
      val iv = randomBytes(IV_BYTES)
      val encryptionKey = key.copyOfRange(0, AES_KEY_BYTES)
      val authenticationKey = key.copyOfRange(AES_KEY_BYTES, KEY_BYTES)
      try {
        val ciphertext = crypt(kCCEncrypt, password, encryptionKey, iv)
        val authenticated =
            RECORD_MAGIC +
                byteArrayOf(RECORD_VERSION) +
                uint32(username.size) +
                username +
                iv +
                uint32(ciphertext.size) +
                ciphertext
        val tag = hmac(authenticationKey, authenticated)
        writeAtomically(recordPath(credential.username), authenticated + tag)
      } finally {
        encryptionKey.fill(0)
        authenticationKey.fill(0)
      }
    } finally {
      key.fill(0)
      password.fill(0)
    }
  }

  fun load(username: String): CampusCredential? = withIosCampusCredentialStorageLock {
    loadLocked(username)
  }

  private fun loadLocked(username: String): CampusCredential? {
    val path = recordPath(username)
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) return null
    val record = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: error("校园凭据数据无效")
    val parsed = parseRecord(record)
    val key = readExistingKey()
    try {
      val encryptionKey = key.copyOfRange(0, AES_KEY_BYTES)
      val authenticationKey = key.copyOfRange(AES_KEY_BYTES, KEY_BYTES)
      try {
        val expectedTag = hmac(authenticationKey, record.copyOfRange(0, parsed.tagOffset))
        val storedTag = record.copyOfRange(parsed.tagOffset, record.size)
        check(constantTimeEquals(expectedTag, storedTag)) { "校园凭据校验失败，请重新登录" }
        check(parsed.usernameBytes.contentEquals(username.encodeToByteArray())) { "校园凭据账号不匹配" }
        val plaintext = crypt(kCCDecrypt, parsed.ciphertext, encryptionKey, parsed.iv)
        return try {
          CampusCredential(username, plaintext.decodeToString(throwOnInvalidSequence = true))
        } finally {
          plaintext.fill(0)
        }
      } finally {
        encryptionKey.fill(0)
        authenticationKey.fill(0)
      }
    } finally {
      key.fill(0)
    }
  }

  fun delete(username: String) = withIosCampusCredentialStorageLock { deleteLocked(username) }

  private fun deleteLocked(username: String) {
    val path = recordPath(username)
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath(path)) {
      check(manager.removeItemAtPath(path, null)) { "无法删除校园凭据" }
    }
  }

  private fun readOrCreateKey(): ByteArray {
    val path = "$directory/$KEY_FILE"
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath(path)) return readAndValidateKey(path)
    check(!hasOtherFiles()) { "校园凭据密钥缺失，请重新登录" }
    val key = randomBytes(KEY_BYTES)
    try {
      writeAtomically(path, key)
      return key
    } catch (error: Throwable) {
      key.fill(0)
      throw error
    }
  }

  private fun readExistingKey(): ByteArray {
    val path = "$directory/$KEY_FILE"
    check(NSFileManager.defaultManager.fileExistsAtPath(path)) { "校园凭据密钥缺失，请重新登录" }
    return readAndValidateKey(path)
  }

  private fun readAndValidateKey(path: String): ByteArray {
    val key = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: error("校园凭据密钥无效，请重新登录")
    check(key.size == KEY_BYTES) {
      key.fill(0)
      "校园凭据密钥无效，请重新登录"
    }
    return key
  }

  private fun hasOtherFiles(): Boolean =
      NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)?.any {
        (it as? String) != KEY_FILE
      } == true

  private fun prepareDirectory() {
    val manager = NSFileManager.defaultManager
    check(
        manager.createDirectoryAtPath(
            directory,
            true,
            mapOf(NSFileProtectionKey to NSFileProtectionComplete),
            null,
        ) || manager.fileExistsAtPath(directory)
    ) {
      "校园安全凭据存储不可用"
    }
    check(
        manager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionComplete),
            ofItemAtPath = directory,
            error = null,
        )
    ) {
      "校园安全凭据存储不可用"
    }
    check(
        NSURL.fileURLWithPath(directory)
            .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    ) {
      "校园安全凭据存储不可用"
    }
  }

  private fun writeAtomically(path: String, bytes: ByteArray) {
    val data = bytes.toNSData()
    check(data.writeToFile(path, true)) { "无法安全保存校园凭据" }
    check(
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionComplete),
            ofItemAtPath = path,
            error = null,
        )
    ) {
      "无法安全保存校园凭据"
    }
  }

  private fun recordPath(username: String): String = "$directory/${recordFileName(username)}"

  private fun parseRecord(record: ByteArray): ParsedRecord {
    check(record.size >= MIN_RECORD_BYTES) { "校园凭据数据无效" }
    check(record.copyOfRange(0, RECORD_MAGIC.size).contentEquals(RECORD_MAGIC)) { "校园凭据数据无效" }
    check(record[RECORD_MAGIC.size] == RECORD_VERSION) { "校园凭据版本不受支持" }
    var offset = RECORD_MAGIC.size + 1
    val usernameLength = readUInt32(record, offset)
    offset += UINT32_BYTES
    check(
        usernameLength in 0..MAX_USERNAME_BYTES &&
            offset + usernameLength + IV_BYTES + UINT32_BYTES <= record.size
    ) {
      "校园凭据数据无效"
    }
    val usernameBytes = record.copyOfRange(offset, offset + usernameLength)
    offset += usernameLength
    val iv = record.copyOfRange(offset, offset + IV_BYTES)
    offset += IV_BYTES
    val ciphertextLength = readUInt32(record, offset)
    offset += UINT32_BYTES
    check(
        ciphertextLength > 0 &&
            ciphertextLength <= MAX_CIPHERTEXT_BYTES &&
            ciphertextLength % AES_BLOCK_BYTES == 0 &&
            offset + ciphertextLength + HMAC_BYTES == record.size
    ) {
      "校园凭据数据无效"
    }
    val tagOffset = offset + ciphertextLength
    return ParsedRecord(usernameBytes, iv, record.copyOfRange(offset, tagOffset), tagOffset)
  }

  private data class ParsedRecord(
      val usernameBytes: ByteArray,
      val iv: ByteArray,
      val ciphertext: ByteArray,
      val tagOffset: Int,
  )

  companion object {
    private const val KEY_FILE = "key-v1"
    private const val KEY_BYTES = 64
    private const val AES_KEY_BYTES = 32
    private const val IV_BYTES = 16
    private const val HMAC_BYTES = 32
    private const val AES_BLOCK_BYTES = 16
    private const val UINT32_BYTES = 4
    private const val MAX_USERNAME_BYTES = 64 * 1024
    private const val MAX_PASSWORD_BYTES = 1024 * 1024
    private const val MAX_CIPHERTEXT_BYTES = MAX_PASSWORD_BYTES + AES_BLOCK_BYTES
    private const val RECORD_VERSION: Byte = 1
    private val RECORD_MAGIC = byteArrayOf(0x42, 0x55, 0x43, 0x43)
    private val MIN_RECORD_BYTES =
        RECORD_MAGIC.size +
            1 +
            UINT32_BYTES +
            IV_BYTES +
            UINT32_BYTES +
            AES_BLOCK_BYTES +
            HMAC_BYTES

    internal fun recordFileName(username: String): String =
        sha256(username.encodeToByteArray()).joinToString("") {
          (it.toInt() and 0xff).toString(16).padStart(2, '0')
        } + ".bin"

    private fun randomBytes(size: Int): ByteArray {
      val bytes = ByteArray(size)
      val status =
          bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
      check(status == 0) { "安全随机数生成失败" }
      return bytes
    }

    private fun sha256(input: ByteArray): ByteArray {
      val output = ByteArray(32)
      output.usePinned { target ->
        if (input.isEmpty()) {
          memScoped {
            val empty = alloc<ByteVar>()
            CC_SHA256(empty.ptr, 0u, target.addressOf(0).reinterpret())
          }
        } else {
          input.usePinned { source ->
            CC_SHA256(source.addressOf(0), input.size.convert(), target.addressOf(0).reinterpret())
          }
        }
      }
      return output
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray {
      val output = ByteArray(HMAC_BYTES)
      key.usePinned { keyPinned ->
        input.usePinned { inputPinned ->
          output.usePinned { outputPinned ->
            CCHmac(
                kCCHmacAlgSHA256,
                keyPinned.addressOf(0),
                key.size.convert(),
                inputPinned.addressOf(0),
                input.size.convert(),
                outputPinned.addressOf(0),
            )
          }
        }
      }
      return output
    }

    private fun crypt(operation: UInt, input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
      val output = ByteArray(input.size + AES_BLOCK_BYTES)
      return memScoped {
        val outputLength = alloc<ULongVar>()
        fun run(inputPointer: CPointer<ByteVar>): Int =
            key.usePinned { keyPinned ->
              iv.usePinned { ivPinned ->
                output.usePinned { outputPinned ->
                  CCCrypt(
                      operation,
                      kCCAlgorithmAES,
                      kCCOptionPKCS7Padding,
                      keyPinned.addressOf(0),
                      key.size.convert(),
                      ivPinned.addressOf(0),
                      inputPointer,
                      input.size.convert(),
                      outputPinned.addressOf(0),
                      output.size.convert(),
                      outputLength.ptr,
                  )
                }
              }
            }
        val status =
            if (input.isEmpty()) {
              val empty = alloc<ByteVar>()
              run(empty.ptr)
            } else {
              input.usePinned { run(it.addressOf(0)) }
            }
        check(status == kCCSuccess) { "校园凭据加密或解密失败" }
        output.copyOf(outputLength.value.toInt())
      }
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
      NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray {
      val length = length.toInt()
      val source = bytes
      return ByteArray(length).also { result ->
        if (length > 0) result.usePinned { memcpy(it.addressOf(0), source, length.convert()) }
      }
    }

    private fun uint32(value: Int): ByteArray {
      require(value >= 0)
      return byteArrayOf(
          (value ushr 24).toByte(),
          (value ushr 16).toByte(),
          (value ushr 8).toByte(),
          value.toByte(),
      )
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int {
      check(offset >= 0 && offset + UINT32_BYTES <= bytes.size) { "校园凭据数据无效" }
      val result =
          ((bytes[offset].toInt() and 0xff) shl 24) or
              ((bytes[offset + 1].toInt() and 0xff) shl 16) or
              ((bytes[offset + 2].toInt() and 0xff) shl 8) or
              (bytes[offset + 3].toInt() and 0xff)
      check(result >= 0) { "校园凭据数据无效" }
      return result
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
      if (left.size != right.size) return false
      var mismatch = 0
      for (index in left.indices) mismatch =
          mismatch or (left[index].toInt() xor right[index].toInt())
      return mismatch == 0
    }
  }
}
