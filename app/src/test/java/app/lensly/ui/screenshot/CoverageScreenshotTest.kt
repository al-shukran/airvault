package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.HideDelay
import app.lensly.ui.flow.ShredStep
import app.lensly.ui.settings.HideDelayScreen
import app.lensly.ui.settings.NoticeBlock
import app.lensly.ui.settings.ThirdPartyLicencesScreen
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
                NoticeBlock("Third-Party Licences", heading = true),
                NoticeBlock("Kotlin, Jetpack Compose and Roborazzi are licensed under the Apache License, Version 2.0.", heading = false),
                NoticeBlock("The Bitcoin and Ethereum libraries are licensed under the MIT License.", heading = false),
            ),
            onBack = {},
        )
    }

    private companion object {
        /** The copy of Delete Wallet, as the route shows it. */
        val DELETE_WALLET = ShredCopy(
            title = "Delete Wallet",
            prompt = "Enter your Application Password to delete this wallet.",
            warningTitle = "Cannot Be Recovered",
            warning = "This wallet and its accounts are erased from this device. It cannot be recovered without its backup.",
            action = "Delete Wallet",
        )
    }
}
