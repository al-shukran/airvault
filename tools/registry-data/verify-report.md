# On-chain verification report

Generated 2026-11-10T18:51:02Z by `tools/registry-data/verify_onchain.py`.

Networks checked: 58. Tokens checked: 249.

Network status: confirmed 55, unconfirmed 3.

Token status: ok 230, rename 1, review 16, unverified 2.

## Networks

| chainId | name | status | RPCs asked | answered the expected chainId | notes |
| --- | --- | --- | --- | --- | --- |
| 1 | Ethereum | confirmed | 3 | 2 | https://api.mycryptoapi.com/eth: HTTP 403 |
| 10 | Optimism | confirmed | 3 | 3 |  |
| 25 | Cronos Mainnet | confirmed | 3 | 3 |  |
| 30 | Rootstock Mainnet | confirmed | 2 | 2 |  |
| 40 | Telos EVM Mainnet | confirmed | 2 | 2 |  |
| 56 | BNB Smart Chain | confirmed | 3 | 3 |  |
| 61 | Ethereum Classic | confirmed | 3 | 1 | https://etc.rivet.link: URLError; https://besu-at.etc-network.info: HTTP 404 |
| 100 | Gnosis | confirmed | 3 | 2 | https://rpc.ankr.com/gnosis: rate limited: Unauthorized: You must authenticate your request with an API key. Create an acco |
| 122 | Fuse Mainnet | confirmed | 2 | 2 |  |
| 130 | Unichain | confirmed | 3 | 3 |  |
| 137 | Polygon PoS | confirmed | 3 | 3 |  |
| 143 | Monad | confirmed | 1 | 1 |  |
| 146 | Sonic Mainnet | confirmed | 3 | 3 |  |
| 169 | Manta Pacific Mainnet | confirmed | 2 | 2 |  |
| 185 | Mint Mainnet | unconfirmed | 3 | 0 | DROPPED: no RPC answered the chain ID within the timeout and retries; https://rpc.mintchain.io: URLError; https://global.rpc.mintchain.io: URLError |
| 196 | X Layer Mainnet | confirmed | 2 | 2 |  |
| 232 | Lens | confirmed | 1 | 1 |  |
| 250 | Fantom Opera | confirmed | 3 | 1 | https://rpc.ftm.tools: HTTP 401; https://fantom-rpc.publicnode.com: HTTP 404 |
| 252 | Fraxtal | confirmed | 2 | 2 |  |
| 324 | zkSync Mainnet | confirmed | 2 | 2 |  |
| 369 | PulseChain | confirmed | 3 | 3 |  |
| 388 | Cronos zkEVM Mainnet | confirmed | 1 | 1 |  |
| 480 | World Chain | confirmed | 3 | 2 | https://worldchain-mainnet.gateway.tenderly.co: HTTP 404 |
| 999 | HyperEVM | confirmed | 1 | 1 |  |
| 1088 | Metis Andromeda Mainnet | confirmed | 3 | 3 |  |
| 1101 | Polygon zkEVM | confirmed | 2 | 2 |  |
| 1135 | Lisk | unconfirmed | 1 | 0 | DROPPED: no RPC answered the chain ID within the timeout and retries; https://rpc.api.lisk.com: HTTP 503 |
| 1284 | Moonbeam | confirmed | 3 | 1 | https://rpc.api.moonbeam.network: URLError; https://moonbeam.public.blastapi.io: HTTP 403 |
| 1285 | Moonriver | confirmed | 3 | 1 | https://rpc.api.moonriver.moonbeam.network: URLError; https://moonriver.public.blastapi.io: URLError |
| 1329 | Sei Network | confirmed | 1 | 1 |  |
| 1868 | Soneium | confirmed | 1 | 1 |  |
| 2020 | Ronin Mainnet | confirmed | 1 | 1 |  |
| 2222 | Kava | confirmed | 3 | 2 | https://kava-evm.rpc.thirdweb.com: -32001: Invalid chain |
| 2741 | Abstract | confirmed | 1 | 1 |  |
| 3338 | peaq | confirmed | 3 | 3 |  |
| 4326 | MegaETH Mainnet | confirmed | 1 | 1 |  |
| 5000 | Mantle | confirmed | 2 | 2 |  |
| 8217 | Kaia Mainnet | confirmed | 1 | 1 |  |
| 8453 | Base | confirmed | 3 | 2 | https://developer-access-mainnet.base.org/: URLError |
| 9745 | Plasma Mainnet | confirmed | 1 | 1 |  |
| 13371 | Immutable zkEVM | confirmed | 2 | 2 |  |
| 33139 | ApeChain | confirmed | 1 | 1 |  |
| 34443 | Mode | unconfirmed | 2 | 0 | DROPPED: no RPC answered the chain ID within the timeout and retries; https://mainnet.mode.network: HTTP 403; https://mode.drpc.org: HTTP 400 |
| 42161 | Arbitrum One | confirmed | 3 | 2 | https://rpcfree.com/arbitrum-rpc: JSONDecodeError |
| 42170 | Arbitrum Nova | confirmed | 2 | 2 |  |
| 42220 | Celo Mainnet | confirmed | 2 | 1 | https://celo-rpc.blockreq.com/v1/rpc/public: HTTP 410 |
| 43114 | Avalanche C-Chain | confirmed | 3 | 2 | https://xrpc.cl/avalanche: HTTP 503 |
| 48900 | Zircuit Mainnet | confirmed | 1 | 1 |  |
| 57073 | Ink | confirmed | 2 | 2 |  |
| 59144 | Linea | confirmed | 3 | 2 | https://linea-rpc.blockreq.com/v1/rpc/public: HTTP 410 |
| 60808 | BOB | confirmed | 2 | 1 | https://bob-mainnet.public.blastapi.io: HTTP 403 |
| 80094 | Berachain | confirmed | 3 | 2 | https://rpc.berachain-apis.com: HTTP 403 |
| 167000 | Taiko | confirmed | 2 | 2 |  |
| 534352 | Scroll | confirmed | 3 | 1 | https://rpc.ankr.com/scroll: HTTP 403; https://scroll-mainnet.chainstacklabs.com: URLError |
| 747474 | katana | confirmed | 3 | 2 | https://katana.gateway.tenderly.co/: HTTP 404 |
| 7777777 | Zora | confirmed | 1 | 1 |  |
| 1313161554 | Aurora Mainnet | confirmed | 2 | 2 |  |
| 1666600000 | Harmony Mainnet Shard 0 | confirmed | 3 | 3 |  |

## Tokens

| chainId | declared symbol | contract | declared dec. | on-chain dec. | on-chain symbol | code | status | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1INCH | `0x111111111117dc0aa78b770fa6a738034120c302` | 18 | 18 | 1INCH | present | ok |  |
| 1 | AAVE | `0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9` | 18 | 18 | AAVE | present | ok |  |
| 1 | APE | `0x4d224452801aced8b2f0aebe155379bb5d594381` | 18 | 18 | APE | present | ok |  |
| 1 | ARB | `0xb50721bcf8d664c30412cfbc6cf7a15145234ad1` | 18 | 18 | ARB | present | ok |  |
| 1 | AXS | `0xbb0e17ef65f82ab018d8edd776e8dd940327b28b` | 18 | 18 | AXS | present | ok |  |
| 1 | BAL | `0xba100000625a3754423978a60c9317c58a424e3d` | 18 | 18 | BAL | present | ok |  |
| 1 | BLUR | `0x5283d291dbcf85356a21ba090e6db59121208b44` | 18 | 18 | BLUR | present | ok |  |
| 1 | BUSD | `0x4fabb145d64652a948d72533023f6e7a623c7c53` | 18 | 18 | BUSD | present | ok |  |
| 1 | cbBTC | `0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf` | 8 | 8 | cbBTC | present | ok |  |
| 1 | cbETH | `0xbe9895146f7af43049ca1c1ae358b0541ea49704` | 18 | 18 | cbETH | present | ok |  |
| 1 | CHZ | `0x3506424f91fd33084466f402d5d97f05f8e3b4af` | 18 | 18 | CHZ | present | ok |  |
| 1 | COMP | `0xc00e94cb662c3520282e6f5717214004a7f26888` | 18 | 18 | COMP | present | ok |  |
| 1 | CRV | `0xd533a949740bb3306d119cc777fa900ba034cd52` | 18 | 18 | CRV | present | ok |  |
| 1 | crvUSD | `0xf939e0a03fb07f59a73314e73794be0e57ac1b4e` | 18 | 18 | crvUSD | present | ok |  |
| 1 | DAI | `0x6b175474e89094c44da98b954eedeac495271d0f` | 18 | 18 | DAI | present | ok |  |
| 1 | ENJ | `0xf629cbd94d3791c9250152bd8dfbdf380e2a3b9c` | 18 | 18 | ENJ | present | ok |  |
| 1 | ENS | `0xc18360217d8f7ab5e7c516566761ea12ce7f9d72` | 18 | 18 | ENS | present | ok |  |
| 1 | EURC | `0x1abaea1f7c830bd89acc67ec4af516284b1bc33c` | 6 | 6 | EURC | present | ok |  |
| 1 | FET | `0xaea46a60368a7bd060eec7df8cba43b7ef41ad85` | 18 | 18 | FET | present | ok |  |
| 1 | FRAX | `0x853d955acef822db058eb8505911ed77f175b99e` | 18 | 18 | FRAX | present | ok |  |
| 1 | FXS | `0x3432b6a60d23ca0dfca7761b7ab56459d9c964d0` | 18 | 18 | FXS | present | ok |  |
| 1 | GALA | `0xd1d2eb1b1e90b638588728b4130137d262c87cae` | 8 | 8 | GALA | present | ok |  |
| 1 | GNO | `0x6810e776880c02933d47db1b9fc05908e5386b96` | 18 | 18 | GNO | present | ok |  |
| 1 | GRT | `0xc944e90c64b2c07662a292be6244bdf05cda44a7` | 18 | 18 | GRT | present | ok |  |
| 1 | GUSD | `0x056fd409e1d7a124bd7017459dfea2f387b6d5cd` | 2 | 2 | GUSD | present | ok |  |
| 1 | IMX | `0xf57e7e7c23978c3caec3c3548e3d615c346e79ff` | 18 | 18 | IMX | present | ok |  |
| 1 | INJ | `0xe28b3b32b6c345a34ff64674606124dd5aceca30` | 18 | 18 | INJ | present | ok |  |
| 1 | LDO | `0x5a98fcbea516cf06857215779fd812ca3bef1b32` | 18 | 18 | LDO | present | ok |  |
| 1 | LINK | `0x514910771af9ca656af840dff83e8264ecf986ca` | 18 | 18 | LINK | present | ok |  |
| 1 | LQTY | `0x6dea81c8171d0ba574754ef6f8b412f2ed88c54d` | 18 | 18 | LQTY | present | ok |  |
| 1 | LUSD | `0x5f98805a4e8be255a32880fdec7f6728c6568ba0` | 18 | 18 | LUSD | present | ok |  |
| 1 | MANA | `0x0f5d2fb29fb7d3cfee444a200298f468908cc942` | 18 | 18 | MANA | present | ok |  |
| 1 | MKR | `0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2` | 18 | 18 | MKR | present | ok |  |
| 1 | MOG | `0xaaee1a9723aadb7afa2810263653a34ba2c21c7a` | 18 | 18 | Mog | present | ok |  |
| 1 | PAXG | `0x45804880de22913dafe09f4980848ece6ecbaf78` | 18 | 18 | PAXG | present | ok |  |
| 1 | PENDLE | `0x808507121b80c02388fad14726482e061b8da827` | 18 | 18 | PENDLE | present | ok |  |
| 1 | POL | `0x455e53cbb86018ac2b8092fdcd39d8444affc3f6` | 18 | 18 | POL | present | ok |  |
| 1 | PYUSD | `0x6c3ea9036406852006290770bedfcaba0e23a0e8` | 6 | 6 | PYUSD | present | ok |  |
| 1 | rETH | `0xae78736cd615f374d3085123a210448e74fc6393` | 18 | 18 | rETH | present | ok |  |
| 1 | RPL | `0xd33526068d116ce69f19a9ee46f0bd304f21a51f` | 18 | 18 | RPL | present | ok |  |
| 1 | RSR | `0x320623b8e4ff03373931769a31fc52a4e78b5d70` | 18 | 18 | RSR | present | ok |  |
| 1 | SAND | `0x3845badade8e6dff049820680d1f14bd3903a5d0` | 18 | 18 | SAND | present | ok |  |
| 1 | sfrxETH | `0xac3e018457b222d93114458476f3e3416abbe38f` | 18 | 18 | sfrxETH | present | ok |  |
| 1 | SHIB | `0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce` | 18 | 18 | SHIB | present | ok |  |
| 1 | SNX | `0xc011a73ee8576fb46f5e1c5751ca3b9fe0af2a6f` | 18 | 18 | SNX | present | ok |  |
| 1 | SPX | `0xe0f63a424a4439cbe457d80e4f4b51ad25b2c56c` | 8 | 8 | SPX | present | ok |  |
| 1 | STRK | `0xca14007eff0db1f8135f4c25b34de49ab0d42766` | 18 | 18 | STRK | present | ok |  |
| 1 | SUSHI | `0x6b3595068778dd592e39a122f4f5a5cf09c90fe2` | 18 | 18 | SUSHI | present | ok |  |
| 1 | tBTC | `0x18084fba666a33d37592fa2633fd49a74dd93a88` | 18 | 18 | tBTC | present | ok |  |
| 1 | TUSD | `0x0000000000085d4780b73119b644ae5ecd22b376` | 18 | 18 | TUSD | present | ok |  |
| 1 | UNI | `0x1f9840a85d5af5bf1d1762f925bdaddc4201f984` | 18 | 18 | UNI | present | ok |  |
| 1 | USDC | `0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48` | 6 | 6 | USDC | present | ok |  |
| 1 | USDP | `0x8e870d67f660d95d5be530380d0ec0bd388289e1` | 18 | 18 | USDP | present | ok |  |
| 1 | USDT | `0xdac17f958d2ee523a2206206994597c13d831ec7` | 6 | 6 | USDT | present | ok |  |
| 1 | WBTC | `0x2260fac5e5542a773aa44fbcfedf7c193bc2c599` | 8 | 8 | WBTC | present | ok |  |
| 1 | WETH | `0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2` | 18 | 18 | WETH | present | ok |  |
| 1 | WLD | `0x163f8c2467924be0ae7b5347228cabf260318753` | 18 | 18 | WLD | present | ok |  |
| 1 | XAUT | `0x68749665ff8d2d112fa859aa293f07a622782f38` | 6 | 6 | XAUt | present | ok |  |
| 1 | YFI | `0x0bc529c00c6401aef6d220be8c6ea1667f6ad93e` | 18 | 18 | YFI | present | ok |  |
| 10 | 1INCH | `0xad42d013ac31486b73b6b059e748172994736426` | 18 | 18 | 1INCH | present | ok |  |
| 10 | AAVE | `0x76fb31fb4af56892a25e32cfc43de717950c9278` | 18 | 18 | AAVE | present | ok |  |
| 10 | BAL | `0xfe8b128ba8c78aabc59d4c64cee7ff28e9379921` | 18 | 18 | BAL | present | ok |  |
| 10 | BUSD | `0x9c9e5fd8bbc25984b178fdce6117defa39d2db39` | 18 | 18 | BUSD | present | ok |  |
| 10 | cbETH | `0xaddb6a0412de1ba0f936dcaeb8aaa24578dcf3b2` | 18 | 18 | cbETH | present | ok |  |
| 10 | CRV | `0x0994206dfe8de6ec6920ff4d779b0d950605fb53` | 18 | 18 | CRV | present | ok |  |
| 10 | crvUSD | `0xc52d7f23a2e460248db6ee192cb23dd12bddcbf6` | 18 | 18 | crvUSD | present | ok |  |
| 10 | DAI | `0xda10009cbd5d07dd0cecc66161fc93d7c9000da1` | 18 | 18 | DAI | present | ok |  |
| 10 | ENS | `0x65559aa14915a70190438ef90104769e5e890a00` | 18 | 18 | ENS | present | ok |  |
| 10 | FRAX | `0x2e3d870790dc77a83dd1d18184acc7439a53f475` | 18 | 18 | FRAX | present | ok |  |
| 10 | FXS | `0x67ccea5bb16181e7b4109c9c2143c24a1c2205be` | 18 | 18 | FXS | present | ok |  |
| 10 | LDO | `0xfdb794692724153d1488ccdbe0c56c252596735f` | 18 | 18 | LDO | present | ok |  |
| 10 | LINK | `0x350a791bfc2c21f9ed5d10980dad2e2638ffa7f6` | 18 | 18 | LINK | present | ok |  |
| 10 | LUSD | `0xc40f949f8a4e094d1b49a23ea9241d289b7b2819` | 18 | 18 | LUSD | present | ok |  |
| 10 | MKR | `0xab7badef82e9fe11f6f33f87bc9bc2aa27f2fcb5` | 18 | 18 | MKR | present | ok |  |
| 10 | OP | `0x4200000000000000000000000000000000000042` | 18 | 18 | OP | present | ok |  |
| 10 | PENDLE | `0xbc7b1ff1c6989f006a1185318ed4e7b5796e66e1` | 18 | 18 | PENDLE | present | ok |  |
| 10 | PEPE | `0xc1c167cc44f7923cd0062c4370df962f9ddb16f5` | 18 | 18 | PEPE | present | ok |  |
| 10 | rETH | `0x9bcef72be871e61ed4fbbc7630889bee758eb81d` | 18 | 18 | rETH | present | ok |  |
| 10 | RPL | `0xc81d1f0eb955b0c020e5d5b264e1ff72c14d1401` | 18 | 18 | RPL | present | ok |  |
| 10 | sfrxETH | `0x484c2d6e3cdd945a8b2df735e079178c1036578c` | 18 | 18 | sfrxETH | present | ok |  |
| 10 | SNX | `0x8700daec35af8ff88c16bdf0418774cb3d7599b4` | 18 | 18 | SNX | present | ok |  |
| 10 | SUSHI | `0x3eaeb77b03dbc0f6321ae1b72b2e9adb0f60112b` | 18 | 18 | SUSHI | present | ok |  |
| 10 | TUSD | `0xcb59a0a753fdb7491d5f3d794316f1ade197b21e` | 18 | 18 | TUSD | present | ok |  |
| 10 | UNI | `0x6fd9d7ad17242c41f7131d257212c54a0e816691` | 18 | 18 | UNI | present | ok |  |
| 10 | USDC | `0x0b2c639c533813f4aa9d7837caf62653d097ff85` | 6 | 6 | USDC | present | ok |  |
| 10 | USDC.e | `0x7f5c764cbc14f9669b88837ca1490cca17c31607` | 6 | 6 | USDC | present | review | symbol mismatch: declared 'USDC.e', on-chain 'USDC' |
| 10 | USDT | `0x94b008aa00579c1307b0ef2c499ad98a8ce58e58` | 6 | 6 | USDT | present | ok |  |
| 10 | WBTC | `0x68f180fcce6836688e9084f035309e29bf0a2095` | 8 | 8 | WBTC | present | ok |  |
| 10 | WETH | `0x4200000000000000000000000000000000000006` | 18 | 18 | WETH | present | ok |  |
| 10 | WLD | `0xdc6ff44d5d932cbd77b52e5612ba0529dc6226f1` | 18 | 18 | WLD | present | ok |  |
| 10 | YFI | `0x9046d36440290ffde54fe0dd84db8b1cfee9107b` | 18 | 18 | YFI | present | ok |  |
| 56 | 1INCH | `0x111111111117dc0aa78b770fa6a738034120c302` | 18 | 18 | 1INCH | present | ok |  |
| 56 | AAVE | `0xfb6115445bff7b52feb98650c87f44907e58f802` | 18 | 18 | AAVE | present | ok |  |
| 56 | AXS | `0x715d400f88c167884bbcc41c5fea407ed4d2f8a0` | 18 | 18 | AXS | present | ok |  |
| 56 | BUSD | `0xe9e7cea3dedca5984780bafc599bd69add087d56` | 18 | 18 | BUSD | present | ok |  |
| 56 | COMP | `0x52ce071bd9b1c4b00a0b92d298c512478cad67e8` | 18 | 18 | COMP | present | ok |  |
| 56 | DAI | `0x1af3f329e8be154074d8769d1ffa4ee058b1dbc3` | 18 | 18 | DAI | present | ok |  |
| 56 | FET | `0x031b41e504677879370e9dbcf937283a8691fa7f` | 18 | 18 | FET | present | ok |  |
| 56 | FRAX | `0x90c97f71e18723b0cf0dfa30ee176ab653e89f40` | 18 | 18 | FRAX | present | ok |  |
| 56 | INJ | `0xa2b726b1145a4773f68593cf171187d8ebe4d495` | 18 | 18 | INJ | present | ok |  |
| 56 | LINK | `0xf8a0bf9cf54bb92f17374d9e9a321e6a111a51bd` | 18 | 18 | LINK | present | ok |  |
| 56 | SUSHI | `0x947950bcc74888a40ffa2593c5798f11fc9124c4` | 18 | 18 | SUSHI | present | ok |  |
| 56 | UNI | `0xbf5140a22578168fd562dccf235e5d43a02ce9b1` | 18 | 18 | UNI | present | ok |  |
| 56 | WETH | `0x2170ed0880ac9a755fd29b2688956bd959f933f8` | 18 | 18 | ETH | present | review | symbol mismatch: declared 'WETH', on-chain 'ETH' |
| 56 | XAUT | `0x21caef8a43163eea865baee23b9c2e327696a3bf` | 6 | 6 | XAUt | present | ok |  |
| 130 | 1INCH | `0xbe41cde1c5e75a7b6c2c70466629878aa9acd06e` | 18 | 18 | 1INCH | present | ok |  |
| 130 | AAVE | `0x02a24c380da560e4032dc6671d8164cfbeeaae1e` | 18 | 18 | AAVE | present | ok |  |
| 130 | BAL | `0x01625e26274ed828ac1d47694c97221b34a8addf` | 18 | 18 | BAL | present | ok |  |
| 130 | cbETH | `0xeb64b50fef2a363940369285f86ae9a68211db59` | 18 | 18 | cbETH | present | ok |  |
| 130 | COMP | `0xdf78e4f0a8279942ca68046476919a90f2288656` | 18 | 18 | COMP | present | ok |  |
| 130 | CRV | `0xac73671a1762fe835208fb93b7ae7490d1c2ccb3` | 18 | 18 | CRV | present | ok |  |
| 130 | DAI | `0x20cab320a855b39f724131c69424240519573f81` | 18 | 18 | DAI | present | ok |  |
| 130 | ENS | `0x80756faf1e7fec5678bf505670ef176ab5f0383a` | 18 | 18 | ENS | present | ok |  |
| 130 | FRAX | `0x8c7879bf25d678d9949f305857bd4437d74132b9` | 18 | 18 | FRAX | present | ok |  |
| 130 | FXS | `0x79301df2117c7f56859fd01b28bbaa61062021d6` | 18 | 18 | FXS | present | ok |  |
| 130 | GRT | `0xbb2272ffc0ef8f439373adffd45c3591b3204d71` | 18 | 18 | GRT | present | ok |  |
| 130 | LDO | `0x68a6dbc7214a0f2b0d875963663f1613814e8829` | 18 | 18 | LDO | present | ok |  |
| 130 | LINK | `0x5a53b6d19d8edcb7923f0d840eebb3f09bbeefb7` | 18 | 18 | LINK | present | ok |  |
| 130 | LQTY | `0x0176b38b7767451b1b682236ece2fae853c71a60` | 18 | 18 | LQTY | present | ok |  |
| 130 | LUSD | `0xf81b7485b4cb59645f74528d702c7f8cd72577fb` | 18 | 18 | LUSD | present | ok |  |
| 130 | MOG | `0x58d68e179864605fea06eaadf1185c6e78921ebd` | 18 | 18 | Mog | present | ok |  |
| 130 | PEPE | `0xd9b5da95b3d97c3e9872102fdb47d4c09074952b` | 18 | 18 | PEPE | present | ok |  |
| 130 | rETH | `0x94cac393f3444cef63a651ffc18497e7e8bd036a` | 18 | 18 | rETH | present | ok |  |
| 130 | RSR | `0x993a565a1e6219951323ca3c34cee0a3b1889066` | 18 | 18 | RSR | present | ok |  |
| 130 | SNX | `0x022d952abcc6c8271f26e59e37a65dc359e6bc88` | 18 | 18 | SNX | present | ok |  |
| 130 | SPX | `0x51a7b9a11f10d04c16306d90dc4ec22b036dd629` | 8 | 8 | SPX | present | ok |  |
| 130 | SUSHI | `0x2982be2d0c6ae4a7d5bc1c8fe7b630e3bdfb3ce5` | 18 | 18 | SUSHI | present | ok |  |
| 130 | tBTC | `0xad497996dc33dc8e8e552824ccee199420bc7814` | 18 | 18 | tBTC | present | ok |  |
| 130 | UNI | `0x8f187aa05619a017077f5308904739877ce9ea21` | 18 | 18 | UNI | present | ok |  |
| 130 | USDC | `0x078d782b760474a361dda0af3839290b0ef57ad6` | 6 | 6 | USDC | present | ok |  |
| 130 | USDT | `0x588ce4f028d8e7b53b687865d6a67b3a54c75518` | 6 | 6 | USDT | present | ok |  |
| 130 | WBTC | `0x927b51f251480a681271180da4de28d44ec4afb8` | 8 | 8 | WBTC | present | ok |  |
| 130 | WETH | `0x4200000000000000000000000000000000000006` | 18 | 18 | WETH | present | ok |  |
| 130 | YFI | `0x52bf54eb4210f588320f3e4c151bca81f84a3201` | 18 | 18 | YFI | present | ok |  |
| 137 | AAVE | `0xd6df932a45c0f255f85145f286ea0b292b21c90b` | 18 | 18 | AAVE | present | ok |  |
| 137 | APE | `0xb7b31a6bc18e48888545ce79e83e06003be70930` | 18 | 18 | APE | present | ok |  |
| 137 | BAL | `0x9a71012b13ca4d3d0cdc72a177df3ef03b0e76a3` | 18 | 18 | BAL | present | ok |  |
| 137 | COMP | `0x8505b9d2254a7ae468c0e9dd10ccea3a837aef5c` | 18 | 18 | COMP | present | ok |  |
| 137 | CRV | `0x172370d5cd63279efa6d502dab29171933a610af` | 18 | 18 | CRV | present | ok |  |
| 137 | DAI | `0x8f3cf7ad23cd3cadbd9735aff958023239c6a063` | 18 | 18 | DAI | present | ok |  |
| 137 | GRT | `0x5fe2b58c013d7601147dcdd68c143a77499f5531` | 18 | 18 | GRT | present | ok |  |
| 137 | LDO | `0xc3c7d422809852031b44ab29eec9f1eff2a58756` | 18 | 18 | LDO | present | ok |  |
| 137 | LINK | `0x53e0bca35ec356bd5dddfebbd1fc0fd03fabad39` | 18 | 18 | LINK | present | ok |  |
| 137 | LUSD | `0x23001f892c0c82b79303edc9b9033cd190bb21c7` | 18 | 18 | LUSD | present | ok |  |
| 137 | MANA | `0xa1c57f48f0deb89f569dfbe6e2b7f46d33606fd4` | 18 | 18 | MANA | present | ok |  |
| 137 | MKR | `0x6f7c932e7684666c9fd1d44527765433e01ff61d` | 18 | 18 | MKR | present | ok |  |
| 137 | SAND | `0xbbba073c31bf03b8acf7c28ef0738decf3695683` | 18 | 18 | SAND | present | ok |  |
| 137 | SNX | `0x50b728d8d964fd00c2d0aad81718b71311fef68a` | 18 | 18 | SNX | present | ok |  |
| 137 | SUSHI | `0x0b3f868e0be5597d5db7feb59e1cadbb0fdda50a` | 18 | 18 | SUSHI | present | ok |  |
| 137 | UNI | `0xb33eaad8d922b1083446dc23f610c2567fb5180f` | 18 | 18 | UNI | present | ok |  |
| 137 | USDC | `0x3c499c542cef5e3811e1192ce70d8cc03d5c3359` | 6 | 6 | USDC | present | ok |  |
| 137 | USDC.e | `0x2791bca1f2de4661ed88a30c99a7a9449aa84174` | 6 | 6 | USDC | present | review | symbol mismatch: declared 'USDC.e', on-chain 'USDC' |
| 137 | USDT | `0xc2132d05d31c914a87c6611c10748aeb04b58e8f` | 6 | 6 | USDT0 | present | review | symbol mismatch: declared 'USDT', on-chain 'USDT0' |
| 137 | WBTC | `0x1bfd67037b42cf73acf2047067bd4f2c47d9bfd6` | 8 | 8 | WBTC | present | ok |  |
| 137 | WETH | `0x7ceb23fd6bc0add59e62ac25578270cff1b9f619` | 18 | 18 | WETH | present | ok |  |
| 137 | XAUT0 | `0xf1815bd50389c46847f0bda824ec8da914045d14` | 6 | 6 | XAUt0 | present | ok |  |
| 137 | YFI | `0xda537104d6a5edd53c6fbba9a898708e465260b6` | 18 | 18 | YFI | present | ok |  |
| 143 | USDC | `0x754704bc059f8c67012fed69bc8a327a5aafb603` | 6 | 6 | USDC | present | ok |  |
| 143 | WBTC | `0x0555e30da8f98308edb960aa94c0db47230d2b9c` | 8 | 8 | WBTC | present | ok |  |
| 143 | WETH | `0xee8c0e9f1bffb4eb878d8f15f368a02a35481242` | 18 | 18 | WETH | present | ok |  |
| 480 | WBTC | `0x03c7054bcb39f7b2e5b2c7acb37583e32d70cfa3` | 8 | 8 | WBTC | present | ok |  |
| 480 | WETH | `0x4200000000000000000000000000000000000006` | 18 | 18 | WETH | present | ok |  |
| 1868 | USDT | `0x3a337a6ada9d885b6ad95ec48f9b75f197b5ae35` | 6 | 6 | USDT | present | ok |  |
| 1868 | WETH | `0x4200000000000000000000000000000000000006` | 18 | 18 | WETH | present | ok |  |
| 8453 | 1INCH | `0xc5fecc3a29fb57b5024eec8a2239d4621e111cbe` | 18 | 18 | 1INCH | present | ok |  |
| 8453 | AAVE | `0x63706e401c06ac8513145b7687a14804d17f814b` | 18 | 18 | AAVE | present | ok |  |
| 8453 | AERO | `0x940181a94a35a4569e4529a3cdfb74e38fd98631` | 18 | 18 | AERO | present | ok |  |
| 8453 | BAL | `0x4158734d47fc9692176b5085e0f52ee0da5d47f1` | 18 | 18 | BAL | present | ok |  |
| 8453 | cbBTC | `0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf` | 8 | 8 | cbBTC | present | ok |  |
| 8453 | cbETH | `0x2ae3f1ec7f1f5012cfeab0185bfc7aa3cf0dec22` | 18 | 18 | cbETH | present | ok |  |
| 8453 | COMP | `0x9e1028f5f1d5ede59748ffcee5532509976840e0` | 18 | 18 | COMP | present | ok |  |
| 8453 | CRV | `0x8ee73c484a26e0a5df2ee2a4960b789967dd0415` | 18 | 18 | CRV | present | ok |  |
| 8453 | crvUSD | `0x417ac0e078398c154edfadd9ef675d30be60af93` | 18 | 18 | crvUSD | present | ok |  |
| 8453 | DAI | `0x50c5725949a6f0c72e6c4a641f24049a917db0cb` | 18 | 18 | DAI | present | ok |  |
| 8453 | DEGEN | `0x4ed4e862860bed51a9570b96d89af5e1b0efefed` | 18 | 18 | DEGEN | present | ok |  |
| 8453 | EURC | `0x60a3e35cc302bfa44cb288bc5a4f316fdb1adb42` | 6 | 6 | EURC | present | ok |  |
| 8453 | FET | `0x74f804b4140ee70830b3eef4e690325841575f89` | 18 | 18 | FET | present | ok |  |
| 8453 | GRT | `0x0f4d237b09cb37d207ba60353dc254d4530d4df1` | 18 | 18 | GRT | present | ok |  |
| 8453 | LQTY | `0x5259384690acf240e9b0a8811bd0ffbfbddc125c` | 18 | 18 | LQTY | present | ok |  |
| 8453 | LUSD | `0x368181499736d0c0cc614dbb145e2ec1ac86b8c6` | 18 | 18 | LUSD | present | ok |  |
| 8453 | MOG | `0x2da56acb9ea78330f947bd57c54119debda7af71` | 18 | 18 | Mog | present | ok |  |
| 8453 | PENDLE | `0xa99f6e6785da0f5d6fb42495fe424bce029eeb3e` | 18 | 18 | PENDLE | present | ok |  |
| 8453 | rETH | `0xb6fe221fe9eef5aba221c348ba20a1bf5e73624c` | 18 | 18 | rETH | present | ok |  |
| 8453 | RPL | `0x1f73eaf55d696bffa9b0ea16fa987b93b0f4d302` | 18 | 18 | RPL | present | ok |  |
| 8453 | RSR | `0xab36452dbac151be02b16ca17d8919826072f64a` | 18 | 18 | RSR | present | ok |  |
| 8453 | SNX | `0x22e6966b799c4d5b13be962e1d117b56327fda66` | 18 | 18 | SNX | present | ok |  |
| 8453 | SPX | `0x50da645f148798f68ef2d7db7c1cb22a6819bb2c` | 8 | 8 | SPX | present | ok |  |
| 8453 | SUSHI | `0x7d49a065d17d6d4a55dc13649901fdbb98b2afba` | 18 | 18 | SUSHI | present | ok |  |
| 8453 | tBTC | `0x236aa50979d5f3de3bd1eeb40e81137f22ab794b` | 18 | 18 | tBTC | present | ok |  |
| 8453 | TOSHI | `0xac1bd2486aaf3b5c0fc3fd868558b082a531b2b4` | 18 | 18 | TOSHI | present | ok |  |
| 8453 | UNI | `0xc3de830ea07524a0761646a6a4e4be0e114a3c83` | 18 | 18 | UNI | present | ok |  |
| 8453 | USDC | `0x833589fcd6edb6e08f4c7c32d4f71b54bda02913` | 6 | 6 | USDC | present | ok |  |
| 8453 | USDT | `0xfde4c96c8593536e31f229ea8f37b2ada2699bb2` | 6 | 6 | USDT | present | ok |  |
| 8453 | WETH | `0x4200000000000000000000000000000000000006` | 18 | 18 | WETH | present | ok |  |
| 8453 | YFI | `0x9eaf8c1e34f05a589eda6bafdf391cf6ad3cb239` | 18 | 18 | YFI | present | ok |  |
| 34443 | USDT | `0xf0f161fda2712db8b566946122a5af183995e2ed` | 6 | - | - | - | unverified | network not accepted |
| 34443 | WBTC | `0xcdd475325d6f564d27247d1dddbb0dac6fa0a5cf` | 8 | - | - | - | unverified | network not accepted |
| 42161 | 1INCH | `0x6314c31a7a1652ce482cffe247e9cb7c3f4bb9af` | 18 | 18 | 1INCH | present | ok |  |
| 42161 | AAVE | `0xba5ddd1f9d7f570dc94a51479a000e3bce967196` | 18 | 18 | AAVE | present | ok |  |
| 42161 | ARB | `0x912ce59144191c1204e64559fe8253a0e49e6548` | 18 | 18 | ARB | present | ok |  |
| 42161 | BAL | `0x040d1edc9569d4bab2d15287dc5a4f10f56a56b8` | 18 | 18 | BAL | present | ok |  |
| 42161 | cbBTC | `0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf` | 8 | 8 | cbBTC | present | ok |  |
| 42161 | cbETH | `0x1debd73e752beaf79865fd6446b0c970eae7732f` | 18 | 18 | cbETH | present | ok |  |
| 42161 | COMP | `0x354a6da3fcde098f8389cad84b0182725c6c91de` | 18 | 18 | COMP | present | ok |  |
| 42161 | CRV | `0x11cdb42b0eb46d95f990bedd4695a6e3fa034978` | 18 | 18 | CRV | present | ok |  |
| 42161 | DAI | `0xda10009cbd5d07dd0cecc66161fc93d7c9000da1` | 18 | 18 | DAI | present | ok |  |
| 42161 | GNO | `0xa0b862f60edef4452f25b4160f177db44deb6cf1` | 18 | 18 | GNO | present | ok |  |
| 42161 | GRT | `0x9623063377ad1b27544c965ccd7342f7ea7e88c7` | 18 | 18 | GRT | present | ok |  |
| 42161 | LDO | `0x13ad51ed4f1b7e9dc168d8a00cb3f4ddd85efa60` | 18 | 18 | LDO | present | ok |  |
| 42161 | LINK | `0xf97f4df75117a78c1a5a0dbb814af92458539fb4` | 18 | 18 | LINK | present | ok |  |
| 42161 | LQTY | `0xfb9e5d956d889d91a82737b9bfcdac1dce3e1449` | 18 | 18 | LQTY | present | ok |  |
| 42161 | LUSD | `0x93b346b6bc2548da6a1e7d98e9a421b42541425b` | 18 | 18 | LUSD | present | ok |  |
| 42161 | PENDLE | `0x0c880f6761f1af8d9aa9c466984b80dab9a8c9e8` | 18 | 18 | PENDLE | present | ok |  |
| 42161 | RPL | `0xb766039cc6db368759c1e56b79affe831d0cc507` | 18 | 18 | RPL | present | ok |  |
| 42161 | RSR | `0xca5ca9083702c56b481d1eec86f1776fdbd2e594` | 18 | 18 | RSR | present | ok |  |
| 42161 | SUSHI | `0xd4d42f0b6def4ce0383636770ef773390d85c61a` | 18 | 18 | SUSHI | present | ok |  |
| 42161 | UNI | `0xfa7f8980b0f1e64a2062791cc3b0871572f1f7f0` | 18 | 18 | UNI | present | ok |  |
| 42161 | USDC | `0xaf88d065e77c8cc2239327c5edb3a432268e5831` | 6 | 6 | USDC | present | ok |  |
| 42161 | USDC.e | `0xff970a61a04b1ca14834a43f5de4533ebddb5cc8` | 6 | 6 | USDC | present | review | symbol mismatch: declared 'USDC.e', on-chain 'USDC' |
| 42161 | USDT | `0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9` | 6 | 6 | USD₮0 | present | review | symbol mismatch: declared 'USDT', on-chain 'USD₮0' |
| 42161 | WBTC | `0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f` | 8 | 8 | WBTC | present | ok |  |
| 42161 | WETH | `0x82af49447d8a07e3bd95bd0d56f35241523fbab1` | 18 | 18 | WETH | present | ok |  |
| 42161 | XAUT0 | `0x40461291347e1ecbb09499f3371d3f17f10d7159` | 6 | 6 | XAUt0 | present | ok |  |
| 42161 | YFI | `0x82e3a8f066a6989666b031d916c43672085b1582` | 18 | 18 | YFI | present | ok |  |
| 42220 | USDC | `0xceba9300f2b948710d2653dd7b07f33a8b32118c` | 6 | 6 | USDC | present | ok |  |
| 42220 | USDT | `0x48065fbbe25f71c9282ddf5e1cd6d6a887483d5e` | 6 | 6 | USD₮ | present | rename | on-chain symbol 'USD₮' is the same asset as declared 'USDT'; on-chain used |
| 42220 | WBTC | `0x8ac2901dd8a1f17a1a4768a6ba4c3751e3995b2d` | 8 | 8 | WBTC | present | ok |  |
| 42220 | WETH | `0xd221812de1bd094f35587ee8e174b07b6167d9af` | 18 | 18 | WETH | present | ok |  |
| 43114 | 1INCH | `0xd501281565bf7789224523144fe5d98e8b28f267` | 18 | 18 | 1INCH.e | present | review | symbol mismatch: declared '1INCH', on-chain '1INCH.e' |
| 43114 | AAVE | `0x63a72806098bd3d9520cc43356dd78afe5d386d9` | 18 | 18 | AAVE.e | present | review | symbol mismatch: declared 'AAVE', on-chain 'AAVE.e' |
| 43114 | BUSD | `0x9c9e5fd8bbc25984b178fdce6117defa39d2db39` | 18 | 18 | BUSD | present | ok |  |
| 43114 | COMP | `0xc3048e19e76cb9a3aa9d77d8c03c29fc906e2437` | 18 | 18 | COMP.e | present | review | symbol mismatch: declared 'COMP', on-chain 'COMP.e' |
| 43114 | EURC | `0xc891eb4cbdeff6e073e859e987815ed1505c2acd` | 6 | 6 | EURC | present | ok |  |
| 43114 | FRAX | `0xd24c2ad096400b6fbcd2ad8b24e7acbc21a1da64` | 18 | 18 | FRAX | present | ok |  |
| 43114 | GRT | `0x8a0cac13c7da965a312f08ea4229c37869e85cb9` | 18 | 18 | GRT.e | present | review | symbol mismatch: declared 'GRT', on-chain 'GRT.e' |
| 43114 | LINK | `0x5947bb275c521040051d82396192181b413227a3` | 18 | 18 | LINK.e | present | review | symbol mismatch: declared 'LINK', on-chain 'LINK.e' |
| 43114 | MKR | `0x88128fd4b259552a9a1d457f435a6527aab72d42` | 18 | 18 | MKR.e | present | review | symbol mismatch: declared 'MKR', on-chain 'MKR.e' |
| 43114 | SNX | `0xbec243c995409e6520d7c41e404da5deba4b209b` | 18 | 18 | SNX.e | present | review | symbol mismatch: declared 'SNX', on-chain 'SNX.e' |
| 43114 | SUSHI | `0x37b608519f91f70f2eeb0e5ed9af4061722e4f76` | 18 | 18 | SUSHI.e | present | review | symbol mismatch: declared 'SUSHI', on-chain 'SUSHI.e' |
| 43114 | USDC | `0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e` | 6 | 6 | USDC | present | ok |  |
| 43114 | USDT | `0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7` | 6 | 6 | USDt | present | ok |  |
| 43114 | WETH | `0x49d5c2bdffac6ce2bfdb6640f4f80f226bc10bab` | 18 | 18 | WETH.e | present | review | symbol mismatch: declared 'WETH', on-chain 'WETH.e' |
| 43114 | XAUT0 | `0x2775d5105276781b4b85ba6ea6a6653beed1dd32` | 6 | 6 | XAUt0 | present | ok |  |
| 43114 | YFI | `0x9eaac1b23d935365bd7b542fe22ceee2922f52dc` | 18 | 18 | YFI.e | present | review | symbol mismatch: declared 'YFI', on-chain 'YFI.e' |
| 57073 | LINK | `0x71052bae71c25c78e37fd12e5ff1101a71d9018f` | 18 | 18 | LINK | present | ok |  |
| 57073 | PYUSD | `0x142cdc44890978b506e745bb3bd11607b7f7faef` | 6 | 6 | PYUSD | present | ok |  |
| 57073 | USDC | `0x2d270e6886d130d724215a266106e6832161eaed` | 6 | 6 | USDC | present | ok |  |
| 57073 | USDC.e | `0xf1815bd50389c46847f0bda824ec8da914045d14` | 6 | 6 | USDC.e | present | ok |  |
| 57073 | WETH | `0x4200000000000000000000000000000000000006` | 18 | 18 | WETH | present | ok |  |
