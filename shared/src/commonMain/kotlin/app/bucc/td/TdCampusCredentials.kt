package app.bucc.td

/** Campus credentials shared with TD only for the lifetime of the authenticated app session. */
data class TdCampusCredential(val username: String, val password: String)

object TdCampusCredentials {
  private var sessionCredential: TdCampusCredential? = null
  private var activeCampusUsername: String? = null

  fun setAuthenticated(username: String, password: String) {
    sessionCredential =
        if (username.isNotBlank() && password.isNotBlank()) {
          TdCampusCredential(username.trim(), password)
        } else {
          null
        }
    activeCampusUsername = username.trim().takeIf(String::isNotBlank)
  }

  fun setActiveAccount(username: String) {
    activeCampusUsername = username.trim().takeIf(String::isNotBlank)
  }

  fun activeUsername(): String? = activeCampusUsername

  /** Returns the campus credential held only for the current authenticated app session. */
  fun current(): TdCampusCredential? {
    return sessionCredential?.takeIf { it.username == activeCampusUsername }
  }

  fun clearSession() {
    sessionCredential = null
    activeCampusUsername = null
  }
}
