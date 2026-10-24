package app.bucc.td

interface TdAppleCancellable {
  fun cancel()
}

interface TdAppleNetworkCallback {
  fun onSuccess(response: ByteArray)

  fun onFailure(message: String)
}

interface TdAppleImageCallback {
  fun onSuccess(name: String, mimeType: String, bytes: ByteArray)

  fun onFailure(message: String)
}

/** Installed by iosApp/TdNetworkBridge.swift before Compose creates [TdRuntime.service]. */
interface TdApplePlatformBridge {
  fun request(
      host: String,
      port: Int,
      bytes: ByteArray,
      timeoutMillis: Long,
      callback: TdAppleNetworkCallback,
  ): TdAppleCancellable

  fun password(studentId: String): String?

  fun savePassword(studentId: String, password: String)

  fun deletePassword(studentId: String)

  fun pickImage(callback: TdAppleImageCallback): TdAppleCancellable
}

object TdAppleBridge {
  private var installed: TdApplePlatformBridge? = null

  fun install(bridge: TdApplePlatformBridge) {
    installed = bridge
  }

  internal fun platformOrNull(): TdApplePlatformBridge? = installed

  fun requirePlatform(): TdApplePlatformBridge =
      installed ?: error("iOS TD 原生桥接未安装，请检查 TdNetworkBridge.swift 的 target 配置")
}
