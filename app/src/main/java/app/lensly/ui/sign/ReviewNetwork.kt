package app.lensly.ui.sign

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import app.airvault.core.model.ChainId
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.SOLANA_FAMILY
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.AlgorandIcon
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.NetworkLogo
import app.lensly.ui.components.SolanaIcon

/** The name of a chain ID that the registry does not hold. */
internal fun unknownNetworkName(chainId: Long): String = "Unknown network (chain id $chainId)"

/** The name of a Bitcoin network a review is for. Testnet4 is named as the test network. */
internal fun bitcoinNetworkName(network: BtcNetwork): String = when (network) {
    BtcNetwork.MAINNET -> "Bitcoin Mainnet"
    BtcNetwork.TESTNET4 -> "Bitcoin Testnet4"
}

/** The registry network of an EVM [chain], by its chain id, or null when the chain is not EVM or the registry does not list it. */
internal fun networkOf(chain: ChainId, networks: List<NetworkEntry>): NetworkEntry? =
    networks.firstOrNull { ChainId("${EVM_FAMILY.namespace}:${it.chainId}") == chain }

/**
 * The logo of the network a request or a record is on, shared by the reviews and the history. A chain of Bitcoin, Solana or Algorand shows
 * its own logo. Any other chain shows the registry's logo of the network named [networkName], looked up by its name. A network with no logo,
 * and a name the registry does not list, show a letter of the name, never the Ethereum logo.
 */
@Composable
internal fun ChainNetworkLogo(chain: ChainId?, networkName: String?, networks: List<NetworkEntry>, size: Dp) {
    when (chain?.namespace) {
        BITCOIN_FAMILY.namespace -> BitcoinIcon(size = size)
        SOLANA_FAMILY.namespace -> SolanaIcon(size = size)
        ALGORAND_FAMILY.namespace -> AlgorandIcon(size = size)
        else -> NetworkLogo(name = networkName, networks = networks, size = size)
    }
}
