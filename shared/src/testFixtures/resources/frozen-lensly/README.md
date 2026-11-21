# Frozen Lensly fixtures

NEVER re-record these files. A frozen Lensly depends on these bytes: it is never updated, so every request Lumio sends it and every answer
Lumio reads must keep matching them. A test that fails on one of these files means a change broke a frozen Lensly. Fix the code, not the file.

Each file holds UR frames, one per line, in the form the wire carries them. Animated codes have one line per part.

- `pairing-v7-raw.txt` and `pairing-v7-brotli.txt` are the pairing of the realistic test vault, version 7, with its body stored raw and
  Brotli-compressed.
- `request-*.txt` are one sign request for each request type a Lumio builder writes: Bitcoin `psbt` and `crypto-psbt`, Ethereum transaction
  and typed data, Solana, and Algorand.
- `response-*.txt` are the signed answers to the Bitcoin, Ethereum, Solana and Algorand requests. Each answer uses the name of its request.

Every file was written once from the public BIP39 test wallet, by a one-off generator that was then removed. There is no regeneration helper,
on purpose. The contract is described in "Frozen Lensly contract" in docs/creating-airvault-apps.md.
