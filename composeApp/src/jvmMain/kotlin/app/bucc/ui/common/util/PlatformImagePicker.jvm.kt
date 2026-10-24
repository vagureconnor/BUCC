package app.bucc.ui.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.bucc.api.storage.MAX_AVATAR_BYTES
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import app.bucc.api.storage.isValidAvatarInput
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

@Composable
actual fun rememberPlatformImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): PlatformImagePicker {
  val currentOnImagePicked by rememberUpdatedState(onImagePicked)
  val currentOnError by rememberUpdatedState(onError)

  return remember {
    object : PlatformImagePicker {
      override val canCapturePhoto: Boolean = false

      override fun pickImage() {
        runCatching {
              val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD)
              dialog.isVisible = true
              val fileName = dialog.file ?: return
              val directory = dialog.directory ?: return
              val file = File(directory, fileName)
              val bytes = file.readBytes()
              val mimeType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
              PickedImage(bytes = bytes, fileName = file.name, mimeType = mimeType)
            }
            .onSuccess(currentOnImagePicked)
            .onFailure { currentOnError(it.message ?: "读取图片失败") }
      }

      override fun capturePhoto() {
        currentOnError("当前平台暂不支持拍照")
      }
    }
  }
}

actual fun compressAvatarImage(image: PickedImage): PickedImage {
  require(isValidAvatarInput(image.bytes, image.mimeType)) {
    if (image.bytes.size > MAX_AVATAR_INPUT_BYTES) "图片超过 20 MB" else "无法识别图片格式，请选择图片文件"
  }
  val input = MemoryCacheImageInputStream(ByteArrayInputStream(image.bytes))
  val reader =
      ImageIO.getImageReaders(input).asSequence().firstOrNull()
          ?: throw IllegalArgumentException("无法读取图片，请选择有效的图片文件")
  val decoded =
      try {
        reader.setInput(input, true, true)
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        var sample = 1
        while (maxOf(width, height) / sample > 2048) sample *= 2
        reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
      } finally {
        reader.dispose()
        input.close()
      }
  val oriented = orientImage(decoded, readExifOrientation(image.bytes))
  val scale = minOf(1.0, 1024.0 / maxOf(oriented.width, oriented.height))
  val width = (oriented.width * scale).toInt().coerceAtLeast(1)
  val height = (oriented.height * scale).toInt().coerceAtLeast(1)
  val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  val graphics = outputImage.createGraphics()
  graphics.color = Color.WHITE
  graphics.fillRect(0, 0, width, height)
  graphics.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BICUBIC,
  )
  graphics.drawImage(oriented, 0, 0, width, height, null)
  graphics.dispose()
  var bytes = ByteArray(0)
  for (quality in listOf(0.92f, 0.84f, 0.76f, 0.68f, 0.60f, 0.52f, 0.44f, 0.40f)) {
    val stream = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val output = ImageIO.createImageOutputStream(stream)
    writer.output = output
    val params = writer.defaultWriteParam
    params.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
    params.compressionQuality = quality
    writer.write(null, javax.imageio.IIOImage(outputImage, null, null), params)
    output.close()
    writer.dispose()
    bytes = stream.toByteArray()
    if (bytes.size <= MAX_AVATAR_BYTES) break
  }
  require(bytes.size <= MAX_AVATAR_BYTES) { "图片压缩后仍超过 2 MB，请选择较简单的图片" }
  return PickedImage(
      bytes,
      image.fileName.substringBeforeLast('.', "avatar") + ".jpg",
      "image/jpeg",
  )
}

private fun orientImage(source: BufferedImage, orientation: Int): BufferedImage {
  val swap = orientation in 5..8
  val width = if (swap) source.height else source.width
  val height = if (swap) source.width else source.height
  if (orientation !in 2..8) return source
  val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
  for (y in 0 until height) for (x in 0 until width) {
    val (sx, sy) =
        when (orientation) {
          2 -> source.width - 1 - x to y
          3 -> source.width - 1 - x to source.height - 1 - y
          4 -> x to source.height - 1 - y
          5 -> y to x
          6 -> y to source.height - 1 - x
          7 -> source.width - 1 - y to source.height - 1 - x
          else -> source.width - 1 - y to x
        }
    result.setRGB(x, y, source.getRGB(sx, sy))
  }
  return result
}

private fun readExifOrientation(bytes: ByteArray): Int {
  if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 1
  var offset = 2
  while (offset + 4 < bytes.size && bytes[offset] == 0xFF.toByte()) {
    val marker = bytes[offset + 1].toInt() and 0xFF
    val length =
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    val start = offset + 4
    if (
        marker == 0xE1 &&
            length >= 16 &&
            start + length - 2 <= bytes.size &&
            bytes.copyOfRange(start, start + 6).decodeToString() == "Exif\u0000\u0000"
    ) {
      val tiff = start + 6
      val little = bytes[tiff] == 'I'.code.toByte()
      fun short(at: Int): Int =
          if (little) (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
          else ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
      fun int(at: Int): Int =
          if (little) short(at) or (short(at + 2) shl 16) else (short(at) shl 16) or short(at + 2)
      val ifd = tiff + int(tiff + 4)
      if (ifd + 2 <= bytes.size) {
        val count = short(ifd).coerceAtMost(512)
        for (entry in 0 until count) {
          val at = ifd + 2 + entry * 12
          if (at + 12 > bytes.size) break
          if (short(at) == 0x0112) return short(at + 8).takeIf { it in 1..8 } ?: 1
        }
      }
      return 1
    }
    if (marker in 0xD9..0xDA || length < 2) break
    offset += length + 2
  }
  return 1
}
