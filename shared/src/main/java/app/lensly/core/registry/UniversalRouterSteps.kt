package app.lensly.core.registry

import app.airvault.abi.AbiDecoder
import app.airvault.abi.AbiValue
import app.airvault.abi.CallDecode
import app.airvault.abi.WhitelistParam
import app.airvault.chains.evm.signer.ContractCall
import app.lensly.core.wallet.toHex
import java.math.BigInteger

/**
 * Steps for `execute(bytes,bytes[],uint256)` on the Uniswap Universal Router. The router runs each command in `commands` on the input at
 * the same index of `inputs`, in order. A command is its low six bits, and its top bit lets that command fail without failing the batch.
 * Each input is ABI-encoded for its command, and it is read with the same bounded decoder as a whitelisted call.
 *
 * Layouts and semantics follow Uniswap/universal-router at b122a8d2a5 for Universal Router V2 (`contracts/base/Dispatcher.sol`,
 * `contracts/UniversalRouter.sol`, `contracts/modules/Payments.sol`, `contracts/modules/Permit2Payments.sol`, the V2 and V3 swap routers,
 * `contracts/libraries/Commands.sol`), and at 95f11c0865 for V1.2. The two agree on commands 0x00 to 0x0e. They differ from 0x10 on: V2 runs
 * the v4 swap at 0x10, and V1.2 runs marketplace commands there, which Lensly does not read. Permit2 structs follow
 * Uniswap/permit2 `IAllowanceTransfer.sol`.
 *
 * A command Lensly does not read is shown as "Unknown command", with its raw input, and the review warns. A command whose input does not
 * decode is shown the same way. Neither hides the rest of the batch.
 */
internal object UniversalRouterSteps {
    private const val EXECUTE_SELECTOR = "0x3593564c"
    private const val EXECUTE_SIGNATURE = "execute(bytes,bytes[],uint256)"
    private const val MSG_SENDER = "0x0000000000000000000000000000000000000001"
    private const val ADDRESS_THIS = "0x0000000000000000000000000000000000000002"
    private const val COMMAND_MASK = 0x3f
    private const val ALLOW_REVERT_FLAG = 0x80
    private const val ALLOW_REVERT_NOTE = " (if it fails, the rest of the batch still runs)"
    private const val SELECTOR_BYTES = 4

    /** CONTRACT_BALANCE in ActionConstants: an amount that means the whole balance the router holds. */
    private val CONTRACT_BALANCE: BigInteger = BigInteger.ONE.shiftLeft(255)

    /** The two router deployments approved on Ethereum mainnet, by chain and address. */
    private enum class Dialect { V1_2, V2 }

    private val DEPLOYMENTS: Map<Pair<Long, String>, Dialect> = mapOf(
        (1L to "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad") to Dialect.V1_2,
        (1L to "0x66a9893cc07d91d95644aedd05d03f95e1dba8af") to Dialect.V2,
    )

    /** The input of each command, as the ABI of its layout reads it. Each is prefixed by a selector before it is decoded. */
    private val V3_EXACT_IN = params(
        "recipient" to "address", "amountIn" to "uint256", "amountOutMin" to "uint256", "path" to "bytes", "payerIsUser" to "bool",
    )
    private val V3_EXACT_OUT = params(
        "recipient" to "address", "amountOut" to "uint256", "amountInMax" to "uint256", "path" to "bytes", "payerIsUser" to "bool",
    )
    private val V2_EXACT_IN = params(
        "recipient" to "address", "amountIn" to "uint256", "amountOutMin" to "uint256", "path" to "address[]", "payerIsUser" to "bool",
    )
    private val V2_EXACT_OUT = params(
        "recipient" to "address", "amountOut" to "uint256", "amountInMax" to "uint256", "path" to "address[]", "payerIsUser" to "bool",
    )
    private val PERMIT2_TRANSFER = params("token" to "address", "recipient" to "address", "amount" to "uint160")
    private val PERMIT2_BATCH = params("permitBatch" to "((address,uint160,uint48,uint48)[],address,uint256)", "signature" to "bytes")
    private val PERMIT2_SINGLE = params("permitSingle" to "((address,uint160,uint48,uint48),address,uint256)", "signature" to "bytes")
    private val PERMIT2_TRANSFER_BATCH = params("batchDetails" to "(address,address,uint160,address)[]")
    private val TOKEN_RECIPIENT_AMOUNT = params("token" to "address", "recipient" to "address", "amount" to "uint256")
    private val RECIPIENT_AMOUNT = params("recipient" to "address", "amount" to "uint256")
    private val BALANCE_CHECK = params("owner" to "address", "token" to "address", "minBalance" to "uint256")

    /** The steps of [call] when it is a Universal Router `execute` call on an approved deployment, and null for any other call. */
    fun describe(call: StepCall, selector: String, signature: String): CallSteps? {
        val dialect = DEPLOYMENTS[call.chainId to call.contract] ?: return null
        if (selector != EXECUTE_SELECTOR || signature != EXECUTE_SIGNATURE) return null
        val commands = call.bytes(0)
        val inputs = call.items(1).map { it.bytes() }
        if (commands.size != inputs.size) {
            return CallSteps(
                emptyList(),
                listOf("The router call has ${commands.size} commands and ${inputs.size} inputs, so the router would revert"),
            )
        }
        if (commands.isEmpty()) return CallSteps(listOf("No commands: the router does nothing"), emptyList())
        val lines = ArrayList<String>()
        val warnings = ArrayList<String>()
        commands.indices.forEach { index ->
            val command = commands[index].toInt() and 0xff
            val type = command and COMMAND_MASK
            val note = if (command and ALLOW_REVERT_FLAG != 0 && failsWithoutRevert(dialect, type)) ALLOW_REVERT_NOTE else ""
            val step = stepOf(dialect, type, inputs[index], call)
            lines += step.lines.map { it + note }
            warnings += step.warnings
        }
        return CallSteps(lines, warnings)
    }

    /**
     * Whether a failure of this command comes back as success=false, which the allow-revert flag lets the batch pass. Every other command
     * reverts the batch on failure, flag or not. The Permit2 permits are a `.call` in V2 and a direct call in V1.2, which reverts. A command
     * Lensly does not read is not listed, so its step gets no note.
     */
    private fun failsWithoutRevert(dialect: Dialect, type: Int): Boolean = when (type) {
        0x0e -> true
        0x03, 0x0a -> dialect == Dialect.V2
        else -> false
    }

    /** The lines and warnings of one command. */
    private class Step(val lines: List<String>, val warnings: List<String> = emptyList())

    private fun stepOf(dialect: Dialect, type: Int, input: ByteArray, call: StepCall): Step {
        val command = "0x" + "%02x".format(type)
        val known = type in 0x00..0x06 || type in 0x08..0x0e || (dialect == Dialect.V2 && type == 0x10)
        if (!known) {
            return Step(
                listOf("Unknown command $command: raw input 0x${input.toHex()}"),
                listOf("Lensly cannot read command $command in this router call. Its raw input is under Details."),
            )
        }
        // A known command whose input does not decode is shown as raw input too, and it warns the same way.
        val step = try {
            decode(type, input, call)
        } catch (e: RuntimeException) {
            null
        }
        return step ?: Step(
            listOf("Command $command could not be read: raw input 0x${input.toHex()}"),
            listOf("Lensly cannot read the input of command $command. Its raw input is under Details."),
        )
    }

    private fun decode(type: Int, input: ByteArray, call: StepCall): Step? = when (type) {
        0x00 -> v3Swap(input, call, exactIn = true)
        0x01 -> v3Swap(input, call, exactIn = false)
        0x02 -> permit2Transfer(input, call)
        0x03 -> permitBatch(input, call)
        0x04 -> sweep(input, call)
        0x05 -> transfer(input, call)
        0x06 -> payPortion(input, call)
        0x08 -> v2Swap(input, call, exactIn = true)
        0x09 -> v2Swap(input, call, exactIn = false)
        0x0a -> permitSingle(input, call)
        0x0b -> wrapEth(input, call)
        0x0c -> unwrapWeth(input, call)
        0x0d -> permit2TransferBatch(input, call)
        0x0e -> balanceCheck(input, call)
        0x10 -> v4Swap(input, call)
        else -> null
    }

    private fun v3Swap(input: ByteArray, call: StepCall, exactIn: Boolean): Step? {
        val values = read(input, if (exactIn) V3_EXACT_IN else V3_EXACT_OUT) ?: return null
        val route = v3Route(values[3].bytes()) ?: return null
        val recipient = values[0].address()
        val amount = values[1].word()
        val limit = values[2].word()
        val payerIsUser = values[4].word() == BigInteger.ONE
        // An exact-output path lists the token it pays out first, so the trade reads from its other end.
        val trade = if (exactIn) route else route.reversed()
        val tokenIn = trade.tokens.first()
        val tokenOut = trade.tokens.last()
        val sentence = if (exactIn) {
            val spend = if (amount == CONTRACT_BALANCE) {
                "all ${call.symbolOf(tokenIn)} the router holds"
            } else {
                "exactly ${call.amount(amount, tokenIn)}"
            }
            "Swap $spend for ≥ ${call.amount(limit, tokenOut)}"
        } else {
            "Swap up to ${call.amount(limit, tokenIn)} for exactly ${call.amount(amount, tokenOut)}"
        }
        return Step(listOf(sentence + " " + via(call, "Uniswap v3", trade) + tail(call, recipient, payerIsUser)))
    }

    private fun v2Swap(input: ByteArray, call: StepCall, exactIn: Boolean): Step? {
        val values = read(input, if (exactIn) V2_EXACT_IN else V2_EXACT_OUT) ?: return null
        val path = values[3].items().map { it.address() }
        if (path.size < 2) return null
        val recipient = values[0].address()
        val amount = values[1].word()
        val limit = values[2].word()
        val payerIsUser = values[4].word() == BigInteger.ONE
        val tokenIn = path.first()
        val tokenOut = path.last()
        val sentence = when {
            !exactIn -> "Swap up to ${call.amount(limit, tokenIn)} for exactly ${call.amount(amount, tokenOut)}"
            amount.signum() == 0 -> "Swap the input already sent to the Uniswap v2 pool for ≥ ${call.amount(limit, tokenOut)}"
            amount == CONTRACT_BALANCE -> "Swap all ${call.symbolOf(tokenIn)} the router holds for ≥ ${call.amount(limit, tokenOut)}"
            else -> "Swap exactly ${call.amount(amount, tokenIn)} for ≥ ${call.amount(limit, tokenOut)}"
        }
        return Step(listOf(sentence + " " + via(call, "Uniswap v2", Route(path, emptyList())) + tail(call, recipient, payerIsUser)))
    }

    private fun permit2Transfer(input: ByteArray, call: StepCall): Step? {
        val values = read(input, PERMIT2_TRANSFER) ?: return null
        val token = values[0].address()
        val recipient = values[1].address()
        val amount = values[2].word()
        return Step(listOf("Transfer ${call.amount(amount, token)} from you to ${recipientName(call, recipient)} via Permit2"))
    }

    private fun permitBatch(input: ByteArray, call: StepCall): Step? {
        val values = read(input, PERMIT2_BATCH) ?: return null
        // The batch is (details, spender, sigDeadline), and each detail is (token, amount, expiration, nonce).
        val batch = values[0].items()
        return permitSteps(call, batch[1].address(), batch[0].items().map { it.items() })
    }

    private fun permitSingle(input: ByteArray, call: StepCall): Step? {
        val values = read(input, PERMIT2_SINGLE) ?: return null
        // The single permit is (details, spender, sigDeadline), and the details are (token, amount, expiration, nonce).
        val single = values[0].items()
        return permitSteps(call, single[1].address(), listOf(single[0].items()))
    }

    /** One approval line per permit detail. An amount that [ContractCall.isUnlimited] reads as unlimited warns. */
    private fun permitSteps(call: StepCall, spender: String, details: List<List<AbiValue>>): Step {
        val who = if (spender == call.contract) "the Universal Router" else shortAddress(spender)
        val lines = ArrayList<String>()
        val warnings = ArrayList<String>()
        details.forEach { detail ->
            val token = detail[0].address()
            val amount = detail[1].word()
            val until = untilText(detail[2].word())
            val unlimited = ContractCall.isUnlimited(amount)
            val quantity = if (unlimited) "unlimited" else call.quantity(amount, token)
            lines += "Approve $who to spend ${call.symbolOf(token)} amount $quantity until $until"
            if (unlimited) {
                warnings += "${ContractCall.UNLIMITED_APPROVAL_PREFIX} $who can spend all of your ${call.symbolOf(token)} until $until"
            }
        }
        if (details.isEmpty()) lines += "Approve nothing: the permit holds no details"
        return Step(lines, warnings)
    }

    private fun sweep(input: ByteArray, call: StepCall): Step? {
        val values = read(input, TOKEN_RECIPIENT_AMOUNT) ?: return null
        val token = values[0].address()
        val recipient = values[1].address()
        val minimum = values[2].word()
        val least = if (minimum.signum() == 0) "" else " (at least ${call.amount(minimum, token)})"
        return Step(listOf("Sweep all ${call.symbolOf(token)} held by the router to ${recipientName(call, recipient)}$least"))
    }

    private fun transfer(input: ByteArray, call: StepCall): Step? {
        val values = read(input, TOKEN_RECIPIENT_AMOUNT) ?: return null
        val token = values[0].address()
        val recipient = recipientName(call, values[1].address())
        val value = values[2].word()
        // The router reads CONTRACT_BALANCE for tokens only, so a native transfer of that value is shown as the amount it is.
        val sentence = if (token != NATIVE_COIN && value == CONTRACT_BALANCE) {
            "Transfer all ${call.symbolOf(token)} held by the router to $recipient"
        } else {
            "Transfer ${call.amount(value, token)} from the router to $recipient"
        }
        return Step(listOf(sentence))
    }

    private fun payPortion(input: ByteArray, call: StepCall): Step? {
        val values = read(input, TOKEN_RECIPIENT_AMOUNT) ?: return null
        val token = values[0].address()
        val recipient = values[1].address()
        val bips = values[2].word()
        val portion = percentOf(bips)
        return Step(listOf("Pay $portion of the router's ${call.symbolOf(token)} balance to ${recipientName(call, recipient)}"))
    }

    private fun wrapEth(input: ByteArray, call: StepCall): Step? {
        val values = read(input, RECIPIENT_AMOUNT) ?: return null
        val recipient = values[0].address()
        val amount = values[1].word()
        val what = if (amount == CONTRACT_BALANCE) {
            "all ${call.network.nativeSymbol} the router holds"
        } else {
            call.nativeAmount(amount)
        }
        val place = when (recipient) {
            ADDRESS_THIS -> " in the router"
            MSG_SENDER -> ""
            else -> if (recipient == call.from) "" else " for ${shortAddress(recipient)}"
        }
        return Step(listOf("Wrap $what → ${call.wrappedSymbol}$place"))
    }

    private fun unwrapWeth(input: ByteArray, call: StepCall): Step? {
        val values = read(input, RECIPIENT_AMOUNT) ?: return null
        val recipient = values[0].address()
        val minimum = values[1].word()
        val least = if (minimum.signum() == 0) "" else " (at least ${call.wrappedAmount(minimum)})"
        val to = when (recipient) {
            MSG_SENDER -> "for you"
            ADDRESS_THIS -> "in the router"
            else -> "for ${call.who(recipient)}"
        }
        return Step(listOf("Unwrap all ${call.wrappedSymbol} the router holds → ${call.network.nativeSymbol} $to$least"))
    }

    private fun permit2TransferBatch(input: ByteArray, call: StepCall): Step? {
        val values = read(input, PERMIT2_TRANSFER_BATCH) ?: return null
        // Each detail is (from, to, amount, token). Permit2 takes the recipient as it is, so it is not mapped as the router's flags are.
        val details = values[0].items().map { it.items() }
        if (details.isEmpty()) return Step(listOf("Transfer nothing via Permit2"))
        return Step(
            details.map { detail ->
                val from = call.who(detail[0].address())
                val to = call.who(detail[1].address())
                "Transfer ${call.amount(detail[2].word(), detail[3].address())} from $from to $to via Permit2"
            },
        )
    }

    private fun balanceCheck(input: ByteArray, call: StepCall): Step? {
        val values = read(input, BALANCE_CHECK) ?: return null
        val owner = call.who(values[0].address())
        val token = values[1].address()
        val minimum = values[2].word()
        return Step(listOf("Require $owner to hold at least ${call.amount(minimum, token)}"))
    }

    /** The steps of a v4 swap, one per action, or null when any of its actions cannot be read. */
    private fun v4Swap(input: ByteArray, call: StepCall): Step? =
        V4SwapSteps.describe(input, call)?.let { Step(it.lines, it.warnings) }

    /** The recipient a command names: the signer for the flag 0x…01, the router for 0x…02, and the address otherwise. */
    private fun recipientName(call: StepCall, address: String): String = when (address) {
        MSG_SENDER -> "you"
        ADDRESS_THIS -> "the router"
        else -> call.who(address)
    }

    /** What a swap says about its payer and its recipient, after its route: nothing when the payer is the router and the recipient is you. */
    private fun tail(call: StepCall, recipient: String, payerIsUser: Boolean): String {
        val notes = ArrayList<String>()
        if (payerIsUser) notes += "paid by you"
        when (recipient) {
            MSG_SENDER -> Unit
            ADDRESS_THIS -> notes += "kept in the router"
            else -> if (recipient != call.from) notes += "sent to ${shortAddress(recipient)}"
        }
        return notes.joinToString("") { ", $it" }
    }

    /** The inputs of a command, read with [layout]. The selector the decoder expects is four zero bytes. Null when they do not decode. */
    private fun read(input: ByteArray, layout: List<WhitelistParam>): List<AbiValue>? {
        val calldata = ByteArray(SELECTOR_BYTES) + input
        return (AbiDecoder.decode(calldata, layout) as? CallDecode.Ok)?.params?.map { it.value }
    }

    private fun params(vararg fields: Pair<String, String>): List<WhitelistParam> =
        fields.map { (name, type) -> WhitelistParam(name, type) }
}
