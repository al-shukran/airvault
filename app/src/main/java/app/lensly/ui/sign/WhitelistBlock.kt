package app.lensly.ui.sign

import androidx.compose.runtime.Composable
import app.lensly.core.registry.ProxyBinding
import app.lensly.ui.components.AddressText

/**
 * The implementation a whitelisted proxy is bound to, in full: its address, its code hash, or both. The refusal and the approved
 * contracts show it, and the transaction review shows it in its own style.
 */
@Composable
internal fun ProxyFacts(proxy: ProxyBinding) {
    proxy.implementationAddress?.let { address ->
        FactRow(label = "Approved Implementation") {
            AddressText(address = address, icon = true)
        }
    }
    proxy.implementationCodeHash?.let { hash ->
        FactRow(label = "Approved Code Hash") {
            MonoText(text = groupHex(hash.removePrefix("0x")))
        }
    }
}
