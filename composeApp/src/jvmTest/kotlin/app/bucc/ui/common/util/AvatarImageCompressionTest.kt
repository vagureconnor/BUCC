package app.bucc.ui.common.util

import app.bucc.api.storage.MAX_AVATAR_BYTES
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AvatarImageCompressionTest {
  @Test
  fun `small valid image is stored as jpeg without exceeding size or dimensions`() {
    val source = BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB)
    val stream = ByteArrayOutputStream()
    ImageIO.write(source, "png", stream)

    val compressed =
        compressAvatarImage(PickedImage(stream.toByteArray(), "avatar.png", "image/png"))
    val decoded = ImageIO.read(compressed.bytes.inputStream())

    assertEquals("image/jpeg", compressed.mimeType)
    assertTrue(compressed.bytes.size <= MAX_AVATAR_BYTES)
    assertTrue(maxOf(decoded.width, decoded.height) <= 1024)
  }

  @Test
  fun `large image is resized and encoded under local storage limit`() {
    val source = BufferedImage(3000, 1600, BufferedImage.TYPE_INT_RGB)
    val graphics = source.createGraphics()
    graphics.color = Color(20, 100, 200)
    graphics.fillRect(0, 0, source.width, source.height)
    graphics.dispose()
    val stream = ByteArrayOutputStream()
    ImageIO.write(source, "png", stream)

    val compressed =
        compressAvatarImage(PickedImage(stream.toByteArray(), "portrait.png", "image/png"))
    val decoded = ImageIO.read(compressed.bytes.inputStream())

    assertEquals("image/jpeg", compressed.mimeType)
    assertEquals("portrait.jpg", compressed.fileName)
    assertTrue(maxOf(decoded.width, decoded.height) <= 1024)
    assertTrue(compressed.bytes.size <= MAX_AVATAR_BYTES)
  }

  @Test
  fun `rejects source larger than 20 MB before decoding`() {
    val oversized = ByteArray(MAX_AVATAR_INPUT_BYTES + 1)
    val error =
        assertFailsWith<IllegalArgumentException> {
          compressAvatarImage(PickedImage(oversized, "large.jpg", "image/jpeg"))
        }
    assertTrue(error.message.orEmpty().contains("20 MB"))
  }

  @Test
  fun `rejects bytes that are not a decodable image`() {
    assertFailsWith<IllegalArgumentException> {
      compressAvatarImage(PickedImage(byteArrayOf(1, 2, 3, 4), "invalid.jpg", "image/jpeg"))
    }
  }

  @Test
  fun `jpeg exif rotation is normalized before resizing`() {
    val source = BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB)
    val stream = ByteArrayOutputStream()
    ImageIO.write(source, "jpeg", stream)
    val jpeg = stream.toByteArray()
    val exif =
        byteArrayOf(
            0x45,
            0x78,
            0x69,
            0x66,
            0,
            0, // Exif\0\0
            0x49,
            0x49,
            0x2A,
            0,
            8,
            0,
            0,
            0, // Little-endian TIFF header
            1,
            0,
            0x12,
            1,
            3,
            0,
            1,
            0,
            0,
            0,
            6,
            0,
            0,
            0,
            0,
            0,
            0,
            0, // Orientation 6
        )
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (exif.size + 2).toByte()) + exif
    val withExif = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + jpeg.copyOfRange(2, jpeg.size)

    val compressed = compressAvatarImage(PickedImage(withExif, "rotated.jpg", "image/jpeg"))
    val decoded = ImageIO.read(compressed.bytes.inputStream())

    assertTrue(decoded.height > decoded.width)
    assertEquals(1024, decoded.height)
  }
}
