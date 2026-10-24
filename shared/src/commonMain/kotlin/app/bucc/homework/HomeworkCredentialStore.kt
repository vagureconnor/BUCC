package app.bucc.homework

import app.bucc.security.CampusCredential
import app.bucc.security.CampusCredentialStorage
import app.bucc.security.CampusSecureCredentials
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class HomeworkIndependentCredentials(val username: String, val password: String) {
  override fun toString(): String =
      "HomeworkIndependentCredentials(username=[redacted], password=[redacted])"
}

/** Keeps independent homework credentials in the platform's encrypted credential store. */
internal class HomeworkCredentialStore(private val storage: CampusCredentialStorage) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun save(account: String, source: HomeworkSource, username: String, password: String) {
    storage.save(
        CampusCredential(
            storageKey(account, source),
            json.encodeToString(HomeworkIndependentCredentials(username, password)),
        ),
    )
  }

  suspend fun load(account: String, source: HomeworkSource): HomeworkIndependentCredentials? {
    val key = storageKey(account, source)
    val saved = storage.load(key) ?: return null
    check(saved.username == key) { "独立账号凭据无效" }
    val credentials = json.decodeFromString<HomeworkIndependentCredentials>(saved.password)
    require(credentials.username.isNotBlank() && credentials.password.isNotBlank()) { "独立账号凭据无效" }
    return credentials
  }

  suspend fun delete(account: String, source: HomeworkSource) {
    storage.delete(storageKey(account, source))
  }

  companion object {
    internal fun storageKey(account: String, source: HomeworkSource): String =
        "homework:$account:${source.name}"
  }
}

internal object PlatformHomeworkCredentialStorage : CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential) = CampusSecureCredentials.save(credential)

  override suspend fun load(username: String): CampusCredential? =
      CampusSecureCredentials.load(username)

  override suspend fun delete(username: String) = CampusSecureCredentials.delete(username)
}
