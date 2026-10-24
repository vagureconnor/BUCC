package app.bucc.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureIconUniquenessTest {
  @Test
  fun `different feature keys use unique vectors`() {
    assertEquals(FeatureIcon.entries.size, FeatureIcon.entries.map { it.vector }.distinct().size)
  }
}
