# Registry data tools

These scripts rebuild the mainnet catalogs that ship in the app:

- `app/src/main/assets/registry/networks.json`
- `app/src/main/assets/registry/tokens.json`

Testnets never go here. They stay in `app/src/debug/assets/registry/*.debug.json`.

Everything is stdlib Python 3.9 or later. Run the scripts with `-I` so no local module can shadow the standard
library.

## Files

| File | What it is |
| --- | --- |
| `build.py` | Fetches the public sources into `raw/` and derives candidates into `out/`. Rewrites `sources.md`. |
| `verify_onchain.py` | Checks each network's chain ID and each token's code, decimals and symbol over JSON-RPC. Writes `verify-report.md`. |
| `feemodel.json` | `feeModel` per chain, with the source for each value. Read by `build.py`. |
| `exclusions.json` | Logo slugs that are not added, with the reason and source. |
| `manual-networks.json` | Chains chainlist does not list as mainnets (HyperEVM). Also gives the RPC that `verify_onchain.py` uses for them. |
| `gap-tokens.json` | Token gaps accepted from an official source, and gaps that were not accepted, with reasons. |
| `sources.md` | Generated. Fetch log, match tables, every verified and unverified token, and the evidence for each gap. |
| `verify-report.md` | Generated. Per-network and per-token results from `verify_onchain.py`. |
| `raw/`, `out/` | Generated and git-ignored. |

## Sources

| Source | Used for | Read at |
| --- | --- | --- |
| https://chainid.network/chains.json | Network names, native currency, chain IDs, RPC lists, testnet and deprecated flags | `build.py`, `verify_onchain.py` |
| https://tokens.uniswap.org | Token candidates (provider 1 of 3) | `build.py` |
| https://static.optimism.io/optimism.tokenlist.json | Token candidates (provider 2 of 3) | `build.py` |
| https://api.coingecko.com/api/v3/asset_platforms and https://tokens.coingecko.com/<platform>/all.json | Token candidates (provider 3 of 3) | `build.py` |
| https://developers.circle.com/stablecoins/usdc-contract-addresses | USDC contracts (the only accepted USDC) | `build.py` |
| https://raw.githubusercontent.com/ethereum-optimism/superchain-registry/main/chainList.json | OP Stack mainnets (`feeModel`) | research run, see `feemodel.json` |
| https://ethbeat.org/scaling/projects/<slug> (L2BEAT; `l2beat.com` redirects here) | Rollup category per chain (`feeModel`) | research run, see `feemodel.json` |
| https://docs.usdt0.to/technical-documentation/deployments and https://docs.usdt0.to/api/deployments | USDT on Arbitrum and Polygon, XAUT0 on Arbitrum, Polygon and Avalanche | research run, see `gap-tokens.json` |
| https://tether.to/en/supported-protocols/ | XAU₮ on BNB Smart Chain | research run, see `gap-tokens.json` |
| https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/hyperevm | HyperEVM chain ID and RPC | research run, see `manual-networks.json` |

Gap and classification sources are read by hand, so their URLs and quotes are recorded in the JSON files,
not fetched by `build.py`.

## Rules

### Networks

1. A network is a chain whose logo is in `RegistryIcons.kt`. Bitcoin is skipped as non-EVM.
2. Its chainlist entry must be a mainnet: not a testnet name and not deprecated.
3. Names, native symbols and decimals of the shipped rows are kept. A mismatch with chainlist is reported in
   `sources.md`, not changed.
4. Chains in `exclusions.json` are not added (Blast, Kroma and OKXChain; see below).
5. `verify_onchain.py` keeps a network when at least one of its RPCs answers `eth_chainId` with the expected value
   and none answers with another value. A network with no answer is kept only when chainlist lists a mainnet, and
   it is flagged in the report.

### feeModel

`L2_ROLLUP` is the value that shows the L1 data-fee note in the sign flow, which says the total may be higher. A
rollup with a hidden data fee therefore gets the note; an independent chain does not.

- `L2_ROLLUP` for every chain that settles to Ethereum. That covers rollups, validiums and optimiums, as L2BEAT
  classifies them, plus chains that describe themselves as Ethereum L2s even when L2BEAT lists them as "Other".
- `L1` for every other chain. These are independent chains (their own consensus, or a sidechain that does not
  settle to Ethereum). They are not listed one by one.
- A chain that is probably an Ethereum L2 but could not be confirmed in the research run keeps `L2_ROLLUP`
  conservatively. These are marked `conservative` in `feemodel.json`.
- The OP Stack mainnets come from the Superchain registry, which Optimism maintains. Other L2s come from their
  L2BEAT project page, followed from `l2beat.com` (which now redirects to `ethbeat.org`).
- The shipped rows' `feeModel` values are kept where they agree with this rule. Changes are listed in `sources.md`.

### Tokens

1. Accepted only when at least two of Uniswap, the Superchain token list and CoinGecko list the same
   `(chainId, contract)` with the same decimals.
2. Symbols match case-insensitively.
3. USDC is accepted only from Circle's official contract page. Bridged USDC (for example Binance-Peg) is not
   accepted.
4. Two contracts for one symbol on one chain, or one contract under two symbols, are not accepted.
5. Gap tokens (below) are accepted only from an official issuer or bridge source that names the contract.
6. `verify_onchain.py` rejects a token whose code is empty, whose `decimals()` is unreadable, or whose `decimals()`
   differs from the declared value. It never rejects on a symbol. A symbol that differs (USDT0, USD₮0, XAUt0) is
   reported. When the on-chain symbol is the same asset written differently (for example USD₮0 for USDT0), the
   on-chain form is written and noted.
7. Each shipped token keeps its existing name, symbol and icon.

### Icons

Icon names are the keys in `RegistryIcons.kt`. A chain's icon is `chain_<slug>` where the slug uses underscores.
A token's icon is `token_<lowercase symbol>`. A logo with no key is shipped as `null`.

## Gaps (USDT, USDC, DAI, XAUT, USDP, PAXG on major chains)

Major chains: 1, 42161, 10, 8453, 137, 56, 43114.

Accepted (official source names the contract, then on-chain check):

- USDT on 42161 and 137: Tether's USDT0 deployments page (`docs.usdt0.to`).
- XAUT on 42161, 137 and 43114 (as XAUT0): USDT0 API (`docs.usdt0.to/api/deployments`).
- XAUT on 56: Tether's supported-protocols page (`tether.to/en/supported-protocols/`).

Not accepted, and the reason is in `gap-tokens.json`:

- USDT on 56. No official source names the Binance-Peg address (0x55d398…). Aggregators only.
- DAI on 43114. Avalanche's official article names DAI.e but gives no address.
- PAXG on 42161 and 137. Only third-party sites give the addresses.
- USDP beyond Ethereum. Paxos's page says "Available on Ethereum and Solana".

## Dropped chains

- Blast (81457): L2BEAT says it is archived and shutting down; bridge withdrawals are closed.
- Kroma (255): shut down on June 30, 2025 (L2BEAT).
- OKXChain (66, the `okex` logo): OKTChain is retired, and OKX moved users to X Layer. X Layer already has the
  `x-layer` logo, so the `okex` logo is dropped rather than re-mapped. This rests on press coverage; no official
  OKX page was read.

Flagged, kept: Abstract (winding down; bridge out before 2026-12-15), Arbitrum Nova (in maintenance per the DAO).

## How to run

From the repository root:

```sh
python3 -I tools/registry-data/build.py
python3 -I tools/registry-data/verify_onchain.py \
  --networks tools/registry-data/out/networks.json \
  --tokens tools/registry-data/out/tokens.json \
  --out-networks app/src/main/assets/registry/networks.json \
  --out-tokens app/src/main/assets/registry/tokens.json
```

`build.py` fetches the sources on every run (about 75 requests, paced at 1.5 seconds, retried with backoff). It
writes `out/` and `sources.md`. It does not touch `app/`.

`verify_onchain.py` makes about 3 requests per network and 3 per token, paced per host (default 0.25 s), with 15 s
timeouts and up to four attempts with backoff. Pass `--report` to write the report somewhere else. Without
`--out-networks` and `--out-tokens` it only reports.

After integration, run `./gradlew :app:testDebugUnitTest`. `ShippedRegistryDataTest` checks the shipped JSON.
