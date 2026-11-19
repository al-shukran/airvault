package app.lensly.app.sign

import android.content.Context
import app.airvault.chains.algorand.signer.AsaCatalog

/**
 * The bundled ASA catalog that Algorand reviews name their assets from: `app/src/main/assets/algorand/asa-catalog.json`, written by
 * `scripts/algorand/gen_asa_catalog.py`. It is read on the first call and kept until the process ends. The read touches the app's assets,
 * so call this off the main thread.
 */
object AsaCatalogHolder {
    /** The catalog's path in the app's assets. */
    private const val BUNDLED_PATH = "algorand/asa-catalog.json"

    private var catalog: AsaCatalog? = null

    /** The bundled catalog. */
    @Synchronized
    fun get(context: Context): AsaCatalog = catalog ?: context.assets.open(BUNDLED_PATH)
        .bufferedReader()
        .use { AsaCatalog.parse(it.readText()) }
        .also { catalog = it }
}
