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
