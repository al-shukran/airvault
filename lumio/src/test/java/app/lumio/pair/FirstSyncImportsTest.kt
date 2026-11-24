package app.lumio.pair

import app.lumio.network.NetworkMode
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test

class FirstSyncImportsTest {

    private val wallet = PairedWallet(id = "wallet-1", name = "Savings", fingerprint = 1L, createdAtMs = 0L, bitcoin = emptyList())

    @Test
    fun aVisitGetsTheImportThatWasKept() {
        val imports = FirstSyncImports()
        val kept = FirstSyncImport(wallet, FirstSync(emptyList()))
        imports.put(wallet.id, NetworkMode.MAINNET, kept)
        assertSame(kept, imports.get(wallet.id, NetworkMode.MAINNET))
    }

    @Test
    fun anImportIsKeptPerWalletAndNetworkSide() {
        val imports = FirstSyncImports()
        imports.put(wallet.id, NetworkMode.MAINNET, FirstSyncImport(wallet, FirstSync(emptyList())))
        assertNull(imports.get(wallet.id, NetworkMode.TESTNET))
        assertNull(imports.get("wallet-2", NetworkMode.MAINNET))
    }
}
