package app.bucc.api.storage

import com.russhwolf.settings.Settings

/**
 * Stores the optional account name. Passwords are never persisted here; BUCC keeps them in the
 * platform secure credential store. The legacy `remember_password` / `auto_login` keys are kept in
 * sync with UBAA's semantics so existing installs keep working after the migration.
 */
object CredentialStore {
  private const val KEY_USERNAME = "saved_username"
  private const val KEY_ACCOUNT_ID = "saved_credential_account_id"
  private const val KEY_PASSWORD = "saved_password"
  private const val KEY_REMEMBER_USERNAME = "remember_username"
  private const val KEY_REMEMBER_PASSWORD = "remember_password"
  private const val KEY_AUTO_LOGIN = "auto_login"

  private var _settings: Settings? = null
  var settings: Settings
    get() = _settings ?: Settings().also { _settings = it }
    set(value) {
      _settings = value
    }

  fun saveUsername(username: String, accountId: String = username) {
    settings.putString(KEY_ACCOUNT_ID, accountId)
    settings.putString(KEY_USERNAME, username)
    settings.remove(KEY_PASSWORD)
  }

  fun getAccountId(): String? = settings.getStringOrNull(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)

  fun getUsername(): String? =
      settings.getStringOrNull(KEY_USERNAME)?.takeIf(String::isNotBlank) ?: getAccountId()

  fun getPassword(): String? {
    settings.remove(KEY_PASSWORD)
    return null
  }

  fun setRememberUsername(enabled: Boolean) {
    settings.putBoolean(KEY_REMEMBER_USERNAME, enabled)
    // Mirror UBAA's key. BUCC has one "记住账号" switch, so remembering also enables startup login.
    settings.putBoolean(KEY_REMEMBER_PASSWORD, enabled)
    settings.putBoolean(KEY_AUTO_LOGIN, enabled)
    if (!enabled) {
      settings.remove(KEY_USERNAME)
      settings.remove(KEY_ACCOUNT_ID)
    }
    settings.remove(KEY_PASSWORD)
  }

  /** Accepts both BUCC's current key and the legacy UBAA key written by older builds. */
  fun isRememberUsername(): Boolean =
      settings.getBoolean(KEY_REMEMBER_USERNAME, false) ||
          settings.getBoolean(KEY_REMEMBER_PASSWORD, false)

  /** UBAA-compatible automatic startup login flag; missing values follow the remembered account. */
  fun isAutoLogin(): Boolean = settings.getBoolean(KEY_AUTO_LOGIN, isRememberUsername())

  fun setAutoLogin(enabled: Boolean) {
    settings.putBoolean(KEY_AUTO_LOGIN, enabled)
    if (enabled) setRememberUsername(true)
  }

  fun clear() {
    settings.remove(KEY_USERNAME)
    settings.remove(KEY_ACCOUNT_ID)
    settings.remove(KEY_PASSWORD)
    settings.remove(KEY_REMEMBER_USERNAME)
    settings.remove(KEY_REMEMBER_PASSWORD)
    settings.remove(KEY_AUTO_LOGIN)
  }
}
