package app.bucc.ui.screens.libbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibBookMapImageLoaderTest {
  private val region =
      LibBookMapSeatRegion("a", 0.0, 0.0, 50.0, 100.0, LibBookSeatMapStatus.Available)
  private val green = 0xff35b333.toInt()
  private val white = 0xffffffff.toInt()
  private val black = 0xff202020.toInt()
  private val gray = 0xffaaaaaa.toInt()

  @Test
  fun statePaletteSamplesSeatFillOnlyAndIgnoresNumbersLegendAndDesks() {
    val base = intArrayOf(green, green, white, black, green, green, green, green)
    val state =
        intArrayOf(
            gray,
            gray,
            0xfff00000.toInt(),
            0xfff00000.toInt(),
            0xfff00000.toInt(),
            0xfff00000.toInt(),
            0xfff00000.toInt(),
            0xfff00000.toInt(),
        )
    assertEquals(gray, sampleLibBookStateFill(base, 8, 1, state, 8, 1, listOf(region)))
  }

  @Test
  fun normalizedSamplingAcceptsLowerResolutionSameAspectSprite() {
    assertEquals(
        gray,
        sampleLibBookStateFill(
            IntArray(16) { green },
            4,
            4,
            IntArray(4) { gray },
            2,
            2,
            listOf(region.copy(width = 100.0)),
        ),
    )
  }

  @Test
  fun mismatchedAspectRatioAndInvalidBuffersFailSafely() {
    assertNull(
        sampleLibBookStateFill(
            IntArray(16) { green },
            4,
            4,
            IntArray(8) { gray },
            4,
            2,
            listOf(region),
        )
    )
    assertNull(sampleLibBookStateFill(IntArray(1), 4, 4, IntArray(16), 4, 4, listOf(region)))
  }

  @Test
  fun whiteTransparentAndDarkStatePixelsCannotBecomePalette() {
    listOf(white, 0x00aaaaaa, black).forEach { target ->
      assertNull(
          sampleLibBookStateFill(
              IntArray(4) { green },
              2,
              2,
              IntArray(4) { target },
              2,
              2,
              listOf(region),
          )
      )
    }
  }
}
