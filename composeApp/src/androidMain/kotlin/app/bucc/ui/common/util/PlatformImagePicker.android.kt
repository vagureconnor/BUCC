package app.bucc.ui.common.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.bucc.api.storage.MAX_AVATAR_BYTES
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import app.bucc.api.storage.isValidAvatarInput
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberPlatformImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): PlatformImagePicker {
  val context = LocalContext.current
  val currentOnImagePicked by rememberUpdatedState(onImagePicked)
  val currentOnError by rememberUpdatedState(onError)

  val pickImageLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.readPickedImage(uri) }
            .onSuccess(currentOnImagePicked)
            .onFailure { currentOnError(it.message ?: "读取图片失败") }
      }

  val takePhotoLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        runCatching { bitmap.toPickedImage() }
            .onSuccess(currentOnImagePicked)
            .onFailure { currentOnError(it.message ?: "拍照失败") }
      }

  return remember {
    object : PlatformImagePicker {
      override val canCapturePhoto: Boolean = true

      override fun pickImage() {
        pickImageLauncher.launch("image/*")
      }

      override fun capturePhoto() {
        takePhotoLauncher.launch(null)
      }
    }
  }
}

private fun Context.readPickedImage(uri: Uri): PickedImage {
  val bytes =
      contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          total += read
          require(total <= MAX_AVATAR_INPUT_BYTES) { "图片超过 20 MB，请选择较小的图片" }
          output.write(buffer, 0, read)
        }
        output.toByteArray()
      } ?: throw IllegalStateException("无法读取所选图片")
  val fileName = resolveFileName(uri) ?: "picked_${System.currentTimeMillis()}.jpg"
  val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
  return PickedImage(bytes = bytes, fileName = fileName, mimeType = mimeType)
}

private fun Context.resolveFileName(uri: Uri): String? {
  return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
  }
}

private fun Bitmap.toPickedImage(): PickedImage {
  val output = ByteArrayOutputStream()
  if (!compress(Bitmap.CompressFormat.JPEG, 92, output)) {
    throw IllegalStateException("无法处理拍摄图片")
  }
  return PickedImage(
      bytes = output.toByteArray(),
      fileName = "camera_${System.currentTimeMillis()}.jpg",
      mimeType = "image/jpeg",
  )
}

actual fun compressAvatarImage(image: PickedImage): PickedImage {
  require(isValidAvatarInput(image.bytes, image.mimeType)) {
    if (image.bytes.size > app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES) "图片超过 20 MB"
    else "无法识别图片格式，请选择图片文件"
  }
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
  require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片，请选择有效的图片文件" }
  val sampleSize =
      generateSequence(1) { it * 2 }
          .takeWhile { maxOf(bounds.outWidth, bounds.outHeight) / it > 2048 }
          .lastOrNull() ?: 1
  val bitmap =
      BitmapFactory.decodeByteArray(
          image.bytes,
          0,
          image.bytes.size,
          BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
          },
      ) ?: throw IllegalArgumentException("无法读取图片，请选择有效的图片文件")
  val oriented =
      runCatching {
            val orientation =
                ExifInterface(image.bytes.inputStream())
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
            val matrix = Matrix()
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val values =
                when (orientation) {
                  ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                      floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                  ExifInterface.ORIENTATION_ROTATE_180 ->
                      floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                  ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                      floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                  ExifInterface.ORIENTATION_TRANSPOSE ->
                      floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                  ExifInterface.ORIENTATION_ROTATE_90 ->
                      floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                  ExifInterface.ORIENTATION_TRANSVERSE ->
                      floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                  ExifInterface.ORIENTATION_ROTATE_270 ->
                      floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                  else -> null
                }
            if (values == null) bitmap
            else {
              matrix.setValues(values)
              Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
          }
          .getOrElse { bitmap }
  if (oriented !== bitmap) bitmap.recycle()
  val scale = minOf(1f, 1024f / maxOf(oriented.width, oriented.height))
  val resized =
      if (scale < 1f)
          Bitmap.createScaledBitmap(
              oriented,
              (oriented.width * scale).toInt().coerceAtLeast(1),
              (oriented.height * scale).toInt().coerceAtLeast(1),
              true,
          )
      else oriented
  if (resized !== oriented) oriented.recycle()
  try {
    val rgbImage = Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888)
    Canvas(rgbImage).apply {
      drawColor(Color.WHITE)
      drawBitmap(resized, 0f, 0f, null)
    }
    var output = ByteArray(0)
    for (quality in listOf(92, 84, 76, 68, 60, 52, 44, 40)) {
      val stream = ByteArrayOutputStream()
      rgbImage.compress(Bitmap.CompressFormat.JPEG, quality, stream)
      output = stream.toByteArray()
      if (output.size <= MAX_AVATAR_BYTES) break
    }
    rgbImage.recycle()
    require(output.size <= MAX_AVATAR_BYTES) { "图片压缩后仍超过 2 MB，请选择较简单的图片" }
    return PickedImage(
        output,
        image.fileName.substringBeforeLast('.', "avatar") + ".jpg",
        "image/jpeg",
    )
  } finally {
    resized.recycle()
  }
}
