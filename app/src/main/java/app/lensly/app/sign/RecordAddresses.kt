package app.lensly.app.sign

import app.airvault.chains.algorand.AlgorandNetwork
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.ChainId
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.canonicalAddressOn
import app.lensly.app.chainOfAddress

/** The name an Algorand record carries for a request refused before any review named its network, and every name an Algorand record can carry. */
private const val ALGORAND_NETWORK = "Algorand"
private val ALGORAND_NETWORK_NAMES: Set<String> = AlgorandNetwork.entries.map { it.displayName }.toSet() + ALGORAND_NETWORK

/**
 * One address a Review Record shows: the label of the row it is in, the address as the record holds it, and the chain it is on.
 * [isOwnChange] marks a Bitcoin output that pays the owner's own change.
 */
data class RecordAddress(val label: String, val address: String, val chain: ChainId, val isOwnChange: Boolean = false)

/**
 * The chain the addresses of [record] are on, or null when the record names none and holds no address. A Bitcoin record is on Bitcoin. A
 * record with a chain id is on an EVM chain. A Solana and an Algorand record name their network, which is kept as its own name.
 */
fun recordChainOf(record: ReviewRecord): ChainId? = when {
    record.kind == ReviewKind.BITCOIN_TRANSACTION -> BITCOIN_FAMILY
    record.chainId != null -> EVM_FAMILY
    record.networkName == solanaManifest.displayName -> SOLANA_FAMILY
    record.networkName in ALGORAND_NETWORK_NAMES -> ALGORAND_FAMILY
    else -> listOfNotNull(record.address, record.to, record.tokenContract).firstNotNullOfOrNull(::chainOfAddress)
}

/**
 * The addresses [record] shows, in the order the detail shows them. A Bitcoin record gives each output with an address. Any other record
 * gives its account, its recipient and its token contract, then each fee and parameter row whose value is an address on the record's
 * chain. A value that is not an address on that chain is text, and it is not listed.
 */
fun recordAddressesOf(record: ReviewRecord): List<RecordAddress> {
    val chain = recordChainOf(record) ?: return emptyList()
    val outputs = record.bitcoin?.outputs.orEmpty().mapIndexedNotNull { index, output ->
        output.address?.takeIf { canonicalAddressOn(it, chain) != null }
            ?.let { RecordAddress("Output $index", it, chain, output.isOwnChange) }
    }
    val fields = listOfNotNull(
        record.address?.let { "Account" to it },
        record.to?.let { "To" to it },
        record.tokenContract?.let { "Token Contract" to it },
    )
    val rows = (fields + record.fee + record.params).mapNotNull { (label, value) ->
        value.takeIf { canonicalAddressOn(it, chain) != null }?.let { RecordAddress(label, it, chain) }
    }
    return outputs + rows
}
