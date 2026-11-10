#!/usr/bin/env python3
"""Derive candidate Lensly registry rows from public sources (stdlib only).

Run from anywhere:
    python3 -I tools/registry-data/build.py

Each run re-fetches the sources listed in sources.md into raw/, writes the candidates to out/, and rewrites
sources.md. Nothing under app/ is written. verify_onchain.py then checks out/ on chain, and README.md says how
the result reaches app/src/main/assets/registry/. Downloaded files are parsed as JSON or searched as text; they
are never executed.
"""
import datetime as dt
import hashlib
import json
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
RAW = HERE / "raw"
OUT = HERE / "out"
EXISTING_NETWORKS = REPO / "app/src/main/assets/registry/networks.json"
EXISTING_TOKENS = REPO / "app/src/main/assets/registry/tokens.json"
REGISTRY_ICONS = REPO / "app/src/main/java/app/lensly/ui/components/RegistryIcons.kt"
FEE_MODEL_FILE = HERE / "feemodel.json"
EXCLUSIONS_FILE = HERE / "exclusions.json"
MANUAL_NETWORKS_FILE = HERE / "manual-networks.json"
GAP_TOKENS_FILE = HERE / "gap-tokens.json"

NON_EVM_SLUGS = {"bitcoin"}  # a logo that is not an EVM chain
MAJOR_CHAIN_IDS = [1, 42161, 10, 8453, 137, 56, 43114]
REQUESTED_SYMBOLS = ["USDT", "USDC", "DAI", "XAUT", "USDP", "PAXG"]
PROVIDER_ORDER = ["uniswap", "superchain", "coingecko"]
NETWORK_FIELDS = ["chainId", "name", "nativeSymbol", "nativeDecimals", "feeModel", "explorerName", "testnet", "icon"]
TOKEN_FIELDS = ["chainId", "contract", "symbol", "name", "decimals", "icon"]
FEE_MODELS = {"L1", "L2_ROLLUP"}

# Logo slug -> exact chainlist name, where the slug does not normalise to the chainlist name.
# Every alias is checked against chains.json at run time.
ALIASES = {
    "avalanche": "Avalanche C-Chain",
    "binance-smart-chain": "BNB Smart Chain Mainnet",
    "blast": "Blast",
    "fantom": "Fantom Opera",
    "harmony": "Harmony Mainnet Shard 0",
    "immutable": "Immutable zkEVM",
    "mega-eth": "MegaETH Mainnet",
    "optimism": "OP Mainnet",
    "sei-network": "Sei Network",
    "telos": "Telos EVM Mainnet",
}

# (output symbol, source symbols matched case-insensitively against each list)
TARGETS = [
    ("USDT", ["USDT"]), ("USDC", ["USDC"]), ("USDC.e", ["USDC.e"]),
    ("DAI", ["DAI"]), ("XAUT", ["XAUT"]), ("USDP", ["USDP", "PAX"]),
    ("PAXG", ["PAXG"]), ("WETH", ["WETH"]),
    ("1INCH", ["1INCH"]), ("AAVE", ["AAVE"]), ("AERO", ["AERO"]), ("APE", ["APE"]),
    ("ARB", ["ARB"]), ("AXS", ["AXS"]), ("BAL", ["BAL"]), ("BLUR", ["BLUR"]),
    ("BUSD", ["BUSD"]), ("cbBTC", ["cbBTC"]), ("cbETH", ["cbETH"]), ("CHZ", ["CHZ"]),
    ("COMP", ["COMP"]), ("CRV", ["CRV"]), ("crvUSD", ["crvUSD"]), ("DEGEN", ["DEGEN"]),
    ("DOGE", ["DOGE"]), ("DYDX", ["DYDX"]), ("ENJ", ["ENJ"]), ("ENS", ["ENS"]),
    ("EURC", ["EURC"]), ("EURS", ["EURS"]), ("FET", ["FET"]), ("FRAX", ["FRAX"]),
    ("FXS", ["FXS"]), ("GALA", ["GALA"]), ("GHO", ["GHO"]), ("GNO", ["GNO"]),
    ("GRT", ["GRT"]), ("GUSD", ["GUSD"]), ("IMX", ["IMX"]), ("INJ", ["INJ"]),
    ("LDO", ["LDO"]), ("LINK", ["LINK"]), ("LQTY", ["LQTY"]), ("LUSD", ["LUSD"]),
    ("MANA", ["MANA"]), ("MATIC", ["MATIC"]), ("MKR", ["MKR"]), ("MOG", ["MOG"]),
    ("OP", ["OP"]), ("PENDLE", ["PENDLE"]), ("PEPE", ["PEPE"]), ("POL", ["POL"]),
    ("PYUSD", ["PYUSD"]), ("rETH", ["rETH"]), ("RPL", ["RPL"]), ("RSR", ["RSR"]),
    ("SAND", ["SAND"]), ("sfrxETH", ["sfrxETH"]), ("SHIB", ["SHIB"]), ("SNX", ["SNX"]),
    ("SPX", ["SPX"]), ("STRK", ["STRK"]), ("SUSHI", ["SUSHI"]), ("TAO", ["TAO"]),
    ("tBTC", ["tBTC"]), ("TOSHI", ["TOSHI"]), ("TUSD", ["TUSD"]), ("UNI", ["UNI"]),
    ("USDe", ["USDe"]), ("WBTC", ["WBTC"]), ("YFI", ["YFI"]), ("WLD", ["WLD"]),
]

TEST_RE = re.compile(
    r"test|sepolia|goerli|holesky|kovan|rinkeby|ropsten|kotti|cardona|amoy|"
    r"bepolia|artio|apothem|alfajores|baklava|floripa|wannsee|hyperspace|fuji|"
    r"devnet",
    re.I,
)
ADDR_RE = re.compile(r"^0x[0-9a-fA-F]{40}$")
RETRY_CODES = {403, 429, 500, 502, 503, 504}
FETCHES = []
FETCH_FAILURES = []


def utc_now():
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def fetch(url, rel, tries=8):
    """Download url into RAW/rel and log the fetch. Retries rate limits with backoff."""
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={"User-Agent": "lensly-registry-build/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                data = resp.read()
            break
        except urllib.error.HTTPError as err:
            if err.code not in RETRY_CODES or attempt == tries - 1:
                raise
            time.sleep(min(90, 5 * 2 ** attempt))
    path = RAW / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    FETCHES.append({"url": url, "utc": utc_now(), "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(), "file": rel})
    time.sleep(1.5)
    return path


def load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def without_comments(obj):
    """Drop keys named _comment, so data files can carry a note."""
    if isinstance(obj, dict):
        return {k: v for k, v in obj.items() if not k.startswith("_")}
    return obj


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def norm(name):
    return slug(re.sub(r"\b(mainnet|network|chain)\b", " ", name, flags=re.I))


def is_mainnet(chain):
    return (not TEST_RE.search(chain["name"])) and chain.get("status") != "deprecated"


def pick_chain(chains, logo_slug):
    """Return (chain entry, match method) or (None, reason)."""
    if logo_slug in ALIASES:
        hits = [c for c in chains if c["name"] == ALIASES[logo_slug]]
        if len(hits) != 1:
            raise SystemExit(f"alias for {logo_slug} matched {len(hits)} entries")
        return hits[0], "alias"
    hits = [c for c in chains if is_mainnet(c) and norm(c["name"]) == logo_slug]
    if len(hits) == 1:
        return hits[0], "name"
    return None, f"{len(hits)} mainnet name candidates"


def registry_icon_keys():
    """Icon names that resolve in RegistryIcons.kt (the generated map from the logo folder)."""
    text = REGISTRY_ICONS.read_text(encoding="utf-8")
    keys = set(re.findall(r'"((?:chain|token)_[a-z0-9_]+)" to R\.drawable', text))
    chain_keys = {k for k in keys if k.startswith("chain_")}
    token_keys = {k for k in keys if k.startswith("token_")}
    if not chain_keys or not token_keys:
        raise SystemExit(f"no icon keys found in {REGISTRY_ICONS}")
    return chain_keys, token_keys


def build_networks(chains, existing, manual, chain_keys, feemodel):
    by_id = {c["chainId"]: c for c in chains}
    rows = {}
    notes = {"matched": [], "unmatched": [], "contradictions": [], "fee": [], "excluded": [], "manual": []}
    existing_by_id = {e["chainId"]: e for e in existing}
    for e in existing:
        row = {f: e[f] for f in NETWORK_FIELDS if f in e}
        row.setdefault("icon", None)
        rows[e["chainId"]] = row
        cl = by_id.get(e["chainId"])
        if cl is None:
            notes["contradictions"].append(f"{e['name']} ({e['chainId']}): not in chains.json")
            continue
        nc = cl["nativeCurrency"]
        if nc.get("symbol") != e["nativeSymbol"]:
            notes["contradictions"].append(
                f"{e['name']}: nativeSymbol {e['nativeSymbol']} vs chains.json {nc.get('symbol')}")
        if nc.get("decimals") != e["nativeDecimals"]:
            notes["contradictions"].append(
                f"{e['name']}: nativeDecimals {e['nativeDecimals']} vs chains.json {nc.get('decimals')}")
        if cl["name"] != e["name"]:
            notes["fee"].append(f"name kept as '{e['name']}' (chains.json: '{cl['name']}')")
    manual_ids = set()
    for m in manual:
        cid = m["chainId"]
        if cid in rows:
            raise SystemExit(f"manual network {cid} is already in the set")
        rows[cid] = {f: m[f] for f in NETWORK_FIELDS}
        manual_ids.add(cid)
        notes["manual"].append(f"{m['name']} ({cid}): from manual-networks.json")
    excluded = {x["slug"]: x for x in load_json(EXCLUSIONS_FILE)["exclusions"]}
    manual_slugs = {m["icon"][len("chain_"):].replace("_", "-") for m in manual if m.get("icon")}
    for logo_slug in sorted(chain_keys):
        key = chain_keys[logo_slug]
        if logo_slug in NON_EVM_SLUGS or logo_slug in manual_slugs:
            continue
        if logo_slug in excluded:
            notes["excluded"].append(f"{logo_slug}: {excluded[logo_slug]['reason']}")
            continue
        chain, how = pick_chain(chains, logo_slug)
        if chain is None:
            notes["unmatched"].append(f"{logo_slug}: {how}")
            continue
        cid = chain["chainId"]
        if cid in manual_ids:
            continue
        if cid in rows:
            rows[cid]["icon"] = key
            notes["matched"].append(f"{logo_slug} -> {cid} (existing row, {how})")
            continue
        nc = chain.get("nativeCurrency") or {}
        if not nc.get("symbol") or not isinstance(nc.get("decimals"), int):
            notes["unmatched"].append(f"{logo_slug}: nativeCurrency missing")
            continue
        rows[cid] = {"chainId": cid, "name": chain["name"], "nativeSymbol": nc["symbol"],
                     "nativeDecimals": nc["decimals"], "feeModel": "L1",
                     "explorerName": (chain.get("explorers") or [{}])[0].get("name"),
                     "testnet": False, "icon": key}
        notes["matched"].append(f"{logo_slug} -> {cid} {chain['name']} ({how})")
    for cid, row in rows.items():
        entry = feemodel.get(str(cid))
        new_fee = entry["feeModel"] if entry else "L1"
        old_fee = (existing_by_id.get(cid) or {}).get("feeModel")
        if old_fee and old_fee != new_fee:
            notes["fee"].append(f"{row['name']} ({cid}): feeModel {old_fee} -> {new_fee} (feemodel.json)")
        row["feeModel"] = new_fee
        if row["icon"] is not None and row["icon"] not in chain_keys.values():
            raise SystemExit(f"icon {row['icon']} does not resolve in RegistryIcons.kt")
    for cid in feemodel:
        if int(cid) not in rows:
            notes["fee"].append(f"feemodel.json lists {cid}, which is not in the candidate set")
    return rows, notes


def target_lookup():
    lookup = {}
    for out, aliases in TARGETS:
        for a in aliases:
            key = a.lower()
            if key in lookup and lookup[key] != out:
                raise SystemExit(f"alias {a} maps to two outputs")
            lookup[key] = out
    return lookup


def icon_for(symbol, token_keys):
    key = "token_" + symbol.lower()
    return key if key in token_keys else None


def load_provider_tokens(networks):
    """Return {provider: [record,...]} with record = dict(chainId, address, symbol, name, decimals)."""
    out = {"uniswap": [], "superchain": [], "coingecko": []}
    for rec in load_json(RAW / "uniswap/tokens.json")["tokens"]:
        out["uniswap"].append(rec)
    for rec in load_json(RAW / "superchain/optimism.tokenlist.json")["tokens"]:
        out["superchain"].append(rec)
    platforms = load_json(RAW / "coingecko-platforms/asset_platforms.json")
    by_chain = {}
    for p in platforms:
        cid = p.get("chain_identifier")
        if isinstance(cid, int):
            by_chain.setdefault(cid, []).append(p["id"])
    cg_map = {}
    for cid in sorted(networks):
        ids = by_chain.get(cid)
        if not ids:
            FETCH_FAILURES.append(f"coingecko: no asset platform for chainId {cid}")
            continue
        if len(ids) > 1:
            FETCH_FAILURES.append(f"coingecko: chainId {cid} has platforms {ids}; used {ids[0]}")
        pid = ids[0]
        try:
            fetch(f"https://tokens.coingecko.com/{pid}/all.json", f"coingecko/{pid}.json")
        except urllib.error.HTTPError as err:
            FETCH_FAILURES.append(f"coingecko {pid} (chainId {cid}): HTTP {err.code}")
            continue
        cg_map[cid] = pid
        for rec in load_json(RAW / f"coingecko/{pid}.json")["tokens"]:
            rec = dict(rec)
            rec.setdefault("chainId", cid)
            out["coingecko"].append(rec)
    return out, cg_map


def collect_candidates(provider_tokens, networks, lookup):
    """cand[(out, chainId, address)][provider] = set of decimals"""
    cand = {}
    names = {}
    for prov in PROVIDER_ORDER:
        for rec in provider_tokens[prov]:
            cid = rec.get("chainId")
            addr = (rec.get("address") or "").lower()
            sym = (rec.get("symbol") or "").lower()
            dec = rec.get("decimals")
            if cid not in networks or not ADDR_RE.match(addr) or sym not in lookup:
                continue
            if not isinstance(dec, int):
                continue
            key = (lookup[sym], cid, addr)
            cand.setdefault(key, {}).setdefault(prov, set()).add(dec)
            names.setdefault(key, {}).setdefault(prov, rec.get("name") or "")
    return cand, names


def evaluate(cand, names, circle_addrs, token_keys):
    verified, unverified = [], []
    accepted_by_key = {}
    status = {}
    for key, provs in cand.items():
        out, cid, addr = key
        values = set().union(*provs.values())
        if out == "USDC" and addr not in circle_addrs:
            status[key] = ("conflict", "USDC is accepted only from Circle's official contract list "
                                       "(this is bridged or wrapped USDC)")
            continue
        if len(values) > 1:
            reason = "decimals conflict across sources: " + ", ".join(
                f"{p}={sorted(d)}" for p, d in sorted(provs.items()))
            status[key] = ("conflict", reason)
        elif len(provs) < 2:
            status[key] = ("single", f"only one source ({', '.join(sorted(provs))})")
        else:
            status[key] = ("accept", sorted(provs))
            accepted_by_key.setdefault((out, cid), []).append(addr)
    final = {}
    for (out, cid), addrs in accepted_by_key.items():
        chosen = addrs
        if len(addrs) > 1 and out == "USDC":
            issuer = [a for a in addrs if a in circle_addrs]
            if len(issuer) == 1:
                chosen = issuer
                for a in addrs:
                    if a != issuer[0]:
                        status[(out, cid, a)] = ("conflict", "second contract for USDC on chain; not Circle-listed")
        if len(chosen) == 1:
            final[(out, cid)] = chosen[0]
        else:
            for a in chosen:
                status[(out, cid, a)] = ("conflict", f"{len(chosen)} contracts accepted for {out} on chainId {cid}")
    seen = {}
    for (out, cid), addr in final.items():
        seen.setdefault((cid, addr), []).append(out)
    for (cid, addr), outs in seen.items():
        if len(outs) > 1:
            for out in outs:
                final.pop((out, cid), None)
                status[(out, cid, addr)] = ("conflict", f"same contract accepted under symbols {outs}")
    for (out, cid), addr in final.items():
        provs = cand[(out, cid, addr)]
        srcs = sorted(provs, key=PROVIDER_ORDER.index)
        name = next((names[(out, cid, addr)][p] for p in PROVIDER_ORDER
                     if p in names[(out, cid, addr)] and names[(out, cid, addr)][p]), out)
        decimals = next(iter(next(iter(provs.values()))))
        verified.append({"chainId": cid, "contract": addr, "symbol": out, "name": name,
                         "decimals": decimals, "icon": icon_for(out, token_keys), "sources": srcs})
    addr_owner = {(cid, addr): out for (out, cid), addr in final.items()}
    for key, provs in cand.items():
        out, cid, addr = key
        if (out, cid) in final and final[(out, cid)] == addr:
            continue
        st, reason = status.get(key, ("single", "not accepted"))
        if st == "accept":
            st, reason = "conflict", "accepted but superseded (see verified rule)"
        if st == "single" and (out, cid) in final:
            reason = f"{reason}; a different contract is verified for {out} on chainId {cid}"
        if (cid, addr) in addr_owner:
            reason = (f"{reason}; this contract is verified as {addr_owner[(cid, addr)]}, "
                      f"so this {out} label is not accepted")
        srcs = sorted(provs, key=PROVIDER_ORDER.index)
        decimals = next(iter(next(iter(provs.values()))))
        name = next((names[key][p] for p in PROVIDER_ORDER if p in names[key] and names[key][p]), out)
        unverified.append({"chainId": cid, "contract": addr, "symbol": out, "name": name,
                           "decimals": decimals, "icon": icon_for(out, token_keys), "sources": srcs,
                           "reason": reason})
    return verified, unverified


def merge_gap_tokens(verified, unverified, networks, gaps, token_keys):
    """Add the gap tokens whose official source names the contract. Decimals are checked on chain later."""
    have = {(r["chainId"], r["symbol"]) for r in verified}
    have_addr = {(r["chainId"], r["contract"]) for r in verified}
    notes = []
    for g in gaps:
        cid, addr, sym = g["chainId"], g["contract"].lower(), g["symbol"]
        if cid not in networks:
            notes.append(f"{sym} on {cid}: network not in the set")
            continue
        if (cid, addr) in have_addr:
            notes.append(f"{sym} on {cid}: contract already verified")
            continue
        if (cid, sym) in have:
            unverified.append({"chainId": cid, "contract": addr, "symbol": sym, "name": g["name"],
                               "decimals": g["decimals"], "icon": None, "sources": [f"official:{g['source']}"],
                               "reason": f"a verified {sym} already exists on chainId {cid}"})
            continue
        verified.append({"chainId": cid, "contract": addr, "symbol": sym, "name": g["name"],
                         "decimals": g["decimals"], "icon": icon_for(sym, token_keys),
                         "sources": [f"official:{g['source']}"]})
        have.add((cid, sym))
        have_addr.add((cid, addr))
        notes.append(f"{sym} on {cid} {addr}: added from {g['source']}")
    return notes


def apply_existing_tokens(verified, existing_tokens):
    """Keep the shipped name, symbol and icon for a (chainId, contract) that is already in tokens.json."""
    by_key = {(t["chainId"], t["contract"].lower()): t for t in existing_tokens}
    for row in verified:
        old = by_key.get((row["chainId"], row["contract"]))
        if old is None:
            continue
        row["name"] = old["name"]
        row["symbol"] = old["symbol"]
        if old.get("icon") is not None:
            row["icon"] = old["icon"]


def check_existing_tokens(existing_tokens, verified, unverified):
    report = []
    v = {(r["chainId"], r["contract"]): r for r in verified}
    u = {(r["chainId"], r["contract"]): r for r in unverified}
    for t in existing_tokens:
        key = (t["chainId"], t["contract"].lower())
        label = f"{t['symbol']} on {t['chainId']} {t['contract']}"
        if key in v:
            ok = v[key]
            if ok["decimals"] != t["decimals"]:
                report.append(f"CONTRADICTION {label}: decimals {t['decimals']} vs verified {ok['decimals']}")
            else:
                report.append(f"confirmed {label} by {', '.join(ok['sources'])}")
        elif key in u:
            report.append(f"NOT VERIFIED {label}: {u[key]['reason']}")
        else:
            report.append(f"NOT FOUND in any source {label}")
    return report


def write_rows(path, key, rows, fields):
    lines = ["{", f'  "{key}": [']
    for i, row in enumerate(rows):
        comma = "," if i < len(rows) - 1 else ""
        clean = {f: row[f] for f in fields}
        lines.append("    " + json.dumps(clean, ensure_ascii=False) + comma)
    lines += ["  ]", "}"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate(network_rows, tokens, chain_keys, token_keys):
    errors = []
    net_ids = {n["chainId"] for n in network_rows}
    if len(net_ids) != len(network_rows):
        errors.append("duplicate network chainId")
    for n in network_rows:
        if n["feeModel"] not in FEE_MODELS:
            errors.append(f"bad feeModel {n['feeModel']} for {n['chainId']}")
        if n["icon"] is not None and n["icon"] not in chain_keys.values():
            errors.append(f"network icon does not resolve: {n['icon']}")
    seen = set()
    for t in tokens:
        if t["chainId"] not in net_ids:
            errors.append(f"token chainId missing from networks: {t['chainId']} {t['symbol']}")
        if not ADDR_RE.match(t["contract"]) or t["contract"] != t["contract"].lower():
            errors.append(f"contract not lowercase 0x+40: {t['contract']}")
        if (t["chainId"], t["contract"]) in seen:
            errors.append(f"duplicate (chainId, contract): {t['chainId']} {t['contract']}")
        seen.add((t["chainId"], t["contract"]))
        if t["icon"] is not None and t["icon"] not in token_keys:
            errors.append(f"token icon does not resolve: {t['icon']}")
    return errors


def write_sources(notes, fetches, failures, verified, unverified, existing_report, missing_major,
                  circle_addrs, provider_counts, cg_map, gap_notes, feemodel, gaps):
    out = []
    out.append("# Registry sources and verification\n")
    out.append(f"Generated by `tools/registry-data/build.py` at {utc_now()} (UTC). Re-run with "
               "`python3 -I tools/registry-data/build.py`, then `verify_onchain.py` (see README.md).\n")
    out.append("## Fetched URLs\n")
    out.append("| URL | Fetched (UTC) | Bytes | SHA-256 | Local file |")
    out.append("| --- | --- | --- | --- | --- |")
    for f in fetches:
        out.append(f"| {f['url']} | {f['utc']} | {f['bytes']} | `{f['sha256'][:16]}...` | raw/{f['file']} |")
    out.append("")
    out.append("Failures and notes:\n")
    for f in failures:
        out.append(f"- {f}")
    out.append("")
    out.append("Manually fetched in the research run (not re-fetched by build.py): the gap sources in "
               "`gap-tokens.json`, `feemodel.json` and `manual-networks.json` list their URLs and quotes.\n")
    out.append("## Rules\n")
    out.append("- Networks: mainnets only. Testnet names and deprecated chains are excluded. Logo slugs come "
               "from `RegistryIcons.kt`, the generated map of the shipped logos, and are matched to "
               "chains.json by normalised name, with `ALIASES` where the slug differs. Bitcoin is skipped as "
               "non-EVM. Chains in `exclusions.json` are not added, and `manual-networks.json` adds a chain that "
               "chainlist does not list as a mainnet.")
    out.append("- feeModel: `feemodel.json` decides. A chain listed there takes its value. Every other chain is "
               "`L1`. The rule and its evidence are in README.md.")
    out.append("- Tokens: a (chainId, contract) is accepted only if at least two of uniswap, superchain and "
               "coingecko list it with the same decimals. Symbols match case-insensitively. Decimals disagreement, "
               "single-source entries, two accepted contracts for one symbol and chain, or one contract under two "
               "symbols go to `out/tokens.unverified.json`.")
    out.append("- USDC: accepted only from Circle's official contract page (`circle-usdc-contract-addresses`). "
               "Bridged USDC (for example Binance-Peg on BNB Smart Chain) is not accepted.")
    out.append("- Gap tokens: a row in `gap-tokens.json` `accepted` is added only when its official source is "
               "listed there. Decimals are declared from that source and must match on chain (verify_onchain.py).")
    out.append("- Existing rows: names, symbols, icons and nativeSymbol values in the shipped JSON are kept. "
               "feeModel changes are listed below.")
    out.append("- Each token's icon is `token_<lowercase symbol>` if RegistryIcons.kt has that key, otherwise null.\n")
    out.append("## Network matches\n")
    out.append("| Logo slug | Result |")
    out.append("| --- | --- |")
    for m in notes["matched"]:
        out.append(f"| {m} | matched |")
    for m in notes["manual"]:
        out.append(f"| {m} | manual |")
    for u in notes["unmatched"]:
        out.append(f"| {u} | NOT MATCHED |")
    for e in notes["excluded"]:
        out.append(f"| {e} | excluded |")
    out.append("")
    out.append("Aliases used: " + ", ".join(f"`{k}` -> \"{v}\"" for k, v in ALIASES.items()) + "\n")
    out.append("Notes on existing rows and fee models:\n")
    for n in notes["fee"]:
        out.append(f"- {n}")
    out.append("")
    out.append("Contradictions with existing networks:\n")
    for c in notes["contradictions"] or ["none"]:
        out.append(f"- {c}")
    out.append("")
    out.append("## feeModel evidence\n")
    out.append("| chainId | feeModel | status | source | note |")
    out.append("| --- | --- | --- | --- | --- |")
    for cid, e in sorted(feemodel.items(), key=lambda kv: int(kv[0])):
        out.append(f"| {cid} | {e['feeModel']} | {e['status']} | {e.get('source') or '-'} | {e['note']} |")
    out.append("")
    out.append("## Gap tokens (official source)\n")
    out.append("| chainId | symbol | contract | decimals | official source | quote |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for g in gaps["accepted"]:
        out.append(f"| {g['chainId']} | {g['symbol']} | `{g['contract']}` | {g['decimals']} | {g['source']} | {g['quote']} |")
    out.append("")
    out.append("Not added (no official source, or the source does not name the contract):\n")
    out.append("| chainId | symbol | contract | reason |")
    out.append("| --- | --- | --- | --- |")
    for g in gaps["not_added"]:
        out.append(f"| {g['chainId']} | {g['symbol']} | `{g['contract']}` | {g['reason']} |")
    out.append("")
    for n in gaps.get("notes", []):
        out.append(f"- {n}")
    out.append("")
    gap_lines = gap_notes
    if gap_lines:
        out.append("Gap build log:\n")
        for line in gap_lines:
            out.append(f"- {line}")
        out.append("")
    out.append("## Verified tokens\n")
    out.append("| chainId | symbol | contract | decimals | agreeing sources | on Circle page |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for r in verified:
        on_circle = ("yes" if r["contract"] in circle_addrs else "no") if r["symbol"] == "USDC" else "n/a"
        out.append(f"| {r['chainId']} | {r['symbol']} | `{r['contract']}` | {r['decimals']} | {', '.join(r['sources'])} | {on_circle} |")
    out.append("")
    out.append("## Unverified tokens (not in tokens.json)\n")
    out.append("| chainId | symbol | contract | decimals | sources | reason |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for r in unverified:
        out.append(f"| {r['chainId']} | {r['symbol']} | `{r['contract']}` | {r['decimals']} | {', '.join(r['sources'])} | {r['reason']} |")
    out.append("")
    out.append("## Existing token entries\n")
    for line in existing_report:
        out.append(f"- {line}")
    out.append("")
    out.append("## Requested symbols missing on major chains\n")
    if missing_major:
        for m in missing_major:
            out.append(f"- {m}")
    else:
        out.append("- none")
    out.append("")
    out.append("## Provider counts\n")
    for k, v in provider_counts.items():
        out.append(f"- {k}: {v} records")
    out.append(f"- CoinGecko platforms fetched: {len(cg_map)} (chainId -> platform: " +
               ", ".join(f"{c}:{p}" for c, p in sorted(cg_map.items())) + ")")
    (HERE / "sources.md").write_text("\n".join(out) + "\n", encoding="utf-8")


def main():
    if RAW.exists():
        shutil.rmtree(RAW)
    RAW.mkdir(parents=True)
    OUT.mkdir(parents=True, exist_ok=True)
    chains_path = fetch("https://chainid.network/chains.json", "chainid/chains.json")
    fetch("https://tokens.uniswap.org", "uniswap/tokens.json")
    fetch("https://static.optimism.io/optimism.tokenlist.json", "superchain/optimism.tokenlist.json")
    fetch("https://api.coingecko.com/api/v3/asset_platforms", "coingecko-platforms/asset_platforms.json")
    circle_path = fetch("https://developers.circle.com/stablecoins/usdc-contract-addresses",
                        "issuer/circle-usdc-contract-addresses.html")

    chains = load_json(chains_path)
    existing = load_json(EXISTING_NETWORKS)["networks"]
    existing_tokens = load_json(EXISTING_TOKENS)["tokens"]
    feemodel = without_comments(load_json(FEE_MODEL_FILE))
    manual = without_comments(load_json(MANUAL_NETWORKS_FILE))["networks"]
    gaps = without_comments(load_json(GAP_TOKENS_FILE))
    gaps_accepted = gaps["accepted"]
    chain_key_set, token_keys = registry_icon_keys()
    chain_keys = {k[len("chain_"):].replace("_", "-"): k for k in chain_key_set}

    networks, notes = build_networks(chains, existing, manual, chain_keys, feemodel)
    network_rows = sorted(networks.values(), key=lambda n: n["chainId"])
    net_ids = {n["chainId"] for n in network_rows}

    lookup = target_lookup()
    provider_tokens, cg_map = load_provider_tokens({c: None for c in net_ids})
    provider_counts = {k: len(v) for k, v in provider_tokens.items()}
    cand, names = collect_candidates(provider_tokens, net_ids, lookup)

    circle_text = Path(circle_path).read_text(encoding="utf-8", errors="replace")
    circle_addrs = {a.lower() for a in re.findall(r"0x[0-9a-fA-F]{40}", circle_text)}

    verified, unverified = evaluate(cand, names, circle_addrs, token_keys)
    gap_notes = merge_gap_tokens(verified, unverified, net_ids, gaps_accepted, token_keys)
    apply_existing_tokens(verified, existing_tokens)
    verified.sort(key=lambda r: (r["chainId"], r["symbol"].lower(), r["contract"]))
    unverified.sort(key=lambda r: (r["chainId"], r["symbol"].lower(), r["contract"]))
    existing_report = check_existing_tokens(existing_tokens, verified, unverified)

    have = {(r["chainId"], r["symbol"]) for r in verified}
    missing_major = []
    for sym in REQUESTED_SYMBOLS:
        for cid in MAJOR_CHAIN_IDS:
            if cid in net_ids and (cid, sym) not in have:
                missing_major.append(f"{sym} missing on chainId {cid}")

    errors = validate(network_rows, verified, chain_keys, token_keys)
    if errors:
        for e in errors:
            print("VALIDATION ERROR:", e, file=sys.stderr)
        sys.exit(1)

    write_rows(OUT / "networks.json", "networks", network_rows, NETWORK_FIELDS)
    write_rows(OUT / "tokens.json", "tokens", verified, TOKEN_FIELDS)
    write_rows(OUT / "tokens.unverified.json", "tokens", unverified, TOKEN_FIELDS + ["reason"])
    write_sources(notes, FETCHES, FETCH_FAILURES, verified, unverified, existing_report, missing_major,
                  circle_addrs, provider_counts, cg_map, gap_notes, feemodel, gaps)

    print(f"networks={len(network_rows)} verified={len(verified)} unverified={len(unverified)}")
    print("unmatched logos:", notes["unmatched"])
    print("missing requested symbols on major chains:", missing_major)
    print("validation: OK")


if __name__ == "__main__":
    main()
