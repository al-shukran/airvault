package app.lensly.ui.home

import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import java.time.Instant

/** The account of the sample records, in full. */
internal const val SAMPLE_ACCOUNT = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"

/** The recipient of the signed sample record, in full. */
internal const val SAMPLE_RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

private const val SAMPLE_CONTRACT = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val MONAD_TESTNET = 10143L

/** The network of the sample records. Any other chain is not in the sample registry. */
internal val sampleNetworks: (Long) -> NetworkEntry? = { chainId ->
    if (chainId == MONAD_TESTNET) {
        NetworkEntry(
            chainId = MONAD_TESTNET,
            name = "Monad Testnet",
            nativeSymbol = "MON",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "MonadVision",
            testnet = true,
        )
    } else {
        null
    }
}

/**
 * Review Records for the History screen: a signed token transfer and a refused call on 11 November, and a cancelled message on the
 * day before. Newest first, as the screen lists them.
 */
internal fun sampleHistory(): List<ReviewRecord> {
    val noon = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()
    val hour = 3_600_000L
    return listOf(
        ReviewRecord(
            atMs = noon,
            outcome = ReviewOutcome.SIGNED,
            kind = ReviewKind.TRANSACTION,
            chainId = MONAD_TESTNET,
            networkName = "Monad Testnet",
            accountIndex = 0,
            address = SAMPLE_ACCOUNT,
            function = "transfer",
            to = SAMPLE_RECIPIENT,
            amount = "1.5 USDC",
            tokenContract = SAMPLE_CONTRACT,
            fee = listOf("Gas limit" to "21000", "Max fee" to "0.0021 MON"),
            params = listOf("to" to SAMPLE_RECIPIENT, "value" to "1500000 (1.5 USDC)"),
            origin = "https://app.example",
            refusal = null,
            dataHash = "3f9a".repeat(16),
        ),
        ReviewRecord(
            atMs = noon - hour,
            outcome = ReviewOutcome.REFUSED,
            kind = ReviewKind.TRANSACTION,
            chainId = MONAD_TESTNET,
            networkName = "Monad Testnet",
            accountIndex = null,
            address = null,
            function = null,
            to = null,
            amount = null,
            tokenContract = null,
            fee = emptyList(),
            params = emptyList(),
            origin = null,
            refusal = "Unknown contract call",
            dataHash = "7c01".repeat(16),
        ),
        ReviewRecord(
            atMs = noon - 24 * hour,
            outcome = ReviewOutcome.CANCELLED,
            kind = ReviewKind.MESSAGE,
            chainId = MONAD_TESTNET,
            networkName = "Monad Testnet",
            accountIndex = 0,
            address = SAMPLE_ACCOUNT,
            function = "Sign message",
            to = null,
            amount = null,
            tokenContract = null,
            fee = emptyList(),
            params = listOf("message" to "Sign in to Lensly"),
            origin = "https://app.example",
            refusal = null,
            dataHash = "b2e8".repeat(16),
        ),
    )
}

/** The id of the sample wallet whose records are [sampleHistory]. */
internal const val SAMPLE_WALLET_ONE = "0d4f5a26-1b8e-4c39-9a71-2f6c8d5e7b10"

/** The id of the other sample wallet, in the Activity samples. */
internal const val SAMPLE_WALLET_TWO = "7b1c3e90-4a2d-47f6-8c15-9e0b6d3a2f84"

/** The two sample wallets: a Mnemonic wallet and a Single Key, so their generated looks differ. */
internal fun sampleWallets(): List<WalletMeta> = listOf(
    WalletMeta(
        id = SAMPLE_WALLET_ONE,
        name = "Seed wallet",
        createdAtMs = 1L,
        deviceGenerated = true,
        accountCount = 3,
        masterFingerprintHex = "1a2b3c4d",
        accountXpub = null,
    ),
    WalletMeta(
        id = SAMPLE_WALLET_TWO,
        name = "Trading",
        createdAtMs = 2L,
        deviceGenerated = false,
        accountCount = 1,
        masterFingerprintHex = null,
        accountXpub = null,
        kind = WalletKind.SINGLE_KEY,
    ),
)

/** A profile for the second sample wallet, so the Activity screen shows a chosen colour and icon beside it. */
internal fun sampleActivityProfiles(): Map<String, WalletProfile> = mapOf(
    SAMPLE_WALLET_TWO to WalletProfile(colour = ProfileColour.TEAL, icon = ProfileIcon.STAR),
)

/**
 * The Activity records of both sample wallets, newest first, as the screen lists them: the signed transfer on the first wallet, the
 * refused call on the second an hour earlier, and the cancelled message on the first a day earlier.
 */
internal fun sampleActivity(): List<WalletReviewRecord> {
    val history = sampleHistory()
    return listOf(
        WalletReviewRecord(SAMPLE_WALLET_ONE, history[0]),
        WalletReviewRecord(SAMPLE_WALLET_TWO, history[1]),
        WalletReviewRecord(SAMPLE_WALLET_ONE, history[2]),
    )
}
