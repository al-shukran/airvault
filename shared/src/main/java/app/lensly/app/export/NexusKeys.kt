package app.lensly.app.export

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.hdKey
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.CryptoHdKeyEncoder
import app.lensly.core.wallet.HdWallet

/**
 * The public account keys that one App of a wallet contributes to the Keystone Nexus export, as crypto-hdkey items (see
 * [MultiAccountsExport]). An App states its keys by implementing this, and `WalletApp` lists the implementation, so the export carries
 * the keys of every App in that list, in its order. The keys are derived from the seed and carry public data only.
 */
interface NexusKeys {
    /** The crypto-hdkey items of this App for [seed], whose master fingerprint is [masterFingerprint]. */
    fun hdKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor>
}

/** The Bitcoin App: the mainnet account 0 of BIP44, BIP49, BIP84 and BIP86, in that order. */
object BitcoinNexusKeys : NexusKeys {
    override fun hdKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor> =
        BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET, accountIndex = 0).map { account ->
            MultiAccountsExport.hdKeyItem(account.hdKey().toUR().cborBytes)
        }
}

/** The Ethereum App: the account key m/44'/60'/0', which the EVM networks of the wallet share. */
object EthereumNexusKeys : NexusKeys {
    private const val ACCOUNT_DEPTH = 3

    override fun hdKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor> {
        val xpub = HdWallet(seed).accountXpub()
        val key = CryptoHdKeyEncoder.ur(
            pubkey33 = xpub.publicKey,
            chainCode32 = xpub.chainCode,
            masterFp = masterFingerprint,
            parentFp = xpub.parentFingerprint,
            name = null,
            depth = ACCOUNT_DEPTH,
        )
        return listOf(MultiAccountsExport.hdKeyItem(key.cborBytes))
    }
}
