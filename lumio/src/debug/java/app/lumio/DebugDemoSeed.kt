package app.lumio

import android.content.Intent
import android.util.Log
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.debugEmulatorVault
import app.lensly.core.wallet.Mnemonic
import app.lumio.home.HomeLoader
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairScan
import app.lumio.pair.PairedWallets
import app.lumio.pair.ScanStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only: a demo vault with a fixed App Password and the pairing of a throwaway wallet, so Lumio's screens open on an emulator without a
 * camera, a password keypad or a physical Lensly. Launch with `adb shell am start -n app.lumio/.MainActivity --es debugDemoSeed "'<24 words>'"`; the mnemonic is never committed.
 *
 * The vault is created when none exists, and opened when it holds this password. The pairing is decoded by the scan, so it is checked as a
 * camera scan checks it, then saved as the review saves it. Testnet is selected, and the Ethereum and native balances are read as a first sync
 * reads them. Then the Wallets home opens, and the wallet opens over it as Open wallet opens it.
 *
 * It runs on an emulator only. On any other device it does nothing, so the fixed password never reaches a phone.
 *
 * This file lives in the debug source set only. The release and benchmark stubs ignore the extra, so their builds never contain its name.
 */
internal object DebugDemoSeed {
    private const val EXTRA = "debugDemoSeed"
    private const val TAG = "DebugDemoSeed"

    /** The App Password of the demo vault. It is public, and it guards only the demo vault of an emulator. */
    private const val SEED_UNLOCK = "Vq7m-Lp2x-Tz9w-Kd4n"

    /** The throwaway test mnemonic that [intent] carries in the seed extra, or null when it asks for no seed. */
    fun mnemonicOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Pairs the demo wallet in the demo vault, then calls [showHome], which opens the hidden area on the Accounts home. Nothing opens on a device
     * that is not an emulator, on a vault with another password, or on a failure, which is logged.
     */
    suspend fun seed(app: LumioApp, mnemonic: String, showHome: () -> Unit) {
        withContext(Dispatchers.IO) { pairDemoWallet(app, mnemonic) } ?: return
        showHome()
        Log.i(TAG, "The demo wallet is on the Accounts home.")
    }

    /** The demo vault, open, with its demo wallet paired and its balances read. Returns the wallet's id, or null when the vault is not open. */
    private suspend fun pairDemoWallet(app: LumioApp, mnemonic: String): String? = coroutineScope {
        val vault = debugEmulatorVault(app) ?: return@coroutineScope refused("this device is not an emulator")
        val holder = app.vaultHolder
        val epoch = holder.unlockEpoch()
        val started = System.nanoTime()
        // The pairing is decoded while the vault opens, so the two costs overlap. A failure is kept until it is needed.
        val pairing = async(Dispatchers.Default) { runCatching { pairingOf(mnemonic) } }
        try {
            if (!vault.exists() && vault.create(SEED_UNLOCK.toCharArray()) != CreateResult.Created) {
                return@coroutineScope refused("the vault was not created")
            }
            val session = (vault.unlock(SEED_UNLOCK.toCharArray()) as? UnlockResult.Unlocked)?.session
                ?: return@coroutineScope refused("the vault does not open with the demo password")
            NetworkModeStore.write(session, NetworkMode.TESTNET)
            val saved = PairedWallets.save(session, pairing.await().getOrThrow(), System.currentTimeMillis())
            if (!holder.adopt(session, epoch)) return@coroutineScope refused("the vault was locked while it was seeded")
            readBalances(app, session)
            Log.i(TAG, "The demo vault is seeded in ${(System.nanoTime() - started) / 1_000_000} ms.")
            saved.id
        } catch (e: Exception) {
            Log.e(TAG, "The demo vault could not be seeded.", e)
            null
        }
    }

    /**
     * The reads that a first sync makes before a wallet opens: the Ethereum and native balances, read side by side. Testnet coins have no price,
     * so the prices are not read. The Bitcoin scans run after the wallet opens, as they do on Home, and the Bitcoin balances show when they land.
     */
    private suspend fun readBalances(app: LumioApp, session: VaultSession) = coroutineScope {
        launch {
            runCatching { HomeLoader.refreshEvm(app, session, NetworkMode.TESTNET) }
                .onFailure { Log.w(TAG, "The Ethereum balances did not read.", it) }
        }
        launch {
            runCatching { HomeLoader.refreshNative(app, session, NetworkMode.TESTNET) }
                .onFailure { Log.w(TAG, "The native balances did not read.", it) }
        }
    }

    /** Logs why the seed did nothing, so a launch that shows no wallet has a reason in logcat. Returns null, which is the seed's answer then. */
    private fun refused(reason: String): String? {
        Log.e(TAG, "The demo vault was not seeded: $reason.")
        return null
    }

    /**
     * The pairing of [mnemonic], as Lensly exports it: its frames go through the scan's decoder, and the first complete pairing is the one kept.
     */
    private fun pairingOf(mnemonic: String): PairingPayload {
        val seed = Mnemonic.of(mnemonic.split(Regex("\\s+"))).use { it.deriveSeed("") }
        val scan = PairScan()
        PairingCodec.urParts(DebugPairFixture.payloadOf(seed), brotli = null).forEach { frame ->
            when (val step = scan.onText(frame)) {
                is ScanStep.Complete -> return step.pairing
                is ScanStep.Refused -> error(step.reason)
                else -> Unit
            }
        }
        error("The demo pairing has no complete frame.")
    }
}
