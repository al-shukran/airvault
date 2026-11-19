package app.airvault.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountIdTest {
    private val algorand = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")

    @Test
    fun splitsIntoItsChainAndAddress() {
        val id = AccountId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:AAAAY5HFKQ")

        assertEquals(algorand, id.chainId)
        assertEquals("AAAAY5HFKQ", id.address)
    }

    @Test
    fun buildsTheIdOfAnAddressOnAChain() {
        assertEquals("eip155:10143:0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B", AccountId.of(ChainId("eip155:10143"), "0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B").caip10)
    }

    @Test
    fun refusesAMalformedId() {
        assertFailsWith<IllegalArgumentException> { AccountId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k") }
        assertFailsWith<IllegalArgumentException> { AccountId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:ab/cd") }
        assertFailsWith<IllegalArgumentException> { AccountId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:") }
    }
}
