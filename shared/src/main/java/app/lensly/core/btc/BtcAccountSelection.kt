package app.lensly.core.btc

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.psbt.Psbt

/**
 * The (master fingerprint, key path) pairs that the inputs of [psbt] name, from their ECDSA and taproot derivations. A PSBT names
 * the key that signs each input this way, as BIP174 asks.
 */
internal fun inputClaims(psbt: Psbt): List<Pair<Long, KeyPath>> = psbt.inputs.flatMap { input ->
    input.derivationPaths.values.map { it.masterKeyFingerprint to it.keyPath } +
        input.taprootDerivationPaths.values.map { it.masterKeyFingerprint to it.keyPath }
}

/** True when some input of [psbt] names this Account: its master fingerprint, and a path under this Account's path. */
fun BtcAccount.claims(psbt: Psbt): Boolean = inputClaims(psbt).any { (fingerprint, path) ->
    fingerprint == masterFingerprint && path.path.size >= ACCOUNT_LEVELS && path.path.take(ACCOUNT_LEVELS) == this.path.path
}

/**
 * Chooses the Bitcoin Account of a wallet that a PSBT spends from. The wallet is given as its Accounts, which share one master
 * fingerprint. Exactly one Account must claim an input, so a request that spends from two Accounts is refused, and so is one that
 * names only another network's keys.
 */
object BtcAccountSelection {
    /**
     * The Account of [accounts] that [psbt] spends from.
     *
     * @throws BtcRefusedException with [BtcRefusal.SeveralAccounts] when more than one Account claims an input,
     *   [BtcRefusal.NetworkMismatch] when the inputs name this wallet's key on a network it holds no Account on, and
     *   [BtcRefusal.NoStoredAccount] when no input names an Account of the wallet
     */
    fun select(accounts: List<BtcAccount>, psbt: Psbt): BtcAccount {
        val claiming = accounts.filter { it.claims(psbt) }
        if (claiming.size > 1) refuse(BtcRefusal.SeveralAccounts)
        claiming.singleOrNull()?.let { return it }
        val fingerprint = accounts.firstOrNull()?.masterFingerprint
        val heldCoinTypes = accounts.map { it.network.coinType.toLong() }.toSet()
        val namesOtherNetwork = fingerprint != null && inputClaims(psbt).any { (claimed, path) ->
            claimed == fingerprint && path.path.size >= ACCOUNT_LEVELS &&
                coinTypeOf(path) !in heldCoinTypes
        }
        refuse(if (namesOtherNetwork) BtcRefusal.NetworkMismatch else BtcRefusal.NoStoredAccount)
    }

    private fun coinTypeOf(path: KeyPath): Long = path.path[1] - DeterministicWallet.hardenedKeyIndex

    private fun refuse(reason: BtcRefusal): Nothing = throw BtcRefusedException(reason)
}

/** The levels of an account path, m/purpose'/coin'/account'. */
private const val ACCOUNT_LEVELS = 3
