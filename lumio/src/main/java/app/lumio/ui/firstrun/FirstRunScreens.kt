package app.lumio.ui.firstrun

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.R
import app.lumio.ui.kit.ClearAirCheckbox
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirLineBox
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirSegments
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.settings.BIOMETRIC_UNAVAILABLE_REASON

/** The gap between the status bar and the chapter bar: the mock puts the bar 8 dp below the status bar, plus 6 dp of its own padding. */
private val CHAPTER_BAR_TOP = 14.dp

/**
 * How a first-run page spaces its parts, in dp as the locked mock measures them. [barTop] is the gap from the status bar to the chapter bar, which
 * the mock draws 14 dp down on most pages and 15 dp down on the duress page. [artTop] is the gap from the chapter bar to the art, [eyebrowTop]
 * from the art (or the bar) to the eyebrow, [titleTop] from the eyebrow to the title, and [bodyTop] from the title to the content. A [centered] page
 * centres its text. The [titleStyle] is the question's type.
 */
internal data class PageSpacing(
    val barTop: Dp = CHAPTER_BAR_TOP,
    val artTop: Dp = 0.dp,
    val eyebrowTop: Dp = 18.dp,
    val titleTop: Dp = 9.2.dp,
    val bodyTop: Dp = ClearAirSpacing.md,
    val centered: Boolean = false,
    val titleStyle: TextStyle? = null,
)

/** The running text of a first-run page: body type, set in its whole line box as the mock's paragraphs are. */
internal val PageBody: TextStyle = ClearAirType.body.copy(lineHeightStyle = ClearAirLineBox)

/** The small text of a first-run page, in its whole line box. */
internal val PageSmall: TextStyle = ClearAirType.small.copy(lineHeightStyle = ClearAirLineBox)

/** The space under the foot's last control, above the navigation bar: the mock's 30 dp from the screen's bottom less the 24 dp of the gesture bar. */
private val FOOT_BOTTOM = 6.dp

/**
 * A first-run page: the chapter bar, the page's [art], its eyebrow and title, then [content]. The [footer] holds the page's actions and sits at
 * the foot, under the content, which scrolls when a small phone cannot show it all. Every first-run page has this shape.
 */
@Composable
internal fun FirstRunPage(
    step: FirstRunStep,
    eyebrow: String,
    title: String,
    footer: @Composable () -> Unit,
    art: (@Composable () -> Unit)? = null,
    spacing: PageSpacing = PageSpacing(),
    content: @Composable () -> Unit = {},
) {
    val palette = LocalClearAirPalette.current
    val side = if (spacing.centered) Alignment.CenterHorizontally else Alignment.Start
    val align = if (spacing.centered) TextAlign.Center else TextAlign.Start
    // The side insets go on the whole page: in landscape the navigation bar is at a side, and the chapter bar, title and body must clear it as the footer does.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter).padding(top = spacing.barTop),
                horizontalAlignment = side,
            ) {
                ChapterBar(step = step)
                Spacer(Modifier.height(spacing.artTop))
                art?.invoke()
                ClearAirEyebrow(text = eyebrow, top = spacing.eyebrowTop, bottom = spacing.titleTop, modifier = Modifier.fillMaxWidth(), textAlign = align)
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    style = spacing.titleStyle ?: ClearAirType.titleXL,
                    color = palette.ink,
                    textAlign = align,
                )
                Spacer(Modifier.height(spacing.bodyTop))
                content()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ClearAirSpacing.gutter)
                .navigationBarsPadding()
                .padding(top = ClearAirSpacing.md, bottom = FOOT_BOTTOM),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            footer()
        }
    }
}

/** The three-segment chapter bar, filled as [chapterFills] says for the step. It is 3 dp thick, with 6 dp between its segments, as the mock draws it. */
@Composable
private fun ChapterBar(step: FirstRunStep) {
    ClearAirSegments(fractions = chapterFills(step).map { it.fraction }, thickness = 3.dp, gap = 6.dp)
}

/** The AirVault mark, drawn at [size]. The first-run pages show it in place of per-step art. */
@Composable
private fun AirVaultMark(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_airvault_mark),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

/** The question of a statement page: 30 sp, a size up from the other first-run pages' 26 sp, as the Welcome mock sets it. */
private val FactTitle: TextStyle @Composable get() = ClearAirType.titleXL.copy(fontSize = 30.sp, lineHeight = 34.sp)

/** The art slot of a statement page: 132 dp tall, the size of the mark on Welcome, with the art centred in it. */
private val FACT_ART_SLOT = 132.dp

/**
 * One fact per page, centred, with its [art] above it, as the Welcome mock draws it: the pages after Welcome use the same layout. [action] is
 * "Get Started" on the first page and "Next" after it.
 */
@Composable
private fun FactPage(
    step: FirstRunStep,
    eyebrow: String,
    title: String,
    body: String,
    action: String,
    onNext: () -> Unit,
    art: @Composable () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    FirstRunPage(
        step = step,
        eyebrow = eyebrow,
        title = title,
        footer = { ClearAirPrimaryButton(text = action, onClick = onNext) },
        art = { Box(modifier = Modifier.fillMaxWidth().height(FACT_ART_SLOT), contentAlignment = Alignment.Center) { art() } },
        spacing = PageSpacing(artTop = 96.dp, eyebrowTop = 33.1.dp, titleTop = 9.8.dp, bodyTop = 15.dp, centered = true, titleStyle = FactTitle),
    ) {
        Text(text = body, style = ClearAirType.body, color = palette.ink2, textAlign = TextAlign.Center)
    }
}

/** Step I1: the AirVault mark large and alone, with the two-app relationship in two sentences. */
@Composable
fun WelcomeScreen(onNext: () -> Unit) = FactPage(
    step = FirstRunStep.Welcome,
    eyebrow = "Lumio",
    title = "Welcome to AirVault",
    body = "Lumio is the online half of AirVault. It shows your balances and carries what Lensly signs to the network. " +
        "Your keys stay with Lensly.",
    action = "Get Started",
    onNext = onNext,
    art = { AirVaultMark(size = 132.dp) },
)

/** Step I2: one fact, with its slab art above it. */
@Composable
fun KeysScreen(onNext: () -> Unit) = FactPage(
    step = FirstRunStep.Keys,
    eyebrow = "Understand · 2 of 5",
    title = "Your Keys Stay on Lensly",
    body = "Your keys live only on your offline Lensly phone. Lumio sees your addresses and balances, never your keys.",
    action = "Next",
    onNext = onNext,
    art = { KeysArt() },
)

/** Step I3: one fact, with its slab art above it. */
@Composable
fun NeverShareScreen(onNext: () -> Unit) = FactPage(
    step = FirstRunStep.NeverShare,
    eyebrow = "Understand · 3 of 5",
    title = "Never Share Your Keys with Anyone",
    body = "No one from AirVault will ever ask for your recovery phrase or password. Anyone who asks is trying to steal from you.",
    action = "Next",
    onNext = onNext,
    art = { NeverShareArt() },
)

/** Step I4: the software is provided as is, with its slab art above it. */
@Composable
fun ProvidedAsIsScreen(onNext: () -> Unit) = FactPage(
    step = FirstRunStep.ProvidedAsIs,
    eyebrow = "Understand · 4 of 5",
    title = "Provided as Is",
    body = "AirVault is provided as is, without warranty. You alone are responsible for your assets.",
    action = "Next",
    onNext = onNext,
    art = { ProvidedAsIsArt() },
)

/** Step I5: the three facts as ticks, and the owner's sentence behind a checkbox. Agree stays disabled until the box is ticked. */
@Composable
fun NoticeScreen(agreed: Boolean, onAgreedChange: (Boolean) -> Unit, onContinue: () -> Unit) {
    val palette = LocalClearAirPalette.current
    FirstRunPage(
        step = FirstRunStep.Notice,
        eyebrow = "Understand · 5 of 5",
        title = "Important Notice",
        footer = {
            ClearAirPrimaryButton(text = "Agree and Continue", onClick = onContinue, enabled = agreed)
            if (!agreed) {
                Text(
                    text = "Tick the box above to continue",
                    style = ClearAirType.label,
                    color = palette.ink2,
                    modifier = Modifier.padding(top = ClearAirSpacing.xs),
                )
            }
        },
        spacing = PageSpacing(eyebrowTop = 29.9.dp, bodyTop = 15.8.dp),
    ) {
        listOf(
            "Your keys never leave your phone",
            "Never share your keys with anyone",
            "The software is provided as is",
        ).forEach { fact ->
            Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                ClearAirIcon(glyph = ClearAirGlyph.CHECK, color = palette.vaultBlue, size = 15.dp, stroke = 2.2f)
                Spacer(Modifier.width(ClearAirSpacing.xs))
                Text(text = fact, style = ClearAirType.body.copy(fontSize = 14.sp, lineHeight = 21.sp, lineHeightStyle = ClearAirLineBox), color = palette.ink)
            }
        }
        Spacer(Modifier.height(12.9.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ClearAirSpacing.touchTarget)
                .toggleable(value = agreed, role = Role.Checkbox, onValueChange = onAgreedChange),
            verticalAlignment = Alignment.Top,
        ) {
            ClearAirCheckbox(checked = agreed, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Text(
                text = "I understand that AirVault, Lensly, or Lumio has no tokens, public sales, or a support team and that you must not " +
                    "transfer any cryptocurrency to anyone, especially someone claiming to be from alshukran.com.",
                style = ClearAirType.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp, lineHeightStyle = ClearAirLineBox),
                color = palette.ink,
            )
        }
    }
}

/** Step I6: what the next screens lead to, in three numbered steps, so the pairing scan is not a surprise. */
@Composable
fun PrepareScreen(onNext: () -> Unit) {
    val palette = LocalClearAirPalette.current
    FirstRunPage(
        step = FirstRunStep.PrepareLensly,
        eyebrow = "Pair · 1 of 2",
        title = "Prepare Lensly for Pairing",
        footer = { ClearAirPrimaryButton(text = "Next", onClick = onNext) },
        art = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PrepareArt() } },
        spacing = PageSpacing(artTop = 52.dp, eyebrowTop = 37.5.dp, bodyTop = 11.5.dp),
    ) {
        Text(
            text = "Keep Lensly nearby. You'll show its pairing code to this phone in a minute.",
            style = PageBody,
            color = palette.ink2,
        )
        Spacer(Modifier.height(10.9.dp))
        NumberedStep(number = 1, text = "Have your Lensly phone with you, charged and unlocked.")
        NumberedStep(number = 2, text = "Set a password for Lumio on the next screens.")
        NumberedStep(number = 3, text = "Then hold Lensly's pairing code up to this phone.")
    }
}

/** One numbered step of a first-run page: the number in an outlined disc, and the words beside it, as the mock draws its steps. */
@Composable
internal fun NumberedStep(number: Int, text: String) {
    val palette = LocalClearAirPalette.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(modifier = Modifier.size(25.dp).border(1.5.dp, palette.ink3, CircleShape), contentAlignment = Alignment.Center) {
            Text(text = "$number", style = ClearAirType.time.copy(fontWeight = FontWeight.SemiBold), color = palette.ink2)
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Text(text = text, style = ClearAirType.body.copy(fontSize = 14.sp, lineHeight = 20.sp, lineHeightStyle = ClearAirLineBox), color = palette.ink)
    }
}

/**
 * Step I8: the duress question. Set Up Now opens the Duress Password page, and Maybe Later goes on to I9, leaving the Duress Password for the
 * Finish Setting Up checklist and Settings → Security, which the line under the question names.
 */
@Composable
fun DuressScreen(onSetUp: () -> Unit, onMaybeLater: () -> Unit) {
    val palette = LocalClearAirPalette.current
    FirstRunPage(
        step = FirstRunStep.Duress,
        eyebrow = "Protect · 2 of 3",
        title = "Create Duress Password Now?",
        footer = {
            ClearAirPrimaryButton(text = "Set Up Now", onClick = onSetUp)
            ClearAirTextAction(text = "Maybe Later", onClick = onMaybeLater, modifier = Modifier.padding(top = ClearAirSpacing.xs))
        },
        art = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DuressArt() } },
        spacing = PageSpacing(barTop = 15.dp, artTop = 64.dp, eyebrowTop = 37.1.dp, titleTop = 10.dp, bodyTop = 13.7.dp),
    ) {
        Text(
            text = "A second password that opens an empty Lumio. Use it if someone forces you to unlock your phone.",
            style = ClearAirType.body,
            color = palette.ink2,
        )
        // The mock puts the line 6 dp under the paragraph, but the paragraph's own 12 px bottom margin collapses with that, so the gap is 12 dp; 13.7 dp lands the line where the mock draws it.
        Spacer(Modifier.height(13.7.dp))
        Text(text = "You can set it up later in Settings → Security.", style = PageSmall, color = palette.ink2)
    }
}

/**
 * Step I9: a fingerprint or face opens Lumio. Enable seals the vault key under the owner's biometric, and Not Now is a calm text
 * action. Without a strong biometric on the phone, Enable is off and the page says why.
 */
@Composable
fun DeviceProtectionScreen(available: Boolean, onEnable: () -> Unit, onNotNow: () -> Unit) {
    val palette = LocalClearAirPalette.current
    FirstRunPage(
        step = FirstRunStep.DeviceProtection,
        eyebrow = "Protect · 3 of 3",
        title = "Enable Device Protection",
        footer = {
            ClearAirPrimaryButton(text = "Enable", onClick = onEnable, enabled = available)
            ClearAirTextAction(text = "Not Now", onClick = onNotNow, modifier = Modifier.padding(top = ClearAirSpacing.xs))
        },
        art = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DeviceProtectionArt() } },
        spacing = PageSpacing(artTop = 60.dp, eyebrowTop = 39.3.dp, bodyTop = 12.1.dp),
    ) {
        Text(text = "Use your fingerprint or face to open Lumio.", style = PageBody, color = palette.ink2)
        Spacer(Modifier.height(10.9.dp))
        Text(
            text = "Your password still works whenever the sensor doesn't. You can change this later in Settings → Security.",
            style = PageSmall,
            color = palette.ink2,
        )
        if (!available) {
            Spacer(Modifier.height(ClearAirSpacing.xs))
            Text(text = BIOMETRIC_UNAVAILABLE_REASON, style = PageSmall, color = palette.ink2)
        }
    }
}
