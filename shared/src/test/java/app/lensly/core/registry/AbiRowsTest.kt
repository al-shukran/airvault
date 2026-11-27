package app.lensly.core.registry

import app.airvault.abi.AbiValue
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import kotlin.test.assertEquals
import org.junit.Test
import org.web3j.crypto.Keys

private const val ALICE = "0x1111111111111111111111111111111111111111"
private const val BOB = "0x2222222222222222222222222222222222222222"

/** The review rows of decoded arguments: what the owner reads before signing, one row per value. */
class AbiRowsTest {

    @Test
    fun aNamedTupleIsShownFieldByFieldUnderItsNames() {
        val hop = WhitelistParam(
            "hop",
            "(address,uint24)",
            components = listOf(WhitelistParam("token", "address"), WhitelistParam("fee", "uint24")),
        )
        val value = AbiValue.Items(listOf(AbiValue.Word(word(ALICE)), AbiValue.Word(word("0bb8"))))

        assertEquals(
            listOf("hop.token (address)" to Keys.toChecksumAddress(ALICE), "hop.fee (uint24)" to "3000"),
            AbiRows.of(DecodedParam(hop, value)),
        )
    }

    @Test
    fun anArrayOfTuplesIsACountThenOneRowPerField() {
        val routes = WhitelistParam(
            "routes",
            "(address,uint24)[]",
            components = listOf(WhitelistParam("token", "address"), WhitelistParam("fee", "uint24")),
        )
        val route = AbiValue.Items(listOf(AbiValue.Word(word(BOB)), AbiValue.Word(word("01f4"))))

        assertEquals(
            listOf(
                "routes (tuple[])" to "1 item",
                "routes[0].token (address)" to Keys.toChecksumAddress(BOB),
                "routes[0].fee (uint24)" to "500",
            ),
            AbiRows.of(DecodedParam(routes, AbiValue.Items(listOf(route)))),
        )
    }

    @Test
    fun aTupleFieldWithoutANameIsShownByItsPosition() {
        val pair = WhitelistParam("pair", "(uint256,bool)")
        val value = AbiValue.Items(listOf(AbiValue.Word(word("05")), AbiValue.Word(word("01"))))

        assertEquals(
            listOf("pair.0 (uint256)" to "5", "pair.1 (bool)" to "true"),
            AbiRows.of(DecodedParam(pair, value)),
        )
    }

    @Test
    fun anAddressArgumentItsArrayItemsAndItsTupleFieldsAreMarkedAsAddresses() {
        val owner = DecodedParam(WhitelistParam("owner", "address"), AbiValue.Word(word(ALICE)))
        val owners = DecodedParam(WhitelistParam("owners", "address[]"), AbiValue.Items(listOf(AbiValue.Word(word(BOB)))))
        val hop = DecodedParam(
            WhitelistParam(
                "hop",
                "(address,uint24)",
                components = listOf(WhitelistParam("token", "address"), WhitelistParam("fee", "uint24")),
            ),
            AbiValue.Items(listOf(AbiValue.Word(word(ALICE)), AbiValue.Word(word("0bb8")))),
        )
        val amount = DecodedParam(WhitelistParam("amount", "uint256"), AbiValue.Word(word("05")))

        assertEquals(listOf(true), AbiRows.typed(owner).map { it.isAddress })
        // The array's own row is its item count, and only its item is an address.
        assertEquals(listOf(false, true), AbiRows.typed(owners).map { it.isAddress })
        assertEquals(listOf(true, false), AbiRows.typed(hop).map { it.isAddress })
        assertEquals(listOf(false), AbiRows.typed(amount).map { it.isAddress })
    }

    @Test
    fun aTokenAmountTakesTheShownQuantityWhenTheRegistryHasTheToken() {
        val amount = DecodedParam(WhitelistParam("amountIn", "uint256", tokenRef = true), AbiValue.Word(word("0f4240")))

        assertEquals(listOf("amountIn (uint256)" to "1.0 USDC"), AbiRows.of(amount) { "1.0 USDC" })
        assertEquals(listOf("amountIn (uint256)" to "1000000"), AbiRows.of(amount) { null })
    }

    private fun word(hex: String): String = hex.padStart(64, '0')
}
