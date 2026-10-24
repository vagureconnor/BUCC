package app.bucc.api.finance

import app.bucc.api.PlatformRsaPublicKeyDer
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZfwPasswordEncryptionTest {
  @Test
  fun `dynamic page public key produces decryptable PKCS1 ciphertext`() {
    val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    val pem =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.encode(keys.public.encoded) +
            "\n-----END PUBLIC KEY-----"
    val encrypted = encryptZfwPassword("mock-password", pem)
    val cipher =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, keys.private) }
    assertEquals("mock-password", cipher.doFinal(Base64.decode(encrypted)).decodeToString())
    assertTrue(PlatformRsaPublicKeyDer.extractPkcs1(keys.public.encoded).isNotEmpty())
  }
}
