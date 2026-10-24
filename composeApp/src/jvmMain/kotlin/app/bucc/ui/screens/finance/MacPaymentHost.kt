package app.bucc.ui.screens.finance

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

internal data class MacPaymentHost(
    val window: Long,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** Uses the JDK peer already opened by BUCC's macOS launcher; never searches unrelated windows. */
internal fun macPaymentWindowPointer(component: Component): Long {
  val owner = SwingUtilities.getWindowAncestor(component) ?: return 0
  val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
  val peer =
      Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
          .getMethod("getPeer", Component::class.java)
          .invoke(accessor, owner)
  val platform = Class.forName("sun.lwawt.LWWindowPeer").getMethod("getPlatformWindow").invoke(peer)
  return Class.forName("sun.lwawt.macosx.CFRetainedResource")
      .getDeclaredField("ptr")
      .apply { isAccessible = true }
      .getLong(platform)
}

internal class MacPaymentHostPanel(private val changed: (MacPaymentHost?) -> Unit) : JPanel() {
  init {
    isOpaque = false
    addComponentListener(
        object : ComponentAdapter() {
          override fun componentMoved(e: ComponentEvent) = publish()

          override fun componentResized(e: ComponentEvent) = publish()

          override fun componentShown(e: ComponentEvent) = publish()
        }
    )
    addHierarchyBoundsListener(
        object : HierarchyBoundsAdapter() {
          override fun ancestorMoved(e: HierarchyEvent) = publish()

          override fun ancestorResized(e: HierarchyEvent) = publish()
        }
    )
  }

  override fun addNotify() {
    super.addNotify()
    SwingUtilities.invokeLater { publish() }
  }

  override fun removeNotify() {
    changed(null)
    super.removeNotify()
  }

  private fun publish() {
    if (!isShowing || width <= 0 || height <= 0) return
    val owner = SwingUtilities.getWindowAncestor(this) as? RootPaneContainer ?: return
    val bounds = SwingUtilities.convertPoint(this, 0, 0, owner.contentPane)
    val pointer = runCatching { macPaymentWindowPointer(this) }.getOrDefault(0)
    changed(
        if (pointer == 0L) null
        else
            MacPaymentHost(
                pointer,
                bounds.x.toDouble(),
                bounds.y.toDouble(),
                width.toDouble(),
                height.toDouble(),
            )
    )
  }
}
