package app.bucc.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NotificationRouteInbox {
  private val mutableDestination = MutableStateFlow<String?>(null)
  val destination: StateFlow<String?> = mutableDestination.asStateFlow()

  fun request(destination: String) {
    if (destination == "todo" || destination == "schedule") mutableDestination.value = destination
  }

  fun consume() {
    mutableDestination.value = null
  }
}
