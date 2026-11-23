package app.lumio.ui.kit

import androidx.compose.runtime.compositionLocalOf
import app.lumio.data.config.Fiat

/**
 * The currency that the amounts of the open vault show in, as the owner set it and as its rate is known now. Every screen that shows a dollar
 * amount formats it with this, so one choice redraws them all. It is US dollars until the vault is open and the rate is read.
 */
val LocalFiat = compositionLocalOf { Fiat.USD }
