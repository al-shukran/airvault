# Creating AirVault chain apps

Status: draft specification. Target of the Phase 2.9 refactor.
Scope: how a blockchain is added to AirVault as an isolated "chain app".

AirVault has two phone apps and one shared code base:

- **Lensly** (`app/`) is the offline vault and signer. It holds the seed, parses requests, shows the review, and signs.
- **Lumio** (`lumio/`) is the online companion wallet. It tracks accounts and balances, builds transactions, and broadcasts them.
- **`shared/`** holds code both apps use: crypto, UR/QR transport, the parser sandbox, the address book.

The two apps talk only through QR codes. Lumio never sees a private key. Lensly never touches a network.

This document says what a chain app is, what it must provide, and what the core does for it. All four current chains (Bitcoin, EVM, Solana, Algorand) must be moved onto this contract in Phase 2.9.

Claims about external standards cite the primary source inline. Items marked **[UNVERIFIED]** were not confirmed against a primary source during research. Do not treat them as fact until someone checks them.

---

## 1. Overview and principles

### 1.1 What a chain app is

A chain app is a set of Gradle modules for one chain family. It plugs into the core through a small set of Kotlin interfaces. The core never names a chain.

```
chains/
  litecoin/
    common/   # manifest, address codec, asset ids, wire types (pure Kotlin, no Android, no network)
    signer/   # Lensly side: parse, review, sign       (depends on common + :core:signer-api)
    wallet/   # Lumio side: balances, build, broadcast (depends on common + :core:wallet-api)
```

A family may host several networks. The `evm` app serves Ethereum mainnet, Monad testnet (`eip155:10143`), Monad mainnet (`eip155:143`), Sepolia, and any other EIP-155 chain.

### 1.2 Principles

1. **The core is chain-blind.** No `when (chain)`, no chain enum, no chain name in core code. Core code looks chains up by id in a registry.
2. **The app owns chain meaning.** Parsing, address encoding, asset rules, review content, and signing are app code.
3. **The core owns safety.** Key derivation, key scope checks, QR transport, the parser sandbox, review rendering, the user's approval, and the audit record are core code. An app cannot skip them.
4. **The app returns data, never UI.** Lensly draws every review screen from a typed model. An app cannot draw its own screen. This keeps the look uniform and stops an app from hiding a field.
5. **Default deny.** An app gets no key paths, no curves, and no capabilities unless its manifest declares them and the core accepts them.
6. **One way to do each thing.** One id scheme (CAIP), one asset model, one field taxonomy, one review model. No per-chain side doors.
7. **Small surface.** If a chain does not need an interface, it does not implement it. Optional parts have defaults.

These follow the patterns used by Ledger device apps (per-app path and curve allowlists, enforced by the OS) and by MetaMask Snaps (the host hands out keys for a declared coin type; the Snap does the rest). Sources: <https://developers.ledger.com/docs/device-app/references/app-permissions>, <https://docs.metamask.io/snaps/reference/snaps-api/snap_getbip44entropy/>.

### 1.3 Core vs chain app

| Concern | Core (`shared/`, `app/`, `lumio/`) | Chain app (`chains/<id>/`) |
|---|---|---|
| Seed, vault, Argon2/AEAD storage | owns | never sees |
| BIP-32 / SLIP-10 / BIP32-Ed25519 derivation | owns | asks for keys by path |
| Path and curve scope check | enforces | declares in manifest |
| Signing primitives (ECDSA, Schnorr, Ed25519) | provides | calls with a scoped key handle |
| Transaction and message encoding | none | owns |
| Request parsing | runs it in the parser sandbox | implements the parser |
| Review screen | renders, enforces coverage and warnings | returns a `Review` model |
| User approval, danger acknowledgement | owns | none |
| Review record (audit log) | stores | supplies record facts |
| QR / UR framing, animated QR, fountain codes | owns | declares UR types and CBOR payloads |
| Address book | owns storage and UI | validates and normalises addresses |
| Address validation | calls the app | implements `AddressCodec` |
| Asset metadata catalogues (bundled, signed) | loads and verifies signature | supplies entries and lookup rules |
| Network access (Lumio only) | owns HTTP, provider routing, health, rate limits | declares provider ops and parses responses |
| Account list, balances UI, send UI | renders | supplies balances, assets, form fields |
| Transaction building and fees | none | owns |
| Broadcast | runs the request through the router | encodes the broadcast call |
| Cover screen, FLAG_SECURE | owns | must not affect |

---

## 2. App manifest and registration

### 2.1 Manifest

Every chain app has one manifest, declared once in `common/` and shared by the signer and wallet sides.

```kotlin
data class ChainManifest(
    val id: AppId,                         // "litecoin"; [a-z0-9-], unique, stable forever
    val displayName: String,               // "Litecoin" (shown inside the vault only, never on the cover)
    val apiVersion: Int,                   // version of the AirVault chain-app contract this app targets
    val appVersion: String,                // the app's own semver
    val namespaces: Set<String>,           // CAIP-2 namespaces this app serves, e.g. "bip122", "eip155"
    val networks: List<NetworkSpec>,       // concrete chains (CAIP-2 ids) with display data
    val keys: List<KeyScope>,              // allowed derivation, see 2.2
    val capabilities: Set<Capability>,     // default empty, see 2.3
    val requestTypes: List<UrTypeSpec>,    // QR request types this app parses (Lensly side)
    val responseTypes: List<UrTypeSpec>,   // QR response types this app emits (Lensly side)
    val fields: List<FieldSpec>,           // transaction extras, see section 6
)

data class NetworkSpec(
    val chainId: ChainId,                  // CAIP-2, e.g. ChainId("eip155", "10143")
    val name: String,                      // "Monad testnet"
    val testnet: Boolean,
    val nativeAsset: AssetSpec,            // symbol, decimals
    val slip44: Int,                       // coin type used for this network's keys
    val primary: Boolean = false,          // an account's native coin is read here; its side lists it first (EVM: Ethereum, Monad testnet)
    val enabledByDefault: Boolean = false, // a new vault reads and shows it at once (Lumio)
)

data class KeyScope(
    val curve: Curve,                      // SECP256K1, ED25519_SLIP10, ED25519_BIP32 (Ledger-style), ...
    val pathPrefixes: List<PathTemplate>,  // e.g. "m/84'/2'/*'", "m/44'/2'/*'"
    val purpose: String,                   // short reason, shown in code review
)

enum class Curve { SECP256K1, ED25519_SLIP10, ED25519_BIP32 }
```

`ChainId`, `AccountId` and `AssetId` are value classes over the CAIP strings (section 4).

### 2.2 Key scope rules (enforced by the core)

The core validates every `KeyScope` when it loads an app. Loading fails if any rule fails. The rules mirror Ledger's `PATH_APP_LOAD_PARAMS` and `CURVE_APP_LOAD_PARAMS` checks (<https://developers.ledger.com/docs/device-app/references/app-permissions>).

1. Every path prefix has at least one hardened component.
2. The coin-type level (index 1 for BIP-44/49/84/86 purposes) is hardened and equals one of the manifest's `slip44` values. An app cannot derive inside another coin's subtree. SLIP-44 registry: <https://github.com/satoshilabs/slips/blob/master/slip-0044.md>.
3. A wildcard is allowed only at the account level and below. `*'` means "any hardened index", `*` means "any non-hardened index". A bare `m/*` is never allowed.
4. `ED25519_SLIP10` paths are hardened at every level. SLIP-10 Ed25519 has no non-hardened derivation.
5. Two installed apps may not declare overlapping prefixes on the same curve, unless one is the declared owner and the other lists it in `sharesKeysWith` (for example, an EVM vendor app for one L2 that reuses `m/44'/60'`). Overlap without that link fails the build.
6. Every signing call is checked against the scope at run time, not only at load time. A path outside the scope throws `KeyScopeViolation`, and the core logs it in the review record.

Why rule 6 matters: Ledger Security Bulletin 014 described Bitcoin-derivative apps (for example Litecoin) that could derive Bitcoin keys because path limits were not enforced at signing time. Source (LSB 014, published 2020-08-04): <https://donjon.ledger.com/lsb/014/>. The Bitcoin app's fix in 1.4.6 is an in-app warning only.

### 2.3 Capabilities

Capabilities are off by default. Each one needs a written reason in the manifest, reviewed like code.

```kotlin
enum class Capability {
    RAW_PRIVATE_KEY,        // app receives private key bytes in a zeroing scope (see 3.4). Needs justification.
    MESSAGE_SIGNING,        // off-chain message signing (EIP-191, Solana off-chain message, ...)
    TYPED_DATA_SIGNING,     // structured data signing (EIP-712)
    BLIND_SIGNING,          // app may offer a "cannot decode" review; the owner's default policy is WARN (8.4)
    WATCH_ONLY_EXPORT,      // app exports extended public keys (xpub-style) at pairing
    MULTI_SCRIPT_ACCOUNTS,  // one account, several script types (Bitcoin family)
}
```

### 2.4 Registration

There is one list of installed signer apps per phone app, and Lumio's build lists the wallet apps it is compiled with. Adding a chain changes only those files.

```kotlin
// app/src/main/java/app/lensly/chains/InstalledSigners.kt
val installedSigners: List<SignerApp> = listOf(
    BitcoinSigner, EvmSigner, SolanaSigner, AlgorandSigner, LitecoinSigner,
)

// lumio/build.gradle.kts: one implementation(project(":chains:<chain>:wallet")) line per chain app
```

At start-up the core builds a `ChainRegistry` from that list:

```kotlin
class ChainRegistry(apps: List<ChainApp>) {
    fun byChain(id: ChainId): ChainApp            // CAIP-2 lookup
    fun byNamespace(ns: String): List<ChainApp>
    fun byRequestType(urType: String): SignerApp  // QR dispatch
    fun networks(): List<NetworkSpec>             // drives pickers, icons, address book chain list
}
```

The registry rejects, at start-up and in a unit test that runs in CI:

- duplicate `AppId`, CAIP-2 chain, or UR type;
- an `apiVersion` the core does not support (the core does not guess);
- a key scope that breaks 2.2;
- a field spec that breaks section 6.

There is no runtime plugin loading. Lensly must not load code from storage or the network (no `DexClassLoader`, no reflection-based discovery). A wallet vendor ships a chain app as source modules, it goes through review, and it is compiled into the build. Vendor apps carry a signed manifest and a human review record (section 2.5).

### 2.5 Wallet vendors

A vendor builds a chain app the same way the core team does:

1. Fork the template at `chains/_template/` (see its README).
2. Fill in the manifest and the interfaces in section 3.
3. Pass the conformance kit (section 9).
4. Open a PR that adds the modules and one line to each `Installed*.kt`.
5. Sign the manifest with the vendor's key. A human reviewer checks the key scope and capabilities and records the review before the app is added to a build. Unsigned or unreviewed vendor apps are never shipped.

A vendor may ship a signer without a wallet side (for example, when the vendor runs its own online wallet that speaks AirVault's QR types). The reverse is not allowed: a wallet side without a signer has nothing to sign with.

### 2.6 Per-Wallet Install

Chain apps are installed per wallet, using the Install and Uninstall actions on the Apps screen of a Mnemonic wallet. The term follows the Ledger model. The rules below apply to Mnemonic wallets, and the last bullet describes a Single Key wallet.

- A chain app is installed in a wallet when the wallet holds at least one account of that app. No record stores the set, so vaults from earlier versions open with their existing apps installed.
- A new, restored or decoy Mnemonic wallet holds one Bitcoin account. The user installs the other apps they want.
- Installing an app creates its account 0. Uninstalling removes all of its accounts. Keys come from the seed, so reinstalling restores the same addresses. Uninstalling keeps the names, labels and groups of the accounts, and reinstalling brings them back: Ethereum leaves the account profile records in place, and Bitcoin moves each profile to the kept profiles of its record. Only deleting the wallet erases them, except that the oldest kept profiles are dropped when more than 100 are kept or the record is full.
- Any app of a Mnemonic wallet can be uninstalled, Bitcoin included. A Mnemonic wallet with no apps shows an empty state with a Manage Apps action.
- Lumio and Rabby pairing share only installed apps. A signing request for an app that is not installed is refused, with a prompt to install that app.
- A Single Key wallet, made by importing one private key, holds one account, which is its Ethereum account. It has no Apps screen and no Manage Apps row, so its Ethereum cannot be uninstalled and no other app can be installed in it. A signing request for an app it does not have is refused, and the refusal has no prompt to install that app.

---

## 3. Interfaces

All interfaces live in two core API modules: `:core:signer-api` (Lensly side) and `:core:wallet-api` (Lumio side). Both depend on `:core:model` (ids, assets, review model, field specs). Neither depends on Android UI.

### 3.1 Common to both sides

```kotlin
interface ChainApp {
    val manifest: ChainManifest
    val addresses: AddressCodec
}

interface AddressCodec {
    /** Decode, check charset, length, checksum and case rules; return the normalised form. */
    fun parse(chain: ChainId, text: String): AddressResult

    /** Address for a public key, for each script type or address kind the app supports. */
    fun fromPublicKey(chain: ChainId, key: PublicKey, kind: AddressKind = AddressKind.DEFAULT): String

    /** Short form for tight UI. The core still shows the full address on review. */
    fun short(address: String): String = address.take(6) + "…" + address.takeLast(6)
}

sealed interface AddressResult {
    data class Valid(val normalised: String, val kind: AddressKind, val embedded: Map<FieldKey, String> = emptyMap()) : AddressResult
    data class Invalid(val reason: AddressError) : AddressResult
}

enum class AddressError { CHARSET, LENGTH, CHECKSUM, MIXED_CASE, WRONG_NETWORK, WRONG_VERSION, NOT_ON_CURVE }
```

Validation contract for every chain: decode, check charset and length, verify the checksum, reject case mixing where the format forbids it, check the network (mainnet vs testnet prefix), and return the normalised form. Examples from primary sources:

- EVM: EIP-55 mixed-case checksum. A mixed-case address must match the checksum. All-lower or all-upper is accepted without one. <https://eips.ethereum.org/EIPS/eip-55>
- Bitcoin SegWit v0: bech32, lowercase only, at most 90 chars. <https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki>
- Bitcoin v1+ (Taproot): bech32m. Reject bech32m on v0 and bech32 on v1+. <https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki>
- `embedded` holds data packed into an address, such as an XRPL X-address tag (<https://xrpl.org/docs/concepts/accounts/addresses>). The core copies it into the matching field (section 6).

The core uses `AddressCodec` for the address book, for review rows, and for the "is this one of my accounts" check. `CanonicalAddress.kt` and its `when (AddressChain)` go away.

### 3.2 Lensly: signer app

```kotlin
interface SignerApp : ChainApp {
    /** Public material Lensly exports at pairing, per account. */
    fun accountExport(chain: ChainId, account: Int, keys: KeyAccess): AccountExport

    /** Runs inside the parser sandbox process. No key access. Pure function of the bytes. */
    val parser: RequestParser

    /** Runs in the app process. Builds the review model from a parsed request. No key access. */
    fun review(request: ParsedRequest, ctx: ReviewContext): Review

    /** Runs in the app process after the user approves. Key access is scoped (3.4). */
    fun sign(request: ParsedRequest, approval: Approval, keys: KeyAccess): SignResponse
}
```

#### Parsing

```kotlin
interface RequestParser {
    /** Bytes from the UR payload. Must not throw; return Refused with a reason instead. */
    fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome
}

sealed interface ParseOutcome {
    data class Parsed(val request: ParsedRequest) : ParseOutcome
    data class Refused(val reason: RefusalReason, val detail: String) : ParseOutcome
}

/** Opaque to the core except for the header. The app serialises its own body. */
data class ParsedRequest(
    val header: RequestHeader,
    val body: ByteArray,                    // app-defined, CBOR; crosses the sandbox boundary
)

data class RequestHeader(
    val app: AppId,
    val chain: ChainId,
    val kind: RequestKind,                  // TRANSACTION, MESSAGE, TYPED_DATA
    val signers: List<SignerRef>,           // which of my keys this asks for: fingerprint + path
    val requestId: ByteArray?,              // echoed in the response (section 7.3)
)
```

The parser runs in `ParserSandboxService` (a separate process) as today. The sandbox wire (`ParserWire.kt`) becomes chain-blind: it carries `ParseOutcome` with the app body as bytes. The per-chain `ScanReply` branches go away.

`ParserLimits` (today in `shared/.../core/parse/ParserLimits.kt`) is set by the core. An app may lower its limits, never raise them.

#### Review model

The app turns a parsed request into a `Review`. The core renders it. The app never draws.

```kotlin
data class Review(
    val title: String,                       // CMOS title case: "Send Bitcoin", "Opt In to Asset"
    val chain: ChainId,
    val sections: List<ReviewSection>,       // in display order
    val warnings: List<Warning>,
    val coverage: Coverage,                  // see "Coverage" below
    val record: RecordFacts,                 // what the core stores in the review record
)

data class ReviewSection(val heading: String?, val rows: List<ReviewRow>)

data class ReviewRow(
    val label: String,                       // "To", "Amount", "Network fee", "Memo"
    val value: DisplayValue,
    val field: FieldPath,                    // the decoded field this row shows, for coverage
    val emphasis: Emphasis = Emphasis.NORMAL,
)

sealed interface DisplayValue {
    data class Address(val chain: ChainId, val address: String, val role: AddressRole) : DisplayValue
    data class Amount(val asset: AssetId, val baseUnits: BigInteger, val decimals: Int?, val symbol: String?) : DisplayValue
    data class Nft(val asset: AssetId, val name: String?, val quantity: BigInteger = BigInteger.ONE) : DisplayValue
    data class Text(val text: String) : DisplayValue           // already validated UTF-8
    data class Bytes(val bytes: ByteArray) : DisplayValue      // shown as hex, never as guessed text
    data class Number(val value: BigInteger, val unit: String? = null) : DisplayValue
    data class FeeRate(val value: BigDecimal, val unit: String) : DisplayValue  // "sat/vB", "gwei"
    data class Time(val epochSeconds: Long) : DisplayValue
    data class Duration(val seconds: Long) : DisplayValue
    data class Choice(val key: String, val label: String) : DisplayValue        // enums
    data class Nested(val title: String, val rows: List<ReviewRow>) : DisplayValue  // inner call, batch item
}
```

Rules the core enforces when it renders:

- `Address` values are always shown in full, with the address book name (if any) and an "add or edit entry" action. This is the existing hard rule for activity details and applies to review too.
- `Amount` values with unknown decimals show base units and the label "decimals unknown". The app must not guess.
- `Bytes` are never decoded as text by the core. If the app thinks they are UTF-8, it must validate and return `Text`.
- Labels and titles use sentence case.

#### Warnings and danger levels

```kotlin
data class Warning(
    val level: Danger,
    val code: String,                 // stable, e.g. "algorand.rekey", "evm.unlimited-approval"
    val message: String,              // plain English, one or two sentences
    val field: FieldPath?,            // the row it refers to; the core highlights it
)

enum class Danger {
    INFO,       // shown, no extra step
    CAUTION,    // shown above the rows
    DANGER,     // user must acknowledge this warning by name before approve is enabled
    BLOCK,      // approve is not offered; the request cannot be signed
}
```

The app chooses the level; the core enforces what each level means. Owner policy (today `BtcPolicies.kt`) can raise a level, never lower it.

Minimum warnings each app must raise when the field exists in its chain. Sources: research on dangerous fields.

| Situation | Minimum level | Source |
|---|---|---|
| Algorand `rekey-to` set (not to sender) | DANGER | <https://dev.algorand.co/concepts/transactions/reference/> |
| Algorand `close-to` set | DANGER | same |
| Algorand `lease` set | CAUTION | same |
| ASA with clawback or freeze address | CAUTION | <https://dev.algorand.co/concepts/assets/asset-operations/> |
| EVM `approve` with unlimited value | DANGER | <https://eips.ethereum.org/EIPS/eip-20> |
| ERC-721/1155 `setApprovalForAll(true)` | DANGER | <https://eips.ethereum.org/EIPS/eip-721> |
| SPL `SetAuthority`; Token-2022 permanent delegate, transfer hook, transfer fee on the mint | DANGER (authority), CAUTION (extensions) | <https://www.solana-program.com/docs/token-2022> |
| Bitcoin RBF signalled (`nSequence` < `0xfffffffe`) | INFO | <https://github.com/bitcoin/bips/blob/master/bip-0125.mediawiki> |
| Recipient requires a memo or tag and it is empty | BLOCK | section 6.3 |
| Address fails validation | BLOCK | section 3.1 |
| Fee above owner threshold | CAUTION or DANGER per policy | `BtcFeeThresholds.kt` today |
| Unknown token or asset id | CAUTION | `AlgorandRequestReview` UNKNOWN_ASSET today |

#### Coverage

Every field the parser decodes must be shown or explicitly excluded. This is the ERC-7730 rule ("every ABI parameter must appear in `fields` or `excluded`", <https://developers.ledger.com/docs/clear-signing/reference/specifications>) applied to all chains.

```kotlin
data class Coverage(
    val decoded: Set<FieldPath>,                // every field the parser read
    val excluded: Map<FieldPath, String>,       // field -> reason, e.g. "genesis hash: checked against network"
    val opaque: List<OpaquePart> = emptyList(), // parts the app could not decode at all
)
```

The core checks, before it enables approve:

- `decoded` minus fields shown in rows minus `excluded` is empty. If not, the review shows a BLOCK "this request has fields the app did not show". This is a programming error and a test must catch it first (section 9).
- If `opaque` is not empty, the request is blind signing. See 8.4.

#### Signing and key access

```kotlin
interface KeyAccess {
    fun publicKey(curve: Curve, path: Path): PublicKey
    fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature   // low-S, with recovery id
    fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray
    fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray

    /** Only with Capability.RAW_PRIVATE_KEY. Bytes are zeroed when the block returns. */
    fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T
}
```

Every call checks `path` against the app's `KeyScope` (2.2 rule 6). The seed is derived and zeroed by the core around the call, as `WalletStore.bitcoinSign/solanaSign/algorandSign` do today.

`sign` returns the response payload. The core wraps it in the response UR type.

```kotlin
data class SignResponse(val urType: String, val cbor: ByteArray)
```

`RAW_PRIVATE_KEY` exists because some libraries (for example bitcoin-kmp's PSBT signer) want a key object. Prefer the primitive calls. New apps should not need the capability.

#### Account matching

`RequestHeader.signers` names the keys the request wants, as `SignerRef(fingerprint, path)`. The core finds the wallet by master fingerprint and checks the path is in scope. This replaces `WalletMatch.claimsAccount` and `routeScan`. An app that cannot name a fingerprint (for example, a raw Solana message) returns a path only, and the core asks the user to pick a wallet.

### 3.3 Lumio: wallet app

```kotlin
interface WalletApp : ChainApp {
    val providers: ProviderSpec              // which data ops the app needs (3.3.5)
    val assets: AssetSource                  // balances and discovery
    val builder: TxBuilder                   // send form -> unsigned request
    val fees: FeeEstimator
    val broadcaster: Broadcaster
    fun accountFrom(export: AccountExport): List<WalletAccount>   // pairing import
}
```

#### 3.3.1 Accounts

```kotlin
data class WalletAccount(
    val id: AccountId,                       // CAIP-10
    val label: String,
    val signer: SignerRef,                   // fingerprint + path, sent back in requests
    val publicKey: PublicKey,
    val kind: AddressKind,                   // script type for Bitcoin family; DEFAULT otherwise
)
```

Bitcoin-family accounts are one `WalletAccount` per script type and network. Watch-only accounts (`BtcWatchOnly.kt`) use the same type with `signer.fingerprint` set and an extended public key in `publicKey`.

#### 3.3.2 Balances and asset discovery

```kotlin
interface AssetSource {
    suspend fun native(account: AccountId, data: DataClient): Balance
    suspend fun tokens(account: AccountId, data: DataClient): List<Balance>          // fungible
    suspend fun nfts(account: AccountId, data: DataClient): List<NftHolding>         // empty by default
    suspend fun lookup(chain: ChainId, ref: String, data: DataClient): AssetLookup   // custom token add
}
```

`lookup` takes what the user typed (an ERC-20 contract, an SPL mint, an ASA id) and returns metadata it read on chain, marked as untrusted (section 5.3). Today's `CustomTokenLookup.kt` is the EVM implementation.

#### 3.3.3 Transaction builder

```kotlin
interface TxBuilder {
    /** Fields the send form shows for this asset and recipient, from the manifest's FieldSpecs. */
    fun form(chain: ChainId, asset: AssetId, recipient: String?): SendForm

    /** Checks that must pass before build: opt-in, trust line, token account, minimum balance. */
    suspend fun preconditions(draft: SendDraft, data: DataClient): List<Precondition>

    /** Builds the unsigned request. Returns the QR parts to show and a handle to match the reply. */
    suspend fun build(draft: SendDraft, fee: FeeChoice, data: DataClient): BuiltRequest

    /** Checks the signed response matches the request (request id, sign data, signer) and returns raw tx bytes. */
    fun accept(built: BuiltRequest, response: SignResponse): SignedTx
}

data class SendDraft(
    val from: AccountId,
    val to: String,                          // already parsed by AddressCodec
    val asset: AssetId,
    val amount: BigInteger,                  // base units
    val extras: Map<FieldKey, FieldValue>,   // memo, tag, ... (section 6)
)
```

`accept` keeps the binding that exists today (`EvmSend.signed`, `AlgorandSend.signed`, `BtcSend.signed`): the response must answer this request and no other.

#### 3.3.4 Fees

```kotlin
interface FeeEstimator {
    suspend fun options(draft: SendDraft, data: DataClient): List<FeeChoice>   // slow / normal / fast
}

data class FeeChoice(val label: String, val params: Map<FieldKey, FieldValue>, val total: Amount?)
```

`params` uses the fee keys from section 6 (`fee.maxFeePerGas`, `fee.satPerVb`, `fee.cuPrice`, ...). Lensly shows the same values back on review.

#### 3.3.5 Broadcast and data providers

```kotlin
interface Broadcaster {
    suspend fun broadcast(chain: ChainId, tx: SignedTx, data: DataClient): TxRef
    suspend fun status(chain: ChainId, ref: TxRef, data: DataClient): TxStatus
}

data class ProviderSpec(
    val ops: Set<OpKey>,                     // namespaced: "evm.call", "btc.utxos", "sol.blockhash"
    val defaults: List<EndpointSpec>,        // built-in endpoints per chain
    val allowCustom: Boolean = true,         // user may add their own RPC URL
)

interface DataClient {
    suspend fun <T> call(chain: ChainId, op: OpKey, request: ByteArray, decode: (ByteArray) -> T): T
}
```

The core keeps the router, scheduler, health checks, rate limits and MEV-protected broadcast routing that live in `lumio/.../data/` today. What changes: `Op` stops being one enum that unions every chain's operations. Ops become namespaced keys declared by the app, and providers register against `(namespace, op)` pairs. `Family` and `NetworkId` are replaced by `ChainId`.

Provider implementations (Etherscan, Esplora, Algod, Solana RPC, ...) move into the chain app's `wallet/` module or into a shared provider module when several apps use them (Blockchair, 3xpl).

---

## 4. Identifiers

AirVault uses CAIP identifiers everywhere: in manifests, in QR payloads, in the address book, and in stored accounts. There is no second scheme.

| Thing | Standard | Form | Example |
|---|---|---|---|
| Chain | CAIP-2 | `namespace:reference` | `eip155:10143` (Monad testnet) |
| Account | CAIP-10 | `chain_id:address` | `eip155:1:0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B` |
| Asset type | CAIP-19 | `chain_id/asset_namespace:asset_reference` | `eip155:1/erc20:0x6b175474e89094c44da98b954eedeac495271d0f` |
| Asset id (NFT) | CAIP-19 | `asset_type/token_id` | `eip155:1/erc721:0x06012c8cf97bead5deae237070f9587f8e7a266d/771769` |
| Coin type | SLIP-44 | integer | `60` (ETH), `0` (BTC) |

Sources: CAIP-10 (Final) <https://github.com/ChainAgnostic/CAIPs/blob/master/CAIPs/caip-10.md>; CAIP-19 (Review) <https://github.com/ChainAgnostic/CAIPs/blob/master/CAIPs/caip-19.md>; SLIP-44 <https://github.com/satoshilabs/slips/blob/master/slip-0044.md>. CAIP-2 (Final) <https://github.com/ChainAgnostic/CAIPs/blob/master/CAIPs/caip-2.md>: namespace `[-a-z0-9]{3,8}`, reference `[-_a-zA-Z0-9]{1,32}`.

Rules:

- CAIP-10 does not require canonical addresses. AirVault does: every address passes through `AddressCodec.parse` before it becomes part of an `AccountId`. Two ids for the same account must compare equal.
- CAIP-19 is in Review status. Pin the version we follow in `:core:model` and note it in the code.
- CAIP-10 limits addresses to 1-128 chars of `[A-Za-z0-9-.%]` and requires URL-encoding of anything else. Address formats in use today fit.
- Namespace and reference strings must be copied from the ChainAgnostic namespaces registry, not invented. Confirmed: `bip122` (<https://github.com/ChainAgnostic/namespaces/blob/master/bip122/caip2.md>), `solana` (<https://github.com/ChainAgnostic/namespaces/blob/master/solana/caip2.md>), `algorand` (<https://github.com/ChainAgnostic/namespaces/blob/master/algorand/caip2.md>), and `tron` (Draft, <https://github.com/ChainAgnostic/namespaces/blob/master/tron/caip2.md>). CAIP-19 profiles exist for other asset namespaces (for example `solana`, status Draft, <https://github.com/ChainAgnostic/namespaces/blob/master/solana/caip19.md>).
- `ChainId` replaces `BtcNetwork`, `AlgorandNetwork`, the EVM `chainId: Long`, Lumio's `NetworkId`, and `NetworkMode`'s special cases. "Testnet or mainnet" is a property of `NetworkSpec`, not a separate enum.

---

## 5. Asset model

### 5.1 Types

```kotlin
sealed interface AssetSpec {
    val id: AssetId                        // CAIP-19
    val chain: ChainId

    data class Native(override val id: AssetId, override val chain: ChainId,
                      val symbol: String, val decimals: Int) : AssetSpec

    data class Fungible(override val id: AssetId, override val chain: ChainId,
                        val standard: String,          // "erc20", "spl", "token-2022", "asa", "trc20", "trc10"
                        val symbol: String?, val name: String?,
                        val decimals: Int?,            // null = unknown; never guessed
                        val trust: Trust,
                        val flags: Set<AssetFlag> = emptySet()) : AssetSpec

    data class Nft(override val id: AssetId, override val chain: ChainId,
                   val standard: String,               // "erc721", "erc1155", "metaplex", "arc3", "arc69", "arc19"
                   val collection: String?, val name: String?,
                   val metadata: MetadataSource,
                   val trust: Trust) : AssetSpec
}

enum class AssetFlag { CLAWBACK, FREEZE, TRANSFER_FEE, TRANSFER_HOOK, PERMANENT_DELEGATE, NON_TRANSFERABLE, MUTABLE_METADATA }
```

Amounts are always integers in base units (`BigInteger`). Decimals is display data. Some chains check decimals in the transfer itself (SPL `transferChecked`), so the app must use the on-chain value there, not the catalogue value. The `transferChecked` payload is discriminator 12, then amount (u64, little-endian), then decimals (u8) (<https://github.com/solana-program/token>, `interface/src/instruction.rs`).

### 5.2 Standards per chain

| Chain | Fungible | Identity | NFT | Source |
|---|---|---|---|---|
| EVM | ERC-20 | contract | ERC-721 (contract, tokenId), ERC-1155 (contract, id, quantity) | <https://eips.ethereum.org/EIPS/eip-20>, <https://eips.ethereum.org/EIPS/eip-721>, <https://eips.ethereum.org/EIPS/eip-1155> |
| Solana | SPL Token, Token-2022 | mint; balances in token accounts | Metaplex Token Metadata (incl. pNFT), compressed NFTs (Bubblegum) | <https://www.solana-program.com/docs/token-2022>, <https://metaplex.com/docs/token-metadata/token-standard>, <https://metaplex.com/docs/bubblegum> |
| Algorand | ASA | uint64 asset id | ASA with ARC-3, ARC-69, ARC-19 metadata | <https://dev.algorand.co/concepts/assets/asset-operations/>, <https://github.com/algorandfoundation/ARCs/blob/main/ARCs/arc-0003.md>, <https://github.com/algorandfoundation/ARCs/blob/main/ARCs/arc-0069.md>, <https://github.com/algorandfoundation/ARCs/blob/main/ARCs/arc-0019.md> |
| Tron | TRC-20 (contract), TRC-10 (protocol-level id) | contract / token id | TRC-721, TRC-1155 | <https://developers.tron.network/docs/token-standards-overview> |
| Bitcoin family | none at protocol level | - | - | Runes and BRC-20 are out of scope (5.5) |

Notes the app must handle:

- ERC-20 says callers must not expect `name`, `symbol` or `decimals` to exist (EIP-20). Missing values stay `null`.
- ERC-1155 `uri(id)` replaces `{id}` with the id as 64-char lowercase zero-padded hex (EIP-1155).
- Token-2022 keeps Token's byte layout for instructions 0-24 and adds new ones from 25. Mint extensions (transfer fee, transfer hook, permanent delegate, non-transferable, confidential transfer, ...) must become `AssetFlag`s and review warnings.
- Metaplex pNFTs are always frozen and move through Token Metadata, not a plain SPL transfer. Token Metadata thaws the source account, transfers, and re-freezes the destination; a token record is required (<https://github.com/metaplex-foundation/mpl-token-metadata/blob/main/programs/token-metadata/program/src/utils/programmable_asset.rs>). The review must say so.
- ARC-89 `supersedes` ARC-19 and ARC-69 (<https://github.com/algorandfoundation/ARCs/blob/main/ARCs/arc-0089.md>). ARC-89 is at Last Call, not Final. Support ARC-19 and ARC-69 read-only; add ARC-89 read-only once it is Final (section 12.1, item 1).
- TRC-10 transfers are protocol-level, not contract calls. TRC-10 is not recommended for new tokens (Tron docs).

### 5.3 Metadata sources and trust

Metadata comes from four kinds of source (research section B). The app declares which one per asset.

```kotlin
sealed interface MetadataSource {
    data object OnChainFields : MetadataSource                       // ASA params, Solana mint
    data class OffChainJson(val uri: String, val hash: ByteArray?) : MetadataSource  // tokenURI, Metaplex uri, ARC-3 (am hash)
    data object TxNote : MetadataSource                              // ARC-69 note
    data object Indexer : MetadataSource                             // compressed NFTs via DAS
}
```

Rules:

- Off-chain JSON is untrusted. Lumio fetches it, verifies the hash when one exists (ARC-3 `am`), and never sends images or URLs to Lensly.
- Lensly never fetches. It shows names only from its bundled, signed catalogue (today `TokenEntry`, `SolanaTokenList`, `AsaCatalog`) or from data inside the request. Request-supplied names are marked "from the request, not verified".
- Mutable metadata (ARC-19, ARC-69 while a manager exists, Metaplex update authority) is re-read; Lumio does not cache it forever.
- Every asset has a trust state: `CATALOGUE` (signed catalogue), `USER_ADDED` (custom token), `UNKNOWN`. Review shows `UNKNOWN` assets with a CAUTION and the raw id.

The bundled catalogues move from per-chain files into one signed registry keyed by CAIP-19 id. Each app supplies its catalogue entries and a loader. The signed-package format (`core/registry/update/`) stays.

### 5.4 Holding preconditions

Some chains need a setup step before an account can hold an asset.

```kotlin
sealed interface Precondition {
    val asset: AssetId
    data class OptIn(override val asset: AssetId, val minBalanceIncrease: Amount) : Precondition    // ASA: 0-amount axfer to self, +0.1 ALGO min balance
    data class TokenAccount(override val asset: AssetId, val owner: AccountId, val payer: AccountId) : Precondition  // SPL associated token account
    data class TrustLine(override val asset: AssetId, val limit: BigInteger?) : Precondition       // XRPL, Stellar
}
```

Lumio shows a missing precondition as its own step with its own QR request. It never folds it silently into a send. A missing opt-in on the recipient side blocks the send with a clear reason. Sources: <https://dev.algorand.co/concepts/assets/asset-operations/>. An SPL associated token account is the PDA with seeds `[wallet, token_program_id, mint]` under the associated-token program ID (<https://github.com/solana-program/associated-token-account/blob/main/interface/src/address.rs>). Stellar and XRPL trust-line details are **[UNVERIFIED]** (pages returned 404 during research).

### 5.5 Out of scope for now

Runes, BRC-20 and Ordinals live on top of Bitcoin outputs and need indexers. They are out of scope. But a Bitcoin-family signer must still guard against them: spending an output that carries an inscription or a rune can destroy it. Lensly has no indexer, so the work is split: Lumio checks an indexer and marks risky inputs in the PSBT (a proprietary PSBT input field defined by the Bitcoin app). Lensly shows a danger warning for every marked input. For unmarked inputs it shows a note that inscription and rune status is not verified offline.

The mark is the input-map entry `BtcPsbt.RISK_MARK`: BIP 174 proprietary key type `0xFC`, prefix `airvault`, subtype `0x01`, no key data, and value `0x01`. An input with one or more such entries is marked; a repeated mark still marks the input, so a malformed PSBT can only add a warning. Any other entry is ignored, so an input carrying only other entries counts as unmarked. A mark can only add a warning. Signing keeps the entry in the PSBT, as BIP 174 requires. The indexer is owner-configured in Lumio's Bitcoin settings and off by default for privacy: with none set, no output is looked up and every input stays unmarked.

---

## 6. Transaction extras

### 6.1 Field taxonomy

Chains differ in their extra fields, but most fit a few kinds. The app declares its fields in the manifest. Lumio builds the send form from them. Lensly checks them on review.

```kotlin
data class FieldSpec(
    val key: FieldKey,                 // namespaced and stable: "memo", "xrpl.destinationTag", "algorand.note"
    val kind: FieldKind,
    val label: String,                 // "Memo", "Destination tag"
    val appliesTo: Set<AssetKindFilter> = setOf(AssetKindFilter.ANY),
    val routing: Boolean = false,      // true if exchanges use it to credit a customer (6.3)
    val danger: Danger = Danger.INFO,  // minimum warning level when set
    val userEditable: Boolean = true,  // false for fields only the app sets (fee params from FeeEstimator)
)

sealed interface FieldKind {
    data class MemoText(val maxBytes: Int?, val encoding: TextEncoding = TextEncoding.UTF8) : FieldKind
    data class MemoBytes(val maxBytes: Int?, val fixedLength: Int? = null) : FieldKind
    data class NumericTag(val bits: Int) : FieldKind                // 32 for XRPL dest tag, 64 for Stellar MEMO_ID
    data class FeeParam(val unit: String, val min: BigInteger?, val max: BigInteger?) : FieldKind
    data class Sequence(val bits: Int) : FieldKind                  // nonce, sequence
    data class Expiry(val basis: ExpiryBasis) : FieldKind           // HEIGHT, TIME, LEDGER, BLOCKHASH
    data object Flag : FieldKind                                    // e.g. RBF on/off
    data class AddressField(val role: AddressRole) : FieldKind      // close-to, rekey-to
}
```

Examples from the research (sources inline):

| Chain | Key | Kind | Limits and notes |
|---|---|---|---|
| Algorand | `algorand.note` | MemoBytes | up to 1024 bytes; shown as text only if valid UTF-8 <https://dev.algorand.co/concepts/transactions/reference/> |
| Algorand | `algorand.closeTo` | AddressField, DANGER | closes the account <https://dev.algorand.co/concepts/transactions/reference/> |
| Algorand | `algorand.rekeyTo` | AddressField, DANGER | hands signing authority to another address; same page |
| Algorand | `algorand.lease` | MemoBytes(fixedLength 32), CAUTION | blocks duplicate txns until `lastValid`; same page |
| Solana | `solana.memo` | MemoText(UTF-8) | invalid UTF-8 fails; length bounded by compute (566 single-byte chars as of v1.5.1) <https://www.solana-program.com/docs/memo> |
| Solana | `fee.cuPrice`, `fee.cuLimit` | FeeParam ComputeBudget instructions `setComputeUnitPrice` and `setComputeUnitLimit` (<https://github.com/solana-program/compute-budget/blob/main/idl.json>). A blockhash is accepted for processing only within 150 slots (`MAX_PROCESSING_AGE`, <https://github.com/anza-xyz/solana-sdk/blob/master/clock/src/lib.rs>) |
| EVM | `fee.maxFeePerGas`, `fee.maxPriorityFeePerGas`, `fee.gasLimit` | FeeParam | EIP-1559 <https://eips.ethereum.org/EIPS/eip-1559> |
| EVM | `evm.nonce` | Sequence(64) | |
| Bitcoin | `fee.satPerVb` | FeeParam | |
| Bitcoin | `btc.rbf` | Flag | BIP125 <https://github.com/bitcoin/bips/blob/master/bip-0125.mediawiki> |
| Bitcoin family | `btc.opReturn` | MemoBytes(80) | Relay policy, not consensus. Core v30.0 raised the default `-datacarriersize` to 100,000 bytes (effectively uncapped). The 80-byte limit (83 script bytes) is the pre-v30 default. Lensly warns above 80 data bytes (section 12.1, item 5). <https://github.com/bitcoin/bitcoin/blob/30.x/doc/release-notes/release-notes-30.0.md> |
| XRPL | `xrpl.destinationTag` | NumericTag(32), routing | <https://xrpl.org/source-and-destination-tags.html> |
| Stellar | `stellar.memo` | MemoText(28) / NumericTag(64) / MemoBytes(32) | <https://developers.stellar.org/docs/encyclopedia/memos> |
| Tron | `fee.feeLimit` | FeeParam | unit is sun (1 TRX = 1,000,000 sun); TRC-20 energy cost **[UNVERIFIED]** <https://developers.tron.network/docs/paying-for-resources> |

Fields that do not fit a generic kind (EVM calldata, Solana instruction lists and account metas, PSBT script details, Cosmos IBC routing) stay in app code. They still reach the review as rows and still count for coverage.

### 6.2 Validation

The core validates generic constraints (byte length, numeric range, UTF-8) on both sides. The app adds chain rules in one place:

```kotlin
interface FieldValidator {
    fun validate(chain: ChainId, key: FieldKey, value: FieldValue, draft: SendDraft?): FieldCheck
}
```

Lumio runs it while the user types. Lensly runs it again on the decoded request. Lensly never trusts Lumio's check.

### 6.3 "Required by recipient"

Whether a memo or tag is required depends on the recipient, not the chain. An exchange deposit address needs one; a personal wallet does not. Source: XRPL tags page (<https://xrpl.org/source-and-destination-tags.html>); exchange practice for Stellar, Solana and Algorand is **[UNVERIFIED]**. XRPL returns `tecDST_TAG_NEEDED` (TER code 143) when the destination requires a tag and none is given (<https://github.com/XRPLF/rippled>, `src/libxrpl/tx/transactors/payment/Payment.cpp` and `include/xrpl/protocol/TER.h`).

So the requirement lives on the address book entry:

```kotlin
data class RecipientRequirement(val field: FieldKey, val value: FieldValue? = null)  // value pinned if the exchange gave a fixed one
// AddressBookEntry gains: val requires: List<RecipientRequirement>
```

Rules:

- Lumio: if the recipient's entry requires a field, the send form shows it as required and will not build without it.
- Lensly: if the recipient's entry (in Lensly's own address book) requires a field and the request leaves it empty or different from the pinned value, the review raises a BLOCK.
- The address book UI offers the required-field toggle only for fields marked `routing = true` in the manifest.
- If the address itself carries the tag (XRPL X-address), `AddressResult.Valid.embedded` fills the field and the review shows where it came from.

### 6.4 Dangerous fields must be surfaced

A field with `danger >= CAUTION` must, when set:

- appear as a row, never in `excluded`;
- raise a warning at the declared level or higher;
- be listed in the review record.

Lumio must not set a dangerous field unless the user asked for that action by name ("close account", "rekey account"). A plain send never sets one.

---

## 7. QR transport

### 7.1 What the core does

The core owns UR encoding, animated multi-part QR, the scanner (`UrScanner`, `UrFrames`, `QrDecodeService`), and CBOR. A chain app supplies only CBOR payload bytes and the UR type string.

### 7.2 UR type registry

UR types follow Blockchain Commons BCR-2020-006: type names use letters, digits and hyphens; `x-` is reserved for user-defined types; top-level UR objects are not tagged (<https://github.com/BlockchainCommons/Research/blob/master/papers/bcr-2020-006-urtypes.md>).

```kotlin
data class UrTypeSpec(
    val type: String,                 // "crypto-psbt", "x-airvault-sign-request"
    val direction: Direction,         // REQUEST (Lumio -> Lensly), RESPONSE (Lensly -> Lumio), PAIRING
    val versions: IntRange,           // payload schema versions the app accepts / emits
    val external: ExternalSpec?,      // if this type is defined outside AirVault, link to the spec
)
```

The `ChainRegistry` maps each type to exactly one app. Unknown types are refused with an explicit "this request type is not supported" message. The parser does not try to guess.

Today's types and owners:

| Type | Direction | Owner after 2.9 | Defined by |
|---|---|---|---|
| `x-airvault-pairing` | pairing | core | AirVault (`PairingCodec.kt`) |
| `psbt`, `crypto-psbt` | request; the response uses the name the request used | `bitcoin` app | BCR-2020-006 registry: the current name is `psbt` (tag 40310); `crypto-psbt` is the retired name. Lensly reads both (`BtcPsbt.REQUEST_UR_TYPES`) and answers under the name its request used. Lumio writes requests as `crypto-psbt` (`BtcPsbt.UR_TYPE`); see section 12.1, item 6. <https://github.com/BlockchainCommons/Research/blob/master/papers/bcr-2020-006-urtypes.md> |
| `eth-sign-request`, `eth-signature` | request, response | `evm` app | not in BCR-2020-006; Keystone-style |
| `sol-sign-request`, `sol-signature` | request, response | `solana` app | Keystone ur-registry types `SOL_SIGN_REQUEST` (sign type 1 or 2) and `SOL_SIGNATURE` <https://github.com/KeystoneHQ/keystone-sdk-rust/blob/master/libs/ur-registry/src/solana/sol_sign_request.rs> |
| `algo-sign-request`, `algo-signature` | request, response | `algorand` app | AirVault |

### 7.3 Generic envelope for new chains

New chains do not invent their own top-level UR type. They use one AirVault envelope:

```
ur:x-airvault-sign-request     (Lumio -> Lensly)
ur:x-airvault-signature        (Lensly -> Lumio)
```

CBOR map:

| Key | Name | Type | Notes |
|---|---|---|---|
| 1 | version | uint | envelope version; unknown -> refuse |
| 2 | requestId | bytes(16) | echoed in the response; required |
| 3 | chain | text | CAIP-2 id; selects the app via `ChainRegistry.byChain` |
| 4 | kind | uint | 1 transaction, 2 message, 3 typed data |
| 5 | signers | array of {fingerprint: bytes(4), path: text} | |
| 6 | payloadVersion | uint | app payload schema version |
| 7 | payload | bytes | app-defined CBOR; the only part the app parses |
| 8 | origin | text, optional | free text, shown as "from the request" |

The response carries `version`, `requestId`, `chain`, and the app-defined `signature` bytes.

The four existing chains keep their current types in 2.9 (they match formats other wallets use). They do not accept the envelope; it is for new chains only (decision, section 12). The core gives envelope and legacy types the same treatment: same sandbox, same review, same record.

The pairing type is renamed to `x-airvault-pairing` to follow the `x-` rule. This is a hard switch: both apps change together and the old name is not accepted. Devices paired under the old name pair again.

### 7.4 Versioning

- Envelope version and app payload version are separate. The core rejects an unknown envelope version. The app rejects an unknown payload version.
- A new payload version is added, never edited. An old version is refused, not read.
- No backward-compatibility code is kept between app versions. Version fields still exist so that a mismatch fails clearly instead of misparsing.

### 7.5 Pairing export

`SignerApp.accountExport` returns per-account public data:

```kotlin
data class AccountExport(
    val chain: ChainId,
    val signer: SignerRef,             // fingerprint + path
    val publicKey: ByteArray,          // 33-byte secp256k1, 32-byte ed25519, or extended key
    val chainCode: ByteArray?,         // for xpub-style watch-only export (WATCH_ONLY_EXPORT)
    val kind: AddressKind,
)
```

`PairingCodec` encodes a list of these without knowing the chain. Its per-kind factory functions are gone: each chain's Lensly side builds its own entries. `PairImport` checks each entry against its chain's manifest (key scope, curve, chain code) and address rules, so the chain is found in the registry, not in the codec.

---

## 8. Security rules

These rules are not optional. A chain app that breaks one does not merge.

### 8.1 Lensly stays offline

- Lensly requests no INTERNET permission (`app/src/main/AndroidManifest.xml` says so today). This stays.
- A signer module (`chains/*/signer`, `chains/*/common`) must not depend on any network library (OkHttp, Ktor, Retrofit, `java.net` HTTP clients). A Gradle dependency check in CI enforces an allowlist.
- The signer side does no I/O except what the core passes in. No file access, no clock-based behaviour that changes what is shown.
- No dynamic code loading in Lensly (2.4).

### 8.2 Key scope

- The app never sees the seed or mnemonic.
- Every key request is checked against the manifest's `KeyScope` (2.2), at load time and at each call.
- Key material lives only inside `KeyAccess` calls and is zeroed after. `RAW_PRIVATE_KEY` needs a justification and a review approved by the project.
- A path in a request that is in scope but not one of the user's known accounts is shown as a CAUTION ("this request asks for an account you have not used before") with the full path.

### 8.3 Clear signing

- Every decoded field is shown or explicitly excluded with a reason (3.2, Coverage). The core checks this before enabling approve.
- Excluded fields are listed under a "Not shown" section the user can open. Nothing is hidden silently.
- Dangerous fields are always rows and always warn (6.4).
- Amounts, addresses, chain and fee are always shown. A review without a chain row does not render.
- The review shows which network (CAIP-2 name) the request is for. A testnet request is labelled as testnet.

A descriptor shows what the template says; it does not prove the transaction is honest or complete (search-result commentary on ERC-7730, **[UNVERIFIED]**). Coverage checks reduce that gap; they do not close it.

### 8.4 Blind signing warns by default

- If `Coverage.opaque` is not empty, the request is blind. The owner's unknown-call policy in settings (`UnknownCallPolicy`) decides it, and its default is WARN: the request is reviewed under a DANGER warning with its raw data shown in full, and the owner may sign it once that warning is ticked. REFUSE turns blind signing off, and nothing is signed.
- A blind request gets a DANGER warning, shows the raw bytes as hex, and shows a hash the user can compare with Lumio.
- Settings to enable blind signing are inside the vault, never on the cover.

### 8.5 Cover screen never leaks crypto

The rule from the project's design rules applies to every chain app: nothing reachable without the unlock gesture may hint at wallets or crypto. For chain apps this means:

- `displayName`, chain icons, asset symbols and review titles are used only inside the unlocked vault.
- A chain app cannot register a notification, shortcut, widget, share target or launcher alias.
- QR scanning from the cover viewfinder must not show chain names in banners or errors. A request for an unknown type reads the same as any unreadable code until the vault is unlocked.

### 8.6 Screens and records

- FLAG_SECURE applies to every vault screen, except debug and benchmark builds on the emulator (existing rule).
- Each signature produces a review record with `RecordFacts` from the app: chain, kind, addresses, amounts, fields, warnings shown, user acknowledgements. The record format is chain-blind.

### 8.7 Untrusted input

- Everything from Lumio is untrusted. Lensly re-parses and re-validates; it does not reuse Lumio's checks.
- The parser runs in the sandbox process with `ParserLimits`. An app parser that throws is reported as a refusal, not a crash.
- Off-chain metadata never reaches Lensly (5.3).

---

## 9. Testing requirements

Every chain app ships with these tests. The conformance kit in `:core:conformance` runs the generic ones from the manifest alone.

### 9.1 Conformance kit (generic, run by core)

- Manifest validity: id format, `apiVersion`, CAIP-2 ids, key scopes pass 2.2, field specs pass 6.1, UR types are unique.
- Key scope negative tests: the kit asks `KeyAccess` for a path just outside each prefix (wrong coin type, unhardened account level, other curve) and expects `KeyScopeViolation`.
- Parser robustness: random and truncated CBOR must give `Refused`, never throw, never exceed `ParserLimits` (fuzz with a fixed seed so CI is stable).
- Coverage: for every fixture request, `decoded == shown ∪ excluded`.
- Dependency check: signer modules have no network dependency (8.1).
- Round trip: `TxBuilder.build` on the wallet side, then `parse` + `review` + `sign` on the signer side, then `accept` on the wallet side, using the conformance test seed.

### 9.2 App tests (written by the app author)

- **Vectors.** Derivation and address vectors from the chain's primary spec (BIP-32/SLIP-10 test vectors, BIP-173/350 address vectors, EIP-55 examples). Signing vectors from the chain's reference implementation or a known-good wallet.
- **Property tests.** Address codec: `parse(fromPublicKey(k))` is `Valid` and stable; a one-character change is `Invalid`. Field codec: encode then decode is identity; lengths at the limit pass, one byte over fails.
- **Golden review tests.** For each fixture request, the `Review` model is serialised to text and compared to a checked-in golden file. This catches a silent change in what the user sees. Golden screenshots of the rendered review are recorded on the Linux CI runner, as for other screenshots.
- **Danger tests.** One fixture per dangerous field and per warning code, asserting the level.
- **Recipient requirement tests.** A recipient that requires a memo, with the memo missing, gives a BLOCK.

### 9.3 Fixtures

Fixtures live in `chains/<id>/common/src/testFixtures/`: request payloads (CBOR hex), expected reviews (golden), expected signatures. Use testnet chains for anything that a test broadcasts. For EVM, Monad testnet (`eip155:10143`) is the default test network.

---

## 10. Walkthroughs

### 10.1 Add a new chain: Litecoin

Litecoin is close to Bitcoin: UTXOs, PSBT, secp256k1. The point of this walkthrough is that the work stays inside `chains/litecoin/`, plus one line per phone app.

Facts checked: SLIP-44 coin type 2 (<https://github.com/satoshilabs/slips/blob/master/slip-0044.md>); CAIP-2 `bip122:12a765e31ffd4059bada1e25190f6e98` (<https://github.com/ChainAgnostic/namespaces/blob/master/bip122/caip2.md>); bech32 HRP `ltc` and P2PKH version 48 (<https://github.com/litecoin-project/litecoin/blob/master/src/chainparams.cpp>). P2SH version bytes are 5 (legacy) and 50 (`SCRIPT_ADDRESS2`, leading `M`) (same source). Still **[UNVERIFIED]** before coding: whether bitcoin-kmp supports Litecoin network parameters, and whether MWEB outputs can appear in a PSBT (section 12.1, item 3).

**Step 1. Create the modules.** Copy `chains/_template/` to `chains/litecoin/`. Add the three modules to `settings.gradle.kts`.

**Step 2. Write the manifest.**

```kotlin
val LitecoinManifest = ChainManifest(
    id = AppId("litecoin"),
    displayName = "Litecoin",
    apiVersion = 1,
    appVersion = "0.1.0",
    namespaces = setOf("bip122"),
    networks = listOf(
        NetworkSpec(ChainId("bip122", LTC_MAINNET_REF), "Litecoin", testnet = false,
                    nativeAsset = native("LTC", 8), slip44 = 2),
    ),
    keys = listOf(
        KeyScope(Curve.SECP256K1, listOf(PathTemplate("m/84'/2'/*'"), PathTemplate("m/44'/2'/*'")),
                 purpose = "native segwit and legacy accounts"),
    ),
    capabilities = setOf(Capability.MULTI_SCRIPT_ACCOUNTS, Capability.WATCH_ONLY_EXPORT),
    requestTypes = listOf(UrTypeSpec("x-airvault-sign-request", Direction.REQUEST, 1..1, null)),
    responseTypes = listOf(UrTypeSpec("x-airvault-signature", Direction.RESPONSE, 1..1, null)),
    fields = listOf(
        FieldSpec(FieldKey("fee.satPerVb"), FieldKind.FeeParam("sat/vB", BigInteger.ONE, null), "Fee rate", userEditable = false),
        FieldSpec(FieldKey("btc.rbf"), FieldKind.Flag, "Replaceable", danger = Danger.INFO),
    ),
)
```

Why the envelope and not `crypto-psbt`: `crypto-psbt` is already owned by the `bitcoin` app, and a PSBT does not say which chain it is for. The envelope's `chain` field removes the guess. The payload is the PSBT bytes.

**Step 3. Address codec.** Implement `AddressCodec` for Litecoin bech32 and base58check. Reuse the Bitcoin family helpers in `chains/bitcoin/common` by depending on it (the shared code is a library, not a parent class). Add vectors.

**Step 4. Signer side.** `parser`: unwrap the envelope payload as a PSBT, check every input's derivation path is in scope and on coin type 2. `review`: reuse the Bitcoin family review builder, which already produces inputs, outputs, change, fee and RBF rows; pass Litecoin units and names. `sign`: sign each owned input with `KeyAccess.signEcdsaSecp256k1`.

**Step 5. Wallet side.** `AssetSource.native` from an Esplora-compatible or Blockchair provider (declare ops `btc.utxos`, `btc.broadcast` with Litecoin endpoints). `TxBuilder` reuses the Bitcoin family coin selection. `FeeEstimator` reads the provider's fee estimates.

**Step 6. Tests.** Run the conformance kit. Add derivation and address vectors, a golden review for a two-output send, and a negative test for a PSBT input on coin type 0 (must be refused).

**Step 7. Register.** Add `LitecoinSigner` to `InstalledSigners.kt` and the `LitecoinWallet` dependency to `lumio/build.gradle.kts`.

Core files touched: `settings.gradle.kts`, `InstalledSigners.kt`, `lumio/build.gradle.kts`. Nothing else. If a step needs a core change, the contract is missing something; raise it rather than adding a `when`.

### 10.2 Short example: Tron with TRC-20

Tron differs more: secp256k1 keys with Ethereum-style signing, base58check addresses, protobuf transactions, a resource model (bandwidth and energy), and two token standards.

Facts checked: SLIP-44 coin type 195 (<https://github.com/satoshilabs/slips/blob/master/slip-0044.md>); `fee_limit` in sun (<https://developers.tron.network/docs/paying-for-resources>); the `tron` CAIP-2 namespace (Draft, <https://github.com/ChainAgnostic/namespaces/blob/master/tron/caip2.md>). A TRON address is a 21-byte value with a leading `0x41` prefix byte, shown in Base58Check as 34 characters starting with `T` (<https://developers.tron.network/docs/account>). Tron docs: <https://developers.tron.network/docs/token-standards-overview>, <https://developers.tron.network/docs/paying-for-resources>.

Manifest highlights:

```kotlin
keys = listOf(KeyScope(Curve.SECP256K1, listOf(PathTemplate("m/44'/195'/*'/0/*")), "Tron accounts"))
fields = listOf(
    FieldSpec(FieldKey("fee.feeLimit"), FieldKind.FeeParam("sun", BigInteger.ZERO, null), "Fee limit", userEditable = false),
    FieldSpec(FieldKey("tron.memo"), FieldKind.MemoText(maxBytes = null), "Memo"),  // size limit [UNVERIFIED]
)
```

Assets:

- TRX: `AssetSpec.Native`.
- TRC-20: `AssetSpec.Fungible(standard = "trc20")`, identity is the contract. A transfer is an ERC-20 style `transfer(address,uint256)` call inside a TVM trigger. The review decodes the call and shows token, recipient (as a T-address), amount with decimals from the catalogue, and the fee limit. Unlimited `approve` is a DANGER, as on EVM.
- TRC-10: `AssetSpec.Fungible(standard = "trc10")`, identity is the protocol token id. The transfer is a protocol-level contract type, not a call.
- NFTs (TRC-721/1155) are optional for a first version. If not supported, `AssetSource.nfts` keeps its empty default and a request containing one is shown with `opaque` parts and treated as blind (8.4).

Review warnings particular to Tron: a fee limit above the owner threshold (CAUTION), and a transfer to an account that is not activated, which costs an extra 1 TRX (1,000,000 sun) on mainnet. That is a CAUTION in the review, not a block (section 12.1, item 2). Source: <https://developers.tron.network/docs/paying-for-resources>.

The ABI decoding helpers Tron needs are the same as EVM's. Move them from `shared/.../core/registry/AbiDecoder.kt` into a shared `abi` library both apps can depend on. That is a library move, not a core change.

---

## 11. Mapping the current chains (Phase 2.9)

### 11.1 How each chain maps onto the spec

CAIP-2 references for `bip122`, `solana` and `algorand` are the registry values (section 4). The Bitcoin testnet4 reference is still a placeholder until checked.

| | Bitcoin | EVM | Solana | Algorand |
|---|---|---|---|---|
| App id | `bitcoin` | `evm` | `solana` | `algorand` |
| CAIP-2 | `bip122:000000000019d6689c085ae165831e93` (mainnet), `bip122:<testnet4>` | `eip155:1`, `eip155:10143` (Monad testnet, default), `eip155:143`, `eip155:11155111`, ... | `solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp` (mainnet), `solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1` (devnet) | `algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k` (mainnet), `algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDe` (testnet) |
| SLIP-44 | 0 (mainnet), 1 (testnet) | 60 | 501 | 283 |
| Curve | SECP256K1 | SECP256K1 | ED25519_SLIP10 | ED25519_BIP32 (Ledger-style, `LedgerEd25519.kt`) |
| Paths | per script type, BIP-44/49/84/86 under coin type | `m/44'/60'/*'/0/0`, `m/44'/60'/0'/0/*` | `m/44'/501'/*'` (all hardened) | `m/44'/283'/*'/0/0` |
| Capabilities | MULTI_SCRIPT_ACCOUNTS, WATCH_ONLY_EXPORT, MESSAGE_SIGNING, RAW_PRIVATE_KEY (bitcoin-kmp; aim to drop) | MESSAGE_SIGNING, TYPED_DATA_SIGNING | MESSAGE_SIGNING (off-chain message) | none |
| Request / response UR | `crypto-psbt` | `eth-sign-request` / `eth-signature` | `sol-sign-request` / `sol-signature` | `algo-sign-request` / `algo-signature` |
| Fungible tokens | none | ERC-20 (Lumio balances, custom add; Lensly catalogue) | SPL, Token-2022 (Lensly review; Lumio balances only) | ASA (Lensly review; Lumio does not build `axfer`) |
| NFTs | none | ERC-721/1155 to add | Metaplex to add | ARC-3/69/19 to add |
| Extras | `fee.satPerVb`, `btc.rbf` | `fee.*` EIP-1559, `evm.nonce` | `solana.memo` (not yet built), `fee.cuPrice`, `fee.cuLimit`, durable nonce | `algorand.note` (Lumio sends empty today), `closeTo`, `rekeyTo`, `lease` |
| Preconditions | none | none | TokenAccount (ATA) | OptIn |
| Lumio providers | Esplora (mempool, Blockstream), Electrum (Bitaroo, Emzy, Blockstream, lu.ke, Seth for Privacy), Blockchair (with a key) | Chainlist RPC, custom RPC, Etherscan, Routescan, Blockscout, 3xpl | Solana RPC, custom RPC | Algod |

The EVM contract registry (whitelist, denylist, protocol steps for Uniswap, CoW, bulker: about 20 of the 26 files in `shared/.../core/registry/`) becomes part of the `evm` app. It is EVM review logic, not core.

Monad is not special in the core. Its chain id, RPC endpoints, explorer, and the "default testnet" choice become data in the `evm` app's `NetworkSpec` list and Lumio's default-enabled set. The 2-byte chain id note in `TxSigner.kt` is an EVM app test vector.

### 11.2 Coupling points to remove

From the codebase research (master `fd05a9aa`; line numbers approximate):

| Today | Where | Replace with |
|---|---|---|
| `AddressChain` (removed) | `shared/.../app/AddressBookEntry.kt` | `AddressBookEntry.chain: ChainId?`, address rules from `ChainRegistry` |
| `AccountChain` (removed) | `lumio/.../wallets/WalletAccounts.kt` | `WalletAccount.id` (CAIP-10) |
| `Family` (removed), `NetworkId` | `lumio/.../data/net/NetworkId.kt` | `NetworkId` wraps `ChainId`; namespaced `OpKey` (open) |
| `BulkChain` (removed) | `app/.../ui/home/BulkGenerateScreen.kt` | `BulkTarget(app: ChainManifest, network: NetworkSpec, scriptType)`: registry networks and manifest key scopes |
| `ChainIconSpec` (removed) | `shared/.../ui/components/ChainIcon.kt` | `ChainLogo` and `chainLogo(network)`: the icon resource of a registry network (vault only) |
| `ScannedRequest` + `routeScan` `when` | `app/.../sign/WalletMatch.kt:25,37` | `ChainRegistry.byRequestType` + `RequestHeader.signers` |
| `ScanReply` per-chain branches, `replyOf` if-chain | `shared/.../core/parse/ParserSandbox.kt:40,109` | `ParseOutcome` |
| `ParserWire` tagged union across chains | `shared/.../core/parse/ParserWire.kt` | chain-blind wire carrying app body bytes |
| `SignStep` per-chain variants, `decideBitcoin/Solana/Algorand/Ethereum`, `sign*`, `cancel*` | `app/.../ui/flow/SignFlow.kt:36, ~402-705` | one `Review` -> approve -> sign flow |
| `SignFlow.ports` per-chain methods | `app/.../ui/flow/VaultSignPorts.kt` | `SignerApp` |
| `Prepared`, `BitcoinPrepared`, `SolanaPrepared`, `AlgorandPrepared` | `SignPipeline.kt:68`, `SignFlow.kt` | `ParsedRequest` + `Review` |
| Per-chain review records and entries | `app/.../sign/*ReviewRecord.kt`, `ReviewHistory.kt` | `RecordFacts` |
| Per-chain review screens | `TxReviewScreen`, `BitcoinReviewScreen`, `SolanaReviewScreen`, `AlgorandReviewScreen` | one renderer for `Review` |
| `WalletStore` per-chain sign and derive methods | `app/.../app/WalletStore.kt` (`bitcoinSign`, `solanaSign`, `algorandSign`, bulk generate) | `KeyAccess` + `SignerApp.accountExport` |
| `WalletView` per-chain fields | `app/.../sign/WalletView.kt` | list of `AccountExport` |
| `canonicalAddressOn` `when`, `chainOfAddress` | `shared/.../app/CanonicalAddress.kt` | `AddressCodec` per app |
| `PairingKey` per-kind factories, `PairImport` per-chain checks (removed) | `shared/.../app/export/PairingCodec.kt`, `lumio/.../pair/PairImport.kt` | `PairingPayload.accounts`: one list of `AccountExport` (spec 7.5); `PairImport` checks each entry against its chain's manifest and address rules |
| `SigningSourcePolicy.bitcoinSigningSource` (removed) | `SettingsStore.kt` | per-app setting: `AppSettings.appSettings`, keyed by `SIGNING_SOURCE_SETTING`, which the Bitcoin app declares (`chains/bitcoin/signer/.../BtcPolicies.kt`) |
| `Op` enum union of all chains | `lumio/.../data/provider/Provider.kt` | namespaced `OpKey` |
| ~39 `Family` switches across ~26 Lumio files | `TxFeed`, `TxHistory`, `NativeBalances`, `PairScreens`, `Router`, ... | `ChainRegistry` lookups |
| Derivation paths spelled in several files | `EvmSend.kt`, `AlgorandSend.kt`, `SignPipeline.kt`, `PairImport.kt`, `WalletStore.kt` | `KeyScope` in the manifest, one place |
| `BtcNetwork`, `AlgorandNetwork`, `NetworkMode.holds(BtcNetwork)` | `shared/.../core/btc/BtcNetwork.kt`, `AlgorandRequestReview.kt`, `lumio/.../network/NetworkMode.kt` | `NetworkSpec.testnet` |
| Monad special cases | `EvmChains.kt` (`PRIMARY_TESTNET_ID`), `EvmSettings.kt` (`DEFAULT_ENABLED`), `ChainlistRpc`, `DefaultEndpoints.kt` | data in the `evm` app |
| Token catalogues per chain (`TokenEntry`, `SolanaTokenList`, `AsaCatalog`) | `shared/.../core/registry/`, `core/solana/`, `core/algorand/` | one signed registry keyed by CAIP-19 |
| `TokenCatalog`/`CustomTokenStore` ERC-20 only | `lumio/.../tokens/` | `AssetSource.lookup` per app |

Suggested order for 2.9, one small PR at a time: (1) `:core:model` ids, assets, review model; (2) `ChainRegistry` and manifests for the four apps, no behaviour change; (3) `AddressCodec` and address book on `ChainId`; (4) parser sandbox on `ParseOutcome`; (5) one review renderer, moving one chain per PR (Algorand first, as it is smallest); (6) `KeyAccess` and the sign flow; (7) Lumio `ChainId` and `OpKey`; (8) Lumio `WalletApp` per chain; (9) remove the enums.

Feature gaps the refactor exposes but does not have to close: Lumio building ASA transfers and opt-ins, SPL transfers (not confirmed in Lumio), Algorand notes, Solana memos, NFTs on all chains.

---

## 12. Design decisions and unverified facts

Design decisions (2026-11-18):

1. **Vendor manifest signing.** Third-party chain apps carry a vendor-signed manifest and a human review record before they ship (section 2.5).
2. **Envelope for existing chains.** No. Bitcoin, EVM, Solana and Algorand keep only their current UR types. `x-airvault-sign-request` is for new chains.
3. **Pairing type name.** Rename to `x-airvault-pairing` as a hard switch. The old name is not accepted, and devices pair again.
4. **Descriptor-driven clear signing.** Adopt ERC-7730 v1 descriptors, validated in CI. Every hand-written EVM protocol step migrates to descriptors before the first AirVault release. ERC-7730 stewardship moved to the Ethereum Foundation's Trillion Dollar Security Initiative on 2026-05-12 (<https://blog.ethereum.org/2026/05/12/clear-signing-announcement>).
5. **Lumio runtime plugins.** No. Chain apps are included at compile time only, in both apps.
6. **Code generation.** No generator. New chains copy `chains/_template/`; EVM chains are data in the `evm` app.
7. **Ordinals and Runes protection.** Lumio flags risky inputs from an indexer, and Lensly warns on them (section 5).

### 12.1 Phase 2.9 Fact-Check Decisions (2026-11-19)

These decisions close the open questions raised by the fact check of this document. Sections above refer to them by item number.

1. **ARC-19 and ARC-69.** Support both read-only. ARC-89 becomes read-only once it is Final (section 5.2).
2. **Tron activation fee.** An unactivated recipient costs 1 TRX on mainnet. Show it as a CAUTION in the review, not a block (section 10.2).
3. **Litecoin MWEB.** Lensly refuses PSBT inputs that use MWEB in 2.9. MWEB support is out of scope (section 10.1).
4. **Memo and tag requirements.** No global rule for Stellar, Solana or Algorand. Required fields are set per address-book entry (section 6.3).
5. **OP_RETURN.** Follow the Core v30 default for relay. Lensly warns above 80 bytes of data (section 6.1).
6. **PSBT wire names.** Existing chains keep their current UR types. Lumio keeps writing `crypto-psbt` (section 7.2), and Lensly also reads the registry's current name `psbt`, answering under the name each request used.
7. **ERC-7730 version.** Keep the v1 descriptor decision, pinned to 2.1.0 (Active). Do not adopt 3.0.0-next (Draft).
8. **Monad testnet.** Chain ID 10143 stays the default testnet, as the project uses it.

BEP-20 (<https://github.com/bnb-chain/BEPs/blob/master/BEPs/BEP20.md>) does not define `getOwner`. A `getOwner` function is not a BEP-20 requirement.

MetaMask Snaps has no coin-type-60 restriction. The `permittedCoinTypes` caveat accepts any non-negative coin type except a forbidden list, whose contents were not checked (<https://github.com/MetaMask/snaps/blob/main/packages/snaps-rpc-methods/src/restricted/caveats/permittedCoinTypes.ts>). MetaMask SIP-14 "Dynamic Permissions" has status Draft (<https://github.com/MetaMask/SIPs/blob/main/SIPS/sip-14.md>).

Carried-over **[UNVERIFIED]** items from research (do not rely on them until checked against a primary source):

- Ledger's BIP32 enforcement blog.
- Ledger Live coin-module structure (CurrencyBridge, AccountBridge, `coin-framework` types).
- Keystone firmware per-chain layout (`rust/apps/<chain>`); `eth-sign-request` definition (not in BCR-2020-006).
- Address formats: base58check (Bitcoin legacy), Litecoin, CashAddr (Bitcoin Cash), Tron, Solana on-curve check, Stellar strkey, SS58, Cosmos bech32, TON, Cardano.
- XRPL issued currencies, trust lines, `RequireAuth`; XRPL and Stellar fee and sequence fields.
- Stellar trustlines; which exchanges require memos on Stellar, Solana, Algorand.
- Tron TRC-20 energy cost, TRC-721 receiver-hook value, memo size.
- Cosmos tx memo; ibc-go denom hashing; Hedera, EOS, TON memo formats.
- Litecoin MWEB outputs.
- Ordinals inscription primary spec.

Codebase items not verified by the research: Lumio SPL transfer support, Lumio `BtcSend` PSBT-building internals, `SigningSourcePolicy` scope beyond Bitcoin, the full count of chain switches in tests.
