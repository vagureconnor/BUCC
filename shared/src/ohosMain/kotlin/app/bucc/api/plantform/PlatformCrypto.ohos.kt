package app.bucc.api.plantform

import app.bucc.api.PlatformRsaPublicKeyDer
import cnames.structs.*
import kotlinx.cinterop.*
import platform.CryptoArchitectureKit.CryptoAsymCipherApi.*
import platform.CryptoArchitectureKit.CryptoAsymKeyApi.*
import platform.CryptoArchitectureKit.CryptoCommonApi.*
import platform.CryptoArchitectureKit.CryptoDigestApi.*
import platform.CryptoArchitectureKit.CryptoSymCipherApi.*
import platform.CryptoArchitectureKit.CryptoSymKeyApi.*

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformAesCbcNoPadding {
  actual fun encrypt(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
      runAes(CipherOperation.ENCRYPT, "CBC", "NoPadding", input, key, iv)

  actual fun decrypt(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
      runAes(CipherOperation.DECRYPT, "CBC", "NoPadding", input, key, iv)
}

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformAesCfbNoPadding {
  actual fun encrypt(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
      runAes(CipherOperation.ENCRYPT, "CFB", "NoPadding", input, key, iv)

  actual fun decrypt(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
      runAes(CipherOperation.DECRYPT, "CFB", "NoPadding", input, key, iv)
}

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformAesEcbPkcs5Padding {
  actual fun encrypt(input: ByteArray, key: ByteArray): ByteArray =
      runAes(CipherOperation.ENCRYPT, "ECB", "PKCS7", input, key, null)

  actual fun decrypt(input: ByteArray, key: ByteArray): ByteArray =
      runAes(CipherOperation.DECRYPT, "ECB", "PKCS7", input, key, null)
}

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformRsaPkcs1Encrypt {
  actual fun encrypt(input: ByteArray, publicKeyDer: ByteArray): ByteArray =
      input.withBlob { inputBlob ->
        val pkcs1Der = PlatformRsaPublicKeyDer.extractPkcs1(publicKeyDer)
        val keyBits = rsaModulusBits(pkcs1Der)
        val generatorName = rsaGeneratorName(keyBits)
        publicKeyDer.withBlob { keyBlob ->
          memScoped {
            val generatorOut = alloc<CPointerVar<cnames.structs.OH_CryptoAsymKeyGenerator>>()
            generatorOut.value = null
            val generatorStatus = OH_CryptoAsymKeyGenerator_Create(generatorName, generatorOut.ptr)
            if (generatorStatus != CRYPTO_SUCCESS) {
              generatorOut.value?.let { OH_CryptoAsymKeyGenerator_Destroy(it) }
              checkCrypto(generatorStatus, "RSA key generator creation")
            }
            val generator =
                generatorOut.value ?: error("RSA key generator creation returned no context")
            try {
              val pairOut = alloc<CPointerVar<cnames.structs.OH_CryptoKeyPair>>()
              pairOut.value = null
              val pairStatus =
                  OH_CryptoAsymKeyGenerator_Convert(
                      generator,
                      CRYPTO_DER,
                      keyBlob,
                      null,
                      pairOut.ptr,
                  )
              if (pairStatus != CRYPTO_SUCCESS) {
                pairOut.value?.let { OH_CryptoKeyPair_Destroy(it) }
                checkCrypto(pairStatus, "RSA public key import")
              }
              val keyPair = pairOut.value ?: error("RSA public key import returned no key")
              try {
                val cipherOut = alloc<CPointerVar<cnames.structs.OH_CryptoAsymCipher>>()
                cipherOut.value = null
                val cipherStatus = OH_CryptoAsymCipher_Create("RSA|PKCS1", cipherOut.ptr)
                if (cipherStatus != CRYPTO_SUCCESS) {
                  cipherOut.value?.let { OH_CryptoAsymCipher_Destroy(it) }
                  checkCrypto(cipherStatus, "RSA cipher creation")
                }
                val cipher = cipherOut.value ?: error("RSA cipher creation returned no context")
                try {
                  checkCrypto(
                      OH_CryptoAsymCipher_Init(cipher, CRYPTO_ENCRYPT_MODE, keyPair),
                      "RSA initialization",
                  )
                  val output = alloc<Crypto_DataBlob>()
                  output.data = null
                  output.len = 0.convert()
                  try {
                    checkCrypto(
                        OH_CryptoAsymCipher_Final(cipher, inputBlob, output.ptr),
                        "RSA encryption",
                    )
                    output.ptr.toByteArray()
                  } finally {
                    OH_Crypto_FreeDataBlob(output.ptr)
                  }
                } finally {
                  OH_CryptoAsymCipher_Destroy(cipher)
                }
              } finally {
                OH_CryptoKeyPair_Destroy(keyPair)
              }
            } finally {
              OH_CryptoAsymKeyGenerator_Destroy(generator)
            }
          }
        }
      }
}

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformMd5Hex {
  actual fun digest(input: ByteArray): String =
      input.withBlob { inputBlob ->
        memScoped {
          val digestOut = alloc<CPointerVar<cnames.structs.OH_CryptoDigest>>()
          digestOut.value = null
          val digestStatus = OH_CryptoDigest_Create("MD5", digestOut.ptr)
          if (digestStatus != CRYPTO_SUCCESS) {
            digestOut.value?.let { OH_DigestCrypto_Destroy(it) }
            checkCrypto(digestStatus, "MD5 context creation")
          }
          val digest = digestOut.value ?: error("MD5 context creation returned no context")
          try {
            if (input.isNotEmpty())
                checkCrypto(OH_CryptoDigest_Update(digest, inputBlob), "MD5 update")
            val output = alloc<Crypto_DataBlob>()
            output.data = null
            output.len = 0.convert()
            try {
              checkCrypto(OH_CryptoDigest_Final(digest, output.ptr), "MD5 finalization")
              output.ptr.toByteArray().toLowerHex()
            } finally {
              OH_Crypto_FreeDataBlob(output.ptr)
            }
          } finally {
            OH_DigestCrypto_Destroy(digest)
          }
        }
      }
}

@OptIn(ExperimentalForeignApi::class)
private fun runAes(
    operation: CipherOperation,
    mode: String,
    padding: String,
    input: ByteArray,
    keyBytes: ByteArray,
    ivBytes: ByteArray?,
): ByteArray {
  val keyAlgorithm = aesKeyAlgorithm(keyBytes)
  return input.withBlob { inputBlob ->
    keyBytes.withBlob { keyBlob ->
      memScoped {
        val generatorOut = alloc<CPointerVar<cnames.structs.OH_CryptoSymKeyGenerator>>()
        generatorOut.value = null
        val generatorStatus = OH_CryptoSymKeyGenerator_Create(keyAlgorithm, generatorOut.ptr)
        if (generatorStatus != CRYPTO_SUCCESS) {
          generatorOut.value?.let { OH_CryptoSymKeyGenerator_Destroy(it) }
          checkCrypto(generatorStatus, "AES key generator creation")
        }
        val generator =
            generatorOut.value ?: error("AES key generator creation returned no context")
        try {
          val keyOut = alloc<CPointerVar<cnames.structs.OH_CryptoSymKey>>()
          keyOut.value = null
          val keyStatus = OH_CryptoSymKeyGenerator_Convert(generator, keyBlob, keyOut.ptr)
          if (keyStatus != CRYPTO_SUCCESS) {
            keyOut.value?.let { OH_CryptoSymKey_Destroy(it) }
            checkCrypto(keyStatus, "AES key import")
          }
          val key = keyOut.value ?: error("AES key import returned no key")
          try {
            val cipherOut = alloc<CPointerVar<cnames.structs.OH_CryptoSymCipher>>()
            cipherOut.value = null
            val cipherName = "$keyAlgorithm|$mode|$padding"
            val cipherStatus = OH_CryptoSymCipher_Create(cipherName, cipherOut.ptr)
            if (cipherStatus != CRYPTO_SUCCESS) {
              cipherOut.value?.let { OH_CryptoSymCipher_Destroy(it) }
              checkCrypto(cipherStatus, "AES cipher creation")
            }
            val cipher = cipherOut.value ?: error("AES cipher creation returned no context")
            try {
              if (ivBytes == null) {
                checkCrypto(
                    OH_CryptoSymCipher_Init(cipher, operation.nativeMode, key, null),
                    "AES cipher initialization",
                )
              } else {
                val paramsOut = alloc<CPointerVar<cnames.structs.OH_CryptoSymCipherParams>>()
                paramsOut.value = null
                val paramsStatus = OH_CryptoSymCipherParams_Create(paramsOut.ptr)
                if (paramsStatus != CRYPTO_SUCCESS) {
                  paramsOut.value?.let { OH_CryptoSymCipherParams_Destroy(it) }
                  checkCrypto(paramsStatus, "AES parameter creation")
                }
                val params =
                    paramsOut.value ?: error("AES parameter creation returned no parameters")
                try {
                  ivBytes.withBlob { ivBlob ->
                    checkCrypto(
                        OH_CryptoSymCipherParams_SetParam(
                            params,
                            CRYPTO_IV_DATABLOB,
                            ivBlob,
                        ),
                        "AES IV setup",
                    )
                    checkCrypto(
                        OH_CryptoSymCipher_Init(cipher, operation.nativeMode, key, params),
                        "AES cipher initialization",
                    )
                  }
                } finally {
                  OH_CryptoSymCipherParams_Destroy(params)
                }
              }
              val updated = alloc<Crypto_DataBlob>()
              updated.data = null
              updated.len = 0.convert()
              val updateBytes =
                  try {
                    checkCrypto(
                        OH_CryptoSymCipher_Update(cipher, inputBlob, updated.ptr),
                        "AES cipher update",
                    )
                    updated.ptr.toByteArray()
                  } finally {
                    OH_Crypto_FreeDataBlob(updated.ptr)
                  }
              val finalized = alloc<Crypto_DataBlob>()
              finalized.data = null
              finalized.len = 0.convert()
              val finalBytes =
                  try {
                    checkCrypto(
                        OH_CryptoSymCipher_Final(cipher, null, finalized.ptr),
                        "AES cipher finalization",
                    )
                    finalized.ptr.toByteArray()
                  } finally {
                    OH_Crypto_FreeDataBlob(finalized.ptr)
                  }
              updateBytes + finalBytes
            } finally {
              OH_CryptoSymCipher_Destroy(cipher)
            }
          } finally {
            OH_CryptoSymKey_Destroy(key)
          }
        } finally {
          OH_CryptoSymKeyGenerator_Destroy(generator)
        }
      }
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.withBlob(block: (CPointer<Crypto_DataBlob>) -> T): T {
  if (isEmpty()) {
    return memScoped {
      val empty = alloc<UByteVar>()
      val blob = alloc<Crypto_DataBlob>()
      blob.data = empty.ptr
      blob.len = 0.convert()
      block(blob.ptr)
    }
  }
  return usePinned { pinned ->
    memScoped {
      val blob = alloc<Crypto_DataBlob>()
      blob.data = pinned.addressOf(0).reinterpret<UByteVar>()
      blob.len = size.convert()
      block(blob.ptr)
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<Crypto_DataBlob>.toByteArray(): ByteArray {
  val blob = pointed
  val length = blob.len.convert<Int>()
  if (length == 0) return ByteArray(0)
  val data = blob.data ?: error("Crypto API returned no output data")
  return data.reinterpret<ByteVar>().readBytes(length)
}

private fun checkCrypto(status: OH_Crypto_ErrCode, operation: String) {
  check(status == CRYPTO_SUCCESS) { "$operation failed with code $status" }
}

private fun aesKeyAlgorithm(key: ByteArray): String {
  val bits = key.size * 8
  require(bits == 128 || bits == 192 || bits == 256) { "Invalid AES key size: ${key.size}" }
  return "AES$bits"
}

private fun rsaGeneratorName(bits: Int): String =
    when (bits) {
      512,
      768,
      1024,
      2048,
      3072,
      4096,
      8192 -> "RSA$bits"
      else -> error("Unsupported RSA public key size: $bits bits")
    }

private fun rsaModulusBits(pkcs1Der: ByteArray): Int {
  var index = 0
  require(pkcs1Der[index++].toInt() and 0xff == 0x30) { "Invalid RSA public key sequence" }
  index += readDerLength(pkcs1Der, index).second
  require(pkcs1Der[index++].toInt() and 0xff == 0x02) { "Missing RSA modulus" }
  val (modulusLength, lengthBytes) = readDerLength(pkcs1Der, index)
  index += lengthBytes
  require(modulusLength > 0 && index + modulusLength <= pkcs1Der.size) { "Invalid RSA modulus" }
  var skippedBytes = 0
  var first = pkcs1Der[index].toInt() and 0xff
  while (first == 0 && skippedBytes < modulusLength - 1) {
    skippedBytes++
    first = pkcs1Der[index + skippedBytes].toInt() and 0xff
  }
  require(first != 0) { "Invalid RSA modulus" }
  var leadingBits = 0
  var mask = 0x80
  while (first and mask == 0) {
    mask = mask ushr 1
    leadingBits++
  }
  return (modulusLength - skippedBytes) * 8 - leadingBits
}

private fun readDerLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
  require(offset < bytes.size) { "Missing DER length" }
  val first = bytes[offset].toInt() and 0xff
  if (first < 0x80) return first to 1
  val count = first and 0x7f
  require(count in 1..4 && offset + count < bytes.size) { "Invalid DER length" }
  var length = 0L
  repeat(count) { index ->
    length = (length shl 8) or (bytes[offset + 1 + index].toInt() and 0xff).toLong()
  }
  require(length in 0..Int.MAX_VALUE.toLong()) { "Invalid DER length" }
  return length.toInt() to count + 1
}

private fun ByteArray.toLowerHex(): String {
  val digits = "0123456789abcdef"
  return buildString(size * 2) {
    for (byte in this@toLowerHex) {
      val value = byte.toInt() and 0xff
      append(digits[value ushr 4])
      append(digits[value and 0x0f])
    }
  }
}

private enum class CipherOperation(val nativeMode: Crypto_CipherMode) {
  ENCRYPT(CRYPTO_ENCRYPT_MODE),
  DECRYPT(CRYPTO_DECRYPT_MODE),
}
