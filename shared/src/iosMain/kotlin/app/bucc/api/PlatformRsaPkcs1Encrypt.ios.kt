package app.bucc.api.plantform

import app.bucc.api.PlatformRsaPublicKeyDer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateWithData
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmRSAEncryptionPKCS1
import platform.posix.memcpy

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual object PlatformRsaPkcs1Encrypt {
  actual fun encrypt(input: ByteArray, publicKeyDer: ByteArray): ByteArray = memScoped {
    val pkcs1Der = PlatformRsaPublicKeyDer.extractPkcs1(publicKeyDer)
    val attributes = keyAttributes()
    try {
      val keyData = pkcs1Der.toCfData()
      try {
        val error = alloc<CFErrorRefVar>()
        error.value = null
        try {
          val publicKey = SecKeyCreateWithData(keyData, attributes, error.ptr)
          error.value?.let {
            CFRelease(it)
            error.value = null
          }
          val key = publicKey ?: error("RSA public key initialization failed")
          try {
            val plaintext = input.toCfData()
            try {
              val encrypted =
                  SecKeyCreateEncryptedData(
                      key,
                      kSecKeyAlgorithmRSAEncryptionPKCS1,
                      plaintext,
                      error.ptr,
                  )
              error.value?.let {
                CFRelease(it)
                error.value = null
              }
              val ciphertext = encrypted ?: error("RSA encryption failed")
              try {
                ciphertext.toByteArray()
              } finally {
                CFRelease(ciphertext)
              }
            } finally {
              CFRelease(plaintext)
            }
          } finally {
            CFRelease(key)
          }
        } finally {
          error.value?.let { CFRelease(it) }
        }
      } finally {
        CFRelease(keyData)
      }
    } finally {
      CFRelease(attributes)
    }
  }

  private fun keyAttributes(): CFDictionaryRef {
    val attributes =
        CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
            ?: error("RSA key attributes allocation failed")
    CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeRSA)
    CFDictionarySetValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    return attributes
  }

  private fun ByteArray.toCfData() = usePinned { pinned ->
    CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret<UByteVar>(), size.convert())
        ?: error("CFData allocation failed")
  }

  private fun platform.CoreFoundation.CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val source = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length).also { result ->
      result.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length.convert()) }
    }
  }
}
