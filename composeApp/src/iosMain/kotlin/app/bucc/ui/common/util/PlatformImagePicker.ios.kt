package app.bucc.ui.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.bucc.api.storage.MAX_AVATAR_BYTES
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import app.bucc.api.storage.isValidAvatarInput
import app.bucc.td.TdAppleBridge
import app.bucc.td.TdAppleCancellable
import app.bucc.td.TdAppleImageCallback
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

@Composable
actual fun rememberPlatformImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): PlatformImagePicker {
  val currentOnImagePicked = rememberUpdatedState(onImagePicked)
  val currentOnError by rememberUpdatedState(onError)

  return remember {
    IosPlatformImagePicker(
        onImagePicked = { currentOnImagePicked.value(it) },
        onError = { currentOnError(it) },
    )
  }
}

@OptIn(ExperimentalForeignApi::class)
actual fun compressAvatarImage(image: PickedImage): PickedImage {
  require(isValidAvatarInput(image.bytes, image.mimeType)) {
    if (image.bytes.size > MAX_AVATAR_INPUT_BYTES) "图片超过 20 MB" else "无法识别图片格式，请选择图片文件"
  }
  val sourceData =
      image.bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = image.bytes.size.toULong())
      }
  val source = UIImage(data = sourceData) ?: throw IllegalArgumentException("无法读取图片，请选择有效的图片文件")
  val pixelWidth = (source.size.useContents { width } * source.scale).toInt()
  val pixelHeight = (source.size.useContents { height } * source.scale).toInt()
  require(pixelWidth > 0 && pixelHeight > 0) { "无法读取图片，请选择有效的图片文件" }
  val ratio = minOf(1.0, 1024.0 / maxOf(pixelWidth, pixelHeight))
  val width = (pixelWidth * ratio).toInt().coerceAtLeast(1)
  val height = (pixelHeight * ratio).toInt().coerceAtLeast(1)
  UIGraphicsBeginImageContextWithOptions(CGSizeMake(width.toDouble(), height.toDouble()), true, 1.0)
  try {
    source.drawInRect(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    val normalized =
        UIGraphicsGetImageFromCurrentImageContext() ?: throw IllegalArgumentException("无法处理所选图片")
    var output: NSData? = null
    for (quality in listOf(0.92, 0.84, 0.76, 0.68, 0.60, 0.52, 0.44, 0.40)) {
      output = UIImageJPEGRepresentation(normalized, quality)
      if (output != null && output.length <= MAX_AVATAR_BYTES.toULong()) break
    }
    val encoded = output ?: throw IllegalArgumentException("无法压缩所选图片")
    require(encoded.length <= MAX_AVATAR_BYTES.toULong()) { "图片压缩后仍超过 2 MB，请选择较简单的图片" }
    val bytes = ByteArray(encoded.length.toInt())
    if (bytes.isNotEmpty()) {
      bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), encoded.bytes, encoded.length) }
    }
    return PickedImage(
        bytes,
        image.fileName.substringBeforeLast('.', "avatar") + ".jpg",
        "image/jpeg",
    )
  } finally {
    UIGraphicsEndImageContext()
  }
}

private class IosPlatformImagePicker(
    private val onImagePicked: (PickedImage) -> Unit,
    private val onError: (String) -> Unit,
) : PlatformImagePicker {
  override val canCapturePhoto: Boolean = false
  private var request: TdAppleCancellable? = null

  override fun pickImage() {
    request?.cancel()
    request =
        TdAppleBridge.requirePlatform()
            .pickImage(
                object : TdAppleImageCallback {
                  override fun onSuccess(name: String, mimeType: String, bytes: ByteArray) {
                    request = null
                    onImagePicked(PickedImage(bytes = bytes, fileName = name, mimeType = mimeType))
                  }

                  override fun onFailure(message: String) {
                    request = null
                    if (message.isNotBlank()) onError(message)
                  }
                }
            )
  }

  override fun capturePhoto() {
    onError("当前平台暂不支持拍照")
  }
}
