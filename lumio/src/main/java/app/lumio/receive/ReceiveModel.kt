package app.lumio.receive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lumio.ui.home.AssetIconSpec

/**
 * A coin the Receive picker offers. [held] puts it under Your Assets, and [subtitle] is the line under its name. [networks] are the networks it is
 * native on, and [networkNote] is the line under the network choices, such as the bridged-asset rule. [algorandAssetId] is the ID of the Algorand asset when
 * the coin is one, so that its Receive can ask whether the account has opted in to it.
 */
internal data class ReceiveCoin(
    val symbol: String,
    val name: String,
    val subtitle: String,
    val held: Boolean,
    val icon: AssetIconSpec,
    val networks: List<ReceiveNetwork>,
    val networkNote: String? = null,
    val algorandAssetId: ULong? = null,
)

/** The capsule under the address of a Receive: Copy Address, or Opt In First for an Algorand asset the account has not opted in to. */
internal sealed interface ReceiveCapsule {
    data object CopyAddress : ReceiveCapsule

    data class OptInFirst(val assetId: ULong) : ReceiveCapsule
}

/**
 * The capsule of a Receive of [coin] into an account whose opt-in to the coin's asset is [optedIn]. Only a confirmed "not opted in" asks for the opt-in:
 * a null answer, while the read runs or after it fails, and an opted-in account both keep Copy Address, so Receive never waits on the network.
 */
internal fun receiveCapsuleOf(coin: ReceiveCoin, optedIn: Boolean?): ReceiveCapsule {
    val assetId = coin.algorandAssetId
    return if (assetId != null && optedIn == false) ReceiveCapsule.OptInFirst(assetId) else ReceiveCapsule.CopyAddress
}

/**
 * A network a coin is native on, with the [accounts] that can take the coin there. [name] is the row's words, such as "Base L2 on Ethereum", and [label]
 * is the short name that the eyebrows and the warning use, such as "Base". [accountNote] is the line under the account choices.
 */
internal data class ReceiveNetwork(
    val name: String,
    val icon: AssetIconSpec,
    val accounts: List<ReceiveAccount>,
    val accountNote: String? = null,
    val label: String = name,
)

/** An account that can receive. [name], [label] and [group] are for the owner to read: the share text never has them. */
internal data class ReceiveAccount(val name: String, val label: String?, val group: String?, val address: String)

/** What a Receive knows: the coin, network and account it opened with, and the ones the owner has picked since. A null answer is asked. */
internal data class ReceiveAnswers(val coin: ReceiveCoin? = null, val network: ReceiveNetwork? = null, val account: ReceiveAccount? = null)

/** The step a Receive shows. [ChooseAsset] lists the coins, and the other steps list the choices of the answers before them. */
internal sealed interface ReceiveStep {
    data class ChooseAsset(val assets: List<ReceiveCoin>) : ReceiveStep

    data class ChooseNetwork(val coin: ReceiveCoin) : ReceiveStep

    data class ChooseAccount(val coin: ReceiveCoin, val network: ReceiveNetwork) : ReceiveStep

    data class Receive(val coin: ReceiveCoin, val network: ReceiveNetwork, val account: ReceiveAccount) : ReceiveStep
}

/**
 * The step that asks next for [answers]. A missing answer is asked, unless it has exactly one choice: that choice is taken, and the step after it
 * is asked. [assets] are the coins the picker lists when no coin is known yet.
 */
internal fun receiveStep(answers: ReceiveAnswers, assets: List<ReceiveCoin>): ReceiveStep {
    val coin = answers.coin ?: assets.singleOrNull() ?: return ReceiveStep.ChooseAsset(assets)
    val network = answers.network ?: coin.networks.singleOrNull() ?: return ReceiveStep.ChooseNetwork(coin)
    val account = answers.account ?: network.accounts.singleOrNull() ?: return ReceiveStep.ChooseAccount(coin, network)
    return ReceiveStep.Receive(coin, network, account)
}

/**
 * One open Receive: its [assets], the answers it opened with, and the answers before each pick, so Back goes one step back. [choose] records a pick,
 * and [back] returns to the answers before the last one.
 */
internal class ReceiveFlow(val assets: List<ReceiveCoin>, start: ReceiveAnswers) {
    /** The answers so far. Reading them in composition makes the sheet recompose when a pick changes them. */
    var answers by mutableStateOf(start)
        private set

    private val history = mutableListOf<ReceiveAnswers>()

    /** The step that asks next. */
    val step: ReceiveStep get() = receiveStep(answers, assets)

    /** True when a pick can be undone: the owner has picked something since the flow opened. */
    val canGoBack: Boolean get() = history.isNotEmpty()

    /** Takes [next] as the answers after a pick, and keeps the current answers for Back. */
    fun choose(next: ReceiveAnswers) {
        history.add(answers)
        answers = next
    }

    /** Returns to the answers before the last pick. Does nothing when no pick has been made. */
    fun back() {
        if (history.isNotEmpty()) answers = history.removeAt(history.lastIndex)
    }
}

/** The warning under the address of a Receive on [network]. */
internal fun receiveNote(network: String): String = "Send only $network assets here. Assets sent on other networks may be lost."

/**
 * The sentence that Share sends with the QR code: what to send, to the full [address], on which network, and the same warning. It has no names, and the
 * address is never shortened, since the owner may paste it from the message.
 */
internal fun receiveShareText(symbol: String, address: String, network: String): String =
    "Send $symbol on $address on the $network network. Assets sent on other networks may be lost."

/** The initials of an account's monogram: the capitals of its [name], at most two, or its first letter when it has none. */
internal fun monogramOf(name: String): String =
    name.filter { it.isUpperCase() }.take(MONOGRAM_LETTERS).ifEmpty { name.take(1).uppercase() }

private const val MONOGRAM_LETTERS = 2
