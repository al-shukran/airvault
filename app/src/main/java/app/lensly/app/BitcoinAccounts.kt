package app.lensly.app

import app.airvault.core.signer.Path
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.airvault.chains.bitcoin.signer.BtcReview
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.PathStep

/**
 * A Bitcoin request reviewed against a wallet: the Bitcoin account it spends from, as its stored record, and the review the owner
 * reads before signing. It holds public data only.
 */
data class BitcoinReviewed(
    val account: BitcoinAccountRecord,
    val review: BtcReview,
    /** The account that signs, as the review names it: its name, its wallet's name and its first receive address. */
    val signer: SigningAccount,
    /** What the Address Book and the wallet's own Bitcoin accounts say of each output address, keyed by [addressKeyOf]. */
    val addressNotes: Map<String, AddressNotes> = emptyMap(),
)

/**
 * A Bitcoin account of a wallet, as the wallet's `bitcoin` record keeps it: its network, its Script Type and its account index, and
 * the first receive address, m/.../0/0, which the owner checks against the online wallet that imports the account. Every field is
 * public.
 *
 * An account is unique inside its wallet by its Script Type, network and index. The same index on two networks is two keys, since
 * each network has its own coin type in the path.
 *
 * [profile] is the name, colour, icon, label and group the owner gave the account, as an Ethereum account has them. A null field is the
 * default, and it is stored inside the wallet's Bitcoin record with the account.
 */
data class BitcoinAccountRecord(
    val network: BtcNetwork,
    val scriptType: ScriptType,
    val index: Int,
    val firstReceiveAddress: String,
    val profile: AccountProfile = AccountProfile(),
) {
    /** The identity of the account inside its wallet. */
    val key: Triple<ScriptType, BtcNetwork, Int>
        get() = Triple(scriptType, network, index)

    /** The derivation path m/purpose'/coin'/account' of this account, which a request names when it spends from the account. */
    val storedPath: Path
        get() = Path(
            listOf(
                PathStep(scriptType.purpose, hardened = true),
                PathStep(network.coinType, hardened = true),
                PathStep(index, hardened = true),
            ),
        )
}

/**
 * What a wallet's `bitcoin` record holds: its [accounts], and the [kept] profiles of accounts that were uninstalled, by account key. An
 * account that returns takes its kept profile back, so uninstalling the Bitcoin app does not lose a name, label or group. Only a profile that
 * is not the default is kept, and the oldest are dropped when more than 100 are kept or the record is full. Every field is public.
 */
data class BitcoinRecord(
    val accounts: List<BitcoinAccountRecord> = emptyList(),
    val kept: Map<Triple<ScriptType, BtcNetwork, Int>, AccountProfile> = emptyMap(),
)

/**
 * The records of the Bitcoin accounts of [scriptType] on [network] at [indices], derived from [seed]. Each one carries its first
 * receive address. [onProgress] receives the number of accounts derived so far, so a long run can show how far it has got.
 */
internal fun bitcoinAccountRecords(
    seed: ByteArray,
    network: BtcNetwork,
    scriptType: ScriptType,
    indices: IntRange,
    onProgress: (Int) -> Unit = {},
): List<BitcoinAccountRecord> = indices.mapIndexed { position, index ->
    val address = BtcAccount.fromSeed(seed, network, scriptType, index).receiveAddress(0)
    onProgress(position + 1)
    BitcoinAccountRecord(network, scriptType, index, address)
}

/** The index of the next Bitcoin account of [scriptType] on [network]: one past the highest one held, or 0 when there is none. */
internal fun nextBitcoinIndex(existing: List<BitcoinAccountRecord>, scriptType: ScriptType, network: BtcNetwork): Int =
    existing.filter { it.scriptType == scriptType && it.network == network }
        .maxOfOrNull { it.index }
        ?.plus(1)
        ?: 0

/** Why a Single Key wallet refuses a Bitcoin account. It has no seed to derive one from. */
internal const val SINGLE_KEY_NO_BITCOIN = "A Single Key wallet has no seed, so it cannot hold Bitcoin accounts."
