package app.lensly.core.btc

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.utils.Either

/** BIP125 opt-in replace-by-fee, so a request can be bumped after it is sent. */
private const val RBF_SEQUENCE = 0xFFFFFFFDL

/**
 * One unspent output that a request spends: the transaction that created it, the index of the output inside that transaction, and the
 * key that owns it, as the [branch] (0 receive, 1 change) and the address [index] of an Account.
 */
class BtcCoin(
    val previous: Transaction,
    val vout: Int,
    val branch: Int,
    val index: Int,
) {
    init {
        require(vout in previous.txOut.indices) { "vout is not an output of the transaction" }
        require(branch == BtcAccount.RECEIVE || branch == BtcAccount.CHANGE) { "branch must be 0 (receive) or 1 (change)" }
        require(index >= 0) { "address index must be 0 or more" }
    }

    /** The value of the spent output, in satoshis. */
    val valueSats: Long get() = previous.txOut[vout].amount.sat
}

/**
 * The unsigned request that spends coins of one Account, built as a PSBT that Lensly's verifier reads. Lumio builds it from public data only:
 * it holds the account's xpub, never a key.
 */
object BtcUnsignedPsbt {
    /** The unsigned transaction that spends [coins] to [outputs]: version 2, with the replace-by-fee sequence on every input. */
    fun transaction(coins: List<BtcCoin>, outputs: List<TxOut>): Transaction = Transaction(
        version = 2,
        txIn = coins.map { TxIn(OutPoint(it.previous.hash, it.vout.toLong()), ByteVector.empty, RBF_SEQUENCE) },
        txOut = outputs,
        lockTime = 0L,
    )

    /**
     * The PSBT of the request. Each input carries the transaction that created its output, and the BIP32 derivation of the key that signs it,
     * which is what Lensly's verifier reads to check the amount and find the key. The Companion App's marker is added as well.
     */
    fun build(account: BtcWatchOnlyAccount, coins: List<BtcCoin>, outputs: List<TxOut>): Psbt {
        require(coins.isNotEmpty()) { "a request spends at least one coin" }
        val psbt = coins.foldIndexed(Psbt(transaction(coins, outputs))) { index, acc, coin -> acc.withInput(index, account, coin) }
        return BtcPsbt.withCompanionMarker(psbt)
    }

    private fun Psbt.withInput(index: Int, account: BtcWatchOnlyAccount, coin: BtcCoin): Psbt {
        val branch = coin.branch.toLong()
        val child = coin.index.toLong()
        val path = account.path.derive(branch).derive(child)
        val key = account.xpub.derivePublicKey(branch).derivePublicKey(child).publicKey
        val fingerprint = account.masterFingerprint
        val ecdsa = mapOf(key to KeyPathWithMaster(fingerprint, path))
        val updated = when (account.scriptType) {
            ScriptType.LEGACY -> updateNonWitnessInput(coin.previous, coin.vout, derivationPaths = ecdsa)
            ScriptType.NESTED_SEGWIT -> updateWitnessInputTx(
                coin.previous, coin.vout, redeemScript = Script.pay2wpkh(key), derivationPaths = ecdsa,
            )
            ScriptType.NATIVE_SEGWIT -> updateWitnessInputTx(coin.previous, coin.vout, derivationPaths = ecdsa)
            ScriptType.TAPROOT -> updateWitnessInputTx(
                coin.previous,
                coin.vout,
                taprootInternalKey = key.xOnly(),
                taprootDerivationPaths = mapOf(key.xOnly() to TaprootBip32DerivationPath(emptyList(), fingerprint, path)),
            )
        }
        return when (updated) {
            is Either.Left -> throw IllegalArgumentException("Input $index could not take its derivation: ${updated.value}")
            is Either.Right -> updated.value
        }
    }
}
