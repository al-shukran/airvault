package app.lumio.demo

import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MarketCoin

/**
 * The market source of demo mode: the first 25 coins by market cap and the coins named "government", as CoinGecko listed them on 2026-11-26, with
 * their contracts on the networks the source names by a chain id. Each logo is a `demo:{id}` URL that [app.lumio.DebugDemoData.coinImage] serves from the debug
 * resources. Generated from the source's answers; debug builds only.
 */
internal object DemoMarket {
    val TOP: List<MarketCoin> = listOf(
        MarketCoin("bitcoin", "Bitcoin", "btc", 1, "demo:bitcoin"),
        MarketCoin("ethereum", "Ethereum", "eth", 2, "demo:ethereum"),
        MarketCoin("tether", "Tether", "usdt", 3, "demo:tether"),
        MarketCoin("binancecoin", "BNB", "bnb", 4, "demo:binancecoin"),
        MarketCoin("usd-coin", "USDC", "usdc", 5, "demo:usd-coin"),
        MarketCoin("ripple", "XRP", "xrp", 6, "demo:ripple"),
        MarketCoin("solana", "Solana", "sol", 7, "demo:solana"),
        MarketCoin("figure-heloc", "Figure Heloc", "figr_heloc", 8, "demo:figure-heloc"),
        MarketCoin("tron", "TRON", "trx", 9, "demo:tron"),
        MarketCoin("hyperliquid", "Hyperliquid", "hype", 10, "demo:hyperliquid"),
        MarketCoin("zcash", "Zcash", "zec", 11, "demo:zcash"),
        MarketCoin("monero", "Monero", "xmr", 12, "demo:monero"),
        MarketCoin("dogecoin", "Dogecoin", "doge", 13, "demo:dogecoin"),
        MarketCoin("leo-token", "LEO Token", "leo", 14, "demo:leo-token"),
        MarketCoin("whitebit", "WhiteBIT Coin", "wbt", 15, "demo:whitebit"),
        MarketCoin("chainlink", "Chainlink", "link", 16, "demo:chainlink"),
        MarketCoin("usds", "USDS", "usds", 17, "demo:usds"),
        MarketCoin("cardano", "Cardano", "ada", 18, "demo:cardano"),
        MarketCoin("ethena-usde", "Ethena USDe", "usde", 19, "demo:ethena-usde"),
        MarketCoin("stellar", "Stellar", "xlm", 20, "demo:stellar"),
        MarketCoin("dai", "Dai", "dai", 21, "demo:dai"),
        MarketCoin("bitcoin-cash", "Bitcoin Cash", "bch", 22, "demo:bitcoin-cash"),
        MarketCoin("near", "NEAR Protocol", "near", 23, "demo:near"),
        MarketCoin("litecoin", "Litecoin", "ltc", 24, "demo:litecoin"),
        MarketCoin("canton-network", "Canton", "cc", 25, "demo:canton-network"),
    )

    /** The market rows a search reads, beyond [TOP]. */
    val SEARCHED: List<MarketCoin> = listOf(
        MarketCoin("backed-swiss-domestic-government-bond-0-3", "Backed Swiss Domestic Government Bond 0-3", "bcsbgc3", null, "demo:backed-swiss-domestic-government-bond-0-3"),
        MarketCoin("deer-seized-by-us-government", "Deer Seized by US Government", "baby", 9481, "demo:deer-seized-by-us-government"),
        MarketCoin("department-of-government-efficiency", "Department Of Government Efficiency", "doge", 4004, "demo:department-of-government-efficiency"),
        MarketCoin("department-of-government-efficiency-2", "Department Of Government Efficiency", "doge", null, null),
        MarketCoin("department-of-government-efficiency-3", "Department Of Government Efficiency", "d.o.g.e", null, null),
        MarketCoin("department-of-government-efficiency-4", "Department Of Government Efficiency", "doge", null, null),
        MarketCoin("department-of-government-efficiency-5", "Department Of Government Efficiency", "d.o.g.e", null, null),
        MarketCoin("ishares-global-government-bond-usd-hedged-active-etf-ondo-tokenized", "iShares Global Government Bond USD Hedged Active ETF (Ondo Tokenized)", "ggovon", 14812, "demo:ishares-global-government-bond-usd-hedged-active-etf-ondo-tokenized"),
        MarketCoin("ousg", "Ondo Short-Term U.S. Government Bond Fund", "ousg", 120, "demo:ousg"),
        MarketCoin("superstate-short-duration-us-government-securities-fund-ustb", "Invesco Short Duration US Government Securities Fund", "ustb", 85, "demo:superstate-short-duration-us-government-securities-fund-ustb"),
    )

    val DIRECTORY: List<DirectoryCoin> = listOf(
        DirectoryCoin("bitcoin", "Bitcoin", "btc", mapOf()),
        DirectoryCoin("ethereum", "Ethereum", "eth", mapOf()),
        DirectoryCoin("tether", "Tether", "usdt", mapOf("eip155:1" to "0xdac17f958d2ee523a2206206994597c13d831ec7", "eip155:42220" to "0x48065fbbe25f71c9282ddf5e1cd6d6a887483d5e", "eip155:8217" to "0xd077a400968890eacc75cdc901f0356c943e4fdb", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "eip155:43114" to "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7", "eip155:2222" to "0x919c1c267bc06a7039e03fcc2ef738525769109c")),
        DirectoryCoin("binancecoin", "BNB", "bnb", mapOf("eip155:1" to "0xb8c77482e45f1f44de1745f52c74426c631bdd52")),
        DirectoryCoin("usd-coin", "USDC", "usdc", mapOf("eip155:1" to "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "eip155:9745" to "0x2d661c89d812261039af9764eceaaee884f5f67f", "eip155:5042" to "0x3600000000000000000000000000000000000000", "eip155:146" to "0x29219dd400f2bf60e5a23d13be72b486d4038894", "algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k" to "31566704", "eip155:295" to "0.0.456858", "eip155:1672" to "0xc879c018db60520f4355c26ed1a6d572cdac1815", "eip155:43114" to "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", "eip155:1776" to "0xa00c59ff5a080d2b954d0c75e46e22a0c371235a", "eip155:99901" to "0xb47c4c8f73f009cc0c0d3f92b388d13e293f0b65", "eip155:50" to "0xfa2958cb79b0491cc627c1557f441ef849ca8eb1", "eip155:1329" to "0xe15fc38f6d8c56af07bbcbe3baf5708a2bf42392", "eip155:10" to "0x0b2c639c533813f4aa9d7837caf62653d097ff85", "eip155:42161" to "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "eip155:143" to "0x754704bc059f8c67012fed69bc8a327a5aafb603", "eip155:324" to "0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4", "eip155:25" to "0x3d7f2c478aafdb65542bcb44bceec05849999d2d", "eip155:196" to "0xb6ceceab302e2e4948951ee7843fc24e92933061", "eip155:2818" to "0xcfb1186f4e93d60e60a8bdd997427d1f33bc372b", "eip155:137" to "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", "eip155:8453" to "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "eip155:98866" to "0x222365ef19f7947e5484218551b56bb3965aa7af", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "eip155:57073" to "0x2d270e6886d130d724215a266106e6832161eaed", "eip155:130" to "0x078d782b760474a361dda0af3839290b0ef57ad6", "eip155:999" to "0xb88339cb7199b77e23db6e890353e22632ba630f", "eip155:480" to "0x79a02482a880bce3f13e09da970dc34db4cd24d1", "eip155:42220" to "0xceba9300f2b948710d2653dd7b07f33a8b32118c")),
        DirectoryCoin("ripple", "XRP", "xrp", mapOf()),
        DirectoryCoin("solana", "Solana", "sol", mapOf()),
        DirectoryCoin("figure-heloc", "Figure Heloc", "figr_heloc", mapOf()),
        DirectoryCoin("tron", "TRON", "trx", mapOf()),
        DirectoryCoin("hyperliquid", "Hyperliquid", "hype", mapOf()),
        DirectoryCoin("zcash", "Zcash", "zec", mapOf()),
        DirectoryCoin("monero", "Monero", "xmr", mapOf()),
        DirectoryCoin("dogecoin", "Dogecoin", "doge", mapOf()),
        DirectoryCoin("leo-token", "LEO Token", "leo", mapOf("eip155:1" to "0x2af5d2ad76741191d15dfe7bf6ac92d4bd912ca3")),
        DirectoryCoin("whitebit", "WhiteBIT Coin", "wbt", mapOf("eip155:1" to "0x925206b8a707096ed26ae47c84747fe0bb734f59")),
        DirectoryCoin("chainlink", "Chainlink", "link", mapOf("eip155:1" to "0x514910771af9ca656af840dff83e8264ecf986ca", "eip155:1284" to "0x012414a392f9fa442a3109f1320c439c45518ac3", "eip155:8453" to "0x88fb150bdc53a65fe94dea0c9ba0a6daf8c6e196", "eip155:50" to "0xe27dd9bf01b55ce6803c0d81386a04212c718b95", "eip155:5000" to "0xfe36cf0b43aae49fbc5cfc5c0af22a623114e043", "eip155:1116" to "0x3902228d6a3d2dc44731fd9d45fee6a61c722d0b", "eip155:196" to "0x8af9711b44695a5a081f25ab9903ddb73acf8fa9", "eip155:42161" to "0xf97f4df75117a78c1a5a0dbb814af92458539fb4", "eip155:25" to "0x8c80a01f461f297df7f9da3a4f740d7297c8ac85", "eip155:185" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:10" to "0x350a791bfc2c21f9ed5d10980dad2e2638ffa7f6", "eip155:1101" to "0xdb7a504cf869484dd6ac5faf925c8386cbf7573d", "eip155:1135" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:43114" to "0x5947bb275c521040051d82396192181b413227a3", "eip155:964" to "0xf09afe78d3c7d359b334d7cb88995751f7ec5e13", "eip155:239" to "0xc2be2f77562a6676098e8d363b9d8a33ea009d4e", "eip155:167000" to "0x917a3964c37993e99a47c779beb5db1e9d13804d", "eip155:128" to "0x9e004545c59d359f6b7bfb06a26390b087717b42", "eip155:81457" to "0x93202ec683288a9ea75bb829c6bacfb2bfea9013", "eip155:34443" to "0x183e3691eff3524b2315d3703d94f922cbe51f54", "eip155:1111" to "0x80f1fcdc96b55e459bf52b998abbe2c364935d69", "eip155:36900" to "0x76a443768a5e3b8d1aed0105fc250877841deb40", "eip155:255" to "0xc1f6f7622ad37c3f46cdf6f8aa0344ade80bf450", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "LinkhB3afbBKb2EQQu7s7umdZceV3wcvAUJhQAfQ23L", "eip155:57073" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:30" to "0x938d84942f5d924070a6bb82f8e56a5e2b3098a4", "eip155:4663" to "0x492641f648a4986844848e0befe66d14817bce34", "eip155:988" to "0x985fb0821eef0056ec26dd8b33dc61b9415b7f4b", "eip155:1868" to "0x32d8f819c8080ae44375f8d383ffd39fc642f3ec", "eip155:42220" to "0xd07294e6e917e07dfdcee882dd1e2565085c2ae0", "eip155:747474" to "0xc2c447b04e0ed3476ddbdae8e9e39be7159d27b6", "eip155:59144" to "0xa18152629128738a5c081eb226335fed4b9c95e9", "eip155:4200" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:8217" to "0x7311ded199cc28d80e58e81e8589aa160199fcd2", "eip155:204" to "0x99f0d88b81b758ab07e22c7aba00e0121a882dea", "eip155:295" to "0x7ce6bb2cc2d3fd45a974da6a0f29236cb9513a98", "eip155:388" to "0x61170ca9fb9cf98d4c7d684e07be6d969d59667e", "eip155:1088" to "0xd2fe54d1e5f568eb710ba9d898bf4bd02c7c0353", "eip155:1285" to "0x8b12ac23bfe11cab03a634c1f117d64a7f2cfd3e", "eip155:3637" to "0x30e85a5c9525ad9a7a0fa5c74df4baf0b01ad241", "eip155:21000000" to "0x7311ded199cc28d80e58e81e8589aa160199fcd2", "eip155:2741" to "0x2ea38d6cdb6774992d4a62fe622f4405663729dd", "eip155:999" to "0x1ac2ee68b8d038c982c1e1f73f596927dd70de59", "eip155:33139" to "0xf575731b78981b86d34321d875a3d25a48479be6", "eip155:100" to "0xe2e73a1c69ecf83f464efce6a5be353a37ca09b2", "eip155:56" to "0xf8a0bf9cf54bb92f17374d9e9a321e6a111a51bd", "eip155:480" to "0x915b648e994d5f31059b38223b9fbe98ae185473", "eip155:592" to "0x31efb841d5e0b4082f7e1267dab8de1b853f2a9d", "eip155:48900" to "0xdee94506570ca186bc1e3516fcf4fd719c312ccd", "eip155:42793" to "0x8ce7618e8f8e514d13889283f58ff03b794e6cc3", "eip155:4326" to "0xee85aefb15b9489563a6a29891ebe0750aa1a7ae", "eip155:5042" to "0x76a443768a5e3b8d1aed0105fc250877841deb40", "eip155:146" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:9745" to "0x76a443768a5e3b8d1aed0105fc250877841deb40", "eip155:200901" to "0x56b275c0ec034a229a1ded8db17089544bc276d9", "eip155:252" to "0xd6a6ba37faac229b9665e86739ca501401f5a940", "eip155:130" to "0xef66491eab4bbb582c57b14778afd8dfb70d8a1a", "eip155:534352" to "0x548c6944cba02b9d1c0570102c89de64d258d3ac", "eip155:98866" to "0xb549b375da0c76f8b3877b9adfdd28378f087a64", "eip155:232" to "0x6e970e8d6758164798290c8db1d79a527ca6e1b2", "eip155:2001" to "0xf390830df829cf22c53c8840554b98eafc5dcbc2", "eip155:1672" to "0x51e2a24742db77604b881d6781ee16b5b8fcbe29", "eip155:68414" to "0x76a443768a5e3b8d1aed0105fc250877841deb40", "eip155:1666600000" to "0x218532a12a389a4a92fc0c5fb22901d1c19198aa", "eip155:39797" to "0x68ca48ca2626c415a89756471d4ade2cc9034008", "eip155:324" to "0x52869bae3e091e36b0915941577f2d47d8d8b534", "eip155:80094" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:250" to "0xb3654dc3d10ea7645f8319668e8f54d2574fbdc8", "eip155:43111" to "0x63dbe12a6381d64ade47bc3d92abf4393dff4bc8", "eip155:2020" to "0x3902228d6a3d2dc44731fd9d45fee6a61c722d0b", "eip155:177" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:143" to "0x76f257b1dda5cc71bee4ef637fbdde4c801310a9", "eip155:2818" to "0x76a443768a5e3b8d1aed0105fc250877841deb40", "eip155:1329" to "0x71052bae71c25c78e37fd12e5ff1101a71d9018f", "eip155:5330" to "0x41ccf59e3f30eb624ef8e5ea34b2da96bee472d9", "eip155:137" to "0x53e0bca35ec356bd5dddfebbd1fc0fd03fabad39", "eip155:7777777" to "0x3662b6f73c5560229d1a98af6e59e6649d568374")),
        DirectoryCoin("usds", "USDS", "usds", mapOf("eip155:1" to "0xdc035d45d973e3ec169d2276ddab16f1e407384f", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "USDSwr9ApdHk5bvJKMjzff41FfuX8bSxdKcR81vTwcA", "eip155:8453" to "0x820c137fa70c8691f0e44dc420a5e53c168921dc", "eip155:42161" to "0x6491c05a82219b8d1479057361ff1654749b876b")),
        DirectoryCoin("cardano", "Cardano", "ada", mapOf()),
        DirectoryCoin("ethena-usde", "Ethena USDe", "usde", mapOf("eip155:1" to "0x4c9edd5852cd905f086c759e8383e09bff1e68b3", "eip155:534352" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:169" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:43114" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:42161" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:324" to "0x39fe7a0dacce31bd90418e3e659fb0b5f0b3db0d", "eip155:143" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:10" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:1088" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:48900" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:4663" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:4326" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:9745" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:252" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:81457" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:56" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:34443" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "DEkqHyPN7GMRJ5cArtQFAWefqbZb33Hyf6s5iCwjEonT", "eip155:59144" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:1923" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:999" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:8453" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:5000" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:2222" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:196" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:80094" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34", "eip155:2818" to "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34")),
        DirectoryCoin("stellar", "Stellar", "xlm", mapOf()),
        DirectoryCoin("dai", "Dai", "dai", mapOf("eip155:1" to "0x6b175474e89094c44da98b954eedeac495271d0f")),
        DirectoryCoin("bitcoin-cash", "Bitcoin Cash", "bch", mapOf()),
        DirectoryCoin("near", "NEAR Protocol", "near", mapOf()),
        DirectoryCoin("litecoin", "Litecoin", "ltc", mapOf()),
        DirectoryCoin("canton-network", "Canton", "cc", mapOf()),
        DirectoryCoin("backed-swiss-domestic-government-bond-0-3", "Backed Swiss Domestic Government Bond 0-3", "bcsbgc3", mapOf("eip155:42161" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2", "eip155:137" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2", "eip155:100" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2", "eip155:1" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2", "eip155:56" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2", "eip155:8453" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2", "eip155:43114" to "0xd8b95b1987741849ca7e71e976aeb535fd2e55a2")),
        DirectoryCoin("deer-seized-by-us-government", "Deer Seized by US Government", "baby", mapOf("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "6pKHwNCpzgZuC9o5FzvCZkYSUGfQddhUYtMyDbEVpump")),
        DirectoryCoin("department-of-government-efficiency", "Department Of Government Efficiency", "doge", mapOf("eip155:1" to "0x1121acc14c63f3c872bfca497d10926a6098aac5", "eip155:8453" to "0x67f0870bb897f5e1c369976b4a2962d527b9562c", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "KQijDbNJ6rPCqhtXrfH6gKa5cH3a39At8vHq34nnPbF")),
        DirectoryCoin("department-of-government-efficiency-2", "Department Of Government Efficiency", "doge", mapOf("eip155:1" to "0xa4fb6e1781fbcc921df51352af4cc83ff6c1308f")),
        DirectoryCoin("department-of-government-efficiency-3", "Department Of Government Efficiency", "d.o.g.e", mapOf("eip155:1" to "0x46fdcddfad7c72a621e8298d231033cc00e067c6")),
        DirectoryCoin("department-of-government-efficiency-4", "Department Of Government Efficiency", "doge", mapOf("eip155:1" to "0x81fcc294d91bd8ffc8a822d7df0e2fd2f8526c39", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "B54Fyr1XrWNA5RsRRkovoVFwQwzwBfqaJPLoFR4pMoAX")),
        DirectoryCoin("department-of-government-efficiency-5", "Department Of Government Efficiency", "d.o.g.e", mapOf("eip155:8453" to "0x599f07567656e6961e20fa6a90685d393808c192")),
        DirectoryCoin("ishares-global-government-bond-usd-hedged-active-etf-ondo-tokenized", "iShares Global Government Bond USD Hedged Active ETF (Ondo Tokenized)", "ggovon", mapOf("eip155:1" to "0xfbd7880ebc5c2b1b871a8ce188c05311015139f4", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "tzuC3sZnHg7spuAFhdCqivx9qtJg15qUBUpCJx1ondo", "eip155:56" to "0x8dd51e233e2344498f876c231a3bd962e9750a6c")),
        DirectoryCoin("ousg", "Ondo Short-Term U.S. Government Bond Fund", "ousg", mapOf("eip155:1" to "0x1b19c19393e2d034d8ff31ff34c81252fcbbee92", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "i7u4r16TcsJTgq1kAG8opmVZyVnAKBwLKu6ZPMwzxNc", "eip155:137" to "0xba11c5effa33c4d6f8f593cfa394241cfe925811")),
        DirectoryCoin("superstate-short-duration-us-government-securities-fund-ustb", "Invesco Short Duration US Government Securities Fund", "ustb", mapOf("eip155:1" to "0x43415eb6ff9db7e26a15b704e7a3edce97d31c4e", "eip155:98866" to "0xe4fa682f94610ccd170680cc3b045d77d9e528a8", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "CCz3SGVziFeLYk2xfEstkiqJfYkjaSWb2GCABYsVcjo2")),
    )
}
