package app.lensly.ui.settings

import app.lensly.core.btc.SigningSourcePolicy
import kotlin.test.assertEquals
import org.junit.Test

/** The Lumio-only option names what the mark is worth: the vault reads a marker without checking who wrote it. */
class BitcoinPolicyCopyTest {
    @Test
    fun theLumioOnlyOptionSaysTheMarkIsNotChecked() {
        assertEquals(
            "Only requests marked by Lumio are signed; the mark is not checked.",
            signingSourceDescription(SigningSourcePolicy.COMPANION_ONLY),
        )
    }
}
