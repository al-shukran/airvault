package app.lumio.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import app.lumio.home.HomeAsset
import app.lumio.home.TokenAsset
import app.lumio.ui.kit.ClearAirDisc
import android.util.LruCache
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
     * A token whose logo Lumio does not ship, drawn from the market source's image at [url], which was kept with it when it was starred. Its [letter]
     * shows only when the image does not load; while it loads, the disc is empty.
     */
    data class Image(val url: String, val letter: String) : AssetIconSpec
}

/** Reads a logo image by its URL, as [app.lumio.data.price.CoinImages.logoOf] does. A test provides its own; the app reads [LumioApp.coinImages]. */
val LocalCoinImages = staticCompositionLocalOf<(suspend (String) -> Bitmap?)?> { null }

/** The test tag of a logo drawn from an image URL. */
const val IMAGE_LOGO_TAG = "image-logo"

/**
 * The logos decoded for the open vault, by URL, so a row drawn again shows its logo on its first frame. At most [DECODED_LOGOS] are kept, and all are
 * dropped when the vault changes or locks.
 */
private object DecodedLogos {
    private var owner: Any? = null
    private val logos = LruCache<String, ImageBitmap>(DECODED_LOGOS)

    @Synchronized
    fun get(owner: Any?, url: String): ImageBitmap? {
        if (owner !== this.owner) {
            logos.evictAll()
            this.owner = owner
        }
        return logos.get(url)
    }

    @Synchronized
    fun put(owner: Any?, url: String, logo: ImageBitmap) {
        if (owner === this.owner) logos.put(url, logo)
    }
}

private const val DECODED_LOGOS = 128

/** The outcome of reading a logo image: still loading, a bitmap, or none. */
sealed interface LogoLoad {
    data object Loading : LogoLoad

    data class Loaded(val bitmap: ImageBitmap) : LogoLoad

    data object Missing : LogoLoad
}

/**
 * The logo at [url], read through [LocalCoinImages], or [LumioApp.coinImages] while the vault is open. A logo decoded for the open vault shows on the
 * first frame.
 */
@Composable
fun rememberLogo(url: String): LogoLoad {
    val app = LocalContext.current.applicationContext as? LumioApp
    val owner = app?.lumioVault()
    val reader = LocalCoinImages.current ?: app?.coinImages?.let { images -> images::logoOf }
    val logo by produceState<LogoLoad>(DecodedLogos.get(owner, url)?.let { LogoLoad.Loaded(it) } ?: LogoLoad.Loading, url, reader, owner) {
        if (value is LogoLoad.Loaded) return@produceState
        val bitmap = reader?.let { read -> withContext(Dispatchers.IO) { read(url)?.asImageBitmap() } }
        value = bitmap?.also { DecodedLogos.put(owner, url, it) }?.let { LogoLoad.Loaded(it) } ?: LogoLoad.Missing
    }
    return logo
}

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
 * A mark Lumio draws in place of the shared logo: the [res] image, drawn at [share] of the icon's size and clipped round when [round], as coin lists
 * draw it.
 */
data class LumioMark(@DrawableRes val res: Int, val share: Float = 1f, val round: Boolean = false)

/**
 * The mark Lumio draws for [spec] in place of the shared logo, for the coins whose coin-list art Lumio ships: Bitcoin, Ether, Tether, BNB, USD Coin,
 * Solana and HyperEVM's HYPE. Each is drawn at the share of the 24 dp logo slot the approved mock gives it. Null for every other logo. Lensly keeps the
 * shared marks.
 */
internal fun lumioMarkOf(spec: AssetIconSpec): LumioMark? = when (spec) {
    AssetIconSpec.Bitcoin -> LumioMark(R.drawable.lumio_mark_bitcoin, round = true)
    AssetIconSpec.Solana -> LumioMark(R.drawable.lumio_mark_solana, share = 22f / 24f)
    is AssetIconSpec.Chain -> when (spec.network?.chainId) {
        ETHEREUM -> LumioMark(R.drawable.lumio_mark_ethereum, share = 23f / 24f)
        BNB_CHAIN -> LumioMark(R.drawable.lumio_mark_bnb, round = true)
        HYPER_EVM -> LumioMark(R.drawable.lumio_mark_hyperevm, round = true)
        else -> null
    }
    is AssetIconSpec.Token -> when (spec.token.icon) {
        USDC_ICON -> LumioMark(R.drawable.lumio_mark_usdc, round = true)
        USDT_ICON -> LumioMark(R.drawable.lumio_mark_tether)
        else -> null
    }
    else -> null
}

private const val ETHEREUM = 1L
private const val BNB_CHAIN = 56L
private const val HYPER_EVM = 999L
private const val USDC_ICON = "token_usdc"
private const val USDT_ICON = "token_usdt"

/** The icon of [spec] at [size]. It is decorative: the text beside it names the asset. */
@Composable
internal fun AssetIcon(spec: AssetIconSpec, size: Dp, modifier: Modifier = Modifier) {
    lumioMarkOf(spec)?.let { mark ->
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(mark.res),
                contentDescription = null,
                modifier = Modifier.size(size * mark.share).then(if (mark.round) Modifier.clip(CircleShape) else Modifier),
            )
        }
        return
    }
    when (spec) {
        AssetIconSpec.Bitcoin -> BitcoinIcon(size = size, modifier = modifier)
        AssetIconSpec.Solana -> SolanaIcon(size = size, modifier = modifier)
        AssetIconSpec.Algorand -> AlgorandIcon(size = size, modifier = modifier)
        is AssetIconSpec.Chain -> ChainIcon(network = spec.network, size = size, modifier = modifier)
        is AssetIconSpec.Token -> TokenIcon(token = spec.token, size = size, modifier = modifier, chain = spec.chain)
        is AssetIconSpec.Letter -> LetterMonogram(letter = spec.letter, size = size, modifier = modifier)
        is AssetIconSpec.Image -> when (val logo = rememberLogo(spec.url)) {
            LogoLoad.Loading -> Box(modifier.size(size))
            LogoLoad.Missing -> LetterMonogram(letter = spec.letter, size = size, modifier = modifier)
            is LogoLoad.Loaded -> RoundLogo(bitmap = logo.bitmap, size = size, modifier = modifier)
        }
    }
}

/** A logo [bitmap], round at [size], as a coin list draws a coin's image. */
@Composable
fun RoundLogo(bitmap: ImageBitmap, size: Dp, modifier: Modifier = Modifier) {
    Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape).testTag(IMAGE_LOGO_TAG))
}

/** The icon of [spec] on the 36 dp disc that leads a list row. */
@Composable
internal fun AssetDisc(spec: AssetIconSpec, modifier: Modifier = Modifier) {
    ClearAirDisc(modifier) { AssetIcon(spec = spec, size = 21.dp) }
}
