package app.lumio.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.components.AlgorandIcon
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.LetterMonogram
import app.lensly.ui.components.SolanaIcon
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.components.tokenIconDrawables
import app.lumio.LumioApp
import app.lumio.R
import app.lumio.data.net.NetworkId
import app.lumio.home.HomeAsset
import app.lumio.home.TokenAsset
import app.lumio.ui.kit.ClearAirDisc
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Home draws beside one asset. [assetIconSpec] decides it without Compose, so the mapping is unit tested. */
sealed interface AssetIconSpec {
    /** The Bitcoin logo, for every Bitcoin coin. */
    data object Bitcoin : AssetIconSpec

    /** The Solana logo, for the Solana coin. */
    data object Solana : AssetIconSpec

    /** The Algorand logo, for the Algorand coin. */
    data object Algorand : AssetIconSpec

    /** The chain logo of a native coin. A null [network] is not in the registry, and shows the Ethereum logo, as [ChainIcon] does. */
    data class Chain(val network: NetworkEntry?) : AssetIconSpec

    /** The token's own logo from the registry. [chain] is the network it is on, which a pegged or bridged copy shows as its badge. */
    data class Token(val token: TokenEntry, val chain: NetworkEntry?) : AssetIconSpec

    /** A token with no logo: a neutral letter, the first letter or digit of its symbol. A token never shows a chain logo. */
    data class Letter(val letter: String) : AssetIconSpec

    /**
     * A token whose logo Lumio does not ship: the logo the price source has for the token at [contract] on [net], read once and kept on disk. Its
     * [letter] shows only when no source has a logo for it; while the logo loads, the disc is empty.
     */
    data class Fetched(val net: NetworkId, val contract: String, val letter: String) : AssetIconSpec
}

/** Reads the logo of a token, as [app.lumio.data.price.TokenLogos.imageOf] does. A test provides its own; the app reads [LumioApp.logos]. */
val LocalTokenLogos = staticCompositionLocalOf<(suspend (NetworkId, String) -> ByteArray?)?> { null }

/** The test tag of a logo read from the price source. */
const val FETCHED_LOGO_TAG = "fetched-logo"

/** The logos decoded this session, by network and contract, so a row drawn again shows its logo on its first frame. */
private val decodedLogos = ConcurrentHashMap<String, ImageBitmap>()

/**
 * The icon of [asset]. [network] is the registry entry of its EVM chain, and [token] the registry entry of an ERC-20 token, or null when the
 * registry does not list it. A token shows its own logo only when the registry names a logo that Lensly ships. Any other token, the owner's
 * or one with no logo, gets a letter, so no token row ever shows a chain logo.
 */
internal fun assetIconSpec(asset: HomeAsset, network: NetworkEntry?, token: TokenEntry?): AssetIconSpec = when {
    asset.network.isBtc -> AssetIconSpec.Bitcoin
    asset !is TokenAsset -> AssetIconSpec.Chain(network)
    token != null && hasLogo(token) -> AssetIconSpec.Token(token, network)
    else -> AssetIconSpec.Letter(letterOf(asset.symbol))
}

/** True when the registry names a logo for [token] and Lensly ships it. */
private fun hasLogo(token: TokenEntry): Boolean = token.icon?.let { tokenIconDrawables.containsKey(it) } == true

/** The icon of an ERC-20 row: the token's logo when Lensly ships it, else a letter, so a token never shows a chain logo. */
internal fun tokenIcon(token: TokenEntry?, network: NetworkEntry?, symbol: String): AssetIconSpec =
    if (token != null && hasLogo(token)) AssetIconSpec.Token(token, network) else AssetIconSpec.Letter(letterOf(symbol))

/** The letter of a token avatar: the first letter or digit of [symbol] in capitals, or "?" when the symbol has neither. */
internal fun letterOf(symbol: String): String =
    symbol.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** The logo of the EVM network [chainId], or the letter of its [name] when the registry does not list it, so a custom network never shows the Ethereum logo. */
internal fun chainIconOf(registry: Registry, chainId: Long, name: String): AssetIconSpec =
    registry.network(chainId)?.let { AssetIconSpec.Chain(it) } ?: AssetIconSpec.Letter(letterOf(name))

/**
 * The round mark Lumio draws in place of the shared logo of [spec], as coin lists show these four: Bitcoin, BNB, HyperEVM's HYPE and USDC. Null for
 * every other logo. Lensly keeps the shared marks.
 */
internal fun roundMarkOf(spec: AssetIconSpec): Int? = when (spec) {
    AssetIconSpec.Bitcoin -> R.drawable.lumio_mark_bitcoin
    is AssetIconSpec.Chain -> when (spec.network?.chainId) {
        BNB_CHAIN -> R.drawable.lumio_mark_bnb
        HYPER_EVM -> R.drawable.lumio_mark_hyperevm
        else -> null
    }
    is AssetIconSpec.Token -> if (spec.token.icon == USDC_ICON) R.drawable.lumio_mark_usdc else null
    else -> null
}

private const val BNB_CHAIN = 56L
private const val HYPER_EVM = 999L
private const val USDC_ICON = "token_usdc"

/** The icon of [spec] at [size]. It is decorative: the text beside it names the asset. */
@Composable
internal fun AssetIcon(spec: AssetIconSpec, size: Dp, modifier: Modifier = Modifier) {
    roundMarkOf(spec)?.let { mark ->
        Image(painter = painterResource(mark), contentDescription = null, modifier = modifier.size(size))
        return
    }
    when (spec) {
        AssetIconSpec.Bitcoin -> BitcoinIcon(size = size, modifier = modifier)
        AssetIconSpec.Solana -> SolanaIcon(size = size, modifier = modifier)
        AssetIconSpec.Algorand -> AlgorandIcon(size = size, modifier = modifier)
        is AssetIconSpec.Chain -> ChainIcon(network = spec.network, size = size, modifier = modifier)
        is AssetIconSpec.Token -> TokenIcon(token = spec.token, size = size, modifier = modifier, chain = spec.chain)
        is AssetIconSpec.Letter -> LetterMonogram(letter = spec.letter, size = size, modifier = modifier)
        is AssetIconSpec.Fetched -> FetchedLogo(spec = spec, size = size, modifier = modifier)
    }
}

/** The logo of a [AssetIconSpec.Fetched] token, round at [size]; nothing while it loads, and its letter when no source has one. */
@Composable
private fun FetchedLogo(spec: AssetIconSpec.Fetched, size: Dp, modifier: Modifier) {
    val reader = LocalTokenLogos.current ?: (LocalContext.current.applicationContext as? LumioApp)?.logos?.let { logos -> logos::imageOf }
    val key = "${spec.net.chain.caip2}|${spec.contract}"
    val logo by produceState<Result<ImageBitmap?>?>(decodedLogos[key]?.let { Result.success(it) }, key, reader) {
        if (value == null) {
            value = Result.success(
                reader?.let { read -> withContext(Dispatchers.IO) { read(spec.net, spec.contract)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } } }
                    ?.also { decodedLogos[key] = it },
            )
        }
    }
    when (val image = logo?.getOrNull()) {
        null -> if (logo != null) LetterMonogram(letter = spec.letter, size = size, modifier = modifier) else Box(modifier.size(size))
        else -> Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape).testTag(FETCHED_LOGO_TAG))
    }
}

/** The icon of [spec] on the 36 dp disc that leads a list row. */
@Composable
internal fun AssetDisc(spec: AssetIconSpec, modifier: Modifier = Modifier) {
    ClearAirDisc(modifier) { AssetIcon(spec = spec, size = 21.dp) }
}
