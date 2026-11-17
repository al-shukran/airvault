package app.lensly.app

import app.lensly.core.algorand.AlgoAddress
import app.lensly.core.btc.BtcNetwork
import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ScriptElt
import org.web3j.crypto.Keys

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")
private val SOLANA_ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

/** A Solana address is the base58 of a 32-byte public key. */
private const val SOLANA_KEY_BYTES = 32

/**
 * The EVM address [text] in its checksum form, or null when it is not one. An address in one letter case is taken as it is. A mixed-case
 * address must carry the right checksum.
 */
fun evmAddressOf(text: String): String? {
    if (!EVM_ADDRESS.matches(text)) return null
    val checksum = Keys.toChecksumAddress(text)
    val hex = text.substring(2)
    val mixed = hex != hex.lowercase() && hex != hex.uppercase()
    return if (mixed && checksum != text) null else checksum
}

/**
 * The Bitcoin address [text] as bitcoin-kmp writes it, on Mainnet or Testnet4, or null when it is not a valid address of either. Every
 * script type that bitcoin-kmp reads is valid. A bech32 address comes back in lower case, so its upper-case form, as a QR code gives it,
 * is the same address.
 */
fun btcAddressOf(text: String): String? {
    val (network, script) = btcScriptOf(text) ?: return null
    return Bitcoin.addressFromPublicKeyScript(network.chain.chainHash, script).right
}

/** The network of the Bitcoin address [text], or null when it is not a valid address of Mainnet or Testnet4. */
fun btcNetworkOf(text: String): BtcNetwork? = btcScriptOf(text)?.first

/** The network and the script of the Bitcoin address [text], or null when it is not a valid address of Mainnet or Testnet4. */
private fun btcScriptOf(text: String): Pair<BtcNetwork, List<ScriptElt>>? {
    if (text.isEmpty()) return null
    // A bech32 address is the same address in either case, and no base58 address starts with BC1 or TB1, so only these are lowered.
    val input = if (text.startsWith("BC1") || text.startsWith("TB1")) text.lowercase() else text
    for (network in BtcNetwork.entries) {
        val script = Bitcoin.addressToPublicKeyScript(network.chain.chainHash, input).right ?: continue
        return network to script
    }
    return null
}

/** The Solana address [text], or null when it is not one: base58 that decodes to a 32-byte public key. */
fun solanaAddressOf(text: String): String? {
    if (!SOLANA_ADDRESS.matches(text)) return null
    val key = runCatching { Base58.decode(text) }.getOrNull() ?: return null
    return if (key.size == SOLANA_KEY_BYTES) text else null
}

/** The Algorand address [text], or null when it is not one: 58 upper-case base32 characters with a matching checksum. */
fun algorandAddressOf(text: String): String? = text.takeIf { runCatching { AlgoAddress.decode(it) }.isSuccess }

/** The canonical form of [text] when it is a valid address on [chain], or null. This is the form the Address Book keeps. */
fun canonicalAddressOn(text: String, chain: AddressChain): String? = when (chain) {
    AddressChain.EVM -> evmAddressOf(text)
    AddressChain.BITCOIN -> btcAddressOf(text)
    AddressChain.SOLANA -> solanaAddressOf(text)
    AddressChain.ALGORAND -> algorandAddressOf(text)
}

/**
 * The chain [text] is a valid address of, or null when it is none. The four chains share no valid address: an EVM address starts with
 * 0x, which none of the others can, and the lengths and checksums of the others tell them apart.
 */
fun chainOfAddress(text: String): AddressChain? = AddressChain.entries.firstOrNull { canonicalAddressOn(text, it) != null }

/**
 * The canonical form of [text], trimmed, or null when it is not a valid address of Ethereum, Bitcoin, Solana or Algorand. An EVM address
 * is in its checksum form, and a Bitcoin address as bitcoin-kmp writes it. Solana and Algorand addresses are kept exactly as written.
 */
fun canonicalAddressOf(text: String): String? = text.trim().let { trimmed ->
    chainOfAddress(trimmed)?.let { canonicalAddressOn(trimmed, it) }
}
