package app.bucc.ui

import app.bucc.ui.screens.grade.gradeTrendAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradeTrendTest {
  @Test
  fun smallChangesRemainVisibleWithinBoundedAxis() {
    val (min, max) = gradeTrendAxis(listOf(3.5, 3.52, 3.55), 4.0, 0.4)
    assertTrue(min < 3.5 && max > 3.55)
    assertTrue(max - min < 0.1)
  }

  @Test
  fun emptyConstantAndBoundaryValuesHaveNonzeroFiniteAxis() {
    assertEquals(0.0 to 4.0, gradeTrendAxis(emptyList(), 4.0, 0.4))
    for (value in listOf(0.0, 2.0, 4.0)) {
      val (min, max) = gradeTrendAxis(listOf(value, value), 4.0, 0.4)
      assertTrue(min >= 0 && max <= 4 && min < max)
      assertTrue(value in min..max)
    }
    assertEquals(0.0 to 4.0, gradeTrendAxis(listOf(Double.NaN), 4.0, 0.4))
  }
}
