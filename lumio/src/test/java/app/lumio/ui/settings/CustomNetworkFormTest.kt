package app.lumio.ui.settings

import app.lumio.evm.CustomChainInput
import app.lumio.evm.CustomNetworkField
import app.lumio.evm.EvmRefusal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** An edit to the Add Custom Network form clears the refusal of the last check, so a message never stays over a value the owner has changed. */
class CustomNetworkFormTest {

    private val typed = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = "https://rpc.example.org", testnet = false)

    @Test
    fun editingTheRefusedFieldClearsItsErrorAndKeepsTheNewValue() {
        val form = CustomNetworkForm(typed, EvmRefusal(CustomNetworkField.RPC, "The endpoint did not answer."))

        val edited = form.edited(typed.copy(rpc = "https://rpc.example.org/v2"))

        assertNull(edited.refusal)
        assertEquals("https://rpc.example.org/v2", edited.input.rpc)
    }

    @Test
    fun editingAnyFieldClearsARefusalThatConcernsNoOneField() {
        val form = CustomNetworkForm(typed, EvmRefusal(null, "The change could not be saved. Try again."))

        assertNull(form.edited(typed.copy(name = "Example 2")).refusal)
    }
}
