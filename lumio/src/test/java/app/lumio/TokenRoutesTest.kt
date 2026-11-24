package app.lumio

import app.lumio.pair.PairedAccount
import kotlin.test.assertEquals
import org.junit.Test

/** The decisions of the Add Token page that its route makes from the owner's account and network picks. */
class TokenRoutesTest {
    private fun account(name: String?) = PairedAccount(index = 0, address = "0x6982508145454ce325ddbe47a25d4ec3d2311933", name = name, label = null, group = null)

    @Test
    fun theHoldingNamesTheAccountAndFallsBackToItsDefaultName() {
        assertEquals("PulsarNova Holds", holdingLabel(account("PulsarNova")))
        assertEquals("Account 1 Holds", holdingLabel(account(null)))
    }

    @Test
    fun pickingTheNetworkThePageAlreadyShowsChangesNothing() {
        val opened = mutableListOf<Long>()

        onNetworkPicked(current = 1L, picked = 1L) { opened += it }
        assertEquals(emptyList(), opened)

        onNetworkPicked(current = 1L, picked = 8453L) { opened += it }
        assertEquals(listOf(8453L), opened)
    }
}
