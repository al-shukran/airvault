package app.airvault.chains.template.signer

import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestParser
import app.lensly.core.parse.ParserLimits

/** Parses the chain's UR payloads inside the Parser Sandbox, without throwing: a request or a refusal with its reason (spec 3.2). */
object TemplateRequestParser : RequestParser {
    override fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome =
        TODO("template: parse the request payload of each UR type (spec 3.2)")
}
