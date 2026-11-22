package app.lumio.ui.send

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.lumio.send.ArrivalWatch
import app.lumio.send.Landing
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.ui.kit.LumioHaptics
import app.lumio.ui.kit.rememberLumioHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val COUNT_STEP_MS = 1_000L

/**
 * The pages of a signed transaction, shared by every chain, in the order its state moves: Last Chance while it waits and then sends, On Its Way once
 * the network has it, and the failure page if the network refused it. The countdown runs only while the transaction is still idle, and "Send Now"
 * skips the wait. [headline] and [timing] say what the network is doing, and [onSent] is told the id the network reported.
 * [arrival] watches the sent transaction until it lands, and then the page says so, or until the network refuses it, and then the page says that instead.
 * Null watches nothing, and the page stays on its way.
 * [onStop] ends the send before anything is sent, [onDone] leaves the sent or failed transaction, and [onReview] is where a failure that cannot be
 * retried goes, so the owner can start the send again.
 */
@Composable
internal fun SignedSendSteps(
    machine: SubmitMachine<*, String>,
    headline: String,
    amount: String,
    recipient: String,
    network: String,
    timing: String,
    fromName: String,
    arrival: ArrivalWatch?,
    explorerUrl: (String) -> String?,
    onExplorer: (String) -> Unit,
    onSent: (String) -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onReview: () -> Unit,
    backLabel: String,
    offline: String?,
    onLock: () -> Unit,
) {
    val state by machine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberLumioHaptics()
    when (val now = state) {
        SubmitState.Idle, SubmitState.Submitting -> {
            var remaining by remember { mutableIntStateOf(LAST_CHANCE_SECONDS) }
            LaunchedEffect(Unit) {
                while (remaining > 0 && machine.state.value is SubmitState.Idle) {
                    delay(COUNT_STEP_MS)
                    if (machine.state.value is SubmitState.Idle) remaining -= 1
                }
                if (machine.state.value is SubmitState.Idle) machine.submit()
            }
            SendLastChancePage(
                recipient = recipient,
                amount = amount,
                remaining = remaining,
                sending = now is SubmitState.Submitting,
                onSendNow = { scope.launch { machine.submit() } },
                onStop = onStop,
                backLabel = backLabel,
                offline = offline,
                onBack = onStop,
                onLock = onLock,
            )
        }
        is SubmitState.Sent -> {
            LaunchedEffect(now.id) {
                haptics.success()
                onSent(now.id)
            }
            val summary = "$amount to $recipient"
            val landing by remember(now.id) { arrival?.landingOf(now.id, summary) ?: MutableStateFlow(Landing.PENDING) }.collectAsState()
            ArrivalHaptic(arrived = landing == Landing.LANDED, id = now.id, haptics = haptics)
            if (landing == Landing.REFUSED) {
                LaunchedEffect(now.id) { haptics.failure() }
                SendOutcomePage(
                    title = "",
                    heading = "It Did Not Go Through",
                    mark = "!",
                    body = "The network did not complete this transaction, so the amount did not arrive.",
                    notes = listOf(
                        "What Happened" to "The network took the transaction, then did not complete it.",
                        "What You Can Do" to "The explorer shows the reason. A new send shows every detail again before Lensly signs it.",
                    ),
                    detail = null,
                    primary = "Back to $fromName",
                    onPrimary = onDone,
                    secondary = explorerUrl(now.id)?.let { "View on Explorer" },
                    onSecondary = { onExplorer(now.id) },
                    backLabel = backLabel,
                    onBack = onDone,
                    onLock = onLock,
                )
            } else {
                SendOnItsWayPage(
                    headline = arrival?.arrivedHeadline?.takeIf { landing == Landing.LANDED } ?: headline,
                    summary = summary,
                    network = network,
                    timing = timing,
                    recipient = recipient,
                    signedAtMs = machine.signedAtMs,
                    sentAtMs = now.sentAtMs,
                    arrived = landing == Landing.LANDED,
                    explorerLabel = explorerUrl(now.id)?.let { "View on Explorer" },
                    onExplorer = { onExplorer(now.id) },
                    onDone = onDone,
                    backLabel = backLabel,
                    onBack = onDone,
                    onLock = onLock,
                )
            }
        }
        is SubmitState.Failed -> {
            LaunchedEffect(Unit) { haptics.failure() }
            SendOutcomePage(
                title = "",
                heading = "Nothing Left Your Account",
                mark = "!",
                body = now.message,
                notes = listOf(
                    "What Happened" to now.message,
                    "What You Can Do" to if (now.retryable) {
                        "Try again sends the same signed transaction. Nothing was sent."
                    } else {
                        "This signed transaction can no longer be sent. Start the send again, and Lensly signs it afresh."
                    },
                ),
                detail = null,
                primary = "Try Again",
                onPrimary = {
                    if (now.retryable) scope.launch { machine.submit() } else onReview()
                },
                secondary = "Back to $fromName",
                onSecondary = onDone,
                backLabel = backLabel,
                onBack = onDone,
                onLock = onLock,
            )
        }
    }
}

/**
 * The confirm haptic, once, when the send lands while the page is open. A page opened after the send had landed stays quiet: the owner already knows.
 */
@Composable
internal fun ArrivalHaptic(arrived: Boolean, id: String, haptics: LumioHaptics) {
    val landedOnOpen = remember(id) { arrived }
    LaunchedEffect(arrived) {
        if (arrived && !landedOnOpen) haptics.success()
    }
}
