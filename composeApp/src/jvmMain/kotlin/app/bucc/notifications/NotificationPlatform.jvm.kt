package app.bucc.notifications

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private interface BuccNotificationNative : Library {
  fun bucc_notifications_schedule(
      coursePlanJson: String,
      dailyTodoEnabled: Int,
      completion: NativeCompletion,
      context: Pointer?,
  )

  fun bucc_notifications_cancel(completion: NativeCompletion, context: Pointer?)

  fun bucc_notifications_permission_status(completion: NativeCompletion, context: Pointer?)

  fun bucc_notifications_request_authorization(completion: NativeCompletion, context: Pointer?)

  fun bucc_notifications_install_open_callback(callback: NativeOpenCallback, context: Pointer?)
}

private fun interface NativeCompletion : Callback {
  fun invoke(context: Pointer?, status: Int)
}

private fun interface NativeOpenCallback : Callback {
  fun invoke(destination: String?, context: Pointer?)
}

private val callbacks = ConcurrentHashMap<Long, CompletableDeferred<String>>()
private val permissionCallbacks =
    ConcurrentHashMap<Long, CompletableDeferred<NotificationPermissionState>>()
private val callbackIds = AtomicLong(1)
private val nativeOpenCallback = NativeOpenCallback { destination, _ ->
  destination?.let(NotificationRouteInbox::request)
}
private val nativeCompletion = NativeCompletion { context, status ->
  if (context == null) return@NativeCompletion
  val token = Pointer.nativeValue(context)
  callbacks
      .remove(token)
      ?.complete(
          when (status) {
            0 -> "scheduled"
            1 -> "partial_capacity"
            2 -> "permission_required"
            4 -> "cancelled"
            else -> "schedule_failed"
          }
      )
}
private val nativePermissionCompletion = NativeCompletion { context, status ->
  if (context == null) return@NativeCompletion
  permissionCallbacks
      .remove(Pointer.nativeValue(context))
      ?.complete(
          when (status) {
            0 -> NotificationPermissionState.GRANTED
            1 -> NotificationPermissionState.NOT_DETERMINED
            2 -> NotificationPermissionState.DENIED
            else -> NotificationPermissionState.UNSUPPORTED
          }
      )
}

actual suspend fun scheduleBuccNotifications(
    events: List<NotificationEvent>,
    courseEnabled: Boolean,
    dailyTodoEnabled: Boolean,
): String {
  if (System.getProperty("os.name").startsWith("Windows", true)) {
    val plan =
        if (courseEnabled) buildCourseReminderPlan(events, System.currentTimeMillis())
        else emptyList()
    return windowsNotificationBridge?.schedule(plan, dailyTodoEnabled) ?: "unavailable"
  }
  if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
      return "unsupported_platform"
  val plan =
      if (courseEnabled) buildCourseReminderPlan(events, System.currentTimeMillis())
      else emptyList()
  val json =
      buildJsonArray {
            plan.forEach { item ->
              add(
                  buildJsonObject {
                    put("eventId", item.eventId)
                    put("triggerAtMillis", item.triggerAtMillis)
                  }
              )
            }
          }
          .toString()
  return invokeNative { native, callback, pointer ->
    native.bucc_notifications_schedule(json, if (dailyTodoEnabled) 1 else 0, callback, pointer)
  }
}

actual suspend fun cancelBuccNotifications() {
  if (System.getProperty("os.name").startsWith("Windows", true)) {
    windowsNotificationBridge?.cancel()
    return
  }
  if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
  invokeNative { native, callback, pointer -> native.bucc_notifications_cancel(callback, pointer) }
}

actual suspend fun buccNotificationPermissionState(): NotificationPermissionState =
    if (System.getProperty("os.name").startsWith("Mac", true)) {
      invokeNativePermission { native, callback, pointer ->
        native.bucc_notifications_permission_status(callback, pointer)
      }
    } else {
      NotificationPermissionState.UNSUPPORTED
    }

actual suspend fun requestBuccNotificationPermission(): NotificationPermissionState =
    if (System.getProperty("os.name").startsWith("Mac", true)) {
      invokeNativePermission { native, callback, pointer ->
        native.bucc_notifications_request_authorization(callback, pointer)
      }
    } else {
      NotificationPermissionState.UNSUPPORTED
    }

actual fun openBuccNotificationSettings() {
  runCatching {
    when {
      System.getProperty("os.name").startsWith("Mac", true) ->
          ProcessBuilder(
                  "open",
                  "x-apple.systempreferences:com.apple.Notifications-Settings.extension",
              )
              .start()
      System.getProperty("os.name").startsWith("Windows", true) ->
          ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:notifications").start()
    }
  }
}

private suspend fun invokeNativePermission(
    call: (BuccNotificationNative, NativeCompletion, Pointer) -> Unit,
): NotificationPermissionState {
  val library = notificationNativeLibrary() ?: return NotificationPermissionState.UNSUPPORTED
  val token = callbackIds.getAndIncrement()
  val result = CompletableDeferred<NotificationPermissionState>()
  permissionCallbacks[token] = result
  try {
    withContext(Dispatchers.IO) {
      val native = Native.load(library.absolutePath, BuccNotificationNative::class.java)
      call(native, nativePermissionCompletion, Pointer.createConstant(token))
    }
    return withTimeout(15_000) { result.await() }
  } catch (_: TimeoutCancellationException) {
    return NotificationPermissionState.UNSUPPORTED
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Throwable) {
    return NotificationPermissionState.UNSUPPORTED
  } finally {
    permissionCallbacks.remove(token)
  }
}

private suspend fun notificationNativeLibrary(): File? =
    withContext(Dispatchers.IO) {
      val resources = System.getProperty("compose.application.resources.dir")
      listOfNotNull(
              resources?.let { File(it, "native/libbucc-native.dylib") },
              File("composeApp/build/native/libbucc-native.dylib"),
          )
          .firstOrNull { it.isFile }
    }

private suspend fun invokeNative(
    call: (BuccNotificationNative, NativeCompletion, Pointer) -> Unit
): String {
  val library =
      withContext(Dispatchers.IO) {
        val resources = System.getProperty("compose.application.resources.dir")
        listOfNotNull(
                resources?.let { File(it, "native/libbucc-native.dylib") },
                File("composeApp/build/native/libbucc-native.dylib"),
            )
            .firstOrNull { it.isFile }
      } ?: return "unavailable"

  val token = callbackIds.getAndIncrement()
  val result = CompletableDeferred<String>()
  callbacks[token] = result
  try {
    withContext(Dispatchers.IO) {
      val native = Native.load(library.absolutePath, BuccNotificationNative::class.java)
      native.bucc_notifications_install_open_callback(nativeOpenCallback, Pointer.createConstant(1))
      call(native, nativeCompletion, Pointer.createConstant(token))
    }
    return withTimeout(15_000) { result.await() }
  } catch (_: TimeoutCancellationException) {
    return "unavailable"
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Throwable) {
    return "unavailable"
  } finally {
    callbacks.remove(token)
  }
}
