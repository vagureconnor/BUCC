package app.bucc.api

/** Extracts an RSA PKCS#1 RSAPublicKey from an X.509 SubjectPublicKeyInfo DER value. */
internal object PlatformRsaPublicKeyDer {
  private val rsaEncryptionOid =
      byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01)

  fun extractPkcs1(spki: ByteArray): ByteArray {
    val outer = readTlv(spki, 0, spki.size)
    require(outer.tag == SEQUENCE && outer.end == spki.size) {
      "Invalid SubjectPublicKeyInfo sequence"
    }

    val algorithm = readTlv(spki, outer.contentStart, outer.end)
    require(algorithm.tag == SEQUENCE) { "Missing SubjectPublicKeyInfo algorithm" }
    val oid = readTlv(spki, algorithm.contentStart, algorithm.end)
    require(oid.tag == OBJECT_IDENTIFIER && oid.contentEquals(spki, rsaEncryptionOid)) {
      "SubjectPublicKeyInfo is not RSA"
    }
    if (oid.end < algorithm.end) {
      val parameters = readTlv(spki, oid.end, algorithm.end)
      require(
          parameters.tag == NULL &&
              parameters.contentStart == parameters.end &&
              parameters.end == algorithm.end
      ) {
        "Unsupported RSA algorithm parameters"
      }
    }

    val bitString = readTlv(spki, algorithm.end, outer.end)
    require(
        bitString.tag == BIT_STRING &&
            bitString.end == outer.end &&
            bitString.contentStart < bitString.end
    ) {
      "Missing RSA public key bit string"
    }
    require(spki[bitString.contentStart].toInt() == 0) { "RSA public key has unused bits" }

    val keyStart = bitString.contentStart + 1
    val key = spki.copyOfRange(keyStart, bitString.end)
    validatePkcs1(key)
    return key
  }

  private fun validatePkcs1(key: ByteArray) {
    val sequence = readTlv(key, 0, key.size)
    require(sequence.tag == SEQUENCE && sequence.end == key.size) {
      "Invalid RSA public key sequence"
    }
    val modulus = readTlv(key, sequence.contentStart, sequence.end)
    val exponent = readTlv(key, modulus.end, sequence.end)
    require(modulus.tag == INTEGER && exponent.tag == INTEGER && exponent.end == sequence.end) {
      "Invalid RSA public key integers"
    }
    requirePositiveInteger(key, modulus)
    requirePositiveInteger(key, exponent)
  }

  private fun requirePositiveInteger(bytes: ByteArray, integer: Tlv) {
    val length = integer.end - integer.contentStart
    require(length > 0) { "Empty RSA public key integer" }
    val first = bytes[integer.contentStart].toInt() and 0xff
    require(first != 0 || length > 1) { "RSA public key integer must be positive" }
    require(first and 0x80 == 0) { "Negative RSA public key integer" }
    if (length > 1 && first == 0) {
      val second = bytes[integer.contentStart + 1].toInt() and 0xff
      require(second and 0x80 != 0) { "Non-canonical RSA public key integer" }
    }
    require((0 until length).any { bytes[integer.contentStart + it].toInt() != 0 }) {
      "Zero RSA public key integer"
    }
  }

  private fun readTlv(bytes: ByteArray, offset: Int, limit: Int): Tlv {
    require(offset >= 0 && limit <= bytes.size && offset < limit) { "Truncated DER value" }
    val tag = bytes[offset].toInt() and 0xff
    var cursor = offset + 1
    require(cursor < limit) { "Missing DER length" }
    val firstLength = bytes[cursor++].toInt() and 0xff
    val length: Int
    if (firstLength < 0x80) {
      length = firstLength
    } else {
      val lengthBytes = firstLength and 0x7f
      require(lengthBytes in 1..4 && cursor + lengthBytes <= limit) { "Invalid DER length" }
      require(bytes[cursor].toInt() != 0) { "Non-canonical DER length" }
      var parsed = 0L
      repeat(lengthBytes) { parsed = (parsed shl 8) or (bytes[cursor++].toInt() and 0xff).toLong() }
      require(parsed >= 0x80 && parsed <= Int.MAX_VALUE) { "Invalid DER length" }
      length = parsed.toInt()
    }
    val endLong = cursor.toLong() + length
    require(endLong <= limit && endLong <= Int.MAX_VALUE) { "DER value exceeds its container" }
    return Tlv(tag, cursor, endLong.toInt())
  }

  private fun Tlv.contentEquals(spi: ByteArray, expected: ByteArray): Boolean =
      end - contentStart == expected.size &&
          expected.indices.all { spi[contentStart + it] == expected[it] }

  private data class Tlv(val tag: Int, val contentStart: Int, val end: Int)

  private const val SEQUENCE = 0x30
  private const val OBJECT_IDENTIFIER = 0x06
  private const val NULL = 0x05
  private const val BIT_STRING = 0x03
  private const val INTEGER = 0x02
}
