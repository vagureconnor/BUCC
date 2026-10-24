package app.bucc.probe

import app.bucc.api.plantform.PlatformImageRasterDecoder
import app.bucc.ui.common.util.PickedImage
import app.bucc.ui.common.util.compressAvatarImage
import kotlin.math.abs

internal var avatarProbeStep = 600

internal fun avatarCompressionProbe() {
  AvatarFixtures.images.forEachIndexed { index, fixture ->
    avatarProbeStep = 610 + index * 10
    val mime = if (fixture.name.endsWith(".png")) "image/png" else "image/jpeg"
    val original = fixture.bytes.copyOf()
    val result = compressAvatarImage(PickedImage(fixture.bytes, fixture.name, mime))
    check(fixture.bytes.contentEquals(original))
    check(result.mimeType == "image/jpeg" && result.fileName.endsWith(".jpg"))
    check(result.bytes.size in 1..2 * 1024 * 1024)
    avatarProbeStep++
    val decoded = PlatformImageRasterDecoder.decode(result.bytes)
    check(decoded.width == fixture.width && decoded.height == fixture.height)
    fixture.samples.forEachIndexed { sampleIndex, sample ->
      avatarProbeStep = 612 + index * 10 + sampleIndex
      val pixel = decoded.argb[sample.y * decoded.width + sample.x]
      check(pixel ushr 24 == 255)
      val tolerance = AvatarFixtures.EXPECTED_JPEG_COLOR_TOLERANCE
      check(abs((pixel shr 16 and 255) - sample.r) <= tolerance)
      check(abs((pixel shr 8 and 255) - sample.g) <= tolerance)
      check(abs((pixel and 255) - sample.b) <= tolerance)
    }
  }
  avatarProbeStep = 700
  check(
      runCatching {
            compressAvatarImage(PickedImage(byteArrayOf(1, 2), "invalid.jpg", "image/jpeg"))
          }
          .isFailure
  )
}
