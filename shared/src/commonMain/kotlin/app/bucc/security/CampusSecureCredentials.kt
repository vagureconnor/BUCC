package app.bucc.security

data class CampusCredential(val username: String, val password: String) {
  override fun toString(): String = "CampusCredential(username=[redacted], password=[redacted])"
}

interface CampusCredentialStorage {
  suspend fun save(credential: CampusCredential)

  suspend fun load(username: String): CampusCredential?

  suspend fun delete(username: String)
}

expect fun createPlatformCampusCredentialStorage(): CampusCredentialStorage

object CampusSecureCredentials {
  private val platformStorage: CampusCredentialStorage by
      lazy(::createPlatformCampusCredentialStorage)
  private var testStorage: CampusCredentialStorage? = null

  private val storage: CampusCredentialStorage
    get() = testStorage ?: platformStorage

  suspend fun save(credential: CampusCredential) = storage.save(credential)

  suspend fun load(username: String): CampusCredential? = storage.load(username)

  suspend fun delete(username: String) = storage.delete(username)

  internal suspend fun <T> withStorageForTests(
      storage: CampusCredentialStorage,
      block: suspend () -> T,
  ): T {
    val previous = testStorage
    testStorage = storage
    try {
      return block()
    } finally {
      testStorage = previous
    }
  }
}
