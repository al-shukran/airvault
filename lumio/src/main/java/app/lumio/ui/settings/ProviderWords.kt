package app.lumio.ui.settings

import app.lumio.data.provider.Trait

/**
 * The word a provider's status reads as on the Clear Air pages, with its glyph: Working, Rate-Limited or Off. The glyph comes with the word, so the status
 * never rests on colour alone. A provider that is Off for want of a key reads Off here, and [providerStatusNote] says why.
 */
fun providerStatusWord(status: ProviderStatus): String = when (status) {
    ProviderStatus.Ready, is ProviderStatus.Healthy -> "✓ Working"
    is ProviderStatus.CircuitOpen, is ProviderStatus.CoolingDown -> "◆ Rate-Limited"
    ProviderStatus.Off, ProviderStatus.NotActive, ProviderStatus.NeedsKey -> "– Off"
}

/** The line under the status word when the owner can act on it: a provider that is Off for want of a key says so. */
fun providerStatusNote(status: ProviderStatus): String? = if (status == ProviderStatus.NeedsKey) "Needs API Key" else null

/** The status word and, when there is one, the line that says why: Working, Rate-Limited or Off, as the Clear Air pages read them. */
fun providerStatusLine(status: ProviderStatus): String = listOfNotNull(providerStatusWord(status), providerStatusNote(status)).joinToString(" · ")

/** The name a provider goes by on the Clear Air pages. A provider with no name here shows its catalogue ID. */
fun providerName(id: String): String = when (id) {
    "blockchair" -> "Blockchair"
    "3xpl" -> "3xpl"
    "routescan" -> "Routescan"
    "etherscan" -> "Etherscan"
    "blockscout" -> "Blockscout"
    "mempool" -> "mempool.space"
    "blockstream" -> "Blockstream"
    "coingecko" -> "CoinGecko"
    "defillama" -> "DefiLlama"
    else -> id
}

/**
 * The factual tags of a provider's page. "Works without an API Key" is stated only when the provider requires no key, and Blockscout is the one provider
 * the page calls anonymous. No other claim about a provider's privacy or MEV protection is shown.
 */
fun providerTags(id: String, traits: Set<Trait>): List<String> = buildList {
    if (Trait.KEY_REQUIRED !in traits) add("Works without an API Key")
    if (id == "blockscout") add("Anonymous")
}

/** How many network names a provider's page lists before it counts the rest. */
private const val NAMES_SHOWN = 4

/**
 * The networks a provider serves, as the page words them: the first four names in the catalogue's order, then how many are left. "Ethereum, Base and
 * Arbitrum" for three networks, and "Ethereum, Base, Arbitrum, Optimism and 10 more" for fourteen.
 */
fun networkSummary(names: List<String>): String {
    val shown = names.take(NAMES_SHOWN)
    val rest = names.size - shown.size
    return when {
        rest > 0 -> "${shown.joinToString(", ")} and $rest more"
        shown.size <= 1 -> shown.joinToString()
        else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
    }
}
