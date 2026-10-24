@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import app.bucc.api.plantform.PlatformAesCbcNoPadding
import app.bucc.api.plantform.PlatformAesCfbNoPadding
import app.bucc.api.plantform.PlatformAesEcbPkcs5Padding
import app.bucc.api.plantform.PlatformMd5Hex

/** Synthetic NIST vectors only; callable on a disposable Harmony emulator. */
@kotlin.native.CName("bucc_crypto_probe")
fun buccCryptoProbe(): Int {
  var stage = 1
  return try {
    val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
    val iv = hex("000102030405060708090a0b0c0d0e0f")
    val plaintext = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
    val cbc = hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")
    val cfb = hex("3b3fd92eb72dad20333449f8e83cfb4ac8a64537a0b3a93fcde3cdad9f1ce58b")
    stage++
    check(PlatformAesCbcNoPadding.encrypt(plaintext, key, iv).contentEquals(cbc))
    stage++
    check(PlatformAesCbcNoPadding.decrypt(cbc, key, iv).contentEquals(plaintext))
    stage++
    check(PlatformAesCfbNoPadding.encrypt(plaintext, key, iv).contentEquals(cfb))
    stage++
    check(PlatformAesCfbNoPadding.decrypt(cfb, key, iv).contentEquals(plaintext))
    stage++
    check(
        PlatformAesCfbNoPadding.encrypt(plaintext.copyOf(31), key, iv).contentEquals(cfb.copyOf(31))
    )
    stage++
    check(
        PlatformAesCfbNoPadding.decrypt(cfb.copyOf(31), key, iv).contentEquals(plaintext.copyOf(31))
    )
    stage++
    check(PlatformAesCbcNoPadding.encrypt(byteArrayOf(), key, iv).isEmpty())
    stage++
    check(PlatformAesCfbNoPadding.encrypt(byteArrayOf(), key, iv).isEmpty())
    val ecbKey = hex("000102030405060708090a0b0c0d0e0f")
    val ecbPlain = hex("00112233445566778899aabbccddeeff")
    val padded = PlatformAesEcbPkcs5Padding.encrypt(ecbPlain, ecbKey)
    stage++
    check(padded.size == 32)
    stage++
    check(padded.copyOf(16).contentEquals(hex("69c4e0d86a7b0430d8cdb78070b4c55a")))
    stage++
    check(PlatformAesEcbPkcs5Padding.decrypt(padded, ecbKey).contentEquals(ecbPlain))
    listOf(0, 1, 15, 16, 17, 31).forEach { length ->
      val input = ByteArray(length) { it.toByte() }
      val encrypted = PlatformAesEcbPkcs5Padding.encrypt(input, ecbKey)
      stage++
      check(encrypted.size == (length / 16 + 1) * 16)
      stage++
      check(PlatformAesEcbPkcs5Padding.decrypt(encrypted, ecbKey).contentEquals(input))
    }
    stage++
    check(PlatformMd5Hex.digest(byteArrayOf()) == "d41d8cd98f00b204e9800998ecf8427e")
    stage++
    check(PlatformMd5Hex.digest("abc".encodeToByteArray()) == "900150983cd24fb0d6963f7d28e17f72")
    1
  } catch (_: Throwable) {
    -stage
  }
}

private fun hex(value: String): ByteArray =
    ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
