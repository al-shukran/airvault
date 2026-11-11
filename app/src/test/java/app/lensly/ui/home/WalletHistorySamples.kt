package app.lensly.ui.home

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

private const val SAMPLE_TOKEN = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
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
            tokenContract = SAMPLE_TOKEN,
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
