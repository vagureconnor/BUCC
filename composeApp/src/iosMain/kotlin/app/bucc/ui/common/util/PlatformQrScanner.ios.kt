package app.bucc.ui.common.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformQrScanner(onDecoded: (String) -> Unit, onCancel: () -> Unit) {
  val latestOnDecoded by rememberUpdatedState(onDecoded)
  val latestOnCancel by rememberUpdatedState(onCancel)
  var message by remember { mutableStateOf("正在检查相机权限…") }
  val scanner = remember { IosQrScanner() }

  DisposableEffect(scanner) {
    val backgroundObserver =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
          scanner.stop()
          message = "应用进入后台，扫描已暂停。返回后请重新打开扫码页面。"
        }
    scanner.start(
        onMessage = { message = it },
        onDecoded = { value -> latestOnDecoded(value) },
    )
    onDispose {
      scanner.stop()
      NSNotificationCenter.defaultCenter.removeObserver(backgroundObserver)
    }
  }

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("将图书馆预约二维码放入取景框")
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      if (scanner.hasCamera) {
        UIKitView(
            factory = { scanner.previewView },
            modifier = Modifier.fillMaxSize(),
            update = {},
        )
      }
    }
    if (!scanner.isRunning && scanner.permissionDenied) {
      Text(message)
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { scanner.openSettings() }) { Text("打开系统设置") }
        Button(onClick = { latestOnCancel() }) { Text("返回") }
      }
    } else {
      Text(message)
      Button(onClick = { latestOnCancel() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text("取消扫码")
      }
    }
  }
}

actual fun supportsQrCamera(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
private class IosQrScanner {
  private val captureSession = AVCaptureSession()
  private val cameraQueue = dispatch_queue_create("app.bucc.qr-scanner", null)
  val hasCamera: Boolean = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null
  val previewView: UIView = QrPreviewView(captureSession)
  var isRunning by mutableStateOf(false)
    private set

  var permissionDenied by mutableStateOf(false)
    private set

  private var configured = false
  private var delivered = false
  private var stopped = false
  private var metadataDelegate: QrMetadataDelegate? = null

  fun start(onMessage: (String) -> Unit, onDecoded: (String) -> Unit) {
    stopped = false
    delivered = false
    if (!hasCamera) {
      onMessage("此设备没有可用摄像头，请在支持摄像头的 iPhone 或 iPad 上扫码。")
      return
    }
    when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
      AVAuthorizationStatusAuthorized -> begin(onMessage, onDecoded)
      AVAuthorizationStatusNotDetermined -> {
        onMessage("请允许 BUCC 使用相机以扫描预约二维码。")
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
          dispatch_async(dispatch_get_main_queue()) {
            if (stopped) return@dispatch_async
            if (granted) begin(onMessage, onDecoded)
            else {
              permissionDenied = true
              onMessage("相机权限未开启。可前往系统设置允许 BUCC 使用相机。")
            }
          }
        }
      }
      else -> {
        permissionDenied = true
        onMessage("相机权限未开启。可前往系统设置允许 BUCC 使用相机。")
      }
    }
  }

  private fun begin(onMessage: (String) -> Unit, onDecoded: (String) -> Unit) {
    if (stopped || !configure()) {
      onMessage("无法启动相机扫描，请返回后重试。")
      return
    }
    metadataDelegate?.onDecoded = { value ->
      if (!stopped && !delivered && value.isNotBlank()) {
        delivered = true
        stop()
        onDecoded(value)
      }
    }
    onMessage("正在扫描图书馆预约二维码…")
    dispatch_async(cameraQueue) {
      captureSession.startRunning()
      dispatch_async(dispatch_get_main_queue()) {
        if (!stopped && captureSession.running) isRunning = true
      }
    }
  }

  private fun configure(): Boolean {
    if (configured) return true
    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
    val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null) ?: return false
    val output = AVCaptureMetadataOutput()
    if (!captureSession.canAddInput(input) || !captureSession.canAddOutput(output)) return false
    captureSession.addInput(input)
    captureSession.addOutput(output)
    val delegate = QrMetadataDelegate {}
    metadataDelegate = delegate
    output.setMetadataObjectsDelegate(delegate, queue = dispatch_get_main_queue())
    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
    configured = true
    return true
  }

  fun stop() {
    stopped = true
    isRunning = false
    dispatch_async(cameraQueue) { if (captureSession.running) captureSession.stopRunning() }
  }

  fun openSettings() {
    val url = NSURL(string = UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url)
  }
}

private class QrMetadataDelegate(var onDecoded: (String) -> Unit) :
    platform.darwin.NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
  override fun captureOutput(
      output: AVCaptureOutput,
      didOutputMetadataObjects: List<*>,
      fromConnection: AVCaptureConnection,
  ) {
    val value =
        didOutputMetadataObjects
            .asSequence()
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
    if (value != null) onDecoded(value)
  }
}

@OptIn(ExperimentalForeignApi::class)
private class QrPreviewView(private val session: AVCaptureSession) :
    UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
  private val previewLayer =
      platform.AVFoundation.AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
      }

  init {
    layer.addSublayer(previewLayer)
  }

  override fun layoutSubviews() {
    super.layoutSubviews()
    previewLayer.frame = bounds
  }
}
