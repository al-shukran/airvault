package app.lensly.core.btc

import app.airvault.chains.bitcoin.signer.BtcFeeThresholds
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.BtcRefusedException
import app.airvault.chains.bitcoin.signer.BtcReview
import app.airvault.chains.bitcoin.signer.BtcReviewInput
import app.airvault.chains.bitcoin.signer.BtcReviewOutput
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.airvault.chains.bitcoin.signer.warnings
import app.airvault.core.model.Curve
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector64
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Output
import fr.acinq.bitcoin.psbt.Psbt

/** Positions of the branch and the address index inside an account path m/purpose'/coin'/account'/branch/index. */
private const val BRANCH_LEVEL = 3
private const val INDEX_LEVEL = 4

/** The most satoshis any amount may be: the whole supply of 21 million BTC. Every sum of amounts stays within it, so none wraps. */
private const val MAX_MONEY = 2_100_000_000_000_000L

/** The sigversion of a legacy digest, and of a witness v0 digest (BIP143), as bitcoin-kmp's hashForSigning takes them. */
private const val SIGVERSION_BASE = 0
private const val SIGVERSION_WITNESS_V0 = 1

/**
 * Reviews a Bitcoin request against the owner's two policies and signs the inputs this Account owns.
 *
 * The signer holds no key material. It reads the Account's public keys and asks [keys], the Wallet's KeyAccess, for every signature.
 * [review] lists every input and output. [sign] reviews the request first, then signs only the inputs whose owner path lies under this
 * Account, each with the key at that path, and attaches each signature to the PSBT the way bitcoin-kmp's Psbt.sign does. A refused request
 * throws [BtcRefusedException] and nothing is signed.
 */
class BtcSigner(
    private val network: BtcNetwork,
    private val scriptType: ScriptType,
    /** The derivation index of the Account inside its Wallet, unique for its [scriptType]. */
    private val accountIndex: Int,
    /** The first 4 bytes of HASH160 of the Wallet's master public key, as an unsigned 32-bit value. */
    private val masterFingerprint: Long,
    /** The Wallet's keys. The signer asks them for public keys and signatures, and never sees a private key. */
    private val keys: KeyAccess,
    private val inputPolicy: InputVerificationPolicy = InputVerificationPolicy.STRICT,
    private val sourcePolicy: SigningSourcePolicy = SigningSourcePolicy.ANY,
    private val feeThresholds: BtcFeeThresholds = BtcFeeThresholds.DEFAULT,
) {
    /** The account path m/purpose'/coin'/[accountIndex]' of this Account, the path [BtcAccount] derives its keys under. */
    private val accountPath: KeyPath = scriptType.accountPath(network, accountIndex)

    /**
     * The review of [psbt]: every input with its verified amount and owner, every output with its own-change label, and the fee.
     * Throws [BtcRefusedException] when a rule refuses the request.
     */
    fun review(psbt: Psbt): BtcReview {
        if (sourcePolicy == SigningSourcePolicy.COMPANION_ONLY && !BtcPsbt.hasCompanionMarker(psbt)) {
            refuse(BtcRefusal.SourceExcluded)
        }
        val tx = psbt.global.tx
        // Two inputs that spend one output would count its amount twice in the fee, so the request is refused.
        val spentOutPoints = HashSet<OutPoint>()
        tx.txIn.forEachIndexed { index, txIn ->
            if (!spentOutPoints.add(txIn.outPoint)) refuse(BtcRefusal.DuplicateInput(index))
        }
        val inputs = tx.txIn.mapIndexed { index, txIn ->
            val input = psbt.inputs[index]
            val spent = spentOutput(index, txIn.outPoint, input)
            if (scriptTypeOf(spent.script, input) != scriptType) refuse(BtcRefusal.ScriptTypeNotUsed(index))
            val owner = ownerOf(index, input, spent.script)
            // An open non-witness input is signed with the legacy digest, which does not commit to the amount. A segwit output (P2WPKH,
            // nested P2WPKH or P2TR) is signed only from its witness UTXO, so it is refused here, before any signature is made.
            if (owner != null && input is Input.NonWitnessInput.PartiallySignedNonWitnessInput && isSegwit(spent.script, input)) {
                refuse(BtcRefusal.SegwitWithoutWitness(index))
            }
            if (owner != null && !sighashIsAll(input)) refuse(BtcRefusal.SighashNotAll(index))
            BtcReviewInput(index, txIn.outPoint, spent.amountSats, spent.verified, owner, txIn.sequence, riskMarked = BtcPsbt.isRiskMarked(input))
        }
        // The user's own Change Output is a change-path address of this Account within the known index range. Any other
        // path or index, including every receive address, is an outside Address.
        val changeIndexByScript = (0 until BtcAccountLayout.GAP_LIMIT).associate { index ->
            ByteVector(scriptPubKeyAt(BtcAccountLayout.CHANGE, index)).toHex() to index
        }
        val outputs = tx.txOut.mapIndexed { index, txOut ->
            val changeIndex = changeIndexByScript[txOut.publicKeyScript.toHex()]
            BtcReviewOutput(
                index = index,
                address = Bitcoin.addressFromPublicKeyScript(
                    network.chain.chainHash,
                    txOut.publicKeyScript.toByteArray(),
                ).right,
                amountSats = txOut.amount.sat,
                isOwnChange = changeIndex != null,
                changeIndex = changeIndex,
                claimsOwnKey = psbt.outputs.getOrNull(index)?.let { claimsFingerprint(it, masterFingerprint) } ?: false,
            )
        }
        val allVerified = inputs.all { it.amountVerified }
        val inputTotal = inputs.fold(0L) { total, input -> total.plusMoney(input.amountSats) }
        val outputTotal = outputs.fold(0L) { total, output -> total.plusMoney(output.amountSats) }
        if (allVerified && outputTotal > inputTotal) refuse(BtcRefusal.OutputsExceedInputs)
        val feeSats = if (allVerified) inputTotal - outputTotal else null
        val estimatedVsize = BtcFeeRate.estimatedVsize(tx, scriptType)
        // The share is taken of what leaves the owner's hands: every output that is not the owner's own change.
        val paidSats = outputs.filterNot { it.isOwnChange }.sumOf { it.amountSats }
        return BtcReview(
            inputPolicy = inputPolicy,
            inputs = inputs,
            outputs = outputs,
            feeSats = feeSats,
            requiresConfirmation = inputPolicy == InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING && !allVerified,
            estimatedVsize = estimatedVsize,
            lockTime = tx.lockTime,
            feeWarnings = feeSats?.let { feeThresholds.warnings(it, paidSats, estimatedVsize) }.orEmpty(),
        )
    }

    /** True when an ECDSA or taproot derivation of [output] names the master key [fingerprint]. */
    private fun claimsFingerprint(output: Output, fingerprint: Long): Boolean =
        output.derivationPaths.values.any { it.masterKeyFingerprint == fingerprint } ||
            output.taprootDerivationPaths.values.any { it.masterKeyFingerprint == fingerprint }

    /**
     * Reviews [psbt] and then signs every input this Account owns. Returns the review and the PSBT with the new signatures.
     *
     * A policy that needs confirmation of unverified amounts refuses unless [unverifiedAmountsConfirmed] is true, which the caller
     * sets only after the owner has confirmed those amounts on screen.
     */
    fun sign(psbt: Psbt, unverifiedAmountsConfirmed: Boolean = false): BtcSignedPsbt {
        val review = review(psbt)
        if (review.requiresConfirmation && !unverifiedAmountsConfirmed) refuse(BtcRefusal.ConfirmationRequired)
        val owned = review.inputs.filter { it.ownerPath != null }
        if (owned.isEmpty()) refuse(BtcRefusal.NothingToSign)
        val spent = spentOutputs(psbt)
        var signed = psbt
        for (input in owned) {
            val path = checkNotNull(input.ownerPath)
            if (signed.inputs[input.index].isFinalized()) refuse(BtcRefusal.AlreadyFinalized(input.index))
            signed = signInput(signed, input.index, path, spent)
        }
        return BtcSignedPsbt(review, signed)
    }

    /**
     * Signs input [index] of [psbt] with the key at [owner], and returns the PSBT with that signature added. The digest, and the field the
     * signature goes into, follow the kind of input, as Psbt.sign picks them: a non-witness input takes the legacy digest, a witness input
     * is signed as P2WPKH or as a P2TR key path, and any other input is refused.
     */
    private fun signInput(psbt: Psbt, index: Int, owner: KeyPath, spent: List<Spent>): Psbt {
        val path = toPath(owner.path)
        val publicKey = PublicKey(keys.publicKey(Curve.SECP256K1, path).bytes)
        val tx = psbt.global.tx
        val signed: Input = when (val input = psbt.inputs[index]) {
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> signNonWitness(index, input, path, publicKey, tx, spent[index])
            is Input.WitnessInput.PartiallySignedWitnessInput -> signWitness(index, input, path, publicKey, tx, spent)
            else -> refuse(BtcRefusal.SigningFailed(index, "the input is not one this signer signs"))
        }
        return psbt.copy(inputs = psbt.inputs.mapIndexed { i, old -> if (i == index) signed else old })
    }

    /**
     * Signs a non-witness input with the legacy digest. The script code is the input's redeem script, which must be the script the output
     * pays, and otherwise the output's own script.
     */
    private fun signNonWitness(
        index: Int,
        input: Input.NonWitnessInput.PartiallySignedNonWitnessInput,
        path: Path,
        publicKey: PublicKey,
        tx: Transaction,
        spent: Spent,
    ): Input {
        val redeem = input.redeemScript
        val scriptCode = if (redeem == null) {
            Script.write(Script.parse(ByteVector(spent.script)))
        } else {
            if (!Script.write(Script.pay2sh(redeem)).contentEquals(spent.script)) {
                refuse(BtcRefusal.SigningFailed(index, "the redeem script does not match the output"))
            }
            Script.write(redeem)
        }
        val sighash = input.sighashType ?: SigHash.SIGHASH_ALL
        val digest = tx.hashForSigning(index, scriptCode, sighash, Satoshi(spent.amountSats), SIGVERSION_BASE)
        return input.copy(partialSigs = input.partialSigs + partialSig(path, publicKey, digest, sighash))
    }

    /** Signs a witness input as P2WPKH, nested or direct, or as a P2TR key path. Any other script is refused. */
    private fun signWitness(
        index: Int,
        input: Input.WitnessInput.PartiallySignedWitnessInput,
        path: Path,
        publicKey: PublicKey,
        tx: Transaction,
        spent: List<Spent>,
    ): Input {
        val script = input.txOut.publicKeyScript.toByteArray()
        val redeem = input.redeemScript?.let { Script.write(it) }
        return when {
            Script.isPay2wpkh(script) -> signP2wpkh(index, input, script, path, publicKey, tx)
            Script.isPay2tr(script) -> signTaprootKeyPath(index, input, path, tx, spent)
            isNestedP2wpkh(script, input) -> signP2wpkh(index, input, checkNotNull(redeem), path, publicKey, tx)
            else -> refuse(BtcRefusal.SigningFailed(index, "the output's script is not one this signer signs"))
        }
    }

    /**
     * Signs a P2WPKH input whose witness program is [program]. The script code is the P2PKH script of the key hash in the program (BIP143),
     * and the digest takes the input's amount.
     */
    private fun signP2wpkh(
        index: Int,
        input: Input.WitnessInput.PartiallySignedWitnessInput,
        program: ByteArray,
        path: Path,
        publicKey: PublicKey,
        tx: Transaction,
    ): Input {
        val scriptCode = Script.write(Script.pay2pkh(program.copyOfRange(2, 22)))
        val sighash = input.sighashType ?: SigHash.SIGHASH_ALL
        val digest = tx.hashForSigning(index, scriptCode, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0)
        return input.copy(partialSigs = input.partialSigs + partialSig(path, publicKey, digest, sighash))
    }

    /**
     * Signs a P2TR key-path input: the BIP341 digest over the outputs of every input, tweaked with no script tree. The sighash is DEFAULT
     * unless the input names one, and a sighash other than DEFAULT is appended to the signature. An input without its internal key is
     * refused, as Psbt.sign refuses it.
     */
    private fun signTaprootKeyPath(
        index: Int,
        input: Input.WitnessInput.PartiallySignedWitnessInput,
        path: Path,
        tx: Transaction,
        spent: List<Spent>,
    ): Input {
        if (input.taprootInternalKey == null) refuse(BtcRefusal.SigningFailed(index, "the taproot input has no internal key"))
        val prevouts = spent.map { TxOut(Satoshi(it.amountSats), ByteVector(it.script)) }
        val sighash = input.sighashType ?: SigHash.SIGHASH_DEFAULT
        val digest = tx.hashForSigningTaprootKeyPath(index, prevouts, sighash)
        val signature = keys.signSchnorrBip340(path, digest.toByteArray(), null)
        val appended = if (sighash == SigHash.SIGHASH_DEFAULT) signature else signature + sighash.toByte()
        return input.copy(taprootKeySignature = ByteVector(appended))
    }

    /** The partial signature of [digest] by the key at [path]: the key's public key, and the DER signature with [sighash] appended. */
    private fun partialSig(path: Path, publicKey: PublicKey, digest: ByteArray, sighash: Int): Pair<PublicKey, ByteVector> {
        val signature = keys.signEcdsaSecp256k1(path, digest)
        val compact = (signature.r + signature.s).byteVector64()
        return publicKey to ByteVector(Transaction.encodeWitnessEcdsaSig(compact, sighash))
    }

    private class Spent(val amountSats: Long, val verified: Boolean, val script: ByteArray)

    /** The output each input of [psbt] spends, read the way [review] reads it. */
    private fun spentOutputs(psbt: Psbt): List<Spent> = psbt.global.tx.txIn.mapIndexed { index, txIn ->
        spentOutput(index, txIn.outPoint, psbt.inputs[index])
    }

    /**
     * The output an input spends. A previous transaction verifies the amount. A witness_utxo alone verifies nothing, and it is
     * accepted only under a weaker policy, for a segwit output.
     */
    private fun spentOutput(index: Int, outPoint: OutPoint, input: Input): Spent {
        val previous = input.nonWitnessUtxo
        val witness = input.witnessUtxo
        if (previous != null) {
            val spentIndex = outPoint.index
            if (previous.txid != outPoint.txid || spentIndex < 0 || spentIndex >= previous.txOut.size) {
                refuse(BtcRefusal.PreviousTransactionMismatch(index))
            }
            val output = previous.txOut[spentIndex.toInt()]
            if (witness != null && witness != output) refuse(BtcRefusal.PreviousTransactionMismatch(index))
            return Spent(output.amount.sat, verified = true, script = output.publicKeyScript.toByteArray())
        }
        val script = witness?.publicKeyScript?.toByteArray()
        if (witness == null || script == null || inputPolicy == InputVerificationPolicy.STRICT || !isSegwit(script, input)) {
            refuse(BtcRefusal.MissingPreviousTransaction(index))
        }
        return Spent(witness.amount.sat, verified = false, script = script)
    }

    /**
     * The path of the key that signs this input, or null when no derivation in the input names this wallet's master fingerprint.
     * A derivation that names the fingerprint must lie under this Account, and the key at its path must produce the spent script.
     */
    private fun ownerOf(index: Int, input: Input, script: ByteArray): KeyPath? {
        val claims = (
            input.derivationPaths.values.map { it.masterKeyFingerprint to it.keyPath } +
                input.taprootDerivationPaths.values.map { it.masterKeyFingerprint to it.keyPath }
            )
            .filter { (fingerprint, _) -> fingerprint == masterFingerprint }
            .map { (_, path) -> path }
            .distinct()
        if (claims.isEmpty()) return null
        if (claims.size > 1) refuse(BtcRefusal.InconsistentOwnership(index))
        val path = claims.single()
        if (path.path.size != 5 || path.path.take(3) != accountPath.path) refuse(BtcRefusal.InputOutsideAccount(index))
        val branch = path.path[BRANCH_LEVEL]
        val child = path.path[INDEX_LEVEL]
        if (branch != 0L && branch != 1L) refuse(BtcRefusal.InputOutsideAccount(index))
        if (DeterministicWallet.isHardened(child)) refuse(BtcRefusal.InputOutsideAccount(index))
        if (!scriptPubKeyAt(branch.toInt(), child.toInt()).contentEquals(script)) {
            refuse(BtcRefusal.InconsistentOwnership(index))
        }
        return path
    }

    /** The KeyAccess path m/purpose'/coin'/account'/[branch]/[index] of an address of this Account. */
    private fun pathOf(branch: Int, index: Int): Path = toPath(accountPath.path + listOf(branch.toLong(), index.toLong()))

    /** The public key at [branch]/[index] of this Account, from the key access. */
    private fun publicKeyAt(branch: Int, index: Int): PublicKey = PublicKey(keys.publicKey(Curve.SECP256K1, pathOf(branch, index)).bytes)

    /** The scriptPubKey of the address at [branch]/[index] of this Account, built from the public key the key access gives. */
    private fun scriptPubKeyAt(branch: Int, index: Int): ByteArray = scriptPubKeyOf(scriptType, publicKeyAt(branch, index))

    /** ALL, or DEFAULT for taproot, which commits to every input and output the way ALL does. Other types leave room to change outputs. */
    private fun sighashIsAll(input: Input): Boolean {
        val sighash = input.sighashType ?: return true
        return sighash == SigHash.SIGHASH_ALL ||
            (scriptType == ScriptType.TAPROOT && sighash == SigHash.SIGHASH_DEFAULT)
    }

    private fun isSegwit(script: ByteArray, input: Input): Boolean =
        Script.isPay2wpkh(script) || Script.isPay2tr(script) || isNestedP2wpkh(script, input)

    private fun isNestedP2wpkh(script: ByteArray, input: Input): Boolean {
        val redeem = input.redeemScript ?: return false
        return Script.isPay2sh(script) &&
            Script.isPay2wpkh(redeem) &&
            Script.write(Script.pay2sh(redeem)).contentEquals(script)
    }

    private fun scriptTypeOf(script: ByteArray, input: Input): ScriptType? = when {
        Script.isPay2pkh(script) -> ScriptType.LEGACY
        Script.isPay2wpkh(script) -> ScriptType.NATIVE_SEGWIT
        Script.isPay2tr(script) -> ScriptType.TAPROOT
        isNestedP2wpkh(script, input) -> ScriptType.NESTED_SEGWIT
        else -> null
    }

    private fun refuse(reason: BtcRefusal): Nothing = throw BtcRefusedException(reason)

    /** This sum plus [amount]. Refused unless both stay within 0..[MAX_MONEY], so the sum cannot wrap. */
    private fun Long.plusMoney(amount: Long): Long {
        if (amount !in 0..MAX_MONEY || this + amount > MAX_MONEY) refuse(BtcRefusal.AmountOutOfRange)
        return this + amount
    }
}

/**
 * The KeyAccess path of a BIP32 path given as its child numbers: a child whose bit 31 is set is hardened, and its index is the other bits.
 */
private fun toPath(children: List<Long>): Path = Path(
    children.map { child -> PathStep(index = (child and 0x7FFF_FFFFL).toInt(), hardened = DeterministicWallet.isHardened(child)) },
)

/** A signed request: the review the owner saw, and the PSBT with this Account's signatures added. */
data class BtcSignedPsbt(val review: BtcReview, val psbt: Psbt) {
    /** The signed PSBT as `ur:crypto-psbt`, the form the online side reads from a QR code. */
    val ur: String
        get() = BtcPsbt.encodeUr(psbt)
}

private fun Input.isFinalized(): Boolean =
    this is Input.FinalizedInputWithoutUtxo ||
        this is Input.WitnessInput.FinalizedWitnessInput ||
        this is Input.NonWitnessInput.FinalizedNonWitnessInput
