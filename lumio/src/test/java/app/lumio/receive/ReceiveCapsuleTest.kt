package app.lumio.receive

import app.lumio.ui.home.AssetIconSpec
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The capsule under a Receive's address. An Algorand asset that the account is confirmed not to have opted in to asks for the opt-in. A read that is still
 * running or has failed, and an account that has opted in, each keep the usual Copy Address, so Receive never waits on the network.
 */
class ReceiveCapsuleTest {
    private val usdc = ReceiveCoin(
        symbol = "USDC",
        name = "USDC",
        subtitle = "",
        held = false,
        icon = AssetIconSpec.Letter("U"),
        networks = emptyList(),
        algorandAssetId = USDC_ID,
    )

    @Test
    fun aReadThatIsRunningOrHasFailedShowsCopyAddress() {
        assertEquals(ReceiveCapsule.CopyAddress, receiveCapsuleOf(usdc, optedIn = null))
    }

    @Test
    fun anAccountThatHasOptedInShowsCopyAddress() {
        assertEquals(ReceiveCapsule.CopyAddress, receiveCapsuleOf(usdc, optedIn = true))
    }

    @Test
    fun anAccountConfirmedNotOptedInShowsOptInFirst() {
        assertEquals(ReceiveCapsule.OptInFirst(USDC_ID), receiveCapsuleOf(usdc, optedIn = false))
    }

    @Test
    fun aCoinThatIsNotAnAlgorandAssetAlwaysShowsCopyAddress() {
        val native = usdc.copy(algorandAssetId = null)
        assertEquals(ReceiveCapsule.CopyAddress, receiveCapsuleOf(native, optedIn = false))
    }

    private companion object {
        const val USDC_ID = 10458941UL
    }
}
