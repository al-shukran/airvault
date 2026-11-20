package app.airvault.chains.bitcoin.signer

import app.airvault.chains.bitcoin.TESTNET_COIN_TYPE
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerRef
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.PathStep
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.psbt.Psbt

/** The refusal of a `crypto-psbt` whose PSBT does not parse. */
const val NOT_A_PSBT = "The Bitcoin request is not a valid PSBT."

/** Clears the hardened bit of a BIP32 child number, which a coin type carries in a path as 0x80000000 plus its value. */
private const val UNHARDENED_MASK = 0x7FFF_FFFFL

/**
 * The chain a PSBT names: the coin type of its first BIP32 path, on an input or an output, gives the network. Coin type 1 is testnet4,
 * and any other coin type, or a PSBT with no path, is mainnet.
 */
private fun chainOf(psbt: Psbt): ChainId {
    val keyPaths = psbt.inputs.flatMap { input ->
        input.derivationPaths.values.map { it.keyPath } + input.taprootDerivationPaths.values.map { it.keyPath }
    } + psbt.outputs.flatMap { output ->
        output.derivationPaths.values.map { it.keyPath } + output.taprootDerivationPaths.values.map { it.keyPath }
    }
    val coinType = keyPaths.firstOrNull()?.path?.getOrNull(1)?.and(UNHARDENED_MASK)
    val testnet = coinType == TESTNET_COIN_TYPE.toLong()
    return bitcoinManifest.networks.first { it.testnet == testnet }.chainId
}

/** The most accounts one request may name. The Parser Sandbox's wire carries at most this many signer references in a header. */
private const val MAX_ACCOUNTS = 16

/**
 * The accounts the inputs of [psbt] claim, in input order, each once: the master fingerprint of a key path with the account path, the first
 * [ACCOUNT_DEPTH] levels of that path. Outputs are left out, since a request asks for signatures on its inputs only.
 */
private fun accountRefsOf(psbt: Psbt): List<SignerRef> = psbt.inputs
    .flatMap { input ->
        input.derivationPaths.values.map { it.masterKeyFingerprint to it.keyPath } +
            input.taprootDerivationPaths.values.map { it.masterKeyFingerprint to it.keyPath }
    }
    .filter { (_, keyPath) -> keyPath.path.size >= ACCOUNT_DEPTH }
    .map { (fingerprint, keyPath) ->
        SignerRef(fingerprint.toInt(), keyPath.path.take(ACCOUNT_DEPTH).map { child -> pathStep(child) })
    }
    .distinct()

/** The path step of a BIP32 child number: a hardened child carries bit 31, and its index is the other bits. */
private fun pathStep(child: Long): PathStep =
    PathStep(index = (child and UNHARDENED_MASK).toInt(), hardened = DeterministicWallet.isHardened(child))

/**
 * The parser of crypto-psbt (spec 3.2). The body is the PSBT in its serialized form, which the app reads. The header names the network the
 * PSBT's derivation paths name, and mainnet when it names none. It also names the accounts the inputs claim, and the wallet is found from
 * those accounts, not from the PSBT alone.
 */
object BitcoinRequestParser : RequestParser {
    override fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome {
        if (urType != BtcPsbt.UR_TYPE) return ParseOutcome.Refused("UR type $urType is not ${BtcPsbt.UR_TYPE}")
        // The size is checked before the PSBT is decoded, as the other parsers do, so an oversized payload is never read.
        if (cbor.size > limits.maxPayloadBytes) return ParseOutcome.Refused("payload exceeds ${limits.maxPayloadBytes} bytes")
        val psbt = try {
            BtcPsbt.fromUr(UR(urType, cbor))
        } catch (e: IllegalArgumentException) {
            return ParseOutcome.Refused(NOT_A_PSBT)
        }
        val body = Psbt.write(psbt).toByteArray()
        if (body.size > limits.maxSignDataBytes) {
            return ParseOutcome.Refused("The PSBT is larger than ${limits.maxSignDataBytes} bytes.")
        }
        val accounts = accountRefsOf(psbt)
        if (accounts.size > MAX_ACCOUNTS) return ParseOutcome.Refused("The PSBT claims more than $MAX_ACCOUNTS Bitcoin accounts.")
        val header = RequestHeader(
            app = bitcoinManifest.id,
            chain = chainOf(psbt),
            kind = RequestKind.TRANSACTION,
            signers = accounts,
            requestId = null,
        )
        return ParseOutcome.Parsed(AppRequest(header, body))
    }
}
