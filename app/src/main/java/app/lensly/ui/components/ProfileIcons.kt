package app.lensly.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.lensly.app.ProfileIcon

/**
 * The crypto, money and life glyphs of the Visual Identity set, beyond the twelve the core icon set has. The project ships no
 * extended icon set, so these are outlines at 24 dp drawn the way the camera glyphs are. They are white, and the call site
 * tints them. Each one is one to four strokes, so it reads at 22 dp.
 */
internal object ProfileIcons {
    /** A star, stroked as an outline: the core Outlined star is a filled one, so it is drawn here to match the set. */
    val Star: ImageVector by lazy {
        outline(
            "Star",
            "M12 17.27 18.18 21 16.54 13.97 22 9.24 14.81 8.63 12 2 9.19 8.63 2 9.24 7.46 13.97 5.82 21z",
        )
    }

    /** A heart, stroked as an outline: the core Outlined heart is a filled one, so it is drawn here to match the set. */
    val Heart: ImageVector by lazy {
        outline(
            "Heart",
            "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5c0-3.08 2.42-5.5 5.5-5.5c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3C19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
        )
    }

    /** A coin with a slot. */
    val Coin: ImageVector by lazy {
        outline(
            "Coin",
            "M4 12a8 8 0 0 1 16 0a8 8 0 0 1 -16 0z",
            "M12 8v8",
            "M9.5 9.5h5",
            "M9.5 14.5h5",
        )
    }

    /** Two coins, one behind the other. */
    val Coins: ImageVector by lazy {
        outline(
            "Coins",
            "M4 14.5a4 4 0 0 1 8 0a4 4 0 0 1 -8 0z",
            "M11 9.5a4 4 0 0 1 8 0a4 4 0 0 1 -2.5 3.7",
        )
    }

    /** A vault door with its dial and handle. */
    val Vault: ImageVector by lazy {
        outline(
            "Vault",
            "M4 5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z",
            "M9 12a3 3 0 0 1 6 0a3 3 0 0 1 -6 0z",
            "M15 12h3",
        )
    }

    /** A bank: pediment, columns and step. */
    val Bank: ImageVector by lazy {
        outline(
            "Bank",
            "M3 9 12 4l9 5",
            "M5.5 9v8",
            "M9.5 9v8",
            "M14.5 9v8",
            "M18.5 9v8",
            "M3.5 20h17",
        )
    }

    /** A payment card with its stripe and chip. */
    val Card: ImageVector by lazy {
        outline(
            "Card",
            "M3 6a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z",
            "M3 10h18",
            "M6 14h3",
        )
    }

    /** A banknote with its value in the middle. */
    val Banknote: ImageVector by lazy {
        outline(
            "Banknote",
            "M2 7.5h20v9H2z",
            "M9.5 12a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1 -5 0z",
            "M5 12h1.5",
            "M17.5 12H19",
        )
    }

    /** A chart that rises to the right, for a price or a balance. */
    val ChartUp: ImageVector by lazy {
        outline(
            "ChartUp",
            "M4 4v16h16",
            "M7 15l3.5-4 3 2.5L20 7",
            "M16.5 7H20v3.5",
        )
    }

    /** A pie chart with two slices marked. */
    val PieChart: ImageVector by lazy {
        outline(
            "PieChart",
            "M4 12a8 8 0 0 1 16 0a8 8 0 0 1 -16 0z",
            "M12 12V4",
            "M12 12l6.5 4",
        )
    }

    /** A receipt with a torn foot. */
    val Receipt: ImageVector by lazy {
        outline(
            "Receipt",
            "M5 3h14v17l-2.5-1.5L14 20l-2-1.5L10 20l-2.5-1.5L5 20z",
            "M8.5 8h7",
            "M8.5 11.5h7",
        )
    }

    /** A purse with a handle. */
    val Purse: ImageVector by lazy {
        outline(
            "Purse",
            "M4 10h16l-1.5 9H5.5z",
            "M9 10V7.5a3 3 0 0 1 6 0V10",
        )
    }

    /** A balance, for a fee, a rate or a verdict. */
    val Scale: ImageVector by lazy {
        outline(
            "Scale",
            "M12 4v16",
            "M5 20h14",
            "M4 9h16",
            "M4 9 1.5 13h5z",
            "M20 9l-2.5 4h5z",
        )
    }

    /** A price tag with its hole. */
    val Tag: ImageVector by lazy {
        outline(
            "Tag",
            "M14 3h7v7L11 20 4 13z",
            "M15.5 6.5a1.5 1.5 0 0 1 3 0a1.5 1.5 0 0 1 -3 0z",
        )
    }

    /** A cut gem, for a holding. */
    val Gem: ImageVector by lazy {
        outline(
            "Gem",
            "M6 4h12l3 5-9 11L3 9z",
            "M3 9h18",
            "M9 4 7.5 9 12 20l4.5-11L15 4",
        )
    }

    /** A rocket, for a launch or a new position. */
    val Rocket: ImageVector by lazy {
        outline(
            "Rocket",
            "M12 3c3 2.5 4.5 6 4.5 9.5L12 21l-4.5-8.5C7.5 9 9 5.5 12 3z",
            "M10 10a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M7.5 13.5 5 16v3l2.8-1.2",
            "M16.5 13.5 19 16v3l-2.8-1.2",
        )
    }

    /** A shield with a tick, for safety. */
    val Shield: ImageVector by lazy {
        outline(
            "Shield",
            "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
            "M8.5 12l2.5 2.5 4.5-4.5",
        )
    }

    /** A hanging lantern with a lit flame, in the place the padlock used to hold. */
    val Lantern: ImageVector by lazy {
        outline(
            "Lantern",
            "M10 3.5a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M7.5 8 12 5.5 16.5 8",
            "M8 8h8v11H8z",
            "M10.5 8v11",
            "M13.5 8v11",
            "M7 19h10",
            "M8.5 21h7",
            "M12 12c1 1.5 1.5 2.5 1.5 3.5a1.5 1.5 0 0 1 -3 0c0-1 .5-2 1.5-3.5z",
        )
    }

    /** Two links of a chain. */
    val Link: ImageVector by lazy {
        outline(
            "Link",
            "M10 14 14 10",
            "M8 12 5.5 14.5a3.5 3.5 0 0 0 4.5 4.5L12.5 16",
            "M16 12l2.5-2.5a3.5 3.5 0 0 0-4.5-4.5L11.5 8",
        )
    }

    /** Three nodes and their links, for a network. */
    val Node: ImageVector by lazy {
        outline(
            "Node",
            "M10 5a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M3 18a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M17 18a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M11 6.8 6 16.2",
            "M13 6.8 18 16.2",
            "M7 18h10",
        )
    }

    /** A block, for one entry of a chain. */
    val Cube: ImageVector by lazy {
        outline(
            "Cube",
            "M12 3l8 4.5v9L12 21l-8-4.5v-9z",
            "M4 7.5 12 12l8-4.5",
            "M12 12v9",
        )
    }

    /** A bolt, for energy or a fee spike. */
    val Spark: ImageVector by lazy {
        outline(
            "Spark",
            "M13 3 5 14h6l-1 7 8-11h-6z",
        )
    }

    /** Two arrows chasing each other, for a swap. */
    val Swap: ImageVector by lazy {
        outline(
            "Swap",
            "M4 9a8 8 0 0 1 12-3.5",
            "M13 2.5 16 5.5 13 8.5",
            "M20 15a8 8 0 0 1-12 3.5",
            "M11 21.5 8 18.5 11 15.5",
        )
    }

    /** A chip on its pins. */
    val Chip: ImageVector by lazy {
        outline(
            "Chip",
            "M7 7h10v10H7z",
            "M10 10h4v4h-4z",
            "M9 7V4",
            "M15 7V4",
            "M9 20v-3",
            "M15 20v-3",
            "M7 9H4",
            "M7 15H4",
            "M20 9h-3",
            "M20 15h-3",
        )
    }

    /** A globe, for a network without borders. */
    val Globe: ImageVector by lazy {
        outline(
            "Globe",
            "M4 12a8 8 0 0 1 16 0a8 8 0 0 1 -16 0z",
            "M12 4c-3 2.5-3 13.5 0 16",
            "M12 4c3 2.5 3 13.5 0 16",
            "M4.5 9.5h15",
            "M4.5 14.5h15",
        )
    }

    /** Two price waves, for a market that moves both ways. */
    val Waves: ImageVector by lazy {
        outline(
            "Waves",
            "M3 9.5c1.5-3.5 4.5-3.5 6 0c1.5 3.5 4.5 3.5 6 0c1.5-3.5 4.5-3.5 6 0",
            "M3 16c1.5-3.5 4.5-3.5 6 0c1.5 3.5 4.5 3.5 6 0c1.5-3.5 4.5-3.5 6 0",
        )
    }

    /** A leaf with its vein. */
    val Leaf: ImageVector by lazy {
        outline(
            "Leaf",
            "M5 19c0-8 5-13 14-13 0 9-5 14-14 13z",
            "M5 19 14 9",
        )
    }

    /** A tree: a rounded crown over a trunk. */
    val Tree: ImageVector by lazy {
        outline(
            "Tree",
            "M9 14a3.5 3.5 0 0 1-1-6.9A4.5 4.5 0 0 1 16 7a3.5 3.5 0 0 1-1 7z",
            "M12 14v7",
            "M9 21h6",
        )
    }

    /** A flower: five petals round its heart, on a stem. */
    val Flower: ImageVector by lazy {
        outline(
            "Flower",
            "M9.5 6a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1 -5 0z",
            "M13.3 8.7a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1 -5 0z",
            "M11.9 13.1a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1 -5 0z",
            "M7.1 13.1a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1 -5 0z",
            "M5.7 8.7a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1 -5 0z",
            "M12 15.5V21",
        )
    }

    /** A sun with its rays. */
    val Sun: ImageVector by lazy {
        outline(
            "Sun",
            "M8 12a4 4 0 0 1 8 0a4 4 0 0 1 -8 0z",
            "M12 2v2.5",
            "M12 19.5V22",
            "M2 12h2.5",
            "M19.5 12H22",
            "M5 5l1.8 1.8",
            "M17.2 17.2 19 19",
            "M19 5l-1.8 1.8",
            "M6.8 17.2 5 19",
        )
    }

    /** A crescent moon. */
    val Moon: ImageVector by lazy {
        outline(
            "Moon",
            "M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z",
        )
    }

    /** A flame. */
    val Flame: ImageVector by lazy {
        outline(
            "Flame",
            "M12 3c1 5 6 6.5 6 11a6 6 0 0 1-12 0c0-3 2-5 6-11z",
        )
    }

    /** A drop. */
    val Drop: ImageVector by lazy {
        outline(
            "Drop",
            "M12 3c3 4 6 7 6 10.5a6 6 0 0 1-12 0C6 10 9 7 12 3z",
        )
    }

    /** An open book. */
    val Book: ImageVector by lazy {
        outline(
            "Book",
            "M12 6C10 4.5 7 4 4 5v13c3-1 6-.5 8 1 2-1.5 5-2 8-1V5c-3-1-6-.5-8 1z",
            "M12 6v13",
        )
    }

    /** A cup of coffee. */
    val Coffee: ImageVector by lazy {
        outline(
            "Coffee",
            "M6 8h11v6a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z",
            "M17 9.5h1.5a2.5 2.5 0 0 1 0 5H17",
            "M8 20h9",
        )
    }

    /** A briefcase. */
    val Briefcase: ImageVector by lazy {
        outline(
            "Briefcase",
            "M3 8h18v12H3z",
            "M9 8V6h6v2",
            "M10 13h4",
        )
    }

    /** A lamp, for a home office. */
    val Lamp: ImageVector by lazy {
        outline(
            "Lamp",
            "M8 4h8l3 8H5z",
            "M12 12v8",
            "M8 20h8",
        )
    }

    /** A bed with its pillow. */
    val Bed: ImageVector by lazy {
        outline(
            "Bed",
            "M3 18v-8",
            "M3 14h18v4",
            "M21 18v-3",
            "M6.5 11.5V9h4.5v2.5",
        )
    }

    /** A die with its pips, for a game of chance. */
    val Dice: ImageVector by lazy {
        outline(
            "Dice",
            "M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
            "M7.5 8.5a1 1 0 0 1 2 0a1 1 0 0 1 -2 0z",
            "M11 12a1 1 0 0 1 2 0a1 1 0 0 1 -2 0z",
            "M14.5 15.5a1 1 0 0 1 2 0a1 1 0 0 1 -2 0z",
        )
    }

    /** A music note. */
    val Music: ImageVector by lazy {
        outline(
            "Music",
            "M9 6.5V19",
            "M19 4v12.5",
            "M9 6.5 19 4",
            "M5 19a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M15 16.5a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
        )
    }

    /** A paper plane. */
    val Plane: ImageVector by lazy {
        outline(
            "Plane",
            "M21 3 3 10.5l7 2.5 2.5 7z",
            "M21 3 10 13",
        )
    }

    /** A car. */
    val Car: ImageVector by lazy {
        outline(
            "Car",
            "M3 16v-3.2l2-5.3h14l2 5.3V16z",
            "M2.5 16h19",
            "M5 16.5a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
            "M15 16.5a2 2 0 0 1 4 0a2 2 0 0 1 -4 0z",
        )
    }

    /** A gift with its ribbon and bow. */
    val Gift: ImageVector by lazy {
        outline(
            "Gift",
            "M4 9h16v4H4z",
            "M6 13h12v8H6z",
            "M12 9v12",
            "M12 9C12 6 9 5 8 6.5S9 9 12 9z",
            "M12 9c0-3 3-4 4-2.5S15 9 12 9z",
        )
    }

    /** A camera. */
    val Camera: ImageVector by lazy {
        outline(
            "Camera",
            "M3 8h4l2-2h6l2 2h4v12H3z",
            "M9 14a3 3 0 0 1 6 0a3 3 0 0 1 -6 0z",
        )
    }

    /** A trophy. */
    val Trophy: ImageVector by lazy {
        outline(
            "Trophy",
            "M8 4h8v4a4 4 0 0 1-8 0z",
            "M8 5H5v2a3 3 0 0 0 3 3",
            "M16 5h3v2a3 3 0 0 1-3 3",
            "M12 12v4",
            "M10 16h4",
            "M9 20h6",
        )
    }

    /** A bicycle: two wheels, the frame, the seat and the handlebar. */
    val Bicycle: ImageVector by lazy {
        outline(
            "Bicycle",
            "M2.5 16a3.5 3.5 0 0 1 7 0a3.5 3.5 0 0 1 -7 0z",
            "M14.5 16a3.5 3.5 0 0 1 7 0a3.5 3.5 0 0 1 -7 0z",
            "M6 16 9.5 9.5h5.5l3 6.5",
            "M12.5 9.5 10 16h3",
            "M15 9.5l1-1.5h2.5",
        )
    }

    /** A tent. */
    val Tent: ImageVector by lazy {
        outline(
            "Tent",
            "M3 20 12 5l9 15z",
            "M12 20v-6",
        )
    }

    /** A ball. */
    val Ball: ImageVector by lazy {
        outline(
            "Ball",
            "M4 12a8 8 0 0 1 16 0a8 8 0 0 1 -16 0z",
            "M12 8l3.5 2.5-1.3 4h-4.4l-1.3-4z",
        )
    }

    /** Headphones. */
    val Headphones: ImageVector by lazy {
        outline(
            "Headphones",
            "M4 14v-2a8 8 0 0 1 16 0v2",
            "M4 14h3v6H5a1 1 0 0 1-1-1z",
            "M20 14h-3v6h2a1 1 0 0 0 1-1z",
        )
    }

    /** A ticket with its tear line. */
    val Ticket: ImageVector by lazy {
        outline(
            "Ticket",
            "M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z",
            "M12 7v2",
            "M12 11.5V13",
            "M12 16v1",
        )
    }

    /** An outline glyph: each path is stroked with rounded ends and joins at a 24 dp viewport. */
    private fun outline(name: String, vararg paths: String, strokeWidth: Float = 1.5f): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.White),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

/** One labelled run of the icon picker: a name, such as "Money", and the icons under it, in picker order. */
internal data class ProfileIconSection(val label: String, val icons: List<ProfileIcon>)

/**
 * The picker's sections, in order. Every icon sits in exactly one section, and the sections together are the whole set in the
 * order of [ProfileIcon.entries], so a saved icon keeps its place.
 */
internal val profileIconSections: List<ProfileIconSection> = listOf(
    ProfileIconSection(
        "Everyday",
        listOf(
            ProfileIcon.STAR,
            ProfileIcon.HEART,
            ProfileIcon.HOME,
            ProfileIcon.PERSON,
            ProfileIcon.PIN,
            ProfileIcon.WRENCH,
            ProfileIcon.SEND,
            ProfileIcon.THUMB_UP,
            ProfileIcon.BELL,
            ProfileIcon.CART,
            ProfileIcon.CALENDAR,
            ProfileIcon.PHONE,
        ),
    ),
    ProfileIconSection(
        "Money",
        listOf(
            ProfileIcon.COIN,
            ProfileIcon.COINS,
            ProfileIcon.VAULT,
            ProfileIcon.BANK,
            ProfileIcon.CARD,
            ProfileIcon.BANKNOTE,
            ProfileIcon.CHART_UP,
            ProfileIcon.PIE_CHART,
            ProfileIcon.RECEIPT,
            ProfileIcon.PURSE,
            ProfileIcon.SCALE,
            ProfileIcon.TAG,
        ),
    ),
    ProfileIconSection(
        "Crypto",
        listOf(
            ProfileIcon.GEM,
            ProfileIcon.ROCKET,
            ProfileIcon.SHIELD,
            ProfileIcon.LANTERN,
            ProfileIcon.LINK,
            ProfileIcon.NODE,
            ProfileIcon.CUBE,
            ProfileIcon.SPARK,
            ProfileIcon.SWAP,
            ProfileIcon.CHIP,
            ProfileIcon.GLOBE,
            ProfileIcon.WAVES,
        ),
    ),
    ProfileIconSection(
        "Life",
        listOf(
            ProfileIcon.LEAF,
            ProfileIcon.TREE,
            ProfileIcon.FLOWER,
            ProfileIcon.SUN,
            ProfileIcon.MOON,
            ProfileIcon.FLAME,
            ProfileIcon.DROP,
            ProfileIcon.BOOK,
            ProfileIcon.COFFEE,
            ProfileIcon.BRIEFCASE,
            ProfileIcon.LAMP,
            ProfileIcon.BED,
        ),
    ),
    ProfileIconSection(
        "Leisure",
        listOf(
            ProfileIcon.DICE,
            ProfileIcon.MUSIC,
            ProfileIcon.PLANE,
            ProfileIcon.CAR,
            ProfileIcon.GIFT,
            ProfileIcon.CAMERA,
            ProfileIcon.TROPHY,
            ProfileIcon.BICYCLE,
            ProfileIcon.TENT,
            ProfileIcon.BALL,
            ProfileIcon.HEADPHONES,
            ProfileIcon.TICKET,
        ),
    ),
)
