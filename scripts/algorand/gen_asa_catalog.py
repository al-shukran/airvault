#!/usr/bin/env python3
"""Builds app/src/main/assets/algorand/asa-catalog.json: the popular Algorand Standard Assets (ASAs) that Lensly names offline.

    python3 scripts/algorand/gen_asa_catalog.py [--out PATH]

Standard library only. The script reads public APIs, so it needs network access; the app never does.

- CoinGecko category "algorand-ecosystem", top 100 coins by market cap (/coins/markets). Each coin's platforms.algorand
  (/coins/{id}) is the mainnet asset ID of its ASA. CoinGecko has no testnet IDs.
- Algonode indexers (/v2/assets/{id}) for mainnet and testnet. The catalog stores the name, unit name and decimals the
  chain itself holds for each asset. A CoinGecko symbol that differs from the chain's unit name is reported, and the chain's
  value is kept.

An asset the indexer does not find, that is deleted, or whose unit name or decimals are unusable is left out and reported
on stderr. Assets are sorted by ID, so a rerun over the same data writes the same file apart from the date.
"""

import argparse
import datetime
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

COINGECKO = "https://api.coingecko.com/api/v3"
MAINNET_INDEXER = "https://mainnet-idx.algonode.cloud"
TESTNET_INDEXER = "https://testnet-idx.algonode.cloud"
USER_AGENT = "lensly-asa-catalog"

# The networks Lensly signs for, with the genesis hash each one pins in AlgorandNetwork. The catalog is keyed by hash, so
# an asset ID is looked up only on the network it belongs to.
NETWORKS = {
    "mainnet-v1.0": ("wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=", MAINNET_INDEXER),
    "testnet-v1.0": ("SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=", TESTNET_INDEXER),
}

# CoinGecko has no testnet asset IDs, so the testnet list is the IDs named here. Each one is checked on the testnet indexer.
TESTNET_ASSET_IDS = [10458941]  # USDC on testnet

# The public CoinGecko API allows only a few calls a minute, so the detail calls are spaced out.
COINGECKO_PAUSE_SECONDS = 7.0
INDEXER_PAUSE_SECONDS = 0.2
MAX_DECIMALS = 19  # the most decimals an Algorand asset can have

DEFAULT_OUT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "algorand" / "asa-catalog.json"


def get_json(url, retries=6):
    """The JSON at [url], or None for a 404. Rate limits and server errors are retried with a pause."""
    for attempt in range(retries):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            if error.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                wait = float(error.headers.get("Retry-After") or 60)
                print(f"retry after HTTP {error.code} in {wait:.0f}s: {url}", file=sys.stderr)
                time.sleep(wait)
                continue
            raise
        except urllib.error.URLError:
            if attempt < retries - 1:
                time.sleep(5 * (attempt + 1))
                continue
            raise
    raise RuntimeError(f"no response from {url}")


def coingecko_algorand_assets():
    """Maps each mainnet ASA ID of the top 100 algorand-ecosystem coins to the coin's symbol."""
    markets = get_json(f"{COINGECKO}/coins/markets?vs_currency=usd&category=algorand-ecosystem&per_page=100")
    if not markets:
        raise SystemExit("CoinGecko returned no coins for the algorand-ecosystem category")
    assets = {}
    for position, coin in enumerate(markets):
        if position:
            time.sleep(COINGECKO_PAUSE_SECONDS)
        query = "localization=false&tickers=false&market_data=false&community_data=false&developer_data=false&sparkline=false"
        detail = get_json(f"{COINGECKO}/coins/{urllib.parse.quote(coin['id'])}?{query}")
        raw = str(((detail or {}).get("platforms") or {}).get("algorand") or "").strip()
        if not raw:
            continue
        if not raw.isdigit():
            print(f"skip {coin['id']}: algorand platform '{raw}' is not an asset ID", file=sys.stderr)
            continue
        assets.setdefault(int(raw), str(coin.get("symbol", "")).upper())
    return assets


def build_network(name, indexer, asset_ids, symbols):
    """The catalog entries of one network, checked against its indexer."""
    entries = []
    for asset_id in sorted(asset_ids):
        time.sleep(INDEXER_PAUSE_SECONDS)
        body = get_json(f"{indexer}/v2/assets/{asset_id}")
        asset = (body or {}).get("asset")
        if not asset:
            print(f"{name}: asset {asset_id} not found, left out", file=sys.stderr)
            continue
        if asset.get("deleted"):
            print(f"{name}: asset {asset_id} is deleted, left out", file=sys.stderr)
            continue
        params = asset.get("params") or {}
        unit = str(params.get("unit-name") or "").strip()
        decimals = params.get("decimals")
        if not unit or not isinstance(decimals, int) or not 0 <= decimals <= MAX_DECIMALS:
            print(f"{name}: asset {asset_id} has no usable unit name or decimals, left out", file=sys.stderr)
            continue
        symbol = symbols.get(asset_id)
        if symbol and symbol != unit.upper():
            print(f"{name}: asset {asset_id} is '{unit}' on chain but '{symbol}' on CoinGecko; the chain's value is kept",
                  file=sys.stderr)
        entries.append({
            "id": asset_id,
            "unit": unit,
            "name": str(params.get("name") or "").strip() or unit,
            "decimals": decimals,
        })
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="where to write the catalog JSON")
    args = parser.parse_args()

    symbols = coingecko_algorand_assets()
    print(f"CoinGecko: {len(symbols)} mainnet ASA IDs in the algorand-ecosystem top 100", file=sys.stderr)
    networks = {
        "mainnet-v1.0": build_network("mainnet-v1.0", MAINNET_INDEXER, symbols.keys(), symbols),
        "testnet-v1.0": build_network("testnet-v1.0", TESTNET_INDEXER, TESTNET_ASSET_IDS, {}),
    }
    document = {
        "generated": datetime.date.today().isoformat(),
        "generator": "scripts/algorand/gen_asa_catalog.py",
        "sources": [
            "CoinGecko category algorand-ecosystem (https://www.coingecko.com/en/categories/algorand-ecosystem): ASA IDs from platforms.algorand",
            "Algonode indexers (https://mainnet-idx.algonode.cloud, https://testnet-idx.algonode.cloud): name, unit name and decimals",
        ],
        "networks": {
            NETWORKS[name][0]: {"genesisId": name, "assets": entries} for name, entries in networks.items()
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {args.out}: mainnet {len(networks['mainnet-v1.0'])} assets, testnet {len(networks['testnet-v1.0'])}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
