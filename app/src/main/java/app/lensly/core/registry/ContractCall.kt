package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger

/**
 * A contract call Lensly decodes for review before it signs. Calldata whose selector is not in [decode] is refused, and the
 * refusal shows its raw bytes. [name] and [signature] are the ABI names. Arguments keep calldata order, and an address is a
 * lowercase 0x string. A call says nothing about the registry: the caller decides what the contract is.
 */
sealed class ContractCall(val name: String, val signature: String) {

    /** `transfer(address to, uint256 value)`. */
    class Transfer(val to: String, val value: BigInteger) : ContractCall("transfer", "transfer(address,uint256)")

    /** `approve(address spender, uint256 value)`. */
    class Approve(val spender: String, val value: BigInteger) : ContractCall("approve", "approve(address,uint256)") {
        /** True when [value] is an unlimited allowance. */
        val unlimited: Boolean get() = isUnlimited(value)
    }

    /** `transferFrom(address from, address to, uint256 value)`. An ERC-20 transfer, or an ERC-721 token id. */
    class TransferFrom(val from: String, val to: String, val value: BigInteger) :
        ContractCall("transferFrom", "transferFrom(address,address,uint256)")

    /** `increaseAllowance(address spender, uint256 addedValue)`. */
    class IncreaseAllowance(val spender: String, val addedValue: BigInteger) :
        ContractCall("increaseAllowance", "increaseAllowance(address,uint256)") {
        /** True when [addedValue] makes the allowance unlimited. */
        val unlimited: Boolean get() = isUnlimited(addedValue)
    }

    /** `decreaseAllowance(address spender, uint256 subtractedValue)`. */
    class DecreaseAllowance(val spender: String, val subtractedValue: BigInteger) :
        ContractCall("decreaseAllowance", "decreaseAllowance(address,uint256)")

    /** `setApprovalForAll(address operator, bool approved)`, the ERC-721 and ERC-1155 operator grant. */
    class SetApprovalForAll(val operator: String, val approved: Boolean) :
        ContractCall("setApprovalForAll", "setApprovalForAll(address,bool)")

    /** `safeTransferFrom(address from, address to, uint256 tokenId)`, an ERC-721 transfer. */
    class SafeTransferFrom(val from: String, val to: String, val tokenId: BigInteger) :
        ContractCall("safeTransferFrom", "safeTransferFrom(address,address,uint256)")

    companion object {
        /** 2^256 - 1, the "unlimited allowance" value most contracts use. */
        val MAX_UINT256: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

        /** An allowance of 2^255 or more is unlimited for any token that exists. */
        private val UNLIMITED_FROM: BigInteger = BigInteger.ONE.shiftLeft(255)

        /** True when [amount] is an unlimited allowance: 2^255 or more. */
        fun isUnlimited(amount: BigInteger): Boolean = amount >= UNLIMITED_FROM

        private const val SELECTOR_BYTES = 4
        private const val WORD_BYTES = 32
        private const val TRANSFER = "a9059cbb"
        private const val APPROVE = "095ea7b3"
        private const val TRANSFER_FROM = "23b872dd"
        private const val INCREASE_ALLOWANCE = "39509351"
        private const val DECREASE_ALLOWANCE = "a457c2d7"
        private const val SET_APPROVAL_FOR_ALL = "a22cb465"
        private const val SAFE_TRANSFER_FROM = "42842e0e"

        /**
         * Decodes calldata with one of the selectors above. Returns null for any other selector, for an argument count other
         * than the signature's, for an address word whose top 12 bytes are not zero, and for a bool word that is not 0 or 1.
         */
        fun decode(data: ByteArray): ContractCall? {
            if (data.size < SELECTOR_BYTES) return null
            return when (data.copyOfRange(0, SELECTOR_BYTES).toHex()) {
                TRANSFER -> argsOf(data, 2)?.let { (to, value) -> address(to)?.let { Transfer(it, uint(value)) } }
                APPROVE -> argsOf(data, 2)?.let { (spender, value) -> address(spender)?.let { Approve(it, uint(value)) } }
                TRANSFER_FROM -> argsOf(data, 3)?.let { (from, to, value) ->
                    address(from)?.let { f -> address(to)?.let { t -> TransferFrom(f, t, uint(value)) } }
                }
                INCREASE_ALLOWANCE -> argsOf(data, 2)?.let { (spender, value) ->
                    address(spender)?.let { IncreaseAllowance(it, uint(value)) }
                }
                DECREASE_ALLOWANCE -> argsOf(data, 2)?.let { (spender, value) ->
                    address(spender)?.let { DecreaseAllowance(it, uint(value)) }
                }
                SET_APPROVAL_FOR_ALL -> argsOf(data, 2)?.let { (operator, approved) ->
                    address(operator)?.let { op -> bool(approved)?.let { SetApprovalForAll(op, it) } }
                }
                SAFE_TRANSFER_FROM -> argsOf(data, 3)?.let { (from, to, tokenId) ->
                    address(from)?.let { f -> address(to)?.let { t -> SafeTransferFrom(f, t, uint(tokenId)) } }
                }
                else -> null
            }
        }

        /** The [count] 32-byte words after the selector, or null unless the calldata holds exactly that many. */
        private fun argsOf(data: ByteArray, count: Int): List<ByteArray>? {
            if (data.size != SELECTOR_BYTES + count * WORD_BYTES) return null
            return List(count) { index ->
                val start = SELECTOR_BYTES + index * WORD_BYTES
                data.copyOfRange(start, start + WORD_BYTES)
            }
        }

        /** The address in a word: its low 20 bytes as 0x hex, or null when the top 12 bytes are not zero. */
        private fun address(word: ByteArray): String? =
            if (word.copyOfRange(0, WORD_BYTES - 20).all { it == 0.toByte() }) {
                "0x" + word.copyOfRange(WORD_BYTES - 20, WORD_BYTES).toHex()
            } else {
                null
            }

        private fun uint(word: ByteArray): BigInteger = BigInteger(1, word)

        private fun bool(word: ByteArray): Boolean? = when (uint(word)) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> null
        }
    }
}
