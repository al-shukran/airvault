package app.lumio.ui.network

import app.lumio.network.NetworkMode

/** The label of a side of the networks: "Mainnet" or "Testnet". */
fun NetworkMode.label(): String = when (this) {
    NetworkMode.MAINNET -> "Mainnet"
    NetworkMode.TESTNET -> "Testnet"
}
