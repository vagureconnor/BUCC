package app.bucc.security

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "app.bucc.campus.credentials"
private const val PREFS_NAME = "campus_secure_credentials"
private const val GCM_TAG_SIZE_BITS = 128

private object AndroidCredentialContext {
  @Volatile var applicationContext: Context? = null
}

fun initializeCampusCredentialStorage(context: Context) {
  AndroidCredentialContext.applicationContext = context.applicationContext
}

actual fun createPlatformCampusCredentialStorage(): CampusCredentialStorage =
    AndroidCampusCredentialStorage()

private class AndroidCampusCredentialStorage : CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val encrypted = cipher.doFinal(credential.password.toByteArray(Charsets.UTF_8))
    val stored =
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    preferences().edit().putString(preferenceKey(credential.username), stored).commit().also {
      check(it) { "无法保存校园凭据" }
    }
  }

  override suspend fun load(username: String): CampusCredential? {
    val stored = preferences().getString(preferenceKey(username), null) ?: return null
    val ivText = stored.substringBefore('.', missingDelimiterValue = "")
    val ciphertextText = stored.substringAfter('.', missingDelimiterValue = "")
    check(ivText.isNotEmpty() && ciphertextText.isNotEmpty()) { "校园凭据数据无效" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        GCMParameterSpec(GCM_TAG_SIZE_BITS, Base64.decode(ivText, Base64.NO_WRAP)),
    )
    val password =
        cipher.doFinal(Base64.decode(ciphertextText, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    return CampusCredential(username, password)
  }

  override suspend fun delete(username: String) {
    preferences().edit().remove(preferenceKey(username)).commit().also { check(it) { "无法删除校园凭据" } }
  }

  private fun preferences() =
      (AndroidCredentialContext.applicationContext ?: error("校园安全凭据存储尚未初始化")).getSharedPreferences(
          PREFS_NAME,
          Context.MODE_PRIVATE,
      )

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
    generator.init(
        android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build(),
    )
    return generator.generateKey()
  }

  private fun preferenceKey(username: String): String =
      "credential." +
          Base64.encodeToString(
              username.toByteArray(Charsets.UTF_8),
              Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
          )
}
