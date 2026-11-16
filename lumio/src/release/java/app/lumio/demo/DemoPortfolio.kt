package app.lumio.demo

import android.app.Application
import androidx.compose.runtime.Composable
import app.lensly.core.vault.VaultSession
import app.lumio.data.asset.AssetId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxQuery
import app.lumio.home.HomeAsset
import app.lumio.network.NetworkMode

/**
 * The release stub of the demo portfolio. It has the functions the repositories call, and each one answers "no demo": the switch is never on, no
 * holding, price, chart or transaction is returned, and no Developer row is drawn. Release builds therefore always read the vault and the network.
 */
internal object DemoPortfolio {
    fun attach(app: Application) = Unit

    suspend fun vaultOpened(session: VaultSession) = Unit

    fun isOn(): Boolean = false

    fun homeAssets(mode: NetworkMode): List<HomeAsset>? = null

    fun price(asset: AssetId): Price? = null

    fun chart(asset: AssetId, range: ChartRange, nowMs: Long): PriceSeries? = null

    fun transactions(query: TxQuery): List<LumioTx>? = null

    fun transaction(account: String, txId: String): LumioTx? = null

    fun developerSection(onChanged: () -> Unit): (@Composable () -> Unit)? = null
}
