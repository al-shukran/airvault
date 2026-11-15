package app.lensly.app

/**
 * An App of a Wallet: the software inside it that manages its accounts and signs, as the apps of a hardware wallet do. [label] is
 * the name the owner reads, and [routeId] is the App's name in a route.
 */
enum class WalletApp(val label: String, val routeId: String) {
    BITCOIN(label = "Bitcoin", routeId = "bitcoin"),
    ETHEREUM(label = "Ethereum", routeId = "ethereum");

    companion object {
        /** The App that [routeId] names, or null when no App has that name. */
        fun fromRouteId(routeId: String?): WalletApp? = entries.firstOrNull { it.routeId == routeId }
    }
}
