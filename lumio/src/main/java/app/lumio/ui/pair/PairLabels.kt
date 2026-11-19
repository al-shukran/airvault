package app.lumio.ui.pair

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairChain

/** The words the pairing screens show for a family, a network, a Script Type and a fingerprint. */
internal object PairLabels {
    fun chainName(chain: PairChain): String = when (chain) {
        PairChain.EVM -> "EVM"
        PairChain.BITCOIN -> "Bitcoin"
        PairChain.SOLANA -> "Solana"
        PairChain.ALGORAND -> "Algorand"
    }

    fun network(network: BtcNetwork): String = when (network) {
        BtcNetwork.MAINNET -> "Mainnet"
        BtcNetwork.TESTNET4 -> "Testnet"
    }

    fun scriptType(scriptType: ScriptType): String = when (scriptType) {
        ScriptType.LEGACY -> "Legacy (BIP44)"
        ScriptType.NESTED_SEGWIT -> "Nested SegWit (BIP49)"
        ScriptType.NATIVE_SEGWIT -> "Native SegWit (BIP84)"
        ScriptType.TAPROOT -> "Taproot (BIP86)"
    }

    /** The master fingerprint in hex, as Lensly shows it. */
    fun fingerprint(fingerprint: Long): String = "%08x".format(fingerprint)
}
