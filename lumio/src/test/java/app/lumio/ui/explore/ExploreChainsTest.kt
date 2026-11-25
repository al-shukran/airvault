package app.lumio.ui.explore

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.Registry
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.ui.home.AssetIconSpec
import app.lumio.wallets.WalletAccount
import kotlin.test.assertEquals
import org.junit.Test

/** The chain rows of the Explore landing: each network the accounts sit on, and each featured network, with its kind and count or chain ID. */
class ExploreChainsTest {

    private val registry = Registry(
        networks = listOf(
            network(1L, "Ethereum", NetworkKind.LAYER_1, parent = null, featured = false),
            network(8453L, "Base", NetworkKind.LAYER_2, parent = 1L, featured = true),
            network(137L, "Polygon PoS", NetworkKind.LAYER_1, parent = null, featured = true),
            network(10143L, "Monad Testnet", NetworkKind.LAYER_1, parent = null, featured = true, testnet = true),
        ),
        tokens = emptyList(),
    )

    private fun network(
        id: Long,
        name: String,
        kind: NetworkKind,
        parent: Long?,
        featured: Boolean,
        testnet: Boolean = false,
    ) = NetworkEntry(
        chainId = id,
        name = name,
        nativeSymbol = "ETH",
        feeModel = FeeModel.L1,
        explorerName = null,
        testnet = testnet,
        featured = featured,
        kind = kind,
        parentChainId = parent,
    )

    private fun row(chainId: Long, description: String) = registry.network(chainId)!!.let { ChainRow(it.name, description, AssetIconSpec.Chain(it)) }

    private fun account(chain: ChainId, index: Int) = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(chain, "0x" + "%040x".format(index + 1)),
        index = index,
        networkName = "network",
        coin = null,
        balance = null,
    )

    @Test
    fun ethereumNetworksAreListedInChainOrderThenSolanaWithKindAndCountOrChainId() {
        val accounts = (0 until 4).map { account(Networks.evm(1L), it) } + account(Networks.SOL_MAINNET, 0)

        assertEquals(
            listOf(
                row(1L, "Layer 1 · 4 of your accounts"),
                row(137L, "Layer 1 · ID 137"),
                row(8453L, "L2 on Ethereum · ID 8453"),
                ChainRow("Solana", "Layer 1 · 1 of your accounts", AssetIconSpec.Solana),
            ),
            chainRows(registry, NetworkMode.MAINNET, accounts),
        )
    }

    @Test
    fun aSideWithoutAccountsShowsItsFeaturedNetworksWithTheirChainIds() {
        assertEquals(
            listOf(row(137L, "Layer 1 · ID 137"), row(8453L, "L2 on Ethereum · ID 8453")),
            chainRows(registry, NetworkMode.MAINNET, emptyList()),
        )
    }

    @Test
    fun theTestnetSideShowsItsOwnFeaturedNetworkOnly() {
        assertEquals(listOf(row(10143L, "Layer 1 · ID 10143")), chainRows(registry, NetworkMode.TESTNET, emptyList()))
    }

    @Test
    fun noAccountsAndNoFeaturedNetworksMeansNoRows() {
        val plain = Registry(networks = listOf(network(1L, "Ethereum", NetworkKind.LAYER_1, parent = null, featured = false)), tokens = emptyList())

        assertEquals(emptyList(), chainRows(plain, NetworkMode.MAINNET, emptyList()))
    }
}
