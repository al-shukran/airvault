package app.airvault.chains.bitcoin.signer

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

/**
 * The parser of crypto-psbt (spec 3.2). The body is the PSBT in its serialized form, which the app reads. A PSBT names no network, so
 * the header names the Bitcoin mainnet, as an Algorand message does. The wallet is found from the PSBT's inputs, not from the header.
 */
object BitcoinRequestParser : RequestParser {
    override fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome {
        if (urType != BtcPsbt.UR_TYPE) return ParseOutcome.Refused("UR type $urType is not ${BtcPsbt.UR_TYPE}")
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
            chain = bitcoinManifest.networks.first { !it.testnet }.chainId,
            kind = RequestKind.TRANSACTION,
            signers = emptyList(),
            requestId = null,
        )
        return ParseOutcome.Parsed(AppRequest(header, body))
    }
}
