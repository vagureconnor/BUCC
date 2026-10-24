package app.bucc.ui.screens.finance

/** All native children use the same official navigation policy and one-shot order POST gate. */
internal class PaymentRequestGate(allowZfw: Boolean) {
  private val policy = PaymentWebNavigationPolicy(allowZfw)

  fun decision(url: String, method: String, isRoot: Boolean): Int =
      policy.decide(url, method, isRoot)

  fun allows(url: String, method: String, isRoot: Boolean): Boolean =
      decision(url, method, isRoot) == 1
}
