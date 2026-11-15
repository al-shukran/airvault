package app.lumio

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.Mnemonic
import app.lumio.btc.BtcSpendable
import app.lumio.btc.scriptOf
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut

/**
 * Debug-only: the coins of the debug pairing fixture's testnet4 accounts, so a Bitcoin send can be shown on an emulator. The fixture is derived
 * from the public BIP39 test mnemonic, which holds no coins on testnet4, and an emulator cannot reach an Esplora server over https. So the
 * fixture gives one confirmed output of 100,000 sat at the account's receive address 0, built here as a real transaction. The PSBT, the
 * signing request and its QR code are the real ones. The output is not on chain, so a broadcast of it fails on the network.
 *
 * This file lives in the debug source set only. The release stub returns null, so release builds never contain it.
 */
internal object DebugSpendFixture {
    private const val SATS = 100_000L

    /** The 24-word BIP39 test vector of 256 zero bits, the one [DebugPairFixture] pairs. */
    private val WORDS = ("abandon ".repeat(23) + "art").split(" ")
    private val seed: ByteArray by lazy { Mnemonic.of(WORDS).use { it.deriveSeed("") } }
    private val fingerprint: Long by lazy { BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT).masterFingerprint }

    /** The spendable coins of [account], or null when [account] is not a testnet4 account of the fixture. */
    fun spendable(account: BtcWatchOnlyAccount): BtcSpendable? {
        if (account.network != BtcNetwork.TESTNET4 || account.masterFingerprint != fingerprint) return null
        val funding = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxHash(ByteVector32(ByteArray(32) { 1 })), 0L), ByteVector.empty, 0xFFFFFFFFL)),
            txOut = listOf(TxOut(Satoshi(SATS), ByteVector(scriptOf(BtcNetwork.TESTNET4, account.receiveAddress(0))))),
            lockTime = 0L,
        )
        return BtcSpendable(listOf(BtcCoin(funding, 0, 0, 0)), nextChangeIndex = 0)
    }
}
