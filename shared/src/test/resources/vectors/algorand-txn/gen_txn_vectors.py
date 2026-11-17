"""Generates vectors.json for the Algorand transaction decoder tests (WI-10).

Reproduce from the worktree root (PYTHON3 is any Python 3 with venv):

    python3 -m venv "$VENV"
    "$VENV/bin/python" -m pip install py-algorand-sdk==2.12.0 msgpack==1.2.4
    "$VENV/bin/python" -I shared/src/test/resources/vectors/algorand-txn/gen_txn_vectors.py \
        > shared/src/test/resources/vectors/algorand-txn/vectors.json

Positive entries are encoded by py-algorand-sdk. Negative entries are py-algorand-sdk output
re-packed with msgpack.packb(use_bin_type=True), the packer algosdk uses, so each one breaks
exactly one canonical rule. Every positive entry is decoded again with the msgpack library and
checked against its expected fields before the file is written.
"""
import base64
import json
import sys
from collections import OrderedDict
from importlib.metadata import version

import msgpack
from algosdk import encoding, transaction

GEN = "testnet-v1.0"
# Testnet block 0 "gen" and "gh", read from https://testnet-api.algonode.cloud/v2/blocks/0.
GH_B64 = "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI="
FIRST, LAST, FEE = 50_000_000, 50_001_000, 1000
SP = transaction.SuggestedParams(fee=FEE, first=FIRST, last=LAST, gh=GH_B64, gen=GEN, flat_fee=True)
GH = base64.b64decode(GH_B64)
# Mainnet block 0 "gen" and "gh", read from https://mainnet-api.algonode.cloud/v2/transactions/params.
GEN_MAIN = "mainnet-v1.0"
GH_MAIN_B64 = "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8="
SP_MAIN = transaction.SuggestedParams(fee=FEE, first=FIRST, last=LAST, gh=GH_MAIN_B64, gen=GEN_MAIN, flat_fee=True)
GH_MAIN = base64.b64decode(GH_MAIN_B64)

# Arbitrary 32-byte public keys, used only as account addresses.
PK_A = "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538"
PK_B = "c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d"
PK_C = "8d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006d"
A = encoding.encode_address(bytes.fromhex(PK_A))
B = encoding.encode_address(bytes.fromhex(PK_B))
C = encoding.encode_address(bytes.fromhex(PK_C))
ASSET = 10458941  # USDC on testnet
ASSET_MAIN_USDC = 31566704  # USDC on mainnet
LEASE = bytes(range(0x40, 0x60))
GROUP = bytes(range(0x60, 0x80))


def addr_hex(addr):
    return encoding.decode_address(addr).hex()


def opt_hex(addr):
    return addr_hex(addr) if addr else None


def canonical(txn):
    return base64.b64decode(encoding.msgpack_encode(txn))


def pay_expect(receiver, amount, *, close=None, note=b"", rekey=None):
    return {
        "type": "pay", "sender": addr_hex(A), "fee": FEE, "firstValid": FIRST, "lastValid": LAST,
        "genesisId": GEN, "genesisHash": GH.hex(), "note": note.hex(),
        "lease": None, "group": None, "rekeyTo": opt_hex(rekey),
        "receiver": addr_hex(receiver), "amount": amount, "closeTo": opt_hex(close),
    }


def axfer_expect(receiver, amount, *, close=None, note=b"", lease=None, group=None, rekey=None,
                 asset_sender=None, asset=ASSET, gen=GEN, gh=GH):
    return {
        "type": "axfer", "sender": addr_hex(A), "fee": FEE, "firstValid": FIRST, "lastValid": LAST,
        "genesisId": gen, "genesisHash": gh.hex(), "note": note.hex(),
        "lease": lease.hex() if lease else None, "group": group.hex() if group else None,
        "rekeyTo": opt_hex(rekey), "receiver": addr_hex(receiver), "amount": amount,
        "closeTo": opt_hex(close), "assetId": asset, "assetSender": opt_hex(asset_sender),
    }


def decoded_fields(raw):
    """Reads a canonical transaction back with the msgpack library, in the expected-field schema."""
    d = msgpack.unpackb(raw, raw=False)
    out = {
        "type": d["type"], "sender": d["snd"].hex(), "fee": d.get("fee", 0),
        "firstValid": d["fv"], "lastValid": d["lv"], "genesisId": d.get("gen", ""),
        "genesisHash": d["gh"].hex(), "note": d.get("note", b"").hex(),
        "lease": d["lx"].hex() if "lx" in d else None,
        "group": d["grp"].hex() if "grp" in d else None,
        "rekeyTo": d["rekey"].hex() if "rekey" in d else None,
    }
    if d["type"] == "pay":
        out["receiver"] = d["rcv"].hex()
        out["amount"] = d.get("amt", 0)
        out["closeTo"] = d["close"].hex() if "close" in d else None
    else:
        out["receiver"] = d["arcv"].hex()
        out["amount"] = d.get("aamt", 0)
        out["closeTo"] = d["aclose"].hex() if "aclose" in d else None
        out["assetId"] = d["xaid"]
        out["assetSender"] = d["asnd"].hex() if "asnd" in d else None
    return out


def repack_sorted(d):
    return msgpack.packb(OrderedDict(sorted(d.items())), use_bin_type=True)


def main():
    pay_basic = transaction.PaymentTxn(A, SP, B, 1_500_000)
    pay_note_close = transaction.PaymentTxn(A, SP, B, 2_000_000_000, close_remainder_to=C,
                                            note=b"rent for March")
    axfer = transaction.AssetTransferTxn(A, SP, B, 250, ASSET)
    axfer_optin = transaction.AssetTransferTxn(A, SP, A, 0, ASSET)
    pay_rekey = transaction.PaymentTxn(A, SP, A, 0, rekey_to=C)
    axfer_full = transaction.AssetTransferTxn(A, SP, B, 1000, ASSET, close_assets_to=C,
                                              revocation_target=B, note=b"x", lease=LEASE, rekey_to=C)
    axfer_full.group = GROUP
    appl = transaction.ApplicationNoOpTxn(A, SP, 123456)
    # Opt-out: the SDK's close-out has amount 0 and its close-to is the receiver.
    axfer_closeout = transaction.AssetCloseOutTxn(A, SP, B, ASSET)
    # Opt-out whose remaining holding goes to an address other than the receiver.
    axfer_optout_to_other = transaction.AssetTransferTxn(A, SP, B, 0, ASSET, close_assets_to=C)
    # A transfer that also closes the holding: 250 units to B, the rest to C.
    axfer_close_with_amount = transaction.AssetTransferTxn(A, SP, B, 250, ASSET, close_assets_to=C)
    # Clawback: the assets are taken from C (asnd), which A is the clawback account for.
    axfer_clawback = transaction.AssetTransferTxn(A, SP, B, 250, ASSET, revocation_target=C)
    axfer_rekey = transaction.AssetTransferTxn(A, SP, B, 250, ASSET, rekey_to=C)
    axfer_mainnet_usdc = transaction.AssetTransferTxn(A, SP_MAIN, B, 1_000_000, ASSET_MAIN_USDC)
    axfer_optin_mainnet_usdc = transaction.AssetTransferTxn(A, SP_MAIN, A, 0, ASSET_MAIN_USDC)

    positive_specs = [
        ("pay_basic", pay_basic, pay_expect(B, 1_500_000), "payment, no note, no close-to"),
        ("pay_note_close", pay_note_close,
         pay_expect(B, 2_000_000_000, close=C, note=b"rent for March"),
         "payment with a note and a close-to address"),
        ("axfer", axfer, axfer_expect(B, 250), "asset transfer of 250 units"),
        ("axfer_optin", axfer_optin, axfer_expect(A, 0),
         "asset opt-in: receiver is the sender and the amount 0 is omitted"),
        ("pay_rekey", pay_rekey, pay_expect(A, 0, rekey=C),
         "rekey payment to the sender itself; the amount 0 is omitted"),
        ("axfer_full_16_keys", axfer_full,
         axfer_expect(B, 1000, close=C, note=b"x", lease=LEASE, group=GROUP, rekey=C, asset_sender=B),
         "asset transfer with every field set: 16 keys, so the map header is map16"),
        ("axfer_closeout", axfer_closeout, axfer_expect(B, 0, close=B),
         "opt-out by close-out: amount 0 is omitted and the close-to is the receiver"),
        ("axfer_optout_to_other", axfer_optout_to_other, axfer_expect(B, 0, close=C),
         "opt-out whose remaining holding goes to an address other than the receiver"),
        ("axfer_close_with_amount", axfer_close_with_amount, axfer_expect(B, 250, close=C),
         "asset transfer of 250 units that also closes the holding to another address"),
        ("axfer_clawback", axfer_clawback, axfer_expect(B, 250, asset_sender=C),
         "clawback: 250 units taken from the asnd account C"),
        ("axfer_rekey", axfer_rekey, axfer_expect(B, 250, rekey=C),
         "asset transfer that also rekeys the sender"),
        ("axfer_mainnet_usdc", axfer_mainnet_usdc,
         axfer_expect(B, 1_000_000, asset=ASSET_MAIN_USDC, gen=GEN_MAIN, gh=GH_MAIN),
         "mainnet USDC transfer of 1000000 base units (1 USDC)"),
        ("axfer_optin_mainnet_usdc", axfer_optin_mainnet_usdc,
         axfer_expect(A, 0, asset=ASSET_MAIN_USDC, gen=GEN_MAIN, gh=GH_MAIN),
         "mainnet USDC opt-in: the receiver is the sender and the amount 0 is omitted"),
    ]

    positive = []
    for name, txn, expected, description in positive_specs:
        raw = canonical(txn)
        got = decoded_fields(raw)
        if got != expected:
            for key in sorted(set(got) | set(expected)):
                if got.get(key) != expected.get(key):
                    print(f"MISMATCH {name} {key}: decoded={got.get(key)!r} "
                          f"expected={expected.get(key)!r}", file=sys.stderr)
            raise SystemExit(f"{name}: encoding does not decode to the expected fields")
        bts = txn.bytes_to_sign()
        if bts != b"TX" + raw:
            raise SystemExit(f"{name}: bytes_to_sign() is not 'TX' + the canonical bytes")
        positive.append({"name": name, "description": description, "hex": raw.hex(),
                         "bytesToSign": bts.hex(), "expected": expected})

    full = canonical(axfer_full)
    if full[0] != 0xDE or len(msgpack.unpackb(full, raw=False)) != 16:
        raise SystemExit("axfer_full_16_keys must use a map16 header with 16 keys")

    # Negative entries: one canonical rule broken each.
    base_pay = msgpack.unpackb(canonical(pay_basic), raw=False)

    items = sorted(base_pay.items())
    keys = [k for k, _ in items]
    i = keys.index("fee")
    items[i], items[i + 1] = items[i + 1], items[i]
    unsorted_raw = msgpack.packb(OrderedDict(items), use_bin_type=True)
    if [k for k in msgpack.unpackb(unsorted_raw, raw=False)] == sorted(keys):
        raise SystemExit("unsorted fixture came out sorted")

    zero_amount = dict(msgpack.unpackb(canonical(pay_rekey), raw=False))
    if "amt" in zero_amount:
        raise SystemExit("pay_rekey should omit amt")
    zero_amount["amt"] = 0

    zero_address = dict(base_pay)
    zero_address["close"] = bytes(32)

    empty_note = dict(base_pay)
    empty_note["note"] = b""

    appl_raw = canonical(appl)
    if msgpack.unpackb(appl_raw, raw=False)["type"] != "appl":
        raise SystemExit("appl fixture is not an application call")

    negative = [
        {"name": "unsorted_keys", "description": "fee and fv swapped: keys not in lexicographic order",
         "hex": unsorted_raw.hex()},
        {"name": "zero_amount_present", "description": "amt written as 0; zero values must be omitted",
         "hex": repack_sorted(zero_amount).hex()},
        {"name": "zero_address_present",
         "description": "close set to 32 zero bytes; zero values must be omitted",
         "hex": repack_sorted(zero_address).hex()},
        {"name": "empty_note_present", "description": "note set to an empty bin; empty values must be omitted",
         "hex": repack_sorted(empty_note).hex()},
        {"name": "appl_noop", "description": "application call (type appl), not a payment or asset transfer",
         "hex": appl_raw.hex()},
    ]

    out = {
        "sdk": f"py-algorand-sdk {version('py-algorand-sdk')}",
        "msgpack": f"msgpack {version('msgpack')}",
        "gen": GEN,
        "gh": GH_B64,
        "positive": positive,
        "negative": negative,
    }
    json.dump(out, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
