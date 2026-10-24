package app.bucc.ui.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.bucc.platform.HarmonyHost

@Composable
actual fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
  val key = remember { Any() }
  val callback = rememberUpdatedState(onBack)
  DisposableEffect(enabled) {
    if (enabled) HarmonyHost.backHandlers[key] = { callback.value() }
    onDispose { HarmonyHost.backHandlers.remove(key) }
  }
}
