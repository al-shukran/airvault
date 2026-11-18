#!/usr/bin/env python3
"""Prints the SPL token and Ed25519 rows of shared/src/test/resources/vectors/solana/vectors.txt.

Run it in a venv with solders 0.29.0, the version the existing rows were made with:

    python3 -I scripts/solana/gen_spl_vectors.py > rows.tsv

Each row is `name<TAB>hex`. A message row is the serialized message a signer signs, built by solders. The ed25519 rows are a 32-byte
seed, its public key, and the 64-byte signature of one message: Ed25519 is deterministic, so the signature is exact.

Keys made from a label are SHA-256(label), as in the rows already in the file. The USDC mint and the associated token account are
real: the ATA is derived from the owner, the mint and the token program, as the ATA program derives it. The derived addresses are
printed to stderr, so the tests can name them.
"""

import hashlib
import struct
import sys

from solders.hash import Hash
from solders.instruction import AccountMeta, Instruction
from solders.keypair import Keypair
from solders.message import Message
from solders.pubkey import Pubkey

SYSTEM = Pubkey.from_string("11111111111111111111111111111111")
TOKEN = Pubkey.from_string("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
TOKEN_2022 = Pubkey.from_string("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
ATA_PROGRAM = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
COMPUTE_BUDGET = Pubkey.from_string("ComputeBudget111111111111111111111111111111")
USDC = Pubkey.from_string("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")


def label_key(label: str) -> Pubkey:
    return Pubkey(hashlib.sha256(label.encode()).digest())


BLOCKHASH = Hash(hashlib.sha256(b"lensly wi5 blockhash").digest())
PAYER = label_key("payer")
OWNER = label_key("owner")
SOURCE = label_key("source-token-account")
DESTINATION = label_key("destination-2")
SOURCE_2 = label_key("source-2")
MINT_2022 = label_key("mint-token-2022")
ATA_2022_DESTINATION = label_key("ata-2022")
UNKNOWN_PROGRAM = label_key("unknown-program")
ATA = Pubkey.find_program_address([bytes(OWNER), bytes(TOKEN), bytes(USDC)], ATA_PROGRAM)[0]


def set_compute_unit_limit(units: int) -> Instruction:
    return Instruction(COMPUTE_BUDGET, bytes([2]) + struct.pack("<I", units), [])


def set_compute_unit_price(micro_lamports: int) -> Instruction:
    return Instruction(COMPUTE_BUDGET, bytes([3]) + struct.pack("<Q", micro_lamports), [])


def ata_create_idempotent(payer: Pubkey, account: Pubkey, owner: Pubkey, mint: Pubkey) -> Instruction:
    return Instruction(
        ATA_PROGRAM,
        bytes([1]),
        [
            AccountMeta(payer, True, True),
            AccountMeta(account, False, True),
            AccountMeta(owner, False, False),
            AccountMeta(mint, False, False),
            AccountMeta(SYSTEM, False, False),
            AccountMeta(TOKEN, False, False),
        ],
    )


def transfer(source: Pubkey, destination: Pubkey, authority: Pubkey, amount: int, program: Pubkey = TOKEN) -> Instruction:
    return Instruction(
        program,
        bytes([3]) + struct.pack("<Q", amount),
        [
            AccountMeta(source, False, True),
            AccountMeta(destination, False, True),
            AccountMeta(authority, True, False),
        ],
    )


def transfer_checked(
    source: Pubkey,
    mint: Pubkey,
    destination: Pubkey,
    authority: Pubkey,
    amount: int,
    decimals: int,
    program: Pubkey = TOKEN,
) -> Instruction:
    return Instruction(
        program,
        bytes([12]) + struct.pack("<QB", amount, decimals),
        [
            AccountMeta(source, False, True),
            AccountMeta(mint, False, False),
            AccountMeta(destination, False, True),
            AccountMeta(authority, True, False),
        ],
    )


def message(instructions: list, blockhash: Hash = BLOCKHASH) -> Message:
    built = Message.new_with_blockhash(instructions, PAYER, blockhash)
    # The serialized bytes must read back as the same message, or the row would not be a message.
    assert Message.from_bytes(bytes(built)) == built
    return built


# A Lumio send from a nonce account (SolanaNonce.transferMessage): AdvanceNonceAccount first, then the token transfer.
SYSVAR_RECENT_BLOCKHASHES = Pubkey.from_string("SysvarRecentB1ockHashes11111111111111111111")
SEND_NONCE_VALUE = Hash(hashlib.sha256(b"lensly spl send nonce value").digest())
RECIPIENT = label_key("recipient")
NONCE_ACCOUNT = Pubkey.create_with_seed(PAYER, "lumio-nonce-0", SYSTEM)
SENDER_ATA = Pubkey.find_program_address([bytes(PAYER), bytes(TOKEN), bytes(USDC)], ATA_PROGRAM)[0]
RECIPIENT_ATA = Pubkey.find_program_address([bytes(RECIPIENT), bytes(TOKEN), bytes(USDC)], ATA_PROGRAM)[0]


def advance_nonce(nonce: Pubkey, authority: Pubkey) -> Instruction:
    return Instruction(
        SYSTEM,
        struct.pack("<I", 4),
        [
            AccountMeta(nonce, False, True),
            AccountMeta(SYSVAR_RECENT_BLOCKHASHES, False, False),
            AccountMeta(authority, True, False),
        ],
    )


def main() -> None:
    compute_transfer = message(
        [
            set_compute_unit_limit(300_000),
            set_compute_unit_price(25_000),
            transfer_checked(SOURCE, USDC, DESTINATION, PAYER, 2_000_000, 6),
        ],
    )
    rows = [
        ("spl-transfer-plain", message([transfer(SOURCE_2, DESTINATION, PAYER, 7)])),
        (
            "spl-usdc-first-transfer",
            message(
                [
                    set_compute_unit_limit(200_000),
                    set_compute_unit_price(1_000),
                    ata_create_idempotent(PAYER, ATA, OWNER, USDC),
                    transfer_checked(SOURCE, USDC, ATA, PAYER, 1_234_567, 6),
                ],
            ),
        ),
        ("spl-compute-transfer-checked", compute_transfer),
        (
            "spl-token2022-transfer-checked",
            message([transfer_checked(SOURCE, MINT_2022, ATA_2022_DESTINATION, PAYER, 42, 9, program=TOKEN_2022)]),
        ),
        (
            "spl-unknown-program-usdc",
            message(
                [
                    transfer_checked(SOURCE, USDC, ATA, PAYER, 1_234_567, 6),
                    Instruction(UNKNOWN_PROGRAM, bytes.fromhex("deadbeef"), [AccountMeta(PAYER, True, True)]),
                ],
            ),
        ),
    ]
    # Appended after the rows above, so the rows already in vectors.txt print as before.
    advance = advance_nonce(NONCE_ACCOUNT, PAYER)
    send_transfer = transfer_checked(SENDER_ATA, USDC, RECIPIENT_ATA, PAYER, 1_234_567, 6)
    create_recipient = ata_create_idempotent(PAYER, RECIPIENT_ATA, RECIPIENT, USDC)
    rows += [
        ("spl-send-nonce-create-ata", message([advance, create_recipient, send_transfer], SEND_NONCE_VALUE)),
        ("spl-send-nonce-existing-ata", message([advance, send_transfer], SEND_NONCE_VALUE)),
    ]
    for name, built in rows:
        data = bytes(built)
        if len(data) > 1232:
            raise SystemExit(f"{name}: {len(data)} bytes is over the 1232-byte limit")
        print(f"{name}\t{data.hex()}")

    seed = hashlib.sha256(b"lensly spl test key").digest()
    key = Keypair.from_seed(seed)
    signature = bytes(key.sign_message(bytes(compute_transfer)))
    print(f"ed25519-seed\t{seed.hex()}")
    print(f"ed25519-pubkey\t{bytes(key.pubkey()).hex()}")
    print(f"ed25519-signature-spl-compute-transfer-checked\t{signature.hex()}")

    print(f"usdc owner ATA (base58): {ATA}", file=sys.stderr)
    print(f"mint-token-2022 (base58): {MINT_2022}", file=sys.stderr)
    print(f"send nonce account (base58): {NONCE_ACCOUNT}", file=sys.stderr)
    print(f"send sender ATA (base58): {SENDER_ATA}", file=sys.stderr)
    print(f"send recipient ATA (base58): {RECIPIENT_ATA}", file=sys.stderr)


if __name__ == "__main__":
    main()
