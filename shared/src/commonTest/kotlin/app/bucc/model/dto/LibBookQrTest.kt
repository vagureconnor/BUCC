package app.bucc.model.dto

import kotlin.test.*

class LibBookQrTest {
  private val base = "https://booking.lib.buaa.edu.cn/h5/index.html#"

  @Test
  fun `official printed and appointment routes resolve without executing an action`() {
    assertEquals(
        LibBookQrResult.Seat(LibBookQrTarget("8", "101", null)),
        parseLibBookReservationQr(base + "/booking?area_id=8&seat_id=101", "2026-10-17"),
    )
    assertEquals(
        LibBookQrResult.Seat(LibBookQrTarget("8", "101", "2026-10-17")),
        parseLibBookReservationQr(
            base + "/mo/seat/appointment?id=8&date=2026-10-17&seat_id=101",
            "2026-10-17",
        ),
    )
    assertEquals("LibBookQrTarget([redacted])", LibBookQrTarget("8", "101", null).toString())
  }

  @Test
  fun `unrelated origins protocols actions and unverified signed QR variants are refused`() {
    for (value in
        listOf(
            base.replace("booking.lib.buaa.edu.cn", "booking.lib.buaa.edu.cn.evil.invalid") +
                "/booking?area_id=8&seat_id=101",
            base.replace("https:", "http:") + "/booking?area_id=8&seat_id=101",
            base + "/scancheck?type=touch",
            base + "/booking?area_id=8&seat_id=101&type=leave",
            base + "/booking?area_id=8&seat_id=101&signature=opaque",
            base.replace("https://", "https://user:secret@") + "/booking?area_id=8&seat_id=101",
        )) {
      assertEquals(LibBookQrResult.Unsupported, parseLibBookReservationQr(value, "2026-10-17"))
    }
  }

  @Test
  fun `expired dates damaged and ambiguous ids do not resolve`() {
    assertEquals(
        LibBookQrResult.Expired,
        parseLibBookReservationQr(
            base + "/mo/seat/appointment?id=8&seat_id=101&date=2026-10-16",
            "2026-10-17",
        ),
    )
    for (query in
        listOf(
            "id=8&id=9&seat_id=101",
            "id=8&seat_id=101&date=2026-02-30",
            "id=8&seat_id=-1",
            "id=8",
        )) assertEquals(
        LibBookQrResult.Invalid,
        parseLibBookReservationQr(base + "/mo/seat/appointment?" + query, "2026-10-17"),
    )
  }
}
