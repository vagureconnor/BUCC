package app.bucc.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class PlatformRsaPublicKeyDerTest {
  @Test
  fun extractsPkcs1KeyFromRsaSubjectPublicKeyInfo() {
    val pkcs1 = syntheticPkcs1Key()

    assertContentEquals(pkcs1, PlatformRsaPublicKeyDer.extractPkcs1(subjectPublicKeyInfo(pkcs1)))
  }

  @Test
  fun acceptsRsaAlgorithmWithoutOptionalNullParameters() {
    val pkcs1 = syntheticPkcs1Key()
    val algorithm = sequence(tlv(OBJECT_IDENTIFIER, RSA_ENCRYPTION_OID))
    val bitString = tlv(BIT_STRING, byteArrayOf(0) + pkcs1)

    assertContentEquals(
        pkcs1,
        PlatformRsaPublicKeyDer.extractPkcs1(sequence(algorithm + bitString)),
    )
  }

  @Test
  fun rejectsNonRsaAlgorithmInsteadOfReturningAlgorithmBytesAsKey() {
    val pkcs1 = syntheticPkcs1Key()
    val wrongOid = RSA_ENCRYPTION_OID.copyOf().also { it[it.lastIndex] = 0x02 }
    val spki =
        sequence(
            sequence(tlv(OBJECT_IDENTIFIER, wrongOid) + tlv(NULL, byteArrayOf())) +
                tlv(BIT_STRING, byteArrayOf(0) + pkcs1),
        )

    assertFailsWith<IllegalArgumentException> { PlatformRsaPublicKeyDer.extractPkcs1(spki) }
  }

  @Test
  fun rejectsNonzeroUnusedBitsInSubjectPublicKeyBitString() {
    val pkcs1 = syntheticPkcs1Key()
    val algorithm = sequence(tlv(OBJECT_IDENTIFIER, RSA_ENCRYPTION_OID) + tlv(NULL, byteArrayOf()))
    val spki = sequence(algorithm + tlv(BIT_STRING, byteArrayOf(1) + pkcs1))

    assertFailsWith<IllegalArgumentException> { PlatformRsaPublicKeyDer.extractPkcs1(spki) }
  }

  @Test
  fun rejectsTruncatedAndTrailingDerData() {
    val spki = subjectPublicKeyInfo(syntheticPkcs1Key())

    assertFailsWith<IllegalArgumentException> {
      PlatformRsaPublicKeyDer.extractPkcs1(spki.copyOf(spki.lastIndex))
    }
    assertFailsWith<IllegalArgumentException> {
      PlatformRsaPublicKeyDer.extractPkcs1(spki + byteArrayOf(0))
    }
  }

  private fun subjectPublicKeyInfo(pkcs1: ByteArray): ByteArray {
    val algorithm = sequence(tlv(OBJECT_IDENTIFIER, RSA_ENCRYPTION_OID) + tlv(NULL, byteArrayOf()))
    val bitString = tlv(BIT_STRING, byteArrayOf(0) + pkcs1)
    return sequence(algorithm + bitString)
  }

  private fun syntheticPkcs1Key(): ByteArray {
    val modulus =
        ByteArray(129).also {
          it[1] = 0x80.toByte()
          for (index in 2 until it.size) it[index] = (index and 0xff).toByte()
        }
    val exponent = byteArrayOf(0x01, 0x00, 0x01)
    return sequence(tlv(INTEGER, modulus) + tlv(INTEGER, exponent))
  }

  private fun sequence(content: ByteArray) = tlv(SEQUENCE, content)

  private fun tlv(tag: Int, content: ByteArray): ByteArray =
      byteArrayOf(tag.toByte()) + encodeLength(content.size) + content

  private fun encodeLength(length: Int): ByteArray {
    if (length < 0x80) return byteArrayOf(length.toByte())
    var remaining = length
    val octets = mutableListOf<Byte>()
    while (remaining > 0) {
      octets.add(0, (remaining and 0xff).toByte())
      remaining = remaining ushr 8
    }
    return byteArrayOf((0x80 or octets.size).toByte()) + octets.toByteArray()
  }

  private companion object {
    const val SEQUENCE = 0x30
    const val OBJECT_IDENTIFIER = 0x06
    const val NULL = 0x05
    const val BIT_STRING = 0x03
    const val INTEGER = 0x02
    val RSA_ENCRYPTION_OID =
        byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01)
  }
}
