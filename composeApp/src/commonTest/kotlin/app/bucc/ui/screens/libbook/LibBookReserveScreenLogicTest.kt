package app.bucc.ui.screens.libbook

import app.bucc.model.dto.LibBookSeatDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibBookReserveScreenLogicTest {
  @Test
  fun `a seat without geometry does not disable other seats on the map`() {
    val valid =
        LibBookSeatDto(
            "valid",
            status = "1",
            pointX = 10.0,
            pointY = 20.0,
            width = 2.0,
            height = 3.0,
        )
    val missing = LibBookSeatDto("missing", status = "1")
    assertTrue(libBookSeatMapHasCoordinates(listOf(valid, missing)))
    assertFalse(libBookSeatMapHasCoordinates(listOf(missing)))
    assertFalse(libBookSeatMapHasCoordinates(emptyList()))
  }

  @Test
  fun `visibleLibBookSeats keeps only reservable seats`() {
    val seats =
        listOf(
            LibBookSeatDto(id = "001", no = "001", status = "2", statusName = "使用中"),
            LibBookSeatDto(id = "002", no = "002", status = "1", statusName = "可预约"),
            LibBookSeatDto(id = "003", no = "003", status = "3", statusName = "临时离开"),
            LibBookSeatDto(id = "008", no = "008", status = "1", statusName = "可预约"),
        )

    assertEquals(listOf("002", "008"), visibleLibBookSeats(seats).map { it.no })
  }

  @Test
  fun `libBookAreaMapResource resolves only packaged maps`() {
    assertNotNull(libBookAreaMapResource("8"))
    assertNull(libBookAreaMapResource("999"))
    assertNull(libBookAreaMapResource(null))
  }

  @Test
  fun `map viewer transform clamps scale and clears pan at minimum zoom`() {
    val zoomed =
        updateLibBookMapViewerTransform(
            LibBookMapViewerTransform(),
            zoomChange = 8f,
            panChangeX = 24f,
            panChangeY = -16f,
        )

    assertEquals(5f, zoomed.scale)
    assertEquals(24f, zoomed.offsetX)
    assertEquals(-16f, zoomed.offsetY)

    val restored =
        updateLibBookMapViewerTransform(
            zoomed,
            zoomChange = 0.01f,
            panChangeX = 12f,
            panChangeY = 8f,
        )

    assertEquals(1f, restored.scale)
    assertEquals(0f, restored.offsetX)
    assertEquals(0f, restored.offsetY)
  }

  @Test
  fun `resetLibBookMapViewerTransform returns neutral viewer state`() {
    val transformed = LibBookMapViewerTransform(scale = 3f, offsetX = 120f, offsetY = -48f)

    assertEquals(LibBookMapViewerTransform(), resetLibBookMapViewerTransform(transformed))
  }
}
