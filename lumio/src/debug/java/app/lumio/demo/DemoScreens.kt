package app.lumio.demo

import app.airvault.core.model.AccountId
import app.lumio.LumioRoute
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.ui.firstrun.FirstRunStep
import app.lumio.watchlist.WatchedAsset

internal enum class DemoTheme { LIGHT, DARK }

/**
 * What the route builders of [DemoScreens] need from the seeded vault: the wallet, its accounts on the side [mode] shows, and the keys of two
 * transactions. [sent] and [receivedToken] are `account|txId` pairs, or null when the history has not landed.
 */
internal class DemoContext(
    val walletId: String,
    val mode: NetworkMode,
    val evm: List<AccountId>,
    val bitcoin: AccountId,
    val sent: String? = null,
    val receivedToken: String? = null,
) {
    /** The first Ethereum account, PulsarNova. */
    val pulsarNova: AccountId get() = evm[0]

    val evmChain: Long get() = if (mode == NetworkMode.TESTNET) DemoFixtures.MONAD_TESTNET else DemoFixtures.ETHEREUM

    fun transaction(key: String?): String = (key ?: "none|none").split("|").let { LumioRoute.transaction(walletId, it[0], it[1]) }
}

/**
 * One mockup frame that debug builds open directly. [id] is the frame's name: `p1` or `p2` for the part of the mockups (`p3` for the Home redesign), then the frame's number.
 * [route] is where it opens, [flags] are the sheet or state that opens on it (see [DebugDemoData.flag]), [profile] is how the demo sources
 * answer, [fontScale] is the system font scale it is captured at, and [size] is a `wm size` the capture sets, or null for the phone's own.
 */
internal class DemoScreen(
    val id: String,
    val themes: Set<DemoTheme>,
    val mode: NetworkMode,
    val flags: Set<String> = emptySet(),
    val profile: DemoProfile = DemoProfile.FULL,
    val fontScale: Float = 1f,
    val size: String? = null,
    val route: (DemoContext) -> String,
)

/** The table of the mockup frames, in the order of the mockups. The capture script walks `tools/mockup-capture/ids.tsv`, which names the same ids. */
internal object DemoScreens {
    private val both = setOf(DemoTheme.LIGHT, DemoTheme.DARK)
    private val light = setOf(DemoTheme.LIGHT)
    private val dark = setOf(DemoTheme.DARK)
    private val main = NetworkMode.MAINNET
    private val test = NetworkMode.TESTNET

    private fun ethereum(chain: Long, test: Boolean) = NetworkId(Networks.evm(chain), testnet = test)

    val all: List<DemoScreen> = listOf(
        DemoScreen("p1-00", light, main) { LumioRoute.HOME },
        DemoScreen("p1-01", dark, main) { LumioRoute.HOME },
        DemoScreen("p1-02", both, test, setOf("panel")) { LumioRoute.HOME },
        DemoScreen("p1-03", both, test) { LumioRoute.TEST_NETWORKS },
        DemoScreen("p1-04", both, main, setOf("search")) { LumioRoute.HOME },
        DemoScreen("p1-05", both, main) { LumioRoute.wallet(it.walletId) },
        DemoScreen("p1-06", both, main) { LumioRoute.account(it.walletId, it.pulsarNova) },
        DemoScreen("p1-07", both, main) { LumioRoute.coin(it.walletId, WatchedAsset(ethereum(DemoFixtures.ETHEREUM, false))) },
        DemoScreen("p1-08", both, main) { LumioRoute.PORTFOLIO },
        DemoScreen("p1-09", both, main, size = "360x640") { LumioRoute.account(it.walletId, it.pulsarNova) },
        DemoScreen("p1-10", both, test) { send(it) },
        DemoScreen("p1-11", both, test, setOf("send:amount")) { send(it) },
        DemoScreen("p1-12", both, test, setOf("send:review")) { send(it) },
        DemoScreen("p1-13", both, test, setOf("send:show")) { send(it) },
        DemoScreen("p1-14", both, test, setOf("send:scan")) { send(it) },
        DemoScreen("p1-15", both, test, setOf("send:signed")) { send(it) },
        DemoScreen("p1-16", both, test, setOf("send:sent")) { send(it) },
        DemoScreen("p1-17", both, test, setOf("send:failed")) { send(it) },
        DemoScreen("p1-18", both, main, setOf("receive")) { LumioRoute.account(it.walletId, it.pulsarNova) },
        DemoScreen("p1-19", both, main) { FirstRunStep.Welcome.route },
        DemoScreen("p1-20", both, main) { FirstRunStep.Notice.route },
        DemoScreen("p1-21", both, main) { FirstRunStep.Duress.route },
        DemoScreen("p1-22", both, main) { FirstRunStep.Pair.route },
        DemoScreen("p1-23", both, main) { LumioRoute.firstSync(it.walletId) },
        DemoScreen("p1-24", both, main, setOf("pair:review")) { LumioRoute.PAIR_REVIEW },
        DemoScreen("p1-25", both, main, profile = DemoProfile.EMPTY) { LumioRoute.PORTFOLIO },
        DemoScreen("p1-26", both, main) { LumioRoute.SETTINGS },
        DemoScreen("p2-00", both, main, profile = DemoProfile.OFFLINE, flags = setOf("offline")) { LumioRoute.HOME },
        DemoScreen("p2-01", dark, main, profile = DemoProfile.OFFLINE, flags = setOf("offline")) { LumioRoute.PORTFOLIO },
        DemoScreen("p2-02", both, main) { LumioRoute.WALLETS },
        DemoScreen("p2-03", both, main, profile = DemoProfile.SLOW, flags = setOf("fresh")) { LumioRoute.wallet(it.walletId) },
        DemoScreen("p2-04", dark, main, profile = DemoProfile.SLOW, flags = setOf("fresh")) { LumioRoute.PORTFOLIO },
        DemoScreen("p2-05", both, main, setOf("filter:chain")) { LumioRoute.PORTFOLIO },
        DemoScreen("p2-06", dark, main, setOf("filter:group")) { LumioRoute.PORTFOLIO },
        DemoScreen("p2-07", both, main, setOf("filter:sort")) { LumioRoute.PORTFOLIO },
        DemoScreen("p2-08", both, main) { LumioRoute.ACTIVITY },
        DemoScreen("p2-09", light, main) { it.transaction(it.sent) },
        DemoScreen("p2-10", dark, main) { it.transaction(it.receivedToken) },
        DemoScreen("p2-11", both, main, fontScale = 2.0f) { it.transaction(it.sent) },
        DemoScreen("p2-12", light, main, setOf("abinfo")) { it.transaction(it.sent) },
        DemoScreen("p2-13", dark, main, setOf("abinfo")) { it.transaction(it.receivedToken) },
        DemoScreen("p2-14", both, main) { LumioRoute.addressHandoff(DemoFixtures.EXCHANGE_DEPOSIT, edit = false) },
        DemoScreen("p2-15", both, main, profile = DemoProfile.ERROR, flags = setOf("fresh")) { LumioRoute.wallet(it.walletId) },
        DemoScreen("p2-16", both, test, setOf("send:amount", "fee:advanced")) { send(it) },
        DemoScreen("p2-17", dark, test, setOf("fee:bitcoin")) { LumioRoute.btcSend(it.walletId, it.bitcoin) },
        DemoScreen("p2-18", both, test, setOf("send:declined")) { send(it) },
        DemoScreen("p2-19", dark, test, setOf("send:mismatch")) { send(it) },
        DemoScreen("p2-20", both, test, setOf("send:amount", "send:changeRecipient")) { send(it) },
        DemoScreen("p2-21", both, test, setOf("send:recipientError")) { send(it) },
        DemoScreen("p2-22", dark, test, setOf("send:amountError")) { send(it) },
        DemoScreen("p2-23", both, main, setOf("receive:asset")) { LumioRoute.account(it.walletId, it.pulsarNova) },
        DemoScreen("p2-24", both, main, setOf("receive:network")) { LumioRoute.account(it.walletId, it.pulsarNova) },
        DemoScreen("p2-25", dark, main, setOf("receive:account")) { LumioRoute.account(it.walletId, it.pulsarNova) },
        DemoScreen("p2-26", light, main) { FirstRunStep.PrepareLensly.route },
        DemoScreen("p2-27", dark, main) { FirstRunStep.PrepareLensly.route },
        DemoScreen("p2-28", both, main, setOf("prefill")) { FirstRunStep.CreatePassword.route },
        DemoScreen("p2-29", light, main) { FirstRunStep.DeviceProtection.route },
        DemoScreen("p2-30", dark, main) { FirstRunStep.DeviceProtection.route },
        DemoScreen("p2-31", both, main, setOf("pair:review")) { LumioRoute.PAIR_REVIEW },
        DemoScreen("p2-32", both, main, setOf("sync:searching")) { LumioRoute.firstSync(it.walletId) },
        DemoScreen("p2-33", dark, main, setOf("sync:none")) { LumioRoute.firstSync(it.walletId) },
        DemoScreen("p2-34", both, main, setOf("pair:review", "pair:repair")) { LumioRoute.PAIR_REVIEW },
        DemoScreen("p2-35", both, main, setOf("camera:denied")) { FirstRunStep.Pair.route },
        DemoScreen("p2-36", both, main, setOf("sort")) { LumioRoute.HOME },
        DemoScreen("p2-37", both, main) { LumioRoute.EXPLORE },
        DemoScreen("p2-38", both, main) { LumioRoute.network(DemoFixtures.ETHEREUM) },
        DemoScreen("p2-39", dark, main) { LumioRoute.provider("etherscan") },
        DemoScreen("p2-40", both, main) { LumioRoute.SETTINGS_DISPLAY },
        DemoScreen("p2-41", dark, main, setOf("currency")) { LumioRoute.SETTINGS_DISPLAY },
        DemoScreen("p2-42", both, main) { LumioRoute.SETTINGS_TRANSACTIONS },
        DemoScreen("p2-43", both, main) { LumioRoute.SETTINGS_NETWORKS },
        DemoScreen("p2-44", both, main) { LumioRoute.DATA_PROVIDERS },
        DemoScreen("p2-45", both, main) { LumioRoute.SETTINGS_WALLETS },
        DemoScreen("p2-46", both, main) { LumioRoute.settingsWallet(it.walletId) },
        DemoScreen("p2-47", dark, main, setOf("wallet:remove")) { LumioRoute.settingsWallet(it.walletId) },
        DemoScreen("p2-48", both, main) { LumioRoute.addressBook(it.walletId) },
        DemoScreen("p2-49", both, main) { LumioRoute.SETTINGS_SECURITY },
        DemoScreen("p2-50", both, main, setOf("prefill")) { LumioRoute.CHANGE_PASSWORD },
        DemoScreen("p2-51", both, main) { LumioRoute.tokens(DemoFixtures.ETHEREUM) },
        DemoScreen("p2-52", both, main) { LumioRoute.SETTINGS_ABOUT },
        DemoScreen("p2-53", dark, main) { LumioRoute.LICENCES },
        DemoScreen("p2-54", both, main, setOf("reset")) { LumioRoute.SETTINGS },
        DemoScreen("p3-00", both, main) { LumioRoute.WATCHLIST_ADD },
        DemoScreen("p3-01", both, main, setOf("watchlist:search")) { LumioRoute.WATCHLIST_ADD },
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): DemoScreen? = id?.let(byId::get)

    private fun send(c: DemoContext): String =
        LumioRoute.send(c.walletId, c.pulsarNova, DemoFixtures.MONAD_TESTNET)
}
