package app.lensly.core.registry

import kotlinx.serialization.Serializable

/** Whether a network has its own consensus ([LAYER_1]) or settles to a parent network as a rollup ([LAYER_2]). */
@Serializable
enum class NetworkKind { LAYER_1, LAYER_2 }
