package app.lensly.app

import app.lensly.core.btc.BtcNetwork
import fr.acinq.bitcoin.Bitcoin
import org.web3j.crypto.Keys

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

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
 * The Bitcoin address [text] as bitcoin-kmp writes it, on Mainnet or Testnet4, or null when it is not a valid address of either. A bech32
 * address comes back in lower case, so its upper-case form, as a QR code gives it, is the same address.
 */
fun btcAddressOf(text: String): String? {
    if (text.isEmpty()) return null
    // A bech32 address is the same address in either case, and no base58 address starts with BC1 or TB1, so only these are lowered.
    val input = if (text.startsWith("BC1") || text.startsWith("TB1")) text.lowercase() else text
    for (network in BtcNetwork.entries) {
        val chainHash = network.chain.chainHash
        val script = Bitcoin.addressToPublicKeyScript(chainHash, input).right ?: continue
        return Bitcoin.addressFromPublicKeyScript(chainHash, script).right
    }
    return null
}

/** The canonical form of [text], trimmed, or null when it is neither a valid EVM address nor a valid Bitcoin address. */
fun canonicalAddressOf(text: String): String? = text.trim().let { evmAddressOf(it) ?: btcAddressOf(it) }
