#!/usr/bin/env python3
"""Check Lensly registry entries against live chains (stdlib only).

Run from anywhere:
    python3 -I tools/registry-data/verify_onchain.py --networks N.json --tokens T.json

For every network it asks up to three public HTTPS RPCs (taken from
chainid.network's chains.json) for eth_chainId. For every token it asks the
network's RPC for eth_getCode, decimals() (0x313ce567) and symbol() (0x95d89b41).

Rules:
  network  confirmed     at least one RPC answers with the expected chain ID, and none answers with another
           conflict      an RPC answers with a different chain ID. Not written to --out-networks.
           unconfirmed   no RPC answered. Kept in --out-networks only if chainlist lists it as a mainnet, and flagged.
  token    reject        eth_getCode is empty, decimals() is unreadable, or decimals() differs from the declared value
           unverified    no RPC answered. Not written to --out-tokens.
           review        symbol() differs from the declared symbol. Kept, flagged, never auto-rejected.
           rename        symbol() differs only by a fold (case, non-alphanumerics, U+20AE TUGRIK SIGN as T), so the
                         on-chain symbol is written to --out-tokens and noted.
           bridged       the chain labels the token as a bridged copy (Avalanche SYMBOL.e, Binance-Peg ETH on
                         chain 56). The on-chain symbol and a "bridged" name are written; see bridge_label().

Writes a Markdown report (default: verify-report.md next to this script). Downloaded chain data is
parsed as JSON only, never executed. Requests are paced per host, retried with backoff, and time out.
"""
import argparse
import json
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CHAINLIST_URL = "https://chainid.network/chains.json"
DEFAULT_CHAINS = HERE / "raw" / "chainid" / "chains.json"
DEFAULT_MANUAL = HERE / "manual-networks.json"
DEFAULT_REPORT = HERE / "verify-report.md"
USER_AGENT = "lensly-registry-verify/1.0"
DECIMALS_CALL = "0x313ce567"
SYMBOL_CALL = "0x95d89b41"
RETRY_HTTP = {408, 425, 429, 500, 502, 503, 504}
# (declared, on-chain) pairs that name the same asset after a bridge upgrade. USDT0 is Tether's omnichain USDT.
SAME_ASSET_RENAMES = {("usdt", "usdt0")}
TEST_NAME = re.compile(
    r"test|sepolia|goerli|holesky|kovan|rinkeby|ropsten|kotti|cardona|amoy|bepolia|artio|apothem|"
    r"alfajores|baklava|floripa|wannsee|hyperspace|fuji|devnet",
    re.I,
)

TIMEOUT_S = 8        # per call
MAX_ATTEMPTS = 3     # the first try plus two retries, per RPC
NETWORK_BUDGET_S = 60  # time spent asking RPCs about one network before the rest are skipped and reported
MIN_GAP_S = 0.25  # minimum pause between two requests to the same host
RPCS_PER_NETWORK = 3

_last_request = {}


class RpcAnswerError(Exception):
    """The node answered with a JSON-RPC error (for example execution reverted). Not retried."""


class RpcUnavailable(Exception):
    """No usable answer after the retries (transport error, timeout, HTTP error, rate limit)."""


def pace(host, gap):
    last = _last_request.get(host)
    if last is not None:
        wait = gap - (time.monotonic() - last)
        if wait > 0:
            time.sleep(wait)
    _last_request[host] = time.monotonic()


def rpc_call(url, method, params, gap=MIN_GAP_S):
    """One JSON-RPC call with pacing, timeout, and backoff. Returns the result field."""
    host = urllib.parse.urlsplit(url).netloc
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode("utf-8")
    last_problem = "no attempt made"
    for attempt in range(MAX_ATTEMPTS):
        pace(host, gap)
        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json",
                     "User-Agent": USER_AGENT},
        )
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as err:
            last_problem = f"HTTP {err.code}"
            if err.code not in RETRY_HTTP:
                raise RpcUnavailable(last_problem)
        except (urllib.error.URLError, OSError, ValueError) as err:
            last_problem = type(err).__name__
        else:
            error = payload.get("error")
            if error is None:
                return payload.get("result")
            message = str(error.get("message", ""))
            if error.get("code") == -32005 or re.search(r"rate|too many|limit", message, re.I):
                last_problem = f"rate limited: {message[:80]}"
            else:
                raise RpcAnswerError(f"{error.get('code')}: {message[:120]}")
        if attempt < MAX_ATTEMPTS - 1:
            time.sleep(min(30.0, 2.0 ** attempt) + random.uniform(0, 0.5))
    raise RpcUnavailable(last_problem)


def decode_uint8(raw):
    if len(raw) < 32:
        return None
    value = int.from_bytes(raw[:32], "big")
    return value if value <= 255 else None


def decode_symbol(raw):
    """ABI string, or bytes32 (MakerDAO-style tokens). Returns None when neither decodes."""
    if len(raw) == 32:
        return raw.rstrip(b"\x00").decode("utf-8", "replace")
    if len(raw) >= 64:
        offset = int.from_bytes(raw[:32], "big")
        if offset + 32 <= len(raw):
            length = int.from_bytes(raw[offset:offset + 32], "big")
            start = offset + 32
            if length <= 1024 and start + length <= len(raw):
                return raw[start:start + length].decode("utf-8", "replace").replace("\x00", "")
    return None


def to_bytes(hex_result):
    if not isinstance(hex_result, str) or not hex_result.startswith("0x"):
        return None
    try:
        return bytes.fromhex(hex_result[2:])
    except ValueError:
        return None


def fold(symbol):
    """Case, non-alphanumerics, and U+20AE (tugrik sign, used by USD₮) are ignored."""
    return re.sub(r"[^a-z0-9]", "", symbol.replace("\u20ae", "T").casefold())


def https_rpcs(chain):
    urls = []
    for url in chain.get("rpc") or []:
        if isinstance(url, str) and url.startswith("https://") and "${" not in url:
            urls.append(url)
    return urls[:RPCS_PER_NETWORK]


def mainnet_by_chainlist(chain):
    """True for a manual override marked mainnet, or a chainlist entry whose name and status look like a mainnet."""
    if chain is None:
        return False
    if chain.get("mainnet") is True:
        return True
    return not TEST_NAME.search(chain.get("name", "")) and chain.get("status") != "deprecated"


def load_manual(path):
    """Manual networks: their RPC list replaces the chainlist RPCs for that chainId (chainlist may reuse the ID)."""
    if not path.exists():
        return {}
    with open(path, encoding="utf-8") as fh:
        entries = json.load(fh).get("networks", [])
    return {e["chainId"]: {"name": e["name"], "rpc": e.get("rpc", []), "mainnet": bool(e.get("mainnet")),
                           "status": None} for e in entries}


def write_rows(path, key, rows):
    """One object per line, the same layout the shipped registry JSON uses."""
    lines = ["{", f'  "{key}": [']
    for i, row in enumerate(rows):
        comma = "," if i < len(rows) - 1 else ""
        lines.append("    " + json.dumps(row, ensure_ascii=False) + comma)
    lines += ["  ]", "}"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def load_chains(path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(CHAINLIST_URL, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as resp:
            path.write_bytes(resp.read())
        time.sleep(1.0)
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def verify_network(chain_id, chain, gap):
    """Return a dict with the status, the RPCs asked, and the RPCs that answered."""
    info = {"chainId": chain_id, "rpcs": [], "match": [], "mismatch": [], "errors": []}
    started = time.monotonic()
    for url in https_rpcs(chain or {}):
        if time.monotonic() - started > NETWORK_BUDGET_S:
            info["errors"].append(f"time budget of {NETWORK_BUDGET_S:.0f}s used up; remaining RPCs not asked")
            break
        info["rpcs"].append(url)
        try:
            got = int(rpc_call(url, "eth_chainId", [], gap), 16)
        except (RpcAnswerError, RpcUnavailable, ValueError, TypeError) as err:
            info["errors"].append(f"{url}: {err}")
            continue
        (info["match"] if got == chain_id else info["mismatch"]).append(f"{url} -> {got}")
    if info["mismatch"]:
        info["status"] = "conflict"
    elif info["match"]:
        info["status"] = "confirmed"
    else:
        info["status"] = "unconfirmed"
    return info


def answering_urls(info):
    """RPC URLs that gave the expected chain ID first, then the rest in chainlist order."""
    good = [m.split(" -> ")[0] for m in info["match"]]
    return good + [u for u in info["rpcs"] if u not in good]


def call_any(urls, method, params, gap):
    """Try each URL in turn. Returns (result, url).

    A JSON-RPC error from one node (for example execution reverted) moves on to the next node. If every node
    fails, the error is re-raised as RpcAnswerError when any node answered with an error (a contract revert
    is then a real answer), and as RpcUnavailable when no node answered at all.
    """
    last_answer = None
    last_unavailable = None
    for url in urls:
        try:
            return rpc_call(url, method, params, gap), url
        except RpcAnswerError as err:
            last_answer = err
        except RpcUnavailable as err:
            last_unavailable = err
    if last_answer is not None:
        raise RpcAnswerError(str(last_answer))
    raise RpcUnavailable(f"all RPCs failed: {last_unavailable}")


def verify_token(row, urls, gap):
    contract = row["contract"]
    out = {"chainId": row["chainId"], "contract": contract, "declared_symbol": row["symbol"],
           "declared_decimals": row["decimals"], "code": None, "onchain_decimals": None,
           "onchain_symbol": None, "status": None, "note": ""}
    if not urls:
        out["status"] = "unverified"
        out["note"] = "no RPC answered for this network"
        return out
    try:
        code, _ = call_any(urls, "eth_getCode", [contract, "latest"], gap)
    except (RpcUnavailable, RpcAnswerError) as err:
        out["status"] = "unverified"
        out["note"] = str(err)
        return out
    out["code"] = "present" if code not in (None, "0x", "") else "empty"
    if out["code"] == "empty":
        out["status"] = "reject"
        out["note"] = "no contract code at this address"
        return out
    try:
        dec_hex, _ = call_any(urls, "eth_call", [{"to": contract, "data": DECIMALS_CALL}, "latest"], gap)
        dec_raw = to_bytes(dec_hex)
        dec = decode_uint8(dec_raw) if dec_raw is not None else None
    except RpcAnswerError as err:
        dec = None
        out["note"] += f"decimals() reverted ({err}); "
    except RpcUnavailable as err:
        out["status"] = "unverified"
        out["note"] = f"decimals() unavailable: {err}"
        return out
    out["onchain_decimals"] = dec
    if dec is None:
        out["status"] = "reject"
        out["note"] += "decimals() unreadable"
        return out
    if dec != row["decimals"]:
        out["status"] = "reject"
        out["note"] += f"decimals mismatch: declared {row['decimals']}, on-chain {dec}"
        return out
    sym = None
    try:
        sym_hex, _ = call_any(urls, "eth_call", [{"to": contract, "data": SYMBOL_CALL}, "latest"], gap)
        sym_raw = to_bytes(sym_hex)
        sym = decode_symbol(sym_raw) if sym_raw is not None else None
    except RpcAnswerError as err:
        out["note"] += f"symbol() reverted ({err}); "
    except RpcUnavailable as err:
        out["note"] += f"symbol() unavailable ({err}); "
    out["onchain_symbol"] = sym
    if not sym:
        out["status"] = "review"
        out["note"] += "symbol() unreadable; declared symbol kept"
    elif sym.casefold() == row["symbol"].casefold():
        out["status"] = "ok"
    elif fold(sym) == fold(row["symbol"]) or (fold(row["symbol"]), fold(sym)) in SAME_ASSET_RENAMES:
        out["status"] = "rename"
        out["note"] += f"on-chain symbol '{sym}' is the same asset as declared '{row['symbol']}'; on-chain used"
    elif bridge_label(row, sym):
        out["status"] = "bridged"
        out["out_name"] = bridge_label(row, sym)
        out["note"] += (f"chain labels this token '{sym}': a bridged asset, shipped with the on-chain symbol "
                        f"and the name '{out['out_name']}'")
    else:
        out["status"] = "review"
        out["note"] += f"symbol mismatch: declared '{row['symbol']}', on-chain '{sym}'"
    return out


# Avalanche bridged tokens: the contract reports "<SYMBOL>.e" and the token is the bridged copy of the asset.
# Shipped with the on-chain symbol and a name that says it is bridged, so a signing review never shows a
# bridged token as the native asset.
AVALANCHE_BRIDGED_NAMES = {
    "1INCH": "1inch", "AAVE": "Aave", "COMP": "Compound", "GRT": "The Graph", "LINK": "Chainlink",
    "MKR": "Maker", "SNX": "Synthetix", "SUSHI": "SushiSwap", "WETH": "Wrapped Ether", "YFI": "yearn.finance",
}


def bridge_label(row, sym):
    """The name to ship for a token that the chain itself labels as bridged, or None.

    Rule 1: on Avalanche (43114), a declared SYMBOL whose contract reports SYMBOL.e is the Avalanche Bridge copy:
            name "<asset name> (Avalanche Bridge)".
    Rule 2: on BNB Smart Chain (56), the declared WETH whose contract reports ETH is Binance Pegged ETH.
    """
    if row["chainId"] == 43114 and row["symbol"] in AVALANCHE_BRIDGED_NAMES:
        if sym.casefold() == (row["symbol"] + ".e").casefold():
            return f"{AVALANCHE_BRIDGED_NAMES[row['symbol']]} (Avalanche Bridge)"
    if row["chainId"] == 56 and row["symbol"] == "WETH" and sym.casefold() == "eth":
        return "Binance Pegged ETH"
    return None


def md_cell(text):
    return str(text if text is not None else "").replace("|", "\\|").replace("\n", " ")


def write_report(path, net_results, tok_results, started, networks_total, tokens_total):
    counts = {}
    for item in tok_results:
        counts[item["status"]] = counts.get(item["status"], 0) + 1
    net_counts = {}
    for item in net_results:
        net_counts[item["status"]] = net_counts.get(item["status"], 0) + 1
    lines = [
        "# On-chain verification report", "",
        f"Generated {started} by `tools/registry-data/verify_onchain.py`.", "",
        f"Networks checked: {networks_total}. Tokens checked: {tokens_total}.", "",
        "Network status: " + ", ".join(f"{k} {v}" for k, v in sorted(net_counts.items())) + ".",
        "",
        "Token status: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) + ".",
        "",
        "## Networks", "",
        "| chainId | name | status | RPCs asked | answered the expected chainId | notes |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for n in net_results:
        notes = "; ".join(n["errors"][:2])
        if n.get("flag"):
            notes = n["flag"] + ("; " + notes if notes else "")
        lines.append(f"| {n['chainId']} | {md_cell(n['name'])} | {n['status']} | {len(n['rpcs'])} | "
                     f"{len(n['match'])} | {md_cell(notes)} |")
    lines += ["", "## Tokens", "",
              "| chainId | declared symbol | contract | declared dec. | on-chain dec. | on-chain symbol | code | status | note |",
              "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for t in tok_results:
        lines.append(
            f"| {t['chainId']} | {md_cell(t['declared_symbol'])} | `{t['contract']}` | {t['declared_decimals']} | "
            f"{t['onchain_decimals'] if t['onchain_decimals'] is not None else '-'} | "
            f"{md_cell(t['onchain_symbol']) if t['onchain_symbol'] is not None else '-'} | {t['code'] or '-'} | "
            f"{t['status']} | {md_cell(t['note'])} |")
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--networks", required=True, type=Path, help="networks JSON ({\"networks\": [...]})")
    ap.add_argument("--tokens", required=True, type=Path, help="tokens JSON ({\"tokens\": [...]})")
    ap.add_argument("--chains", type=Path, default=DEFAULT_CHAINS,
                    help=f"chainlist chains.json (fetched from {CHAINLIST_URL} when missing)")
    ap.add_argument("--manual", type=Path, default=DEFAULT_MANUAL,
                    help="manual-networks.json: RPCs for chains chainlist does not list as mainnets")
    ap.add_argument("--report", type=Path, default=DEFAULT_REPORT, help="Markdown report to write")
    ap.add_argument("--out-networks", type=Path, help="write accepted networks here")
    ap.add_argument("--out-tokens", type=Path, help="write accepted tokens here")
    ap.add_argument("--gap", type=float, default=MIN_GAP_S, help="minimum seconds between requests to one host")
    ap.add_argument("--only", help="check only these tokens, as chainId:contract,chainId:contract,...; the networks "
                                   "checked are just the chains they use")
    ap.add_argument("--merge-base", type=Path,
                    help="with --only: write the accepted checks into this tokens JSON, keeping every other row of "
                         "it as already verified. Rows of the checked keys are replaced or dropped; new rows are added")
    args = ap.parse_args(argv)
    if args.only and args.out_networks:
        ap.error("--only checks a subset of chains, so it cannot write --out-networks")
    if args.merge_base and not args.only:
        ap.error("--merge-base is used with --only")

    started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    chains = {c["chainId"]: c for c in load_chains(args.chains)}
    manual = load_manual(args.manual)
    chains.update(manual)  # a manual entry replaces the chainlist entry with the same chainId
    for cid, m in manual.items():
        m.setdefault("rpc", [])
        m["chainId"] = cid
    networks = json.loads(args.networks.read_text(encoding="utf-8"))["networks"]
    tokens = json.loads(args.tokens.read_text(encoding="utf-8"))["tokens"]
    if args.only:
        wanted = []
        for item in args.only.split(","):
            cid, _, contract = item.strip().partition(":")
            wanted.append((int(cid), contract.lower()))
        by_key = {(t["chainId"], t["contract"].lower()): t for t in tokens}
        missing = [w for w in wanted if w not in by_key]
        if missing:
            raise SystemExit(f"--only names tokens not in {args.tokens}: {missing}")
        tokens = [by_key[w] for w in wanted]
        used = {t["chainId"] for t in tokens}
        networks = [n for n in networks if n["chainId"] in used]

    net_results = []
    net_urls = {}
    for n in networks:
        chain = chains.get(n["chainId"])
        if chain is not None and n["chainId"] in manual:
            chain = dict(chain)
            chain["rpc"] = list(manual[n["chainId"]]["rpc"])
        info = verify_network(n["chainId"], chain, args.gap)
        info["name"] = n["name"]
        if info["status"] == "unconfirmed":
            info["flag"] = "DROPPED: no RPC answered the chain ID within the timeout and retries"
        info["accepted"] = info["status"] == "confirmed"
        net_results.append(info)
        net_urls[n["chainId"]] = answering_urls(info)
        print(f"network {n['chainId']:>10} {n['name'][:28]:<28} {info['status']}", file=sys.stderr)

    accepted_networks = {r["chainId"] for r in net_results if r["accepted"]}
    tok_results = []
    for t in tokens:
        if t["chainId"] not in accepted_networks:
            tok_results.append({"chainId": t["chainId"], "contract": t["contract"], "declared_symbol": t["symbol"],
                                "declared_decimals": t["decimals"], "code": None, "onchain_decimals": None,
                                "onchain_symbol": None, "status": "unverified",
                                "note": "network not accepted"})
            continue
        try:
            res = verify_token(t, net_urls.get(t["chainId"], []), args.gap)
        except Exception as err:  # one bad item must not stop a long run; it is reported as unverified
            res = {"chainId": t["chainId"], "contract": t["contract"], "declared_symbol": t["symbol"],
                   "declared_decimals": t["decimals"], "code": None, "onchain_decimals": None,
                   "onchain_symbol": None, "status": "unverified", "note": f"internal error: {err!r}"}
        tok_results.append(res)
        print(f"token {t['chainId']:>10} {t['symbol']:<10} {res['status']}", file=sys.stderr)

    write_report(args.report, net_results, tok_results, started, len(networks), len(tokens))
    if args.only:
        note = ("> Partial re-check (`--only`): only the listed changed entries were checked on chain in this run. "
                "Every other token keeps the result of the full run recorded in the git history of this file.\n\n")
        args.report.write_text(note + args.report.read_text(encoding="utf-8"), encoding="utf-8")

    if args.out_networks:
        keep = [dict(n) for n in networks if n["chainId"] in accepted_networks]
        write_rows(args.out_networks, "networks", keep)
    if args.out_tokens:
        by_key = {(r["chainId"], r["contract"]): r for r in tok_results}
        keep = []
        for t in tokens:
            r = by_key.get((t["chainId"], t["contract"]))
            if r is None or r["status"] not in ("ok", "rename", "review", "bridged"):
                continue
            row = dict(t)
            if r["status"] == "rename":
                row["symbol"] = r["onchain_symbol"]
            if r["status"] == "bridged":
                row["symbol"] = r["onchain_symbol"]
                row["name"] = r["out_name"]
            keep.append(row)
        if args.merge_base:
            base = json.loads(args.merge_base.read_text(encoding="utf-8"))["tokens"]
            checked = {(t["chainId"], t["contract"].lower()) for t in tokens}
            kept = [b for b in base if (b["chainId"], b["contract"].lower()) not in checked]
            keep = sorted(kept + keep, key=lambda r: r["chainId"])  # stable: base order kept within a chain
        write_rows(args.out_tokens, "tokens", keep)
    return 0


if __name__ == "__main__":
    sys.exit(main())
