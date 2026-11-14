package app.lensly.core.btc

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.psbt.UpdateFailure
import fr.acinq.bitcoin.utils.Either

/**
 * Builds spends of accounts on testnet4 for the signer tests. A funding transaction pays one address of an account, and a
 * PSBT spends those outputs with the BIP32 derivation each Script Type needs. [finalize] and [verifies] are test-only: a signed
 * PSBT leaves the vault, and the online side finalizes it.
 */
internal object BtcFixtures {
    /** One funding output and the derivation that claims it. [claimedIndex] differs from [index] only in negative tests. */
    class Spend(
        val funding: Transaction,
        val account: BtcAccount,
        val branch: Int,
        val index: Int,
        val claimedIndex: Int = index,
    )

    /** A transaction whose output 0 pays [amount] to the address at [branch]/[index]. [salt] makes its txid unique. */
    fun funding(account: BtcAccount, branch: Int, index: Int, amount: Long, salt: Int = 1): Transaction = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(TxHash(ByteVector32(ByteArray(32) { salt.toByte() })), 0L), ByteVector.empty, 0xFFFFFFFFL)),
        txOut = listOf(TxOut(Satoshi(amount), ByteVector(account.scriptPubKey(branch, index)))),
        lockTime = 0L,
    )

    /**
     * An unsigned PSBT that spends each spend's output 0 and pays [outputs]. Each input carries the previous transaction
     * unless [withPreviousTransaction] is false, in which case only the witness UTXO is carried. [sighash] applies to every input.
     */
    fun psbt(
        spends: List<Spend>,
        outputs: List<TxOut>,
        sighash: Int? = null,
        withPreviousTransaction: Boolean = true,
    ): Psbt {
        val tx = Transaction(
            version = 2,
            txIn = spends.map { TxIn(OutPoint(it.funding.hash, 0L), ByteVector.empty, 0xFFFFFFFDL) },
            txOut = outputs,
            lockTime = 0L,
        )
        return spends.fold(Psbt(tx)) { psbt, spend -> psbt.withDerivedInput(spend, sighash, withPreviousTransaction) }
    }

    /** The previous outputs the spends consume, in input order. */
    fun prevouts(spends: List<Spend>): List<TxOut> = spends.map { it.funding.txOut[0] }

    /** Adds the UTXO and the derivation of [spend] to the input that spends its funding output. */
    private fun Psbt.withDerivedInput(spend: Spend, sighash: Int?, withPreviousTransaction: Boolean): Psbt {
        val account = spend.account
        val path = account.path.derive(spend.branch.toLong()).derive(spend.claimedIndex.toLong())
        val fingerprint = account.masterFingerprint
        val pub = account.publicKey(spend.branch, spend.index)
        val ecdsaPath = mapOf(pub to KeyPathWithMaster(fingerprint, path))
        val outPoint = OutPoint(spend.funding.hash, 0L)
        val output = spend.funding.txOut[0]
        val updated = when (account.scriptType) {
            ScriptType.LEGACY -> updateNonWitnessInput(
                spend.funding, 0, sighashType = sighash, derivationPaths = ecdsaPath,
            )
            ScriptType.NESTED_SEGWIT -> if (withPreviousTransaction) {
                updateWitnessInputTx(
                    spend.funding, 0, redeemScript = Script.pay2wpkh(pub), sighashType = sighash, derivationPaths = ecdsaPath,
                )
            } else {
                updateWitnessInput(
                    outPoint, output, redeemScript = Script.pay2wpkh(pub), sighashType = sighash, derivationPaths = ecdsaPath,
                )
            }
            ScriptType.NATIVE_SEGWIT -> if (withPreviousTransaction) {
                updateWitnessInputTx(spend.funding, 0, sighashType = sighash, derivationPaths = ecdsaPath)
            } else {
                updateWitnessInput(outPoint, output, sighashType = sighash, derivationPaths = ecdsaPath)
            }
            ScriptType.TAPROOT -> {
                val taproot = mapOf(
                    pub.xOnly() to TaprootBip32DerivationPath(emptyList(), fingerprint, path),
                )
                if (withPreviousTransaction) {
                    updateWitnessInputTx(
                        spend.funding, 0, sighashType = sighash,
                        taprootInternalKey = pub.xOnly(), taprootDerivationPaths = taproot,
                    )
                } else {
                    updateWitnessInput(
                        outPoint, output, sighashType = sighash,
                        taprootInternalKey = pub.xOnly(), taprootDerivationPaths = taproot,
                    )
                }
            }
        }
        return updated.unwrap()
    }

    /** Finalizes every input of [signed] for its Script Type and extracts the transaction. */
    fun finalize(signed: Psbt, spends: List<Spend>): Transaction {
        var psbt = signed
        spends.forEachIndexed { index, spend ->
            val pub = spend.account.publicKey(spend.branch, spend.index)
            psbt = when (spend.account.scriptType) {
                ScriptType.LEGACY -> {
                    val sig = checkNotNull(psbt.inputs[index].partialSigs[pub]) { "input $index has no signature" }
                    psbt.finalizeNonWitnessInput(index, listOf(OP_PUSHDATA(sig), OP_PUSHDATA(pub.value))).unwrap()
                }
                ScriptType.NESTED_SEGWIT, ScriptType.NATIVE_SEGWIT -> {
                    val sig = checkNotNull(psbt.inputs[index].partialSigs[pub]) { "input $index has no signature" }
                    psbt.finalizeWitnessInput(index, ScriptWitness(listOf(sig, pub.value))).unwrap()
                }
                ScriptType.TAPROOT -> {
                    val sig = checkNotNull(psbt.inputs[index].taprootKeySignature) { "input $index has no signature" }
                    psbt.finalizeWitnessInput(index, ScriptWitness(listOf(sig))).unwrap()
                }
            }
        }
        return psbt.extract().unwrap()
    }

    /** True when bitcoin-kmp's script interpreter accepts every input of [tx] against [prevouts]. */
    fun verifies(tx: Transaction, prevouts: List<TxOut>): Boolean = tx.txIn.indices.all { index ->
        runCatching {
            Script.Runner(
                Script.Context(tx, index, prevouts[index].amount, prevouts),
                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
            ).verifyScripts(
                tx.txIn[index].signatureScript.toByteArray(),
                prevouts[index].publicKeyScript.toByteArray(),
                tx.txIn[index].witness,
            )
        }.getOrDefault(false)
    }

    private fun <T> Either<UpdateFailure, T>.unwrap(): T = when (this) {
        is Either.Left -> error("PSBT step failed: ${this.value}")
        is Either.Right -> this.value
    }
}
