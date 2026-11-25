package app.lumio.demo

import app.lensly.app.AddressBookEntry
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.wallet.Mnemonic
import app.lumio.DebugPairFixture
import app.lumio.pair.PairImport
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The demo wallet and the values of the approved Clear Air mockups: the wallet Air 1, its accounts PulsarNova, FelixStone and NothingLess,
 * their balances, the prices and the address book. The wallet is the public BIP39 test vector, so no real key is involved.
 */
internal object DemoFixtures {
    /** The 24-word BIP39 test vector of 256 zero bits. It is public and holds no coins. */
    const val MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

    const val WALLET_NAME = "Air 1"

    /** The EVM account names in account order. */
    val EVM_NAMES = listOf("PulsarNova", "FelixStone", "NothingLess")

    const val EXCHANGE_DEPOSIT = "0x9f3c8a1d2b7e4f60a5c91d3e8b2f7a6c000041d0"
    const val LENA_PARK = "0x4b1e9d0c7a3f5e2186b4d9a0c3e7f1250000a07c"
    /** An address that is in no Address Book: the sender of the token receipt, so its Address-Book Info sheet is the Unknown one. */
    const val STRANGER = "0xd8da6bf26964af9d7eed9e03e53415d37aa96045"
    const val EXTERNAL_BTC = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    /** The Monad Testnet chain id and the mainnet chains that hold demo balances. */
    const val MONAD_TESTNET = 10143L
    const val ETHEREUM = 1L
    const val ARBITRUM = 42161L

    const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    const val USDC_ARBITRUM = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
    const val WETH_ARBITRUM = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1"

    const val BTC_USD = 108_420.35
    const val ETH_USD = 4_285.70
    const val SOL_USD = 184.20

    private val seed: ByteArray by lazy { Mnemonic.of(MNEMONIC.split(" ")).use { it.deriveSeed("") } }

    /** The pairing that the seed saves: the debug fixture's accounts under the mockups' names, and the mockups' address book. */
    fun payload(): PairingPayload {
        val base = DebugPairFixture.payloadOf(seed, evmAccounts = EVM_NAMES.size)
        var evm = 0
        val accounts = base.accounts.map { account ->
            if (account.export.chain.caip2.startsWith("eip155")) {
                val index = evm++
                account.copy(
                    name = EVM_NAMES[index],
                    label = if (index == 0) "Trading" else null,
                    group = if (index == 0) "DeFi" else null,
                )
            } else {
                account
            }
        }
        return base.copy(
            name = WALLET_NAME,
            autoNameBase = WALLET_NAME,
            accounts = accounts,
            addressBook = listOf(
                AddressBookEntry("5f0c2a1e-0001", EXCHANGE_DEPOSIT, "Exchange Deposit", "Exchanges", group = "Exchanges"),
                AddressBookEntry("5f0c2a1e-0002", LENA_PARK, "Lena Park", "Friends", group = "Friends"),
                AddressBookEntry("5f0c2a1e-0003", EXTERNAL_BTC, "Cold Storage", null),
            ),
        )
    }

    private val paired: PairingPayload by lazy { payload() }

    /** The EVM addresses of the demo wallet, lower case, in account order. */
    val evmAddresses: List<String> by lazy { PairImport.ethereumAddressesOf(paired).map { it.lowercase() } }

    private val solana: List<String> by lazy { PairImport.solanaPublicKeysOf(paired).map(SolanaAccount::addressOf) }
    private val algorand: List<String> by lazy { PairImport.algorandPublicKeysOf(paired).map(AlgorandAccount::addressOf) }

    /** The Bitcoin accounts of [network]: the native SegWit account is Savings, the taproot account Spending. */
    private fun bitcoin(network: BtcNetwork): List<BtcWatchOnlyAccount> = PairImport.bitcoinAccountsOf(paired).filter { it.network == network }

    /** Bitcoin balances in satoshis by receive address 0: Savings 0.2000 BTC and Spending 0.0104 BTC on mainnet, and test coins on testnet4. */
    val bitcoinSats: Map<String, Long> by lazy {
        buildMap {
            for (network in BtcNetwork.entries) {
                val main = network == BtcNetwork.MAINNET
                for (account in bitcoin(network)) {
                    val sats = when (account.scriptType) {
                        ScriptType.NATIVE_SEGWIT -> if (main) 20_000_000L else 5_000_000L
                        ScriptType.TAPROOT -> if (main) 1_040_000L else 1_000_000L
                        else -> 0L
                    }
                    if (sats > 0) put(account.receiveAddress(0), sats)
                }
            }
        }
    }

    /** The native balance in base units of [address] on the EVM chain [chainId], or zero. The demo funds PulsarNova, FelixStone and NothingLess. */
    fun nativeWei(chainId: Long, address: String): BigInteger {
        val index = evmAddresses.indexOf(address.lowercase())
        val coins = when (chainId) {
            ETHEREUM -> listOf("11", "0", "4")
            ARBITRUM -> listOf("0", "3.23", "0")
            MONAD_TESTNET -> listOf("12.5", "1.2", "0.4")
            else -> emptyList()
        }.getOrNull(index) ?: "0"
        return units(coins, 18)
    }

    /** The token balance in base units of [owner] for the token [contract] on [chainId], or zero. */
    fun tokenUnits(chainId: Long, contract: String, owner: String): BigInteger {
        if (evmAddresses.indexOf(owner.lowercase()) != 0) return BigInteger.ZERO
        return when (chainId to contract.lowercase()) {
            ETHEREUM to USDC_ETHEREUM -> units("10000", 6)
            ARBITRUM to USDC_ARBITRUM -> units("10100", 6)
            ARBITRUM to WETH_ARBITRUM -> units("0.85", 18)
            else -> BigInteger.ZERO
        }
    }

    /** The lamports of the Solana account at [position]: 31.5 SOL on mainnet and 2.5 on devnet for the first account. */
    fun lamports(position: Int, mainnet: Boolean): BigInteger = if (position == 0) units(if (mainnet) "31.5" else "2.5", 9) else BigInteger.ZERO

    /** The microAlgos of [address]: 1,250 ALGO on mainnet and 10 on testnet for the first account. */
    fun microAlgos(address: String, mainnet: Boolean): BigInteger =
        if (algorand.firstOrNull() == address) units(if (mainnet) "1250" else "10", 6) else BigInteger.ZERO

    fun solanaAddresses(): List<String> = solana

    private fun units(amount: String, decimals: Int): BigInteger = BigDecimal(amount).movePointRight(decimals).toBigIntegerExact()
}
