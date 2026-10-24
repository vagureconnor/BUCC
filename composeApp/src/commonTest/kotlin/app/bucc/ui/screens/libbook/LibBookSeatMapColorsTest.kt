package app.bucc.ui.screens.libbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LibBookSeatMapColorsTest {
  @Test
  fun changingOneSeatOnlyChangesItsChromaticFill() {
    val green = 0xff35b333.toInt()
    val white = 0xffffffff.toInt()
    val outline = 0xff263126.toInt()
    val brownDesk = 0xffb89e67.toInt()
    val pixels = intArrayOf(green, white, outline, green, brownDesk, green)
    val changed =
        recolorLibBookMapPixels(
            pixels,
            6,
            1,
            listOf(LibBookMapSeatRegion("one", 0.0, 0.0, 50.0, 100.0, LibBookSeatMapStatus.Booked)),
        )
    assertNotEquals(green, changed[0])
    assertEquals(white, changed[1], "White seat number stays legible")
    assertEquals(outline, changed[2], "Chair outline is untouched")
    assertEquals(green, changed[3], "Adjacent seat is untouched")
    assertEquals(brownDesk, changed[4])
    assertEquals(green, changed[5], "Legend outside seat rectangles is untouched")
    assertEquals(green, pixels[0], "Original HD bitmap is immutable")
  }

  @Test
  fun availableKeepsOriginalGreenOrBlueAndRestrictedOverridesStatus() {
    val original = intArrayOf(0xff35b333.toInt(), 0xff2575b8.toInt())
    assertEquals(
        original.toList(),
        recolorLibBookMapPixels(
                original,
                2,
                1,
                listOf(
                    LibBookMapSeatRegion(
                        "a",
                        0.0,
                        0.0,
                        100.0,
                        100.0,
                        LibBookSeatMapStatus.Available,
                    )
                ),
            )
            .toList(),
    )
    assertEquals(LibBookSeatMapStatus.Restricted, libBookSeatMapStatus("1", false))
    assertEquals(LibBookSeatMapStatus.Unknown, libBookSeatMapStatus("99", true))
    assertEquals(LibBookSeatMapStatus.Leave, libBookSeatMapStatus("7", true))
    listOf("2", "10", "11").forEach {
      assertEquals(LibBookSeatMapStatus.Booked, libBookSeatMapStatus(it, true))
    }
    listOf("6", "8", "9").forEach {
      assertEquals(LibBookSeatMapStatus.InUse, libBookSeatMapStatus(it, true))
    }
    listOf("3", "4", "5").forEach {
      assertEquals(LibBookSeatMapStatus.Closed, libBookSeatMapStatus(it, true))
    }
  }

  @Test
  fun hitTestingUsesImageFitZoomAndPanAndRejectsLetterboxAndUnavailable() {
    val viewport = LibBookMapViewport(400f, 400f, 200, 100, 2f, 30f, -10f)
    val seats =
        listOf(LibBookMapSeatRegion("a", 25.0, 25.0, 10.0, 10.0, LibBookSeatMapStatus.Available))
    val point = viewport.imageToView(60f, 30f)
    assertEquals("a", libBookSeatMapHit(seats, viewport, point.first, point.second))
    assertNull(libBookSeatMapHit(seats, viewport, 0f, 0f))
    assertNull(
        libBookSeatMapHit(
            seats.map { it.copy(status = LibBookSeatMapStatus.InUse) },
            viewport,
            point.first,
            point.second,
        )
    )
  }

  @Test
  fun invalidCoordinatesNeverProduceClickableRegions() {
    assertNull(libBookMapSeatRegion("missing", null, 2.0, 2.0, 2.0, "1", true))
    assertNull(libBookMapSeatRegion("outside", 99.0, 2.0, 4.0, 2.0, "1", true))
    assertNull(libBookMapSeatRegion("nan", Double.NaN, 2.0, 2.0, 2.0, "1", true))
    assertNull(libBookMapSeatRegion("zero", 1.0, 2.0, 0.0, 2.0, "1", true))
  }

  @Test
  fun pinchAndTrackpadUseSameZoomPanBoundsAndHitTransform() {
    val pinch = updateLibBookMapGesture(LibBookMapGestureTransform(), 2f, 12f, -8f)
    val trackpadFactor = libBookTrackpadMagnificationFactor(1.0)!!
    val trackpad = updateLibBookMapGesture(LibBookMapGestureTransform(), trackpadFactor, 12f, -8f)
    assertEquals(pinch, trackpad)
    assertEquals(5f, updateLibBookMapGesture(pinch, 20f).zoom)
    assertEquals(LibBookMapGestureTransform(), updateLibBookMapGesture(pinch, 0.1f))
    assertEquals(pinch, updateLibBookMapGesture(pinch, Float.NaN))
    assertNull(libBookTrackpadMagnificationFactor(Double.NaN))
    assertNull(libBookTrackpadMagnificationFactor(0.0))
    val viewport = LibBookMapViewport(400f, 400f, 200, 100, pinch.zoom, pinch.panX, pinch.panY)
    val region = LibBookMapSeatRegion("a", 25.0, 25.0, 10.0, 10.0, LibBookSeatMapStatus.Available)
    val point = viewport.imageToView(60f, 30f)
    assertEquals("a", libBookSeatMapHit(listOf(region), viewport, point.first, point.second))
  }

  @Test
  fun normalTwoFingerScrollingIsNotConvertedToZoom() {
    assertNull(libBookMapScrollZoomFactor(3f, false))
    assertNull(libBookMapScrollZoomFactor(0f, true))
    assertNull(libBookMapScrollZoomFactor(Float.NaN, true))
    kotlin.test.assertTrue(libBookMapScrollZoomFactor(-1f, true)!! > 1f)
    kotlin.test.assertTrue(libBookMapScrollZoomFactor(1f, true)!! < 1f)
  }

  @Test
  fun boldSelectionFrameStrokesStayConstantAcrossMapZoom() {
    val region = LibBookMapSeatRegion("a", 25.0, 25.0, 10.0, 10.0, LibBookSeatMapStatus.Available)
    val original = LibBookMapViewport(400f, 400f, 200, 100)
    val zoomed = original.copy(zoom = 5f, panX = 15f)
    val first = libBookMapSelectionFrame(region, original, 2f)
    val second = libBookMapSelectionFrame(region, zoomed, 2f)
    assertEquals(12f, first.outerStrokePx)
    assertEquals(6f, first.innerStrokePx)
    assertEquals(first.outerStrokePx, second.outerStrokePx)
    assertEquals(first.innerStrokePx, second.innerStrokePx)
    kotlin.test.assertTrue(second.width > first.width)
    val point = zoomed.imageToView(50f, 25f)
    assertEquals(point.first - 8f, second.left)
    assertEquals(point.second - 8f, second.top)
  }
}
