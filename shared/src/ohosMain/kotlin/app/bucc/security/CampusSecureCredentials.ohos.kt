@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.security

import cnames.structs.OH_CryptoDigest
import cnames.structs.OH_CryptoRand
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CryptoArchitectureKit.CryptoCommonApi.*
import platform.CryptoArchitectureKit.CryptoDigestApi.*
import platform.CryptoArchitectureKit.CryptoRandApi.*
import platform.UniversalKeystoreKit.HuksKeyApi.*
import platform.UniversalKeystoreKit.HuksParamSetApi.*
import platform.UniversalKeystoreKit.HuksTypeApi.*
import platform.posix.memset

actual fun createPlatformCampusCredentialStorage(): CampusCredentialStorage =
    HuksCampusCredentialStorage()

private val credentialMutex = Mutex()

/** Settings contains only authenticated ciphertext. The AES key stays non-exportable in HUKS. */
internal class HuksCampusCredentialStorage(private val settings: Settings = Settings()) :
    CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential) =
      credentialMutex.withLock {
        withContext(Dispatchers.Default) {
          val alias = credentialAlias(credential.username)
          val plaintext = credential.password.encodeToByteArray()
          try {
            require(plaintext.size <= MAX_PASSWORD_BYTES) { "校园账号密码超出安全存储长度限制" }
            ensureKey(alias)
            val nonce = randomNonce()
            val encrypted = crypt(alias, plaintext, nonce, encrypt = true)
            check(encrypted.size == plaintext.size + TAG_SIZE) { "安全凭据加密结果无效" }
            settings.putString(
                storageKey(alias),
                Base64.encode(byteArrayOf(FORMAT_VERSION) + nonce + encrypted),
            )
          } finally {
            plaintext.fill(0)
          }
        }
      }

  override suspend fun load(username: String): CampusCredential? =
      credentialMutex.withLock {
        withContext(Dispatchers.Default) {
          val alias = credentialAlias(username)
          val encoded = settings.getStringOrNull(storageKey(alias)) ?: return@withContext null
          check(encoded.length <= MAX_ENCODED_BYTES) { "安全凭据不可读取，请重新登录" }
          val sealed =
              try {
                Base64.decode(encoded)
              } catch (_: IllegalArgumentException) {
                error("安全凭据不可读取，请重新登录")
              }
          check(
              sealed.size in
                  (1 + NONCE_SIZE + TAG_SIZE)..(1 + NONCE_SIZE + TAG_SIZE + MAX_PASSWORD_BYTES) &&
                  sealed[0] == FORMAT_VERSION
          ) {
            "安全凭据格式无效，请重新登录"
          }
          if (!keyExists(alias)) return@withContext null
          val nonce = sealed.copyOfRange(1, 1 + NONCE_SIZE)
          val ciphertext = sealed.copyOfRange(1 + NONCE_SIZE, sealed.size)
          val plaintext = crypt(alias, ciphertext, nonce, encrypt = false)
          try {
            CampusCredential(username, plaintext.decodeToString(throwOnInvalidSequence = true))
          } finally {
            plaintext.fill(0)
          }
        }
      }

  override suspend fun delete(username: String) =
      credentialMutex.withLock {
        withContext(Dispatchers.Default) {
          val alias = credentialAlias(username)
          alias.encodeToByteArray().withHuksBlob { blob ->
            val code = OH_Huks_DeleteKeyItem(blob, null).useContents { errorCode }
            check(code == 0 || code.toUInt() == OH_HUKS_ERR_CODE_ITEM_NOT_EXIST) { "安全凭据删除失败" }
          }
          settings.remove(storageKey(alias))
        }
      }

  private fun storageKey(alias: String) = "campus_credential_$alias"
}

private const val FORMAT_VERSION: Byte = 1
private const val NONCE_SIZE = 12
private const val TAG_SIZE = 16
private const val MAX_PASSWORD_BYTES = 8192
private const val MAX_ENCODED_BYTES = 12_000

private fun credentialAlias(username: String): String {
  val identity = ("BUCC:campus:v1\u0000" + username).encodeToByteArray()
  require(identity.size <= 4096) { "校园账号标识过长" }
  return memScoped {
    val context = alloc<CPointerVar<OH_CryptoDigest>>().apply { value = null }
    check(OH_CryptoDigest_Create("SHA256", context.ptr) == CRYPTO_SUCCESS) { "安全凭据摘要不可用" }
    val digest = checkNotNull(context.value)
    try {
      identity.usePinned { pin ->
        val input =
            alloc<Crypto_DataBlob>().apply {
              data = pin.addressOf(0).reinterpret()
              len = identity.size.toULong()
            }
        check(OH_CryptoDigest_Update(digest, input.ptr) == CRYPTO_SUCCESS) { "安全凭据摘要失败" }
      }
      val output =
          alloc<Crypto_DataBlob>().apply {
            data = null
            len = 0u
          }
      try {
        check(OH_CryptoDigest_Final(digest, output.ptr) == CRYPTO_SUCCESS && output.len == 32uL) {
          "安全凭据摘要失败"
        }
        checkNotNull(output.data).reinterpret<ByteVar>().readBytes(32).joinToString("") { byte ->
          (byte.toInt() and 255).toString(16).padStart(2, '0')
        }
      } finally {
        OH_Crypto_FreeDataBlob(output.ptr)
      }
    } finally {
      OH_DigestCrypto_Destroy(digest)
    }
  }
}

private fun randomNonce(): ByteArray = memScoped {
  val context = alloc<CPointerVar<OH_CryptoRand>>().apply { value = null }
  check(OH_CryptoRand_Create(context.ptr) == CRYPTO_SUCCESS) { "安全随机数不可用" }
  val random = checkNotNull(context.value)
  try {
    val output =
        alloc<Crypto_DataBlob>().apply {
          data = null
          len = 0u
        }
    try {
      check(
          OH_CryptoRand_GenerateRandom(random, NONCE_SIZE, output.ptr) == CRYPTO_SUCCESS &&
              output.len == NONCE_SIZE.toULong()
      ) {
        "安全随机数生成失败"
      }
      checkNotNull(output.data).reinterpret<ByteVar>().readBytes(NONCE_SIZE)
    } finally {
      OH_Crypto_FreeDataBlob(output.ptr)
    }
  } finally {
    OH_CryptoRand_Destroy(random)
  }
}

private fun keyExists(alias: String): Boolean =
    alias.encodeToByteArray().withHuksBlob { blob ->
      when (val code = OH_Huks_IsKeyItemExist(blob, null).useContents { errorCode }) {
        0 -> true
        OH_HUKS_ERR_CODE_ITEM_NOT_EXIST.toInt() -> false
        else -> error("系统安全凭据库不可用（$code），请重试登录")
      }
    }

private fun ensureKey(alias: String) {
  if (keyExists(alias)) return
  alias.encodeToByteArray().withHuksBlob { blob ->
    withHuksParams(OH_HUKS_KEY_PURPOSE_ENCRYPT or OH_HUKS_KEY_PURPOSE_DECRYPT) { params ->
      checkHuks(OH_Huks_GenerateKeyItem(blob, params, null))
    }
  }
}

private fun crypt(alias: String, input: ByteArray, nonce: ByteArray, encrypt: Boolean): ByteArray =
    alias.encodeToByteArray().withHuksBlob { aliasBlob ->
      val tag = if (encrypt) null else input.copyOfRange(input.size - TAG_SIZE, input.size)
      val content = if (encrypt) input else input.copyOfRange(0, input.size - TAG_SIZE)
      val purpose = if (encrypt) OH_HUKS_KEY_PURPOSE_ENCRYPT else OH_HUKS_KEY_PURPOSE_DECRYPT
      withHuksParams(purpose, nonce, ("BUCC:campus:v1:" + alias).encodeToByteArray(), tag) { params
        ->
        memScoped {
          val handleBytes = allocArray<UByteVar>(8)
          val handle =
              alloc<OH_Huks_Blob>().apply {
                data = handleBytes
                size = 8u
              }
          checkHuks(OH_Huks_InitSession(aliasBlob, params, handle.ptr, null))
          var finished = false
          val capacity = content.size + TAG_SIZE + 1
          val outputBytes = allocArray<UByteVar>(capacity)
          val output =
              alloc<OH_Huks_Blob>().apply {
                data = outputBytes
                size = capacity.toUInt()
              }
          try {
            val result =
                content.withHuksBlob { data ->
                  OH_Huks_FinishSession(handle.ptr, params, data, output.ptr)
                }
            checkHuks(result)
            finished = true
            val expected = if (encrypt) content.size + TAG_SIZE else content.size
            check(output.size == expected.toUInt()) { "安全凭据加解密结果无效" }
            outputBytes.reinterpret<ByteVar>().readBytes(expected)
          } finally {
            memset(outputBytes, 0, capacity.toULong())
            if (!finished) OH_Huks_AbortSession(handle.ptr, params)
          }
        }
      }
    }

private inline fun <T> withHuksParams(
    purpose: UInt,
    nonce: ByteArray? = null,
    aad: ByteArray? = null,
    tag: ByteArray? = null,
    block: (CPointer<OH_Huks_ParamSet>) -> T,
): T = memScoped {
  val scalars =
      listOf(
          OH_HUKS_TAG_ALGORITHM to OH_HUKS_ALG_AES,
          OH_HUKS_TAG_KEY_SIZE to OH_HUKS_AES_KEY_SIZE_256,
          OH_HUKS_TAG_PURPOSE to purpose,
          OH_HUKS_TAG_PADDING to OH_HUKS_PADDING_NONE,
          OH_HUKS_TAG_BLOCK_MODE to OH_HUKS_MODE_GCM,
      )
  val blobs =
      listOfNotNull(
          nonce?.let { OH_HUKS_TAG_NONCE to it },
          aad?.let { OH_HUKS_TAG_ASSOCIATED_DATA to it },
          tag?.let { OH_HUKS_TAG_AE_TAG to it },
      )
  val count = scalars.size + blobs.size
  val params = allocArray<OH_Huks_Param>(count)
  memset(params, 0, (count * sizeOf<OH_Huks_Param>()).toULong())
  scalars.forEachIndexed { index, (key, value) ->
    params[index].tag = key
    params[index].uint32Param = value
  }
  blobs.forEachIndexed { index, (key, value) ->
    params[scalars.size + index].apply {
      this.tag = key
      blob.size = value.size.toUInt()
      blob.data =
          allocArray<UByteVar>(value.size.coerceAtLeast(1)).also { target ->
            value.forEachIndexed { offset, byte -> target[offset] = byte.toUByte() }
          }
    }
  }
  val holder = alloc<CPointerVar<OH_Huks_ParamSet>>().apply { value = null }
  checkHuks(OH_Huks_InitParamSet(holder.ptr))
  try {
    checkHuks(OH_Huks_AddParams(holder.value, params, count.toUInt()))
    checkHuks(OH_Huks_BuildParamSet(holder.ptr))
    block(checkNotNull(holder.value))
  } finally {
    OH_Huks_FreeParamSet(holder.ptr)
  }
}

private inline fun <T> ByteArray.withHuksBlob(block: (CPointer<OH_Huks_Blob>) -> T): T = memScoped {
  if (isEmpty()) {
    val value =
        alloc<OH_Huks_Blob>().apply {
          size = 0u
          data = alloc<UByteVar>().ptr
        }
    block(value.ptr)
  } else
      this@withHuksBlob.usePinned { pin ->
        val value =
            alloc<OH_Huks_Blob>().apply {
              size = this@withHuksBlob.size.toUInt()
              data = pin.addressOf(0).reinterpret()
            }
        block(value.ptr)
      }
}

private fun checkHuks(result: CValue<OH_Huks_Result>) {
  val code = result.useContents { errorCode }
  check(code == 0) { "系统安全凭据操作失败（$code），请重试登录" }
}
