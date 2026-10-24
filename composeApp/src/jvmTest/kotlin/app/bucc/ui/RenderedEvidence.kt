package app.bucc.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Optional export of synthetic, rendered test content for local verification reports. */
internal fun saveRenderedEvidence(name: String, image: ImageBitmap) {
  val directory = System.getenv("BUCC_TEST_EVIDENCE_DIR")?.let(::File) ?: return
  directory.mkdirs()
  val pixels = image.toPixelMap()
  val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
  for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(
      x,
      y,
      pixels[x, y].toArgb(),
  )
  ImageIO.write(output, "png", File(directory, "$name.png"))
}
