package app.airvault.chains.evm.signer

/**
 * An eth-sign-request and what its data decodes to. A transaction dataType carries its decoded transaction, or the reason it
 * is refused. Typed data carries its parse result, the typed message or the reason it is refused. Other dataTypes carry neither.
 * [call] is set when a decoded transaction's calldata is a call [ContractCall] knows.
 * [multicall] is set when that calldata is a multicall whose calls the sandbox read, level by level.
 */
data class ParsedRequest(
    val request: EthSignRequest,
    val tx: UnsignedTx.Result?,
    val call: ContractCall?,
    val multicall: Multicall? = null,
    val typed: TypedDataParser.Result? = null,
)
