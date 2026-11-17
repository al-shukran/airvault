package app.lensly.core.algorand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** An Algorand Standard Asset as the bundled catalog names it: its ID, its unit name, its name and its decimals. */
data class Asa(val id: ULong, val unitName: String, val name: String, val decimals: Int)

/**
 * The popular ASAs that a review names, per network. The list is bundled in the app (`app/src/main/assets/algorand/asa-catalog.json`,
 * written by `scripts/algorand/gen_asa_catalog.py`), so the review works offline. An asset the catalog does not name is shown by its ID,
 * with its amount in base units and a warning, because Lensly cannot know its decimals.
 *
 * Asset IDs differ between networks, so each network's assets are keyed by its genesis hash. [lookup] finds an asset only on the network
 * that lists it.
 */
class AsaCatalog private constructor(private val assetsByGenesisHash: Map<String, Map<ULong, Asa>>) {
    /** The asset [assetId] on [network], or null when the catalog does not name it on that network. */
    fun lookup(network: AlgorandNetwork, assetId: ULong): Asa? = assetsByGenesisHash[genesisHashKey(network)]?.get(assetId)

    /** Every asset that [network] lists, in the order the catalog file gives them. */
    fun assetsOn(network: AlgorandNetwork): List<Asa> = assetsByGenesisHash[genesisHashKey(network)]?.values?.toList().orEmpty()

    companion object {
        /** A catalog that names no asset, so every asset is unknown. */
        val EMPTY = AsaCatalog(emptyMap())

        private const val MAX_DECIMALS = 19

        /**
         * The catalog in [json], the file the generator writes. Throws [IllegalArgumentException] for an asset with no unit name, decimals
         * outside 0 to 19, or an ID listed twice on one network, so a damaged file cannot name assets wrongly.
         */
        fun parse(json: String): AsaCatalog {
            val networks = Json.parseToJsonElement(json).jsonObject.getValue("networks").jsonObject
            val byHash = networks.mapValues { (hash, network) ->
                val assets = network.jsonObject.getValue("assets").jsonArray.map { asset(it.jsonObject) }
                require(assets.map { it.id }.toSet().size == assets.size) { "asa-catalog: an asset ID is listed twice on $hash" }
                assets.associateBy { it.id }
            }
            return AsaCatalog(byHash)
        }

        private fun asset(entry: JsonObject): Asa {
            val asset = Asa(
                id = entry.getValue("id").jsonPrimitive.long.toULong(),
                unitName = entry.getValue("unit").jsonPrimitive.content,
                name = entry.getValue("name").jsonPrimitive.content,
                decimals = entry.getValue("decimals").jsonPrimitive.int,
            )
            require(asset.unitName.isNotEmpty() && asset.decimals in 0..MAX_DECIMALS) {
                "asa-catalog: asset ${asset.id} needs a unit name and decimals from 0 to $MAX_DECIMALS"
            }
            return asset
        }

        private fun genesisHashKey(network: AlgorandNetwork): String = java.util.Base64.getEncoder().encodeToString(network.genesisHash)
    }
}
