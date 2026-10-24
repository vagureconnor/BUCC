@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package app.bucc.platform

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.napi.JsEnv
import androidx.compose.ui.napi.JsObject
import androidx.compose.ui.napi.asString
import androidx.compose.ui.napi.js
import androidx.compose.ui.window.ComposeArkUIViewController
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.bucc.App
import app.bucc.notifications.NotificationRouteInbox
import app.bucc.td.HarmonyTdRuntime
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.initMainHandler
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_value

/** Only the app-owned Ability can access this bridge; it is never registered with a web page. */
internal object HarmonyHost {
  lateinit var bridge: JsObject
  private var nextRequest = 1
  private val pending = mutableMapOf<Int, CompletableDeferred<JsObject>>()
  val backHandlers = linkedMapOf<Any, () -> Unit>()

  suspend fun request(operation: String, payload: JsonObject = JsonObject(emptyMap())): JsObject =
      withContext(Dispatchers.Main) {
        val id = nextRequest++
        val response = CompletableDeferred<JsObject>()
        pending[id] = response
        try {
          bridge.call(
              "request",
              js {
                "id"(id)
                "operation"(operation)
                "payload"(payload.toString())
              },
          )
          response.await()
        } finally {
          pending.remove(id)
          if (!response.isCompleted) bridge.call("cancel", js { "id"(id) })
        }
      }

  fun complete(id: Int, value: napi_value) {
    pending.remove(id)?.complete(JsObject(value))
  }
}

internal val JsObject.status: String
  get() = this["status"].asString().orEmpty()

@kotlin.native.CName("bucc_create_controller")
fun createBuccController(
    env: napi_env,
    files: CPointer<ByteVar>,
    cache: CPointer<ByteVar>,
    bridge: napi_value,
): napi_value {
  initMainHandler(env)
  JsEnv.init(env)
  HarmonyAppDirectories.initialize(files.toKString(), cache.toKString())
  HarmonyHost.bridge = JsObject(bridge)
  return ComposeArkUIViewController(env) {
    val owner = remember {
      object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
      }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    LaunchedEffect(Unit) {
      HarmonyTdRuntime.executionActive.collect { enabled ->
        HarmonyHost.bridge.call("keepAwake", js { "enabled"(enabled) })
      }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { App() }
  }
}

@kotlin.native.CName("bucc_complete_request")
fun completeBuccHostRequest(id: Int, value: napi_value) = HarmonyHost.complete(id, value)

@kotlin.native.CName("bucc_handle_back")
fun handleBuccBack(): Boolean {
  val handler = HarmonyHost.backHandlers.values.lastOrNull() ?: return false
  handler()
  return true
}

@kotlin.native.CName("bucc_notify_background")
fun notifyBuccBackground() = HarmonyTdRuntime.notifyAppBackgrounded()

@kotlin.native.CName("bucc_notification_route")
fun routeBuccNotification(route: CPointer<ByteVar>) =
    NotificationRouteInbox.request(route.toKString())
