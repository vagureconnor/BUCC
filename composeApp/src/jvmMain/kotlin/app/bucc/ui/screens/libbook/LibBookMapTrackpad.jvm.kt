package app.bucc.ui.screens.libbook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.Window
import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.libBookMapTrackpadZoom(onZoom: (Float) -> Unit): Modifier {
  var hovered by remember { mutableStateOf(false) }
  val currentOnZoom by rememberUpdatedState(onZoom)
  DisposableEffect(hovered) {
    val registration = if (hovered) attachLibBookMacMagnification { currentOnZoom(it) } else null
    onDispose { registration?.close() }
  }
  return onPointerEvent(PointerEventType.Enter) { hovered = true }
      .onPointerEvent(PointerEventType.Exit) { hovered = false }
      .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        val factor =
            libBookMapScrollZoomFactor(
                event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat(),
                event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed,
            )
        if (factor != null) {
          currentOnZoom(factor)
          event.changes.forEach { it.consume() }
        }
      }
}

/** Reflection keeps Linux/Windows JVM builds independent of Apple's optional AWT classes. */
internal fun attachLibBookMacMagnification(onZoom: (Float) -> Unit): AutoCloseable? {
  if (!System.getProperty("os.name", "").contains("Mac", ignoreCase = true)) return null
  val roots =
      Window.getWindows()
          .filter { it.isShowing && it.isFocused }
          .mapNotNull { (it as? RootPaneContainer)?.rootPane }
  return registerLibBookMacMagnification(roots, onZoom)
}

internal fun registerLibBookMacMagnification(
    roots: List<JComponent>,
    onZoom: (Float) -> Unit,
): AutoCloseable? {
  if (!System.getProperty("os.name", "").contains("Mac", ignoreCase = true)) return null
  return try {
    val listenerType = Class.forName("com.apple.eawt.event.MagnificationListener")
    val gestureType = Class.forName("com.apple.eawt.event.GestureListener")
    val utilities = Class.forName("com.apple.eawt.event.GestureUtilities")
    val add = utilities.getMethod("addGestureListenerTo", JComponent::class.java, gestureType)
    val remove =
        utilities.getMethod("removeGestureListenerFrom", JComponent::class.java, gestureType)
    val listener =
        Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) {
            proxy,
            method,
            args ->
          when (method.name) {
            "magnify" -> {
              val event = args?.firstOrNull()
              if (event != null) {
                val amount = event.javaClass.getMethod("getMagnification").invoke(event) as Double
                libBookTrackpadMagnificationFactor(amount)?.let {
                  onZoom(it)
                  event.javaClass.getMethod("consume").invoke(event)
                }
              }
              null
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "BUCC library map magnification listener"
            else -> null
          }
        }
    roots.forEach { add.invoke(null, it, listener) }
    AutoCloseable {
      val cleanup = Runnable { roots.forEach { remove.invoke(null, it, listener) } }
      if (SwingUtilities.isEventDispatchThread()) cleanup.run()
      else SwingUtilities.invokeLater(cleanup)
    }
  } catch (_: ReflectiveOperationException) {
    null
  }
}
