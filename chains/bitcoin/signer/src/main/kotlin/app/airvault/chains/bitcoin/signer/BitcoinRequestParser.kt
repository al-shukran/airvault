package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.RequestParser
import app.lensly.core.parse.ParserLimits
import com.sparrowwallet.hummingbird.UR
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

/**
 * The parser of crypto-psbt (spec 3.2). The body is the PSBT in its serialized form, which the app reads. The header names the network the
 * PSBT's derivation paths name, and mainnet when it names none. The wallet is found from the PSBT's inputs, not from the header.
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
        val header = RequestHeader(
            app = bitcoinManifest.id,
            chain = chainOf(psbt),
            kind = RequestKind.TRANSACTION,
            signers = emptyList(),
            requestId = null,
        )
        return ParseOutcome.Parsed(AppRequest(header, body))
    }
}
