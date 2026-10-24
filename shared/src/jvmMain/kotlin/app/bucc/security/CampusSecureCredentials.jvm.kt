package app.bucc.security

actual fun createPlatformCampusCredentialStorage(): CampusCredentialStorage =
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
      val home = System.getProperty("user.home")
      check(!home.isNullOrBlank()) { "无法访问应用凭据存储目录" }
      MacCampusCredentialStorage(
          MacCampusCredentialVault(
              java.nio.file.Paths.get(home, "Library", "Application Support", "BUCC", "credentials")
          )
      )
    } else if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      WindowsCampusCredentialStorage()
    } else {
      UnavailableCampusCredentialStorage()
    }

private class MacCampusCredentialStorage(private val vault: MacCampusCredentialVault) :
    CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential) {
    vault.save(credential)
  }

  override suspend fun load(username: String): CampusCredential? = vault.load(username)

  override suspend fun delete(username: String) = vault.delete(username)
}

private class UnavailableCampusCredentialStorage : CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential): Nothing = unavailable()

  override suspend fun load(username: String): Nothing = unavailable()

  override suspend fun delete(username: String): Nothing = unavailable()

  private fun unavailable(): Nothing = error("系统安全凭据存储仅支持 macOS 和 Windows")
}
