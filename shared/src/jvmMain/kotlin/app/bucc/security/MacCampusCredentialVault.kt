package app.bucc.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypted, per-user campus credentials stored in the app-private macOS data directory. */
internal class MacCampusCredentialVault(private val directory: Path) {
  fun save(credential: CampusCredential) =
      synchronized(IO_LOCK) {
        ensureDirectory()
        val key = readOrCreateKey()
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(credential.username.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(credential.password.toByteArray(StandardCharsets.UTF_8))
        val record =
            ByteBuffer.allocate(HEADER.size + nonce.size + encrypted.size)
                .put(HEADER)
                .put(nonce)
                .put(encrypted)
                .array()
        writeAtomically(recordPath(credential.username), record)
      }

  fun load(username: String): CampusCredential? =
      synchronized(IO_LOCK) {
        val recordPath = recordPath(username)
        if (!Files.exists(recordPath)) return@synchronized null
        ensureDirectory()
        val key = readExistingKey()
        makePrivate(recordPath)
        val record = Files.readAllBytes(recordPath)
        check(
            record.size >= HEADER.size + NONCE_BYTES + TAG_BYTES &&
                record.copyOfRange(0, HEADER.size).contentEquals(HEADER)
        ) {
          "校园凭据数据无效"
        }
        val nonceStart = HEADER.size
        val nonce = record.copyOfRange(nonceStart, nonceStart + NONCE_BYTES)
        val encrypted = record.copyOfRange(nonceStart + NONCE_BYTES, record.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(username.toByteArray(StandardCharsets.UTF_8))
        val password = cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
        CampusCredential(username, password)
      }

  fun delete(username: String) =
      synchronized(IO_LOCK) {
        val recordPath = recordPath(username)
        if (Files.exists(recordPath)) {
          ensureDirectory()
          Files.deleteIfExists(recordPath)
        }
      }

  private fun readOrCreateKey(): ByteArray {
    val keyPath = directory.resolve(KEY_FILE)
    if (Files.exists(keyPath)) return readAndValidateKey(keyPath)
    check(!hasOtherFiles()) { "校园凭据密钥缺失" }
    val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
    val temporary = Files.createTempFile(directory, ".bucc-key-", ".tmp", FILE_PERMISSIONS)
    return try {
      Files.write(temporary, key)
      try {
        // A hard link publishes the complete private file atomically and never replaces a winner.
        Files.createLink(keyPath, temporary)
        key
      } catch (_: java.nio.file.FileAlreadyExistsException) {
        readAndValidateKey(keyPath)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun readExistingKey(): ByteArray {
    val keyPath = directory.resolve(KEY_FILE)
    check(Files.exists(keyPath)) { "校园凭据密钥缺失" }
    return readAndValidateKey(keyPath)
  }

  private fun readAndValidateKey(keyPath: Path): ByteArray {
    makePrivate(keyPath)
    return Files.readAllBytes(keyPath).also { check(it.size == KEY_BYTES) { "校园凭据密钥无效" } }
  }

  private fun hasOtherFiles(): Boolean =
      Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString() != KEY_FILE } }

  private fun ensureDirectory() {
    Files.createDirectories(directory, DIRECTORY_PERMISSIONS)
    makePrivate(directory)
  }

  private fun writeAtomically(target: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(directory, ".bucc-", ".tmp", FILE_PERMISSIONS)
    try {
      Files.write(temporary, bytes)
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException("无法安全保存校园凭据", unsupported)
      }
      makePrivate(target)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun makePrivate(path: Path) {
    Files.setPosixFilePermissions(
        path,
        if (Files.isDirectory(path)) DIRECTORY_PERMISSION_SET else FILE_PERMISSION_SET,
    )
  }

  private fun recordPath(username: String): Path = directory.resolve(recordFileName(username))

  companion object {
    private const val KEY_FILE = "key-v1"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val HEADER =
        byteArrayOf('B'.code.toByte(), 'U'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(), 1)
    private val IO_LOCK = Any()
    private val DIRECTORY_PERMISSION_SET =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    private val FILE_PERMISSION_SET =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val DIRECTORY_PERMISSIONS =
        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSION_SET)
    private val FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(FILE_PERMISSION_SET)

    internal fun recordFileName(username: String): String {
      val hash =
          MessageDigest.getInstance("SHA-256").digest(username.toByteArray(StandardCharsets.UTF_8))
      return hash.joinToString(separator = "") { "%02x".format(it) } + ".bin"
    }
  }
}
