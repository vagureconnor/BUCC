package app.bucc.connection

/** Private loopback helper protocol; implementations must never log request or response bodies. */
interface PhysicalCampusHelper {
  suspend fun exchange(request: String): String

  suspend fun close()
}

internal val physicalCampusProxySupported: Boolean
  get() =
      System.getProperty("os.name").startsWith("Mac", true) &&
          (System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0) >= 12
