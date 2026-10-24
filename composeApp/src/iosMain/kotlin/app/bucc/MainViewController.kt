package app.bucc

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import app.bucc.td.TdRuntime
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
  DisposableEffect(Unit) {
    val observer =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
          TdRuntime.notifyAppBackgrounded()
        }
    onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
  }
  App()
}
