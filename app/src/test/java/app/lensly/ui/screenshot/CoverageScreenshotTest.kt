package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.HideDelay
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.ui.flow.ShredStep
import app.lensly.ui.settings.HideDelayScreen
import app.lensly.ui.settings.NoticeBlock
import app.lensly.ui.settings.ThirdPartyLicencesScreen
import app.lensly.ui.sign.NoMatchingWalletScreen
import app.lensly.ui.wallet.PassphraseBackupScreen
import app.lensly.ui.wallet.ShredCopy
import app.lensly.ui.wallet.ShredScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Lensly screens that no other screenshot test records: the hidden-tile delay, a sign request that matches no
 * wallet, the passphrase backup, the shred confirmation step, and the third-party licences. They use fake state in the same theme,
 * frame and recording as the other screenshot tests, and they are recorded with `:app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hideDelay() = compose.snapshot("hide_delay_screen") {
        HideDelayScreen(
            shortest = HideDelay.DEFAULT_MIN_MS.toInt(),
            longest = HideDelay.DEFAULT_MAX_MS.toInt(),
            onShortest = {},
            onLongest = {},
            onBack = {},
        )
    }

    @Test
    fun noMatchingWallet() = compose.snapshot("no_matching_wallet_screen") {
        NoMatchingWalletScreen(request = REQUEST, several = false, onScanAgain = {}, onCancel = {})
    }

    @Test
    fun passphraseBackup() = compose.snapshot("passphrase_backup_screen") {
        PassphraseBackupScreen(writtenDown = false, onWrittenDownChange = {}, onCreate = {})
    }

    @Test
    fun shred() = compose.snapshot("shred_screen") {
        ShredScreen(
            step = ShredStep.Password(busy = false),
            copy = DELETE_WALLET,
            passwordLength = 0,
            onKey = {},
            onBackspace = {},
            onSubmit = {},
            onConfirm = {},
            onBack = {},
        )
    }

    @Test
    fun licences() = compose.snapshot("licences_screen") {
        ThirdPartyLicencesScreen(
            blocks = listOf(
                NoticeBlock("Third-party licences", heading = true),
                NoticeBlock("Kotlin, Jetpack Compose and Roborazzi are licensed under the Apache License, Version 2.0.", heading = false),
                NoticeBlock("The Bitcoin and Ethereum libraries are licensed under the MIT License.", heading = false),
            ),
            onBack = {},
        )
    }

    private companion object {
        /** A sign request from a fake wallet: the fingerprint and the path are made up, and the address is absent. */
        val REQUEST = EthSignRequest(
            requestId = ByteArray(16) { 1 },
            signData = byteArrayOf(1),
            dataType = DataType.PERSONAL_MESSAGE,
            chainId = 1L,
            derivationPath = listOf(
                PathStep(44, true),
                PathStep(60, true),
                PathStep(0, true),
                PathStep(0, false),
                PathStep(0, false),
            ),
            sourceFingerprint = 0x1a2b3c4d,
            address = null,
            origin = null,
        )

        /** The copy of Delete Wallet, as the route shows it. */
        val DELETE_WALLET = ShredCopy(
            title = "Delete wallet",
            prompt = "Enter your Application Password to delete this wallet.",
            warningTitle = "Cannot be recovered",
            warning = "This wallet and its accounts are erased from this device. It cannot be recovered without its backup.",
            action = "Delete wallet",
        )
    }
}
