package app.lumio.pair

import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.lensly.app.export.PairingCodec
import app.lensly.core.frozen.FrozenFixtures
import app.lensly.core.ur.AlgoSignature
import app.lensly.core.ur.EthSignature
import app.lensly.core.ur.SolSignature
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.Test

/**
 * What a frozen Lensly wrote, read by this Lumio: its pairing, version 7 raw and Brotli-compressed, and the signed answer to each request.
 * Lumio keeps reading pairing version 7 for as long as a frozen Lensly exists, so a reader change that would refuse one fails here.
 */
class FrozenLenslyFixturesTest {
    @Test
    fun theFrozenPairingsDecodeToTheVault() {
        val raw = PairingCodec.fromUr(FrozenFixtures.ur("pairing-v7-raw"), null)
        val brotli = PairingCodec.fromUr(FrozenFixtures.ur("pairing-v7-brotli"), BrotliDecoder)
        for (payload in listOf(raw, brotli)) {
            assertEquals("Family vault", payload.name)
            assertEquals(14, payload.accounts.size)
            assertEquals(12, payload.addressBook.size)
        }
    }

    @Test
    fun theBrotliPairingIsRefusedWithoutItsDecoder() {
        assertFailsWith<IllegalArgumentException> { PairingCodec.fromUr(FrozenFixtures.ur("pairing-v7-brotli"), null) }
    }

    @Test
    fun everyFrozenAnswerDecodes() {
        assertEquals(1, BtcPsbt.fromUr(FrozenFixtures.ur("response-psbt")).inputs.size)
        assertEquals(1, BtcPsbt.fromUr(FrozenFixtures.ur("response-crypto-psbt")).inputs.size)
        assertIs<EthSignature.Result.Ok>(EthSignature.decode(FrozenFixtures.ur("response-eth")))
        assertIs<SolSignature.Result.Ok>(SolSignature.decode(FrozenFixtures.ur("response-sol")))
        assertIs<AlgoSignature.Result.Ok>(AlgoSignature.decode(FrozenFixtures.ur("response-algo")))
    }
}
