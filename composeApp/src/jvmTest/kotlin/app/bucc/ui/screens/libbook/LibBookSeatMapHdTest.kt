package app.bucc.ui.screens.libbook

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibBookSeatMapHdTest {
  @Test
  fun actualHdMapRetainsSeatNumberNeighborAndLegendWhenOneSeatChanges() {
    val resource = "src/commonMain/composeResources/drawable/area_8.jpg"
    val image =
        ImageIO.read(listOf(File(resource), File("composeApp/$resource")).first { it.exists() })
    assertEquals(1921, image.width)
    assertEquals(1080, image.height)
    val original = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    // Seat 001 in the bundled HD plan. Seat 002 and the legend stay outside this region.
    val left = 307
    val top = 141
    val right = 336
    val bottom = 169
    val region =
        LibBookMapSeatRegion(
            "001",
            left * 100.0 / image.width,
            top * 100.0 / image.height,
            (right - left) * 100.0 / image.width,
            (bottom - top) * 100.0 / image.height,
            LibBookSeatMapStatus.Booked,
        )
    val output = recolorLibBookMapPixels(original, image.width, image.height, listOf(region))
    var changed = 0
    var whiteNumberPixels = 0
    for (index in original.indices) {
      val x = index % image.width
      val y = index / image.width
      val inside = x in left until right && y in top until bottom
      if (original[index] != output[index]) {
        changed++
        assertTrue(inside, "Only seat 001 may change; index=$index")
      }
      if (inside) {
        val rgb = original[index]
        val r = rgb ushr 16 and 255
        val g = rgb ushr 8 and 255
        val b = rgb and 255
        if (r > 220 && g > 220 && b > 220) {
          whiteNumberPixels++
          assertEquals(rgb, output[index], "Seat number must remain crisp")
        }
        if (r < 50 && g < 50 && b < 50)
            assertEquals(rgb, output[index], "Dark outlines are preserved")
      }
    }
    assertTrue(changed > 50, "Fixture contains the chair fill")
    assertTrue(whiteNumberPixels > 4, "Fixture contains the white seat number")
    System.getenv("BUCC_TEST_EVIDENCE_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
          val directory = File(path)
          assertTrue(directory.isDirectory || directory.mkdirs(), "Evidence directory is writable")
          assertTrue(ImageIO.write(image, "png", File(directory, "library-area-8-hd-original.png")))
          val changedImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
          changedImage.setRGB(0, 0, image.width, image.height, output, 0, image.width)
          assertTrue(
              ImageIO.write(
                  changedImage,
                  "png",
                  File(directory, "library-area-8-hd-seat-001-booked.png"),
              )
          )
        }
  }
}
