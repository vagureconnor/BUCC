package app.bucc.ui.common.util

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun PlatformQrScanner(onDecoded: (String) -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val latestOnDecoded by rememberUpdatedState(onDecoded)
  val latestOnCancel by rememberUpdatedState(onCancel)
  val hasCamera =
      remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
      }
  var permitted by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    )
  }
  var foreground by
      remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
      }
  var message by remember { mutableStateOf("正在检查相机权限…") }
  var retry by remember { mutableStateOf(0) }
  var delivered by remember { mutableStateOf(false) }
  val previewView =
      remember(context) {
        PreviewView(context).apply {
          implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
      }
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (!granted) message = "相机权限未开启。请允许使用相机，或在系统设置中开启后返回。"
      }

  LaunchedEffect(Unit) {
    if (!hasCamera) message = "此设备没有可用摄像头，请返回使用座位列表预约。"
    else if (!permitted) permissionLauncher.launch(Manifest.permission.CAMERA)
  }
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event ->
      foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
      if (event == Lifecycle.Event.ON_RESUME) {
        permitted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
      }
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(permitted, foreground, retry, delivered, owner, previewView) {
    val active = AtomicBoolean(true)
    val decoded = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()
    val mainExecutor = ContextCompat.getMainExecutor(context)
    var cameraProvider: ProcessCameraProvider? = null
    var preview: Preview? = null
    var analysis: ImageAnalysis? = null
    if (permitted && hasCamera && foreground && !delivered) {
      message = "正在启动相机…"
      val future = runCatching { ProcessCameraProvider.getInstance(context) }.getOrNull()
      if (future == null) message = "无法初始化相机，请重试或返回使用座位列表预约。"
      if (future != null)
          future.addListener(
              {
                if (active.get()) {
                  try {
                    val provider = future.get()
                    cameraProvider = provider
                    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                      message = "没有可用的后置摄像头，请返回使用座位列表预约。"
                    } else {
                      val cameraPreview = Preview.Builder().build()
                      preview = cameraPreview
                      cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                      val cameraAnalysis =
                          ImageAnalysis.Builder()
                              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                              .build()
                      analysis = cameraAnalysis
                      val reader = QRCodeReader()
                      cameraAnalysis.setAnalyzer(executor) { image ->
                        try {
                          if (active.get() && !decoded.get()) {
                            val crop = image.cropRect
                            val plane = image.planes.first()
                            val buffer = plane.buffer.duplicate()
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            val luminance =
                                copyQrLuminance(
                                    bytes,
                                    plane.rowStride,
                                    plane.pixelStride,
                                    crop.left,
                                    crop.top,
                                    crop.width(),
                                    crop.height(),
                                )
                            val source =
                                PlanarYUVLuminanceSource(
                                    luminance,
                                    crop.width(),
                                    crop.height(),
                                    0,
                                    0,
                                    crop.width(),
                                    crop.height(),
                                    false,
                                )
                            val value = reader.decode(BinaryBitmap(HybridBinarizer(source))).text
                            if (!value.isNullOrBlank() && decoded.compareAndSet(false, true)) {
                              mainExecutor.execute {
                                if (active.get()) {
                                  cameraAnalysis.clearAnalyzer()
                                  provider.unbind(cameraPreview, cameraAnalysis)
                                  delivered = true
                                  message = "已识别二维码，正在检查预约信息…"
                                  latestOnDecoded(value)
                                }
                              }
                            }
                          }
                        } catch (_: ReaderException) {
                          // A frame without a readable QR code is expected; keep scanning.
                        } catch (_: IllegalArgumentException) {
                          // Ignore an invalid camera frame, without retaining its contents.
                        } finally {
                          reader.reset()
                          image.close()
                        }
                      }
                      provider.bindToLifecycle(
                          owner,
                          CameraSelector.DEFAULT_BACK_CAMERA,
                          cameraPreview,
                          cameraAnalysis,
                      )
                      message = "正在扫描图书馆预约二维码…"
                    }
                  } catch (_: Exception) {
                    analysis?.clearAnalyzer()
                    val bound = listOfNotNull(preview, analysis).toTypedArray()
                    if (bound.isNotEmpty()) cameraProvider?.unbind(*bound)
                    message = "无法启动相机，请重试或返回使用座位列表预约。"
                  }
                }
              },
              mainExecutor,
          )
    } else if (permitted && !foreground && !delivered) {
      message = "扫描已暂停，返回应用后继续。"
    }
    onDispose {
      active.set(false)
      analysis?.clearAnalyzer()
      val bound = listOfNotNull(preview, analysis).toTypedArray()
      if (bound.isNotEmpty()) cameraProvider?.unbind(*bound)
      executor.shutdown()
    }
  }

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("将图书馆预约二维码放入取景框")
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      if (hasCamera && permitted)
          AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
    Text(message)
    if (hasCamera && !permitted) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("允许相机") }
        Button(
            onClick = {
              context.startActivity(
                  Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          Uri.parse("package:${context.packageName}"),
                      )
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            }
        ) {
          Text("打开系统设置")
        }
      }
    } else if (hasCamera && !delivered) {
      Button(onClick = { retry++ }) { Text("重新启动相机") }
    }
    Button(onClick = { latestOnCancel() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
      Text("取消扫码")
    }
  }
}

actual fun supportsQrCamera(): Boolean = true
