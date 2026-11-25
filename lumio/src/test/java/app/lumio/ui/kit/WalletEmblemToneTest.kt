package app.lumio.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletEmblemToneTest {

    @Test
    fun theTonesFollowTheVaultOrder() {
        assertEquals(WalletEmblemTone.BLUE, WalletEmblemTone.at(0))
        assertEquals(WalletEmblemTone.STONE, WalletEmblemTone.at(1))
        assertEquals(WalletEmblemTone.BLUE, WalletEmblemTone.at(2))
    }

    @Test
    fun aWalletNotInTheListTakesTheFirstTone() {
        assertEquals(WalletEmblemTone.BLUE, WalletEmblemTone.at(-1))
    }
}
