package app.lumio.ui.screenshot

/** One row of the approved Home mock's watchlist: what it says, its logo and how the mock sets it in the disc, and its day's prices. */
internal data class FixtureRow(
    val name: String,
    val ticker: String,
    val price: String,
    val change: Double,
    val logo: Int,
    val logoDp: Int,
    val logoScale: Float,
    val logoFill: Long?,
    val ticks: List<Double>,
)

/** The twelve assets of the approved Home mock, read from it, so the goldens show the same data. A test fixture only: no default watchlist has them. */
internal val MOCK_WATCHLIST: List<FixtureRow> = listOf(
    FixtureRow(name = "Bitcoin", ticker = "BTC", price = "$60,572.00", change = 3.36, logo = 0, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(59925.898667, 59360.560000, 59468.243556, 59602.848000, 59791.294222, 60652.762667, 60787.367111, 60895.050667, 61204.640889, 61204.640889, 61043.115556, 61339.245333, 61420.008000, 61689.216889, 61581.533333, 61608.454222, 61796.900444, 61635.375111, 61446.928889, 61420.008000, 61729.598222, 61756.519111, 61420.008000)),
    FixtureRow(name = "Ethereum", ticker = "ETH", price = "$1,699.68", change = 3.4, logo = 1, logoDp = 23, logoScale = 1.0f, logoFill = null, ticks = listOf(1680.794667, 1665.686400, 1678.528427, 1681.550080, 1678.528427, 1710.633493, 1712.899733, 1715.921387, 1720.453867, 1717.432213, 1717.432213, 1722.720107, 1727.252587, 1731.785067, 1728.763413, 1728.008000, 1731.785067, 1725.364053, 1718.187627, 1720.453867, 1731.029653, 1734.051307, 1725.364053)),
    FixtureRow(name = "Tether", ticker = "USDT", price = "$0.9990", change = 0.02, logo = 2, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(0.999777, 0.998001, 0.999777, 0.998001, 0.999999, 0.998223, 0.999999, 0.998223, 0.999999, 0.998223, 0.999777, 0.998223, 0.999777, 0.998001, 0.999999, 0.998001, 0.999999, 0.998001, 0.999999, 0.998223, 0.999999, 0.998223, 0.999999)),
    FixtureRow(name = "BNB", ticker = "BNB", price = "$550.50", change = 2.1, logo = 3, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(544.872667, 539.490000, 540.958000, 542.426000, 544.138667, 551.968000, 552.946667, 555.148667, 556.861333, 555.638000, 554.659333, 556.372000, 557.595333, 559.552667, 559.797333, 560.531333, 561.510000, 560.286667, 557.962333, 556.739000, 557.717667, 556.739000, 553.803000)),
    FixtureRow(name = "USD Coin", ticker = "USDC", price = "$0.9995", change = 0.01, logo = 4, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(1.000388, 0.998612, 1.000388, 0.998612, 1.000388, 0.998834, 1.000388, 0.998612, 1.000388, 0.998834, 1.000166, 0.998834, 1.000166, 0.998612, 1.000166, 0.998834, 1.000166, 0.998834, 1.000166, 0.998834, 1.000166, 0.998834, 1.000166)),
    FixtureRow(name = "Solana", ticker = "SOL", price = "$70.72", change = 4.16, logo = 5, logoDp = 22, logoScale = 1.0f, logoFill = null, ticks = listOf(69.981369, 69.305600, 69.635627, 69.887076, 69.918507, 71.002880, 71.285760, 71.600071, 71.788658, 71.788658, 71.757227, 71.631502, 71.945813, 72.071538, 72.165831, 72.040107, 72.134400, 71.977244, 71.820089, 71.945813, 72.102969, 72.102969, 71.741511)),
    FixtureRow(name = "Spiko Amundi Overnight Swap Fund (EUR)", ticker = "eurSAFO", price = "$1.1132", change = 0.18, logo = 6, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(1.111963, 1.109984, 1.111963, 1.110479, 1.113942, 1.115674, 1.115426, 1.115179, 1.114437, 1.114684, 1.114437, 1.114437, 1.113695, 1.114190, 1.113200, 1.113200, 1.112458, 1.113200, 1.112705, 1.114684, 1.113695, 1.114932, 1.113942)),
    FixtureRow(name = "BlackRock USD Institutional Digital Liquidity Fund", ticker = "BUIDL", price = "$1.0000", change = 0.0, logo = 7, logoDp = 32, logoScale = 1.12f, logoFill = 0xFF000000, ticks = listOf(1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889)),
    FixtureRow(name = "Invesco Short Duration US Government Securities Fund", ticker = "USTB", price = "$11.29", change = 0.01, logo = 8, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036, 11.279964, 11.300036)),
    FixtureRow(name = "Janus Henderson Anemoy AAA CLO Fund", ticker = "JAAA", price = "$1.0580", change = 0.04, logo = 9, logoDp = 32, logoScale = 0.88f, logoFill = 0xFFFFFFFF, ticks = listOf(1.058823, 1.056942, 1.058823, 1.056942, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058, 1.057177, 1.059058)),
    FixtureRow(name = "Ondo Short-Term U.S. Government Bond Fund", ticker = "OUSG", price = "$117.40", change = 0.01, logo = 10, logoDp = 24, logoScale = 1.0f, logoFill = null, ticks = listOf(117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356, 117.295644, 117.504356)),
    FixtureRow(name = "Tradable APAC Diversified Finance Provider SSTN", ticker = "PC0000033", price = "$1.0000", change = 0.0, logo = 11, logoDp = 24, logoScale = 0.72f, logoFill = 0xFF000000, ticks = listOf(1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889, 0.999111, 1.000889)),
)
