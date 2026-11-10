package app.lensly.core.registry

import kotlinx.serialization.Serializable

/** How a network prices transactions. Drives fee estimation, not chain identity. */
@Serializable
enum class FeeModel {
    /** Base fee plus priority fee, paid in native currency (Ethereum-style). */
    L1,

    /** Rollup: execution fee plus an L1 data posting fee. */
    L2_ROLLUP,
}
