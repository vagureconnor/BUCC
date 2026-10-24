package app.bucc.platform

/** Initialized by the native Ability host from its own application Context before creating BUCC. */
object HarmonyAppDirectories {
  private var roots: Pair<String, String>? = null

  fun initialize(filesDir: String, cacheDir: String) {
    val directories = validateDirectory(filesDir) to validateDirectory(cacheDir)
    check(roots == null || roots == directories) {
      "Harmony application directories cannot change while running"
    }
    roots = directories
  }

  internal val filesDir: String
    get() = checkNotNull(roots) { "Harmony application host is not initialized" }.first

  internal val cacheDir: String
    get() = checkNotNull(roots) { "Harmony application host is not initialized" }.second

  private fun validateDirectory(path: String): String {
    require(path.startsWith('/') && path.length in 2..4096 && '\u0000' !in path) {
      "Invalid Harmony application directory"
    }
    require(path.split('/').none { it == "." || it == ".." }) {
      "Invalid Harmony application directory"
    }
    return path.trimEnd('/').also {
      require(it.isNotEmpty()) { "Invalid Harmony application directory" }
    }
  }
}
