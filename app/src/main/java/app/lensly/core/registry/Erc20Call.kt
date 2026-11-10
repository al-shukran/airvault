package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger

/** The two ERC-20 calls the vault decodes for review before signing. */
sealed class Erc20Call {
    /** `transfer(address to, uint256 amount)`. [to] is a lowercase 0x address. */
    data class Transfer(val to: String, val amount: BigInteger) : Erc20Call()

    /** `approve(address spender, uint256 amount)`. [spender] is a lowercase 0x address. */
    data class Approve(val spender: String, val amount: BigInteger) : Erc20Call() {
        /** True when [amount] is 2^256 - 1, the common "unlimited allowance" value. */
        val unlimited: Boolean get() = amount == MAX_UINT256
    }

    companion object {
        private const val TRANSFER_SELECTOR = "a9059cbb"
        private const val APPROVE_SELECTOR = "095ea7b3"
        private const val CALLDATA_LENGTH = 4 + 32 + 32

        val MAX_UINT256: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

        /**
         * Decodes transfer or approve calldata. Returns null for any other selector, for any length
         * other than exactly 68 bytes, or when the address word has non-zero high bytes.
         */
        fun decode(data: ByteArray): Erc20Call? {
            if (data.size != CALLDATA_LENGTH) return null
            val selector = data.copyOfRange(0, 4).toHex()
            val address = decodeAddress(data) ?: return null
            val amount = BigInteger(1, data.copyOfRange(36, 68))
            return when (selector) {
                TRANSFER_SELECTOR -> Transfer(address, amount)
                APPROVE_SELECTOR -> Approve(address, amount)
                else -> null
            }
        }

        /** The address is the low 20 bytes of the word at offset 4; the top 12 bytes must be zero. */
        private fun decodeAddress(data: ByteArray): String? {
            for (i in 4 until 16) {
                if (data[i] != 0.toByte()) return null
            }
            return "0x" + data.copyOfRange(16, 36).toHex()
        }
    }
}
