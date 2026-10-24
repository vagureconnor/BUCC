package app.bucc.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationBackTest {
  @Test
  fun `campus finance payment child consumes global back before app stack pop`() {
    val navigation = NavigationController().apply { navigateTo(AppScreen.CAMPUS_FINANCE) }
    var paymentPageClosed = false

    val handled =
        dispatchNavigationBack(AppScreen.CAMPUS_FINANCE, navigation) { paymentPageClosed = true }

    assertTrue(handled)
    assertTrue(paymentPageClosed)
    assertEquals(AppScreen.CAMPUS_FINANCE, navigation.currentScreen)
    assertEquals(2, navigation.navStack.size)
  }

  @Test
  fun `campus finance without payment child falls back to app stack pop`() {
    val navigation = NavigationController().apply { navigateTo(AppScreen.CAMPUS_FINANCE) }

    val handled = dispatchNavigationBack(AppScreen.CAMPUS_FINANCE, navigation, null)

    assertTrue(handled)
    assertFalse(navigation.navStack.contains(AppScreen.CAMPUS_FINANCE))
    assertEquals(AppScreen.HOME, navigation.currentScreen)
  }
}
