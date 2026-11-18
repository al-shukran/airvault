package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import java.math.BigInteger

/** An Algorand address: 58 characters of the base32 alphabet, so a request never carries a path separator into a URL. */
private val ALGORAND_ADDRESS = Regex("[A-Z2-7]{58}")

/** The assets that [address] holds on [net]: one [AsaHolding] for each asset the account has opted in to. */
class AsaHoldings(net: NetworkId, val address: String) : Request<List<AsaHolding>>(net, Op.TOKEN_BALANCES) {
    init {
        require(ALGORAND_ADDRESS.matches(address)) { "Not an Algorand address." }
    }
}

/** One asset an account holds: its [assetId], its [amount] in asset units, and whether the asset is [frozen] for the account. */
data class AsaHolding(val assetId: ULong, val amount: BigInteger, val frozen: Boolean)

/** The parameters of the asset [assetId] on [net], as the network holds them now. */
class AsaAssetInfo(net: NetworkId, val assetId: ULong) : Request<AsaInfo>(net, Op.ASSET_INFO) {
    init {
        require(assetId > 0UL) { "An asset ID is more than 0." }
    }
}

/** An asset's [decimals], 0 to 19, and its [unitName] and [name], which the creator may leave empty. */
data class AsaInfo(val decimals: Int, val unitName: String, val name: String)
