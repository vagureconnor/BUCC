package app.bucc.ui.screens.td

import app.bucc.td.TdAppleBridge
import app.bucc.td.TdAppleCancellable
import app.bucc.td.TdAppleImageCallback
import app.bucc.td.TdAppleNetworkCallback
import app.bucc.td.TdApplePlatformBridge

/** Compose-framework facade so Swift does not need the shared module exported separately. */
interface TdNativeCancellable {
  fun cancel()
}

interface TdNativeNetworkCallback {
  fun onSuccess(response: ByteArray)

  fun onFailure(message: String)
}

interface TdNativeImageCallback {
  fun onSuccess(name: String, mimeType: String, bytes: ByteArray)

  fun onFailure(message: String)
}

interface TdNativePlatformBridge {
  fun request(
      host: String,
      port: Int,
      bytes: ByteArray,
      timeoutMillis: Long,
      callback: TdNativeNetworkCallback,
  ): TdNativeCancellable

  fun password(studentId: String): String?

  fun savePassword(studentId: String, password: String)

  fun deletePassword(studentId: String)

  fun pickImage(callback: TdNativeImageCallback): TdNativeCancellable
}

object TdNativeBridgeInstaller {
  fun install(bridge: TdNativePlatformBridge) {
    TdAppleBridge.install(
        object : TdApplePlatformBridge {
          override fun request(
              host: String,
              port: Int,
              bytes: ByteArray,
              timeoutMillis: Long,
              callback: TdAppleNetworkCallback,
          ): TdAppleCancellable =
              bridge
                  .request(
                      host = host,
                      port = port,
                      bytes = bytes,
                      timeoutMillis = timeoutMillis,
                      callback =
                          object : TdNativeNetworkCallback {
                            override fun onSuccess(response: ByteArray) {
                              callback.onSuccess(response)
                            }

                            override fun onFailure(message: String) {
                              callback.onFailure(message)
                            }
                          },
                  )
                  .asAppleCancellable()

          override fun password(studentId: String): String? = bridge.password(studentId)

          override fun savePassword(studentId: String, password: String) {
            bridge.savePassword(studentId, password)
          }

          override fun deletePassword(studentId: String) {
            bridge.deletePassword(studentId)
          }

          override fun pickImage(callback: TdAppleImageCallback): TdAppleCancellable =
              bridge
                  .pickImage(
                      object : TdNativeImageCallback {
                        override fun onSuccess(
                            name: String,
                            mimeType: String,
                            bytes: ByteArray,
                        ) {
                          callback.onSuccess(name, mimeType, bytes)
                        }

                        override fun onFailure(message: String) {
                          callback.onFailure(message)
                        }
                      }
                  )
                  .asAppleCancellable()
        }
    )
  }
}

private fun TdNativeCancellable.asAppleCancellable(): TdAppleCancellable {
  val native = this
  return object : TdAppleCancellable {
    override fun cancel() = native.cancel()
  }
}
