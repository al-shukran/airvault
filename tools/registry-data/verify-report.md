> Partial re-check (`--only`): only the listed changed entries were checked on chain in this run. Every other token keeps the result of the full run recorded in the git history of this file.

# On-chain verification report

Generated 2026-11-10T19:40:26Z by `tools/registry-data/verify_onchain.py`.

Networks checked: 2. Tokens checked: 13.

Network status: confirmed 2.

Token status: bridged 11, ok 2.

## Networks

| chainId | name | status | RPCs asked | answered the expected chainId | notes |
| --- | --- | --- | --- | --- | --- |
| 56 | BNB Smart Chain | confirmed | 3 | 3 |  |
| 43114 | Avalanche C-Chain | confirmed | 3 | 2 | https://xrpc.cl/avalanche: HTTP 503 |

## Tokens

| chainId | declared symbol | contract | declared dec. | on-chain dec. | on-chain symbol | code | status | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 43114 | 1INCH | `0xd501281565bf7789224523144fe5d98e8b28f267` | 18 | 18 | 1INCH.e | present | bridged | chain labels this token '1INCH.e': a bridged asset, shipped with the on-chain symbol and the name '1inch (Avalanche Bridge)' |
| 43114 | AAVE | `0x63a72806098bd3d9520cc43356dd78afe5d386d9` | 18 | 18 | AAVE.e | present | bridged | chain labels this token 'AAVE.e': a bridged asset, shipped with the on-chain symbol and the name 'Aave (Avalanche Bridge)' |
| 43114 | COMP | `0xc3048e19e76cb9a3aa9d77d8c03c29fc906e2437` | 18 | 18 | COMP.e | present | bridged | chain labels this token 'COMP.e': a bridged asset, shipped with the on-chain symbol and the name 'Compound (Avalanche Bridge)' |
| 43114 | GRT | `0x8a0cac13c7da965a312f08ea4229c37869e85cb9` | 18 | 18 | GRT.e | present | bridged | chain labels this token 'GRT.e': a bridged asset, shipped with the on-chain symbol and the name 'The Graph (Avalanche Bridge)' |
| 43114 | LINK | `0x5947bb275c521040051d82396192181b413227a3` | 18 | 18 | LINK.e | present | bridged | chain labels this token 'LINK.e': a bridged asset, shipped with the on-chain symbol and the name 'Chainlink (Avalanche Bridge)' |
| 43114 | MKR | `0x88128fd4b259552a9a1d457f435a6527aab72d42` | 18 | 18 | MKR.e | present | bridged | chain labels this token 'MKR.e': a bridged asset, shipped with the on-chain symbol and the name 'Maker (Avalanche Bridge)' |
| 43114 | SNX | `0xbec243c995409e6520d7c41e404da5deba4b209b` | 18 | 18 | SNX.e | present | bridged | chain labels this token 'SNX.e': a bridged asset, shipped with the on-chain symbol and the name 'Synthetix (Avalanche Bridge)' |
| 43114 | SUSHI | `0x37b608519f91f70f2eeb0e5ed9af4061722e4f76` | 18 | 18 | SUSHI.e | present | bridged | chain labels this token 'SUSHI.e': a bridged asset, shipped with the on-chain symbol and the name 'SushiSwap (Avalanche Bridge)' |
| 43114 | WETH | `0x49d5c2bdffac6ce2bfdb6640f4f80f226bc10bab` | 18 | 18 | WETH.e | present | bridged | chain labels this token 'WETH.e': a bridged asset, shipped with the on-chain symbol and the name 'Wrapped Ether (Avalanche Bridge)' |
| 43114 | YFI | `0x9eaac1b23d935365bd7b542fe22ceee2922f52dc` | 18 | 18 | YFI.e | present | bridged | chain labels this token 'YFI.e': a bridged asset, shipped with the on-chain symbol and the name 'yearn.finance (Avalanche Bridge)' |
| 56 | WETH | `0x2170ed0880ac9a755fd29b2688956bd959f933f8` | 18 | 18 | ETH | present | bridged | chain labels this token 'ETH': a bridged asset, shipped with the on-chain symbol and the name 'Binance Pegged ETH' |
| 56 | USDT | `0x55d398326f99059ff775485246999027b3197955` | 18 | 18 | USDT | present | ok |  |
| 56 | USDC | `0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d` | 18 | 18 | USDC | present | ok |  |
