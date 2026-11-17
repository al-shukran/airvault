package app.lumio.pair

import app.lensly.app.export.DemoPairingGolden
import app.lensly.core.ur.UrScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo pairing as Lensly's encoder writes it with Brotli (DemoPairingGolden). Lumio reads the frames back through its decoder and its
 * importer, and the code stays within the size the pairing is designed for.
 *
 * Measured for version 5 (this test): the code is 4,318 bytes in 22 frames at 200 bytes per frame. The budget of 4,608 bytes leaves 290 bytes
 * of headroom, so a change that adds a fifth of a frame fails here. The version-5 demo has no Solana or Algorand keys, so its size did not
 * change from version 3. The worst case the UI allows is measured in PairingSizeTest, and it is far above this budget.
 */
class PairDemoBrotliGoldenTest {

    @Test
    fun lumioImportsTheBrotliFramesAsTheDemoPairingWithinTheSizeBudget() {
        val scanner = UrScanner()
        DemoPairingGolden.FRAMES_BROTLI.forEach { scanner.receive(it) }
        val ur = requireNotNull(scanner.result())

        val read = PairImport.of(ur)
        val expected = DemoPairingGolden.payload()

        assertTrue("the code is ${ur.cborBytes.size} bytes, over the 4608-byte budget", ur.cborBytes.size <= 4608)
        assertEquals(expected.fingerprint, read.fingerprint)
        assertEquals(expected.name, read.name)
        assertEquals(expected.addressBook, read.addressBook)
        assertEquals(expected.apps.map { it.accounts }, read.apps.map { it.accounts })
        assertEquals(
            expected.apps.map { app -> app.keys.map { it.publicKey.toList() } },
            read.apps.map { app -> app.keys.map { it.publicKey.toList() } },
        )
    }
}
