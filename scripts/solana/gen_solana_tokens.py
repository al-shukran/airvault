#!/usr/bin/env python3
"""Writes the bundled SPL token list, shared/src/main/assets/registry/solana-tokens.json.

Two calls to CoinGecko's public API give the candidates. The markets call gives the top 100 coins by market cap in the solana-ecosystem
category. The coins list, with platforms, gives each coin's Solana platform address, which is its mint. One call covers every coin, so the
public rate limit is not reached. Each mint is then read from Solana mainnet with getMultipleAccounts (jsonParsed). It must be an
initialized SPL Token or Token-2022 mint, and the decimals written are the chain's. The first ten tokens are printed with their decimals,
as the check of the list.

Run it with the standard library only, from the repository root:

    python3 -I scripts/solana/gen_solana_tokens.py [OUTPUT]

Without OUTPUT it writes the bundled file. The file is CoinGecko data: licenses/THIRD-PARTY-NOTICES.txt carries its attribution.
"""

import datetime
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

COINGECKO = "https://api.coingecko.com/api/v3"
SOLANA_RPC = "https://api.mainnet-beta.solana.com"
SPL_TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
CATEGORY = "solana-ecosystem"
DEFAULT_OUTPUT = "shared/src/main/assets/registry/solana-tokens.json"
MAX_ATTEMPTS = 6
# A generic client name: the request names no project and no person.
USER_AGENT = "solana-token-list-generator/1.0"
CHECKED_PREVIEW = 10


def get_json(url, payload=None, timeout=60):
    """Reads one JSON document. A GET has no payload, and a POST sends the payload as JSON. A 429 waits and tries again."""
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    for attempt in range(1, MAX_ATTEMPTS + 1):
        request = urllib.request.Request(url, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == MAX_ATTEMPTS:
                raise
            wait = float(error.headers.get("Retry-After") or 30 * attempt)
            print(f"rate limited; waiting {wait:.0f} seconds", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def market_coins():
    """The category's coins, in market-cap order, with CoinGecko's ids, symbols and names."""
    query = urllib.parse.urlencode({"vs_currency": "usd", "category": CATEGORY, "per_page": 100, "page": 1})
    return get_json(f"{COINGECKO}/coins/markets?{query}")


def solana_mints():
    """The Solana platform address of each coin CoinGecko lists, by coin id. None when a coin has no Solana platform."""
    query = urllib.parse.urlencode({"include_platform": "true"})
    rows = get_json(f"{COINGECKO}/coins/list?{query}", timeout=180)
    return {row["id"]: (row.get("platforms") or {}).get("solana") or None for row in rows}


def chain_accounts(mints):
    """The parsed account of each mint on Solana mainnet, in the order of [mints]."""
    accounts = []
    for start in range(0, len(mints), 100):
        batch = mints[start : start + 100]
        reply = get_json(
            SOLANA_RPC,
            {"jsonrpc": "2.0", "id": 1, "method": "getMultipleAccounts", "params": [batch, {"encoding": "jsonParsed"}]},
        )
        if "error" in reply:
            raise SystemExit(f"Solana RPC error: {reply['error']}")
        accounts.extend(reply["result"]["value"])
    return accounts


def chain_decimals(mint, account):
    """The decimals of [mint] as the chain holds them. Stops the run when the account is not an initialized SPL mint."""
    if account is None:
        raise SystemExit(f"{mint}: no account on Solana mainnet")
    if account["owner"] not in (SPL_TOKEN, TOKEN_2022):
        raise SystemExit(f"{mint}: owned by {account['owner']}, not an SPL Token or Token-2022 program")
    parsed = account["data"].get("parsed") if isinstance(account["data"], dict) else None
    if not parsed or parsed.get("type") != "mint" or not parsed["info"].get("isInitialized"):
        raise SystemExit(f"{mint}: not an initialized mint that the RPC can parse")
    return int(parsed["info"]["decimals"])


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUTPUT
    mints_by_id = solana_mints()
    candidates = []
    for coin in market_coins():
        mint = mints_by_id.get(coin["id"])
        if mint:
            candidates.append((coin["id"], mint, coin["symbol"].upper(), coin["name"]))

    unique = []
    seen = set()
    for row in candidates:
        if row[1] not in seen:
            seen.add(row[1])
            unique.append(row)

    accounts = chain_accounts([row[1] for row in unique])
    tokens = []
    for index, ((coin_id, mint, symbol, name), account) in enumerate(zip(unique, accounts)):
        decimals = chain_decimals(mint, account)
        tokens.append({"mint": mint, "symbol": symbol, "name": name, "decimals": decimals, "coingeckoId": coin_id})
        if index < CHECKED_PREVIEW:
            print(f"checked {symbol}: {decimals} decimals on chain", file=sys.stderr)

    document = {
        "source": f"CoinGecko public API, category {CATEGORY}, top 100 by market cap; data provided by CoinGecko",
        "retrieved": datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
        "tokens": tokens,
    }
    with open(output, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(document, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {len(tokens)} tokens to {output}", file=sys.stderr)


if __name__ == "__main__":
    main()
