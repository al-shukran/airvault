package app.lumio.ui.receive

import app.lumio.receive.ReceiveAccount
import org.junit.Test
import kotlin.test.assertEquals

/** The line under a Receive account's name writes its address as the ends of its groups of 4. */
class ReceiveAccountLineTest {
    @Test
    fun theLineShowsTheLabelTheGroupAndTheEndsOfTheAddress() {
        val account = ReceiveAccount(name = "PulsarNova 5", label = "Trading", group = "DeFi", address = "0x102030405060708090112131415161718191122b")
        assertEquals("Trading · Group DeFi\n0x1020 3040 … 8191 122b", accountLine(account))
        assertEquals("0x1020 3040 … 8191 122b", accountLine(account.copy(label = null, group = null)))
    }
}
