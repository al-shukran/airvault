package app.lumio.pair

import app.lensly.app.export.DemoPairingGolden
import app.lensly.core.ur.UrScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo pairing as Lensly's encoder writes it with Brotli (DemoPairingGolden). Lumio reads the frames back through its decoder and its
 * importer, and the code stays within the size the pairing is designed for.
 */
class PairDemoBrotliGoldenTest {

    @Test
    fun lumioImportsTheBrotliFramesAsTheDemoPairingWithinTheSizeBudget() {
        val scanner = UrScanner()
        DemoPairingGolden.FRAMES_BROTLI.forEach { scanner.receive(it) }
        val ur = requireNotNull(scanner.result())

        val read = PairImport.of(ur)
        val expected = DemoPairingGolden.payload()

        assertTrue("the code is ${ur.cborBytes.size} bytes", ur.cborBytes.size <= 4608)
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
