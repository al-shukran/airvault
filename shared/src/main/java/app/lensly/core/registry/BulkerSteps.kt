package app.lensly.core.registry

import app.lensly.core.wallet.toHex

/**
 * Steps for `invoke(bytes32[],bytes[])` on the Compound V3 bulker that Ethereum mainnet uses. The bulker runs each action in order for the
 * signer. An action is named by an ASCII word, and its data is the ABI encoding of that action's arguments, read with the same bounded
 * decoder as a whitelisted call. Lensly reads three actions: supply an asset, supply the native coin, and withdraw the native coin, each on a
 * market it knows. Any other action, or a market it does not know, is shown as raw data, and the review warns. The names and layouts are
 * those of contracts/bulkers/BaseBulker.sol in compound-finance/comet at f766f515.
 */
internal object BulkerSteps {
    private const val BULKER = "0xa397a8c2086c554b531c02e29f3291c9704b00c7"
    private const val INVOKE_SELECTOR = "0x555029a6"
    private const val INVOKE_SIGNATURE = "invoke(bytes32[],bytes[])"
    private const val MAINNET = 1L
    private const val WETH_MAINNET = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
    private const val SELECTOR_BYTES = 4
    private const val ASCII_FROM = 0x20
    private const val ASCII_TO = 0x7e

    /** One action: the layout of its data, and the steps it reads from the decoded values, or null when it does not describe them. */
    private class Action(val layout: List<WhitelistParam>, val describe: (List<AbiValue>) -> CallSteps?)

    private val MARKET_AND_TO_AND_ASSET_AND_AMOUNT = listOf(
        WhitelistParam("comet", "address"),
        WhitelistParam("to", "address"),
        WhitelistParam("asset", "address"),
        WhitelistParam("amount", "uint256"),
    )
    private val MARKET_AND_TO_AND_AMOUNT = listOf(
        WhitelistParam("comet", "address"),
        WhitelistParam("to", "address"),
        WhitelistParam("amount", "uint256"),
    )

    /** The action a name stands for, with the call it belongs to, or null when Lensly does not read that name. */
    private fun actionFor(name: String?, call: StepCall): Action? = when (name) {
        "ACTION_SUPPLY_ASSET" -> Action(MARKET_AND_TO_AND_ASSET_AND_AMOUNT) { v -> supplyAsset(call, v) }
        "ACTION_SUPPLY_NATIVE_TOKEN" -> Action(MARKET_AND_TO_AND_AMOUNT) { v -> supplyNative(call, v) }
        "ACTION_WITHDRAW_NATIVE_TOKEN" -> Action(MARKET_AND_TO_AND_AMOUNT) { v -> withdrawNative(call, v) }
        else -> null
    }

    /** The steps of [call] when it is an approved bulker invoke on mainnet, and null for any other call. */
    fun describe(call: StepCall, selector: String, signature: String): CallSteps? {
        if (call.chainId != MAINNET || call.contract != BULKER || selector != INVOKE_SELECTOR || signature != INVOKE_SIGNATURE) return null
        val names = call.items(0)
        val data = call.items(1)
        if (names.size != data.size) {
            return CallSteps(
                emptyList(),
                listOf("The bulker call has ${names.size} actions and ${data.size} data items, so the bulker would revert"),
            )
        }
        if (names.isEmpty()) return CallSteps(listOf("No actions: the bulker does nothing"), emptyList())
        val lines = ArrayList<String>()
        val warnings = ArrayList<String>()
        names.indices.forEach { index ->
            val word = (names[index] as AbiValue.Word).hex
            val name = actionName(word)
            val raw = data[index].bytes().toHex()
            val action = actionFor(name, call)
            val steps = action?.let { readAndDescribe(it, data[index].bytes()) }
            if (steps == null) {
                val label = name ?: "0x$word"
                lines += if (action == null) {
                    "Unknown bulker action $label: raw input 0x$raw"
                } else {
                    "Bulker action $label could not be read: raw input 0x$raw"
                }
                warnings += "Lensly cannot read bulker action $label. Its raw input is under Details."
            } else {
                lines += steps.lines
                warnings += steps.warnings
            }
        }
        return CallSteps(lines, warnings)
    }

    /** The name in an action word: ASCII text, then zero bytes. Null when the word is not a name. */
    private fun actionName(word: String): String? {
        val bytes = hexBytesOrNull(word) ?: return null
        val name = bytes.takeWhile { it != 0.toByte() }.toByteArray()
        if (name.isEmpty() || name.any { (it.toInt() and 0xff) !in ASCII_FROM..ASCII_TO }) return null
        if (bytes.drop(name.size).any { it != 0.toByte() }) return null
        return String(name, Charsets.US_ASCII)
    }

    /** The steps of one action from its data, or null when the data does not decode or the action does not describe it. */
    private fun readAndDescribe(action: Action, input: ByteArray): CallSteps? {
        val values = when (val decoded = AbiDecoder.decode(ByteArray(SELECTOR_BYTES) + input, action.layout)) {
            is CallDecode.Ok -> decoded.params.map { it.value }
            is CallDecode.Refused -> return null
        }
        return action.describe(values)
    }

    private fun supplyAsset(call: StepCall, v: List<AbiValue>): CallSteps? {
        val market = ProtocolSteps.compoundMarket(MAINNET, v[0].address()) ?: return null
        val to = v[1].address()
        val asset = v[2].address()
        val collateral = if (asset == market.base) "" else " as collateral"
        val line = "Supply ${call.amount(v[3].word(), asset)}$collateral to Compound V3 (${market.symbol}) for ${call.who(to)}"
        return CallSteps(listOf(line), emptyList())
    }

    private fun supplyNative(call: StepCall, v: List<AbiValue>): CallSteps? {
        val market = ProtocolSteps.compoundMarket(MAINNET, v[0].address()) ?: return null
        val amount = v[2].word()
        // The bulker's whole-amount value repays the signer's whole borrow, which is not read, so only a fixed amount is shown.
        if (market.base != WETH_MAINNET || amount == ContractCall.MAX_UINT256) return null
        val line = "Supply ${call.nativeAmount(amount)} to Compound V3 (${market.symbol}) for ${call.who(v[1].address())}"
        return CallSteps(listOf(line), emptyList())
    }

    private fun withdrawNative(call: StepCall, v: List<AbiValue>): CallSteps? {
        val market = ProtocolSteps.compoundMarket(MAINNET, v[0].address()) ?: return null
        if (market.base != WETH_MAINNET) return null
        val to = v[1].address()
        val amount = v[2].word()
        val what = if (amount == ContractCall.MAX_UINT256) "all ${call.network.nativeSymbol}" else call.nativeAmount(amount)
        val warnings = if (to == call.from) emptyList() else listOf("This withdraws to ${shortAddress(to)}, not you")
        return CallSteps(listOf("Withdraw $what from Compound V3 (${market.symbol}) to ${call.who(to)}"), warnings)
    }
}
