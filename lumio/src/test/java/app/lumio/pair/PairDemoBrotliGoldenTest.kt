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
 * Measured for version 6 (this test): the code is 3,257 bytes in 11 frames at 300 bytes per frame. The budget of 3,600 bytes is the same
 * budget as the device test that writes these frames, and leaves 343 bytes of headroom. The demo carries the Ethereum and Bitcoin Apps only:
 * twelve Ethereum accounts and the three Bitcoin accounts the owner stores. The worst case the UI allows is measured in PairingSizeTest, and
 * it is far above this budget.
 */
class PairDemoBrotliGoldenTest {

    @Test
    fun lumioImportsTheBrotliFramesAsTheDemoPairingWithinTheSizeBudget() {
        val scanner = UrScanner()
        DemoPairingGolden.FRAMES_BROTLI.forEach { scanner.receive(it) }
        val ur = requireNotNull(scanner.result())

        val read = PairImport.of(ur)
        val expected = DemoPairingGolden.payload()

        assertTrue("the code is ${ur.cborBytes.size} bytes, over the 3600-byte budget", ur.cborBytes.size <= 3600)
        assertEquals(expected.fingerprint, read.fingerprint)
        assertEquals(expected.name, read.name)
        assertEquals(expected.addressBook, read.addressBook)
        assertEquals(expected.accounts.map { it.shape() }, read.accounts.map { it.shape() })
    }
}
