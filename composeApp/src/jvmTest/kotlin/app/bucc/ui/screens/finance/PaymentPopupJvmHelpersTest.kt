package app.bucc.ui.screens.finance

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentPopupJvmHelpersTest {
  @Test
  fun `screenshot resize caps longest edge and keeps QR readable`() {
    val code =
        MultiFormatWriter()
            .encode("https://pay.example/qr?token=fixture", BarcodeFormat.QR_CODE, 420, 420)
    val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = java.awt.Color.WHITE
    graphics.fillRect(0, 0, image.width, image.height)
    val qr = BufferedImage(code.width, code.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until code.height) for (x in 0 until code.width) qr.setRGB(
        x,
        y,
        if (code.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
    )
    graphics.drawImage(qr, 390, 190, null)
    graphics.dispose()

    val scaled = scaleScreenshot(image, 1024)
    assertTrue(maxOf(scaled.width, scaled.height) <= 1024)
    assertEquals(listOf("https://pay.example/qr?token=fixture"), decodePaymentQrCodes(scaled))
  }
}
