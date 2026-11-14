package app.lensly.core.btc

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.DeterministicWallet

/**
 * The Bitcoin network an Account is derived for. Mainnet uses coin type 0 and testnet4 uses coin type 1, as BIP44 asks
 * for every test network. Testnet4 has the testnet3 address prefixes: `tb1` for bech32, `m` or `n` for P2PKH and `2` for P2SH.
 */
enum class BtcNetwork(
    /** The chain whose genesis hash, bech32 prefix and base58 version bytes the address helpers use. */
    val chain: Chain,
    /** The BIP44 coin type, the second level of every account path. */
    val coinType: Int,
    /** The version bytes of an account's extended public key: `xpub` on mainnet, `tpub` on testnet4. */
    internal val xpubVersion: Int,
) {
    MAINNET(Chain.Mainnet, coinType = 0, xpubVersion = DeterministicWallet.xpub),
    TESTNET4(Chain.Testnet4, coinType = 1, xpubVersion = DeterministicWallet.tpub),
}
