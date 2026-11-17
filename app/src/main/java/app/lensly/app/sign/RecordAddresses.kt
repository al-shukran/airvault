package app.lensly.app.sign

import app.lensly.app.AddressChain
import app.lensly.app.canonicalAddressOn
import app.lensly.app.chainOfAddress

/**
 * One address a Review Record shows: the label of the row it is in, the address as the record holds it, and the chain it is on.
 * [isOwnChange] marks a Bitcoin output that pays the owner's own change.
 */
data class RecordAddress(val label: String, val address: String, val chain: AddressChain, val isOwnChange: Boolean = false)

/**
 * The chain the addresses of [record] are on, or null when the record names none and holds no address. A Bitcoin record is on Bitcoin. A
 * record with a chain id is on an EVM chain. A Solana and an Algorand record name their network, which is kept as its own name.
 */
fun recordChainOf(record: ReviewRecord): AddressChain? = when {
    record.kind == ReviewKind.BITCOIN_TRANSACTION -> AddressChain.BITCOIN
    record.chainId != null -> AddressChain.EVM
    record.networkName == SOLANA_NETWORK -> AddressChain.SOLANA
    record.networkName in ALGORAND_NETWORK_NAMES -> AddressChain.ALGORAND
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
        record.tokenContract?.let { "Token contract" to it },
    )
    val rows = (fields + record.fee + record.params).mapNotNull { (label, value) ->
        value.takeIf { canonicalAddressOn(it, chain) != null }?.let { RecordAddress(label, it, chain) }
    }
    return outputs + rows
}
