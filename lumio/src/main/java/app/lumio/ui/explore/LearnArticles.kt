package app.lumio.ui.explore

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.settings.SettingsFrame
import kotlin.math.ceil

/** A Learn AirVault article: its catalogue [id], its [title], and its [paragraphs] of plain text. */
data class LearnArticle(val id: String, val title: String, val paragraphs: List<String>) {
    /** The minutes the article takes to read, at 200 words a minute and never fewer than one. */
    val minutes: Int get() = maxOf(1, ceil(paragraphs.sumOf { it.split(' ').size } / WORDS_PER_MINUTE).toInt())

    /** The line under the title on Explore, such as "1 min read". */
    val readTime: String get() = "$minutes min read"
}

private const val WORDS_PER_MINUTE = 200.0

/**
 * The Learn AirVault articles, in the order Explore lists them. Each one states how AirVault works as the code and the README describe it, in the
 * words an owner uses, with no term left unexplained.
 */
internal object LearnArticles {
    val ALL: List<LearnArticle> = listOf(
        LearnArticle(
            id = "air-gap",
            title = "How the Air Gap Works",
            paragraphs = listOf(
                "AirVault keeps your private keys on a phone that never goes online. That phone runs Lensly. Lumio, the app you are in now, runs on your everyday " +
                    "phone and does everything that needs the internet: it looks up your balances and prices, builds each payment and sends it.",
                "The two phones never connect to each other. They pass information as QR codes: one phone shows a code on its screen, and the other reads it " +
                    "with its camera. This gap is why nothing on the internet can reach your keys.",
                "A payment makes one round trip. Lumio builds the payment and shows it as QR codes. Lensly scans them and shows you a review. When you approve, " +
                    "Lensly signs the payment and shows the signed result as QR codes. Lumio scans them and sends the payment to the network.",
                "Lumio never holds a private key, so it cannot move your money on its own. Lensly asks Android for no network access at all, so a payment " +
                    "can only leave it as a code that you choose to show.",
                "The gap only protects you while the Lensly phone stays offline. Keep it off Wi-Fi and mobile data, and install Lensly only from a release that " +
                    "you have checked.",
            ),
        ),
        LearnArticle(
            id = "review",
            title = "Reading a Review on Lensly",
            paragraphs = listOf(
                "Before Lensly signs anything it shows you a review. The review lists what the payment will do, and you decide whether it matches what you " +
                    "meant. Lensly signs only when you hold the Hold to Sign button, and Reject ends the request without signing.",
                "Read the address that will receive the money first. Lensly shows the full address, so compare it with the address you meant, not only its " +
                    "first and last characters. Lensly also tells you when an address is new to you, when it matches a saved name in your Address Book, and when " +
                    "it looks very much like an address you saved.",
                "Then read the network and the amount. Check that the network is the one you intended, because the same address can exist on several " +
                    "networks, and that the amount and the fee are what you expect.",
                "If anything in the review looks wrong, or you do not understand a line, reject the request. Nothing is signed, and you can start again from " +
                    "Lumio. When Lensly cannot read or understand a request, it refuses it, tells you why, and signs nothing.",
            ),
        ),
    )

    fun find(id: String): LearnArticle? = ALL.firstOrNull { it.id == id }
}

/** A Learn AirVault article as a page: its read time, then its paragraphs. */
@Composable
fun LearnArticleScreen(article: LearnArticle, offline: String?, backLabel: String, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    SettingsFrame(title = article.title, offline = offline, onBack = onBack, onLock = onLock, backLabel = backLabel) {
        Text(
            text = article.readTime,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter).padding(top = ClearAirSpacing.xs),
            style = ClearAirType.small,
            color = palette.ink2,
        )
        article.paragraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter).padding(top = ClearAirSpacing.md),
                style = ClearAirType.body,
                color = palette.ink,
            )
        }
        Spacer(Modifier.height(ClearAirSpacing.xl))
    }
}
