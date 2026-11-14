package app.lensly.core.btc

import fr.acinq.bitcoin.Transaction

/**
 * The size of a request once it is signed, estimated from its unsigned transaction. The signatures are not made yet, so each
 * input adds the bytes its Script Type's signature takes. The estimate counts a 72-byte DER signature with its sighash byte and
 * a 33-byte compressed key, or a 65-byte Schnorr signature with its sighash byte. A signature of another length moves the
 * result by a few vbytes, so the fee rate it gives is an estimate, and the review says so.
 */
object BtcFeeRate {
    /** The scriptSig of a P2PKH input: a push of the 72-byte signature and a push of the 33-byte key. */
    private const val LEGACY_SCRIPT_SIG = 1 + 72 + 1 + 33

    /** The scriptSig of a P2SH-P2WPKH input: a push of its 22-byte witness program. */
    private const val NESTED_SCRIPT_SIG = 1 + 22

    /** The witness of a P2WPKH input: the item count, a push of the 72-byte signature and a push of the 33-byte key. */
    private const val P2WPKH_WITNESS = 1 + (1 + 72) + (1 + 33)

    /** The witness of a P2TR key-path input: the item count and a push of the 65-byte Schnorr signature. */
    private const val P2TR_WITNESS = 1 + (1 + 65)

    /** The segwit marker and flag, two weight units, which every segwit transaction carries once. */
    private const val SEGWIT_MARKER_WEIGHT = 2L

    /**
     * The estimated virtual size, in vbytes, of [tx] once every input of [scriptType] is signed. [tx] is the unsigned transaction of
     * a PSBT, whose inputs have empty scriptSigs, so its base size is the size before the signatures.
     */
    fun estimatedVsize(tx: Transaction, scriptType: ScriptType): Long {
        val inputs = tx.txIn.size.toLong()
        val baseWeight = 4L * Transaction.baseSize(tx).toLong()
        val perInputWeight = when (scriptType) {
            ScriptType.LEGACY -> 4L * LEGACY_SCRIPT_SIG
            ScriptType.NESTED_SEGWIT -> 4L * NESTED_SCRIPT_SIG + P2WPKH_WITNESS
            ScriptType.NATIVE_SEGWIT -> P2WPKH_WITNESS.toLong()
            ScriptType.TAPROOT -> P2TR_WITNESS.toLong()
        }
        val marker = if (scriptType == ScriptType.LEGACY || inputs == 0L) 0L else SEGWIT_MARKER_WEIGHT
        val weight = baseWeight + inputs * perInputWeight + marker
        return (weight + 3) / 4
    }
}
