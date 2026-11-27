# AirVault Offensive Audit Playbook

Repeatable playbook for a project-run offensive assessment of AirVault. Paste the whole file into an agent session, or give it to a human lead. The revision history is at the end.

## 1. Purpose and How to Use

AirVault has two Android apps in one repository:

- **Lensly** (`app.lensly`, module `:app`) is the offline vault and signer. It holds the BIP39 mnemonic and signs. It requests no INTERNET permission.
- **Lumio** (`app.lumio`, module `:lumio`) is the online companion. It is watch-only, builds requests, and broadcasts. It never holds a wallet key.

The question this run answers first: **can an attacker who holds the phone get the mnemonic?** Rank every finding by how much it moves that answer, and by its effect on funds.

Use this file as the task for a fresh orchestrator session, or hand it to a human lead who runs the same roles. If the runner has no subagents, run the roles in sequence under the same context rules.

In this file, "docs N.N" means section N.N of `docs/creating-airvault-apps.md`. "Item N" means threat item N in `shared/src/main/java/app/lensly/core/vault/FailureDelay.kt`.

| Input | Required | Meaning |
|---|---|---|
| `REPO` | Yes | Clean checkout of the repository. Read-only for the run (section 2). |
| `COMMIT` | Yes | Commit under test. Record `git rev-parse HEAD` and `git status --porcelain`. A dirty tree gets a SHA-256 of its diff. |
| `RUN_DATE` | Yes | Run date, `yyyy-mm-dd`. |
| `REPORT_DIR` | Yes | Directory outside the repository, chosen by the runner. |
| `DEVICES` | No | Test devices or emulators designated by the project: model, Android version, patch level, root and StrongBox state, lock screen type. Without them, device disciplines are recorded as coverage gaps. Before dispatch, record installed applicationIds and whether a vault exists on every designated device. If a test would displace an existing vault, declare a Gap and use a clean device. |
| `VARIANTS` | No | `debug`, `release`, `benchmark`. Default: `release` built from `COMMIT`, plus `debug` for comparison. |
| `SCOPE` | No | `full` (default), or the named disciplines from section 6. |
| `BUDGET` | No | Time and usage ceiling. Section 4.4 sets the wrap-up points. |

Output: `REPORT_DIR/audit-reports/<RUN_DATE>-<commit>.md`, where `<commit>` is the first 8 characters of `COMMIT`. Write the report in the template of section 8. Working files (ledger, briefs, transcripts, PoCs) go to `REPORT_DIR/audit-runs/<RUN_DATE>-<commit>/`. Never commit reports or run files to the repository unless the project requests it.

Start checklist:

- [ ] Record `RUN_DATE`, `COMMIT`, repository state, tool versions, and device facts (section 6.1, Stage 0).
- [ ] Confirm section 2 authorizes this run. If it does not, stop and write one paragraph that says so.
- [ ] Run the seed check (section 5.3) and the discovery commands (section 5.1).
- [ ] Open the ledger (section 4.3). Build the requested variants, and record each APK's SHA-256.
- [ ] On memory-constrained runners (4 GB or less, no swap), build and test with `-Dorg.gradle.jvmargs=-Xmx1792m -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --no-daemon`. `--offline` needs a warm cache: when a test dependency such as roborazzi-compose is not cached, test runs need network.
- [ ] Dispatch the disciplines (section 6) within the concurrency cap (section 4.2).

## 2. Rules of Engagement

This playbook runs only when the project starts it, against the project's own apps and project-designated test devices. Never target anyone else's device, account, wallet, endpoint, or person.

| Rule | Requirement |
|---|---|
| Targets | Lensly and Lumio, built from `COMMIT`, plus project-designated devices and emulators. Nothing else. |
| Mnemonics | Throwaway test mnemonics, generated for this run and never reused. Reports show at most the first and last word, or a hash. |
| Funds | Never real funds, and never a mainnet broadcast. Broadcast tests use a public testnet and a faucet-funded throwaway key. A signing defect stops at the signed bytes. |
| Network | Public testnet RPC only, at a polite rate. No scanning. No load against third parties. |
| Repository | Read-only. PoCs go on a scratch branch `audit/<RUN_DATE>-<commit>` in a separate worktree, which is never pushed unless the project requests it. No commits to the default branch, no tags, no release or workflow dispatch. |
| Secrets | Never read, request, print, or copy release keystores, CI secrets, signing passwords, or API keys. |
| Signing | Test APKs are signed with a throwaway key made for this run. The release key is never used. |
| Devices | Return each device to its prior state, or record every change: root, developer options, adb authorization, Keystore state, clock. Factory-reset rooted devices afterward. |
| People | No social engineering of real people. The coerced-user scenario (S5) uses the project's test operator in the coercer role. |
| Disclosure | Results stay with the project. Label issues from the repository's set: `scope:lensly`, `scope:lumio`, `scope:common`; `app:bitcoin`, `app:ethereum`, `app:solana`, `app:algorand`; `difficulty:easy`, `difficulty:medium`, `difficulty:hard`; `area:core`, `area:chains`, `area:abi`, `area:shared`, `area:benchmark`, `area:security`, `area:build`, `area:ci`. Send Critical and High findings to the project privately before any public issue. |
| Stop | Stop at once if a test touches a real vault, key, fund, or person. Log the stop as a process incident. |

## 3. Adversary Model

### 3.1 Attacker Profile

An extremely skilled, persistent, well-funded team with unlimited time. The run's budget limits the run, not the attacker. Tools: static analysis (jadx, apktool, Ghidra, CodeQL, Semgrep), dynamic analysis (adb, Frida and objection on non-production builds or rooted test devices), fuzzers (Jazzer, libFuzzer, AFL++), forensic imaging where the lab has it, software timing measurement, and scripted social engineering of the user flow. The attacker reads the repository, its docs, its threat-model comments, and its tests. Obscurity is not a control in this model.

Out of scope unless the project adds it: hardware side channels, fault injection, and TEE or StrongBox internals. Record them as assumptions (3.2).

### 3.2 Primary Scenario: Physical Possession of the Phone

The attacker holds the phone and wants the mnemonic. Test every variant and report each one.

| Variant | Attacker State | Question |
|---|---|---|
| P0 | Off or locked, no secret known | Does anything from the vault leave the device without an unlock? |
| P1 | Locked, knows the device credential | Does the credential alone reach the mnemonic? Can a newly enrolled biometric reach it? |
| P2 | Unlocked, knows neither password nor gesture | Does any route show vault data, accept a try, or leak a secret through the UI, IME, clipboard, accessibility, recents, or screen capture? |
| P3 | Unlocked, knows the entry gesture | Is a wrong gesture counted? Does the gate reveal that a hidden area exists? |
| P4 | Rooted later: file access, reboots, clock and boot-count changes | Does rollback, a clock change, or a storage edit reset the failure counter? |
| P5 | Holds an older copy of app storage and Keystore state | Does restoring an older state give more guesses? |
| P6 | Process access on a rooted or debuggable build | Can the mnemonic, a password, or a derived key be read from memory, logs, or dumps? |

Goals, in order: the mnemonic or a private key; the App Password; a signature on a request the owner did not review on Lensly.

Assumptions the team does not control. Test each one on every device model in scope, and trust none of them:

- The Keystore (KeyMint) key is non-exportable, and a deleted key stays deleted after an attacker restores the Keystore database. `FailureStore.kt` names this as a residual risk.
- Sandboxing, SELinux, and Binder hold on an unrooted device.
- Argon2id, AES-256-GCM, HMAC-SHA-256, SHA-2, secp256k1, and Ed25519 are not broken.
- Release builds contain no debug code, and no debug switch changes release behavior.

### 3.3 Secondary Scenarios

| ID | Scenario | Capability | Goal |
|---|---|---|---|
| S1 | Hostile or compromised Lumio | Sends any request, label, or pairing | Get a signature the owner did not review; cause erase or lockout; expose addresses |
| S2 | Hostile QR | Presents any code to the camera | Crash or hang a decoder; reach parser bugs in the sandbox; trigger erase |
| S3 | Malicious registry or update package | Supplies a package through any update input | Change names, decimals, or networks; roll back; block signing |
| S4 | Supply chain and build | Compromises a dependency, action, tool, or the release path | Ship a modified APK that the owner installs |
| S5 | Coerced user | Forces an unlock with the Duress Password | Make the decoy indistinguishable; show that a decoy exists |
| S6 | Shoulder surfing, screen capture, app switcher | Watches the screen or the thumbnail | Read the mnemonic or a password |
| S7 | Clipboard | Reads or writes the clipboard | Read copied secrets; inject an address into a field |
| S8 | Accessibility service | Reads the window tree and sends input | Read or type secrets |
| S9 | Malicious keyboard (IME) | Receives every committed character | Read passwords, passphrases, or mnemonic words |
| S10 | Other installed apps | Normal permissions, intents, shared storage | Reach exported components; inject intents; read shared files |

## 4. Orchestration Model

The audit fills context fast, so the orchestrator stays small. It holds plans, briefs, short returns, and the ledger. It never holds source code.

### 4.1 Roles

| Role | Count | Job | Context Rule |
|---|---|---|---|
| Orchestrator | 1 | Plans, dispatches, dedupes, keeps the ledger, writes the report | Reads file lists and the fixed templates only. Never reads source or full PoC logs. |
| Discovery worker | 1 to 3 | Maps the surface (5.1) and extends the seed (5.3) | Read-only. Returns a file list and a table. |
| Discipline worker | One per discipline (6) | Hypothesizes, designs, and executes attacks in one area | Writes PoCs in its own scratch area. Returns templates only. |
| Validator | One per candidate | Reproduces one candidate from its steps | Never sees the discoverer's reasoning. Works from a clean checkout. |
| Reporter | Orchestrator or one worker | Assembles the report from section 8 | Uses section 8 only. |

Model tiers: use the cheapest tier that can do the job. Escalate one tier at a time after a failed validation, and name the failure in the escalation.

### 4.2 Dispatch Rules

- Concurrency cap: at most 5 subagents running at once. Queue the rest, and dispatch independent work in one batch.
- Each worker gets its section 6 checklist verbatim, plus the brief below, and nothing else.
- Dedupe by root cause before validation. Validators are different workers, with no shared scratch files.
- A worker that finds nothing still returns its coverage table, listing what it tried.
- A blocked worker returns `BLOCKED` with the exact blocker. Re-scope the work; do not retry blind.
- Every brief writes `docs/auditing-airvault.md` before each section number. Never cite a bare number.
- Validator briefs cite the validation rule as "section 7.1, item 2", never as a shorthand number.

Worker brief:

```text
ROLE: <discipline or validator>
GOAL: <one sentence>
COMMIT: <sha>    REPO (read-only): <path>    SCRATCH: <path>
SCOPE: <paths and attack ideas>    OUT OF SCOPE: <list>
RULES: section 2 of the playbook, verbatim
BUDGET: <tool calls or minutes>; stop when the goal is met or blocked
RETURN: one Finding Template (8.1) per Candidate, and one Coverage table for everything else.
  Max 40 lines per finding, 120 lines in total. Report anything that cost time or caused confusion.
```

### 4.3 Ledger

`REPORT_DIR/audit-runs/<RUN_DATE>-<commit>/ledger.md` holds one row per candidate: ID, area, status, worker, validator, notes. Statuses: `Candidate`, `Reproducing`, `Confirmed`, `Rejected`, `Hypothesis`, `Duplicate`. Only `Confirmed` enters the findings. `Hypothesis` goes to the appendix. `Rejected` keeps its reason and moves to Checked and Held.

### 4.4 Budget and Wrap-Up

| Point | Action |
|---|---|
| 60% of budget | Start no new discipline workers. Queue the validators. |
| 85% of budget | Tell every running worker to finish its current PoC, save its scratch area, and return now. Validate what has returned. |
| 95%, or the usage ceiling | Write the report now. Mark unfinished work as coverage gaps with its last state. Keep the ledger and scratch areas. |
| Repeated tool or connection failures | Treat as 95%. Log the failures in the tool summary. |

A partial report is valid. A report that claims coverage it did not run is not.

## 5. Attack Surface Map

This map is a seed. Discovery workers extend it on every run, drop dead entries, and list each change under Proposed Changes in the report.

### 5.1 Discovery Commands

Read-only, from the repository root:

```bash
git rev-parse HEAD && git status --porcelain
tokei --exclude '*.md' .
rg -n 'exported|isolatedProcess|debuggable|allowBackup|dataExtractionRules|process=' --glob '*Manifest*.xml' .
rg -n 'buildTypes|isMinifyEnabled|isDebuggable|profileable|signingConfig' app/build.gradle.kts lumio/build.gradle.kts
rg -n 'external fun|System.loadLibrary|externalNativeBuild' --glob '*.kt' --glob '*.kts' .
find . -name '*.so' -not -path '*/build/*'
rg -n 'KeyGenParameterSpec|AndroidKeyStore|KeyGenerator|KeyPairGenerator' shared app lumio core chains
rg -n 'Argon2|PBKDF2|SecureRandom|kotlin\.random|java\.util\.Random' shared core chains app lumio abi
rg -n 'FLAG_SECURE|setRecentsScreenshotEnabled|ClipboardManager|setPrimaryClip|Log\.[dviwe]|println' app shared core chains lumio
rg -n '^on:|pull_request_target|issue_comment|workflow_run|uses: ' .github/workflows
gh issue list --label area:security --state closed --limit 200
gh pr list --label area:security --state merged --limit 200
```

For each APK in `VARIANTS`, run `apkanalyzer manifest print`, `apkanalyzer dex packages`, `apkanalyzer files list`, `aapt2 dump badging`, `apksigner verify --print-certs`, and `shasum -a 256`. Check `exported`, `debuggable`, `allowBackup`, the permission list, and the native libraries.

The release APKs are unsigned by design: the tag workflow signs them. `apksigner verify` on the unsigned artifact fails with `Missing META-INF/MANIFEST.MF`, which is correct, not a finding. Sign a copy with a throwaway key in the scratch area and verify that copy with `--min-sdk-version 24` (the command the tag workflow uses), and check the unsigned artifact with `zipalign -c` and `apkanalyzer`. `apkanalyzer` runs from the SDK's `cmdline-tools`, so set `ANDROID_HOME` first.

### 5.2 Terms the Code Uses

| Term | Meaning | Look At |
|---|---|---|
| App Password | Password of the real vault | `shared/src/main/java/app/lensly/ui/gate/`, `shared/src/main/java/app/lensly/core/vault/Vault.kt` |
| Duress Password, Decoy Vault | Password and vault for the decoy | `shared/src/main/java/app/lensly/ui/navigation/GalleryGate.kt`, `app/src/main/java/app/lensly/app/DecoyVault.kt` |
| Failure Delay, erase count | Wait after wrong tries; erase after N wrong tries | `shared/src/main/java/app/lensly/core/vault/FailureDelay.kt`, `shared/src/main/java/app/lensly/core/vault/FailureStore.kt` |
| Entry gesture | Opens the hidden area from the cover screen; kept outside the vault | `shared/src/main/java/app/lensly/app/EntryGestureStore.kt`, `shared/src/main/java/app/lensly/ui/camera/EntryGesture.kt` |
| Cover screen, hidden area | The front UI, and the routes behind the gesture | `shared/src/main/java/app/lensly/ui/CoverActivity.kt`, `shared/src/main/java/app/lensly/ui/navigation/HiddenArea.kt` |
| Shown equals signed | The review text matches the signed bytes | Per-chain review files (5.3, F) |

### 5.3 Seed High-Value Targets

Paths are repository-relative. Check them with the seed check, then extend the list. Use the `rg -o` seed check as the authority for path existence; prose sub-lists are naming aids. Item numbers refer to `FailureDelay.kt`.

```bash
rg -o '[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+\.(kt|kts|xml|yml|sh|pro|c|txt|aidl|toml|md|properties)\b' docs/auditing-airvault.md | sort -u | while read -r p; do [ -e "$p" ] || echo "MISSING: $p"; done
```

- A. Vault and KDF: `shared/src/main/java/app/lensly/core/vault/Vault.kt` (create, unlock, erase), `VaultLayout.kt` (format version 5, two slots, photo directories, slot MAC), `VaultHeader.kt` (fields, KDF parameters, flags), `VaultKeystore.kt` (hardware wrap key, user authentication, biometric invalidation, StrongBox fallback, `k2`), `KeystorePolicy.kt`, `KeystoreBackend.kt`, `Aead.kt` (AES-256-GCM), `Argon2Kdf.kt`, `KdfCalibration.kt`, `VaultSession.kt`, `WalletFolder.kt`, `shared/src/main/java/app/lensly/core/vault/MediaEnvelope.kt`, `BackupExclusion.kt`. Test double: `shared/src/testFixtures/java/app/lensly/core/vault/MemoryKeystore.kt`.
- B. Failure counter, decoy, erase: `FailureDelay.kt` (its header lists threat items 1 to 17; each is a claim to test), `FailureStore.kt` (two copies, per-sequence HMAC keys, key deletion, residual risks), `PasswordPolicy.kt`, `app/src/main/java/app/lensly/app/DecoyVault.kt`, `app/src/main/java/app/lensly/ui/settings/FailureCapScreen.kt`, `shared/src/main/java/app/lensly/ui/gate/PasswordBuffer.kt`, `UnlockScreen.kt`, `CreatePasswordScreen.kt`, `shared/src/main/java/app/lensly/ui/flow/AppPasswordCheck.kt`.
- C. Gate, cover screen, gesture: `shared/src/main/java/app/lensly/ui/SecureWindow.kt`, `shared/src/main/java/app/lensly/ui/navigation/CoverNavHost.kt`, `GalleryGate.kt`, `LockPolicy.kt`, `shared/src/main/java/app/lensly/ui/flow/EntryGestureChangeFlow.kt`, `EntryGestureChangePorts.kt`, `shared/src/main/java/app/lensly/ui/camera/PhotoStore.kt`, `CameraController.kt`, `lumio/src/main/java/app/lumio/EntryGestureRoutes.kt`, `lumio/src/main/java/app/lumio/LumioVault.kt`.
- D. Mnemonic and keys: `shared/src/main/java/app/lensly/core/wallet/Mnemonic.kt` (words are a `CharArray`; each `get` yields a short-lived `String`; `text()` returns `CharArray`; `deriveSeed(passphrase: String)`), `EntropyMixer.kt` (dice input), `HdWallet.kt`, `Slip10Ed25519.kt`, `EvmKey.kt`, `Ed25519Signer.kt`, `chains/evm/signer/src/main/kotlin/app/lensly/core/evm/Secp256k1.kt` (web3j signer, no production caller; bitcoin-kmp `WalletKeyAccess` signs), `PrivateKey.kt`, `LedgerPassphrase.kt`, `WalletKeyAccess.kt`, `SingleKeyAccess.kt`, `core/signer-api/src/main/kotlin/app/airvault/core/signer/KeyAccess.kt`, `ScopedKeyAccess.kt`. Screens and flows: `app/src/main/java/app/lensly/ui/wallet/DiceEntryScreen.kt`, `ShowSecretScreen.kt`, `MnemonicQuiz.kt`, `PassphraseGate.kt`, `ShredScreen.kt`; `app/src/main/java/app/lensly/ui/flow/RevealFlow.kt`, `ShredFlow.kt`, `AccountExportScreen.kt`; `docs/recover-with-ledger.md`.
- E. Transport and sandboxes: `shared/src/main/java/app/lensly/core/parse/ParserSandbox.kt` (the trusted side re-checks every result), `ParserSandboxService.kt`, `ParserWire.kt`, `shared/src/main/aidl/app/lensly/core/parse/IParserSandbox.aidl`, `shared/src/main/aidl/app/lensly/core/qr/IQrDecoder.aidl`, `core/signer-api/src/main/kotlin/app/lensly/core/parse/ParserLimits.kt`, `shared/src/main/java/app/lensly/core/qr/QrDecodeService.kt`, `QrDecodeClient.kt`, `BinderQrDecodeTransport.kt`, `QrDecodeLimits.kt`, `LumaFrames.kt`, `shared/src/main/java/app/lensly/core/ur/UrScanner.kt`, `core/signer-api/src/main/kotlin/app/lensly/core/ur/Cbor.kt`.
- F. Chain parsers and review:
  - Bitcoin: `chains/bitcoin/signer/src/main/kotlin/app/airvault/chains/bitcoin/signer/BtcPsbt.kt`, `BitcoinRequestParser.kt`, `BtcReview.kt`, `BtcReviewWarnings.kt`; `shared/src/main/java/app/lensly/core/btc/BtcUnsignedPsbt.kt`.
  - EVM: `chains/evm/signer/src/main/kotlin/app/airvault/chains/evm/signer/UnsignedTx.kt`, `EvmRequestParser.kt`, `EvmReview.kt`, `EvmTypedReview.kt`, `TypedData.kt`, `TypedDataParser.kt`; `chains/evm/signer/src/main/kotlin/app/lensly/core/evm/TxSigner.kt`, `MessageSigner.kt`, `TypedDataSigner.kt`; `abi/` (pure JVM ABI decoder).
  - Off-chain venues (Hyperliquid and similar): `chains/evm/signer/src/main/kotlin/app/lensly/core/registry/HyperCoreBridge.kt`, `OffChainDomains.kt`.
  - Solana: `chains/solana/signer/src/main/kotlin/app/airvault/chains/solana/signer/SolanaMessageParser.kt`, `SolanaRequestParser.kt`, `SolanaReview.kt`; `shared/src/main/java/app/lensly/core/solana/SolanaTransaction.kt`.
  - Algorand: `chains/algorand/signer/src/main/kotlin/app/airvault/chains/algorand/signer/AlgorandRequestParser.kt`, `AlgorandRequestReview.kt`; `shared/src/main/java/app/lensly/core/algorand/AlgoSignedTxn.kt`.
  - Review and signing: `app/src/main/java/app/lensly/app/sign/SignPipeline.kt`, `ReviewRecord.kt`; `app/src/main/java/app/lensly/ui/sign/ReviewScreen.kt`, `HoldToSign.kt`; `shared/src/main/java/app/lensly/core/vault/ReviewPool.kt`; `core/model/src/main/kotlin/app/airvault/core/model/Review.kt`.
  - Address Book: `app/src/main/java/app/lensly/app/AddressBook.kt` (a sealed vault record), and the names the review shows from it (`app/src/main/java/app/lensly/ui/sign/ReviewNames.kt`).
  - Manifests (coverage and unknown-call policy): `chains/evm/common/src/main/kotlin/app/airvault/chains/evm/EvmManifest.kt`, `chains/bitcoin/common/src/main/kotlin/app/airvault/chains/bitcoin/BitcoinManifest.kt`, `chains/solana/common/src/main/kotlin/app/airvault/chains/solana/SolanaManifest.kt`, `chains/algorand/common/src/main/kotlin/app/airvault/chains/algorand/AlgorandManifest.kt`, `core/signer-api/src/main/kotlin/app/lensly/core/registry/UnknownCallPolicy.kt`, `chains/evm/signer/src/main/kotlin/app/lensly/core/registry/ProxyPolicy.kt`.
  - Bulk signing: `shared/src/main/java/app/lensly/core/wallet/BulkCap.kt`, `BulkPolicy.kt`, `BulkRun.kt`; `app/src/main/java/app/lensly/app/SettingsStore.kt` holds the saved cap.
- G. Registry and updates: `shared/src/main/java/app/lensly/core/registry/update/RegistryUpdate.kt`, `PackageFormat.kt`, `RegistryPackage.kt`, `RegistryCatalog.kt`, `TrustedKey.kt`; `shared/src/main/java/app/lensly/core/registry/RegistryLoader.kt`; `app/src/main/java/app/lensly/app/RegistryHolder.kt`; `shared/src/main/assets/registry/` (bundled registry); `tools/registry-pack/` (package builder), `tools/registry-pack/sign-candidate.sh`; `tools/registry-data/` (source data).
- H. Lumio: `lumio/src/main/AndroidManifest.xml`, `lumio/src/main/res/xml/receive_share_paths.xml` (FileProvider paths), `lumio/src/main/java/app/lumio/pair/PairedWallets.kt`, `shared/src/main/java/app/lensly/app/export/PairingCodec.kt`, `PairingExport.kt`, `app/src/main/java/app/lensly/app/export/AccountExport.kt`, `BrotliEncoder.kt`, `lumio/src/main/java/app/lumio/pair/PairAddressBooks.kt`, `lumio/src/main/java/app/lumio/send/PaymentCode.kt`, `lumio/src/main/java/app/lumio/send/solana/SolanaSend.kt`, `lumio/src/main/java/app/lumio/send/algorand/AlgorandSendRoute.kt`, `lumio/src/main/java/app/lumio/data/rpc/RpcPlugins.kt`, `lumio/src/main/java/app/lumio/ui/scan/LumioScanScreen.kt`, `lumio/src/main/java/app/lumio/ui/kit/ClearAirField.kt`, `app/src/main/java/app/lensly/ui/flow/PairWalletScreen.kt`.
- I. Native: `app/src/main/cpp/brotli_jni.c`, `app/src/main/cpp/CMakeLists.txt` (pins the upstream Brotli SHA-256), `app/src/main/cpp/brotli/` (vendored copy), and every `.so` in the APK.
- J. Manifests, build, release, CI: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `shared/src/main/AndroidManifest.xml`, `app/build.gradle.kts` (release R8; `benchmark` is debug-signed, not minified, and profileable), `lumio/build.gradle.kts`, `app/proguard-rules.pro`, `lumio/proguard-rules.pro`, `gradle/verification-metadata.xml`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/release.yml` (tag signing, attestation), `ci.yml`, `nightly.yml`, `comment-run.yml` and `codeql-command.yml` (`issue_comment` triggers), `master-red.yml` (`workflow_run` trigger), the rest of `.github/workflows/` (`cache-cleanup.yml`, `codeql.yml`, `dependency-graph.yml`, `record-goldens.yml`), `scripts/ci/check-manifest.sh` (guards merged debug manifests only), `scripts/ci/fuzz-nightly.sh`, `scripts/ci/shards.txt`, `docs/releasing.md`.
- K. Debug surfaces (must be absent from release): `app/src/debug/AndroidManifest.xml`, `app/src/debug/java/app/lensly/ui/gate/GatePreviewActivity.kt`, `app/src/debug/java/app/lensly/ui/wallet/WalletPreviewActivity.kt`, `app/src/debug/java/app/lensly/ui/qr/QrPreviewActivity.kt`, `shared/src/debug/java/app/lensly/DebugLaunch.kt` (`debugHidden` extra), `lumio/src/debug/AndroidManifest.xml`, `lumio/src/debug/java/app/lumio/DebugPairFixture.kt`, `lumio/src/debug/java/app/lumio/DebugDemoSeed.kt` (`debugDemoSeed` extra), `lumio/src/debug/res/xml/network_security_config.xml`.
- L. Claims to test: `docs/creating-airvault-apps.md` sections 8.1 to 8.7, and 12 (treat every `[UNVERIFIED]` marker as a hypothesis to resolve); `README.md` (the offline claim); the header comments of `FailureDelay.kt` and `FailureStore.kt`.

### 5.4 Known Checks to Run First

These are checks, not findings. Close each one as Held, Confirmed (with a finding), or Gap.

- [ ] Release merged manifest and DEX: no debug-only component, `debugHidden` handler, or preview activity. `check-manifest.sh` guards only the debug merges. The release merge is checked by the `manifest` step of `run-step.sh` and by the signed-APK check in `release.yml`; both must reject `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`. Test each by adding the permission to a copy of the merged release manifest and confirming each check fails on the copy.
- [x] Fuzz coverage, Held (#762): `fuzz-nightly.sh` lists the five targets and the gate's fuzz step replays the whole `app.lensly.fuzz` package, `AlgorandRequestFuzzTest` included, and the nightly run fuzzes each for 15 minutes. `scripts/ci/fuzz-nightly.sh --list` prints all five. The Algorand request parser is no longer seed-replay-only.
- [ ] Fuzz cache: `fuzz-nightly.sh` must pass `--no-build-cache` to both Gradle calls. With the build cache on (`org.gradle.caching=true`), a cached fuzz test task is served `FROM-CACHE`, counts as a pass, and fuzzes nothing. Check every nightly log for a `Done N runs` line per target before reporting fuzz coverage. To test a regression, run `JAZZER_FUZZ=1 ./gradlew :shared:cleanTestDebugUnitTest :shared:testDebugUnitTest --tests app.lensly.fuzz.CborFuzzTest -PfuzzLane -PfuzzMaxSeconds=20` twice with no cache flag. The second run prints `FROM-CACHE` in about 2 seconds, with no `Done N runs` line.
- [ ] On-disk names: `VaultLayout.kt` names photo directories and key files `real` and `decoy`. Check whether a file-system image shows which vault exists, and whether the decoy's footprint matches the real vault's.
- [ ] Photo capture with no vault and with a vault: the outcome and the message must match in both states. Photos taken before setup are kept in a pending pair (`PendingPhotoKey.kt`). Take a photo before creating a vault and after, and compare the result and the message, because any difference shows whether a vault exists.
- [ ] Backup exclusion: an APK whose merged manifest allows backup refuses to create a vault (`BackupExclusion.kt`, `Vault.refusal()`).
- [ ] Header binding: flip each `VaultHeader.kt` field (version, KDF parameters, salt, creation time, flags, key security level, and the MAC) and confirm that each flip erases the vault before a password is checked (AV-007, fixed in #746; `VaultHeaderBindingTest` covers each field). Run it against the commit under test.
- [ ] Clock and boot count: items 4 to 10 and 16, each with a root-level clock or boot-count test on a test device.
- [ ] Password result signature: item 12 says a passing try saves once more than a failing try. Measure whether an observer can tell right from wrong, or real from decoy.
- [ ] Mnemonic strings: measure how long `String` copies of words and the passphrase survive after `wipe()`. Use a heap dump on a debuggable test build and a memory scan on a rooted test device.
- [ ] Biometric and credential changes: `VaultKeystore.kt` disables biometric invalidation. Test what enrollment, a lock-screen change, or credential removal does, and whether it reaches the erase path (item 15).
- [ ] Map keys from 1000 are refused like any other unknown key, so they cannot change the review or the signed bytes.
- [ ] Algorand: `rekeyTo` and `assetSender` (clawback) are refused; `closeTo` warns; a group warns that the other transactions in it are not shown.
- [ ] EVM transaction types `0x03` (blob) and `0x04` (EIP-7702 authorization list) are fully shown or refused.
- [ ] Bitcoin: legacy inputs are checked against the full previous transaction, and sighash types other than `SIGHASH_ALL` are refused or labeled.
- [ ] Solana: accounts from address lookup tables appear with the table and index, and the review warns that they cannot be checked offline.
- [ ] Emulator checks in `KeystorePolicy.kt` and `SecureWindow.kt` never relax a control in a release build, including on a rooted device with spoofed build fields.
- [ ] `comment-run.yml` and `codeql-command.yml`: what they execute, with which token, and which secrets they can read.
- [ ] `release.yml`: the master-reachability guard runs before the keystore secrets are read.
- [ ] Accessibility and clipboard: record what a test accessibility service reads on each vault screen, and confirm no secret reaches the clipboard. Do not assume `FLAG_SECURE` covers accessibility.

## 6. Attack Disciplines

Each discipline has a goal, a checklist, and attack ideas. For each idea, hypothesize the attack, design a test, execute it, and record one result: Held, Confirmed, Hypothesis, or Gap. "Not tried" is always a Gap, never Held.

### 6.1 Physical-Possession Mnemonic Extraction

Goal: find a working path from the phone (each variant in 3.2) to the mnemonic, or show that none exists within the budget. Work the stages in order, and record the ones that held.

#### Stage 0: Triage and State Capture

- [ ] Record model, Android version, patch level, KeyMint level, StrongBox presence, lock screen type, enrolled biometrics, root and adb state, and the APK SHA-256.
- [ ] Record vault file names, sizes, and times at creation, after one wrong try, after a right unlock, and after a restore.

#### Stage 1: Locked Device and Boot State (P0)

- [ ] After a reboot and before the first unlock, app storage must be unreadable (file-based encryption). Record what becomes readable after the first unlock, and by what means.
- [ ] With USB debugging already enabled and authorized, run `adb backup` against the release build. Expected: no app data.
- [ ] Check file and directory modes for the vault and for the `noBackupFilesDir` registry directory. Nothing from the vault shows on notifications, the app switcher, or the lock screen.

#### Stage 2: Known Device Credential (P1)

- [ ] With the credential known, the gate reveals no vault data.
- [ ] A biometric enrolled with that credential reaches nothing beyond the hardware-key step. Test the 30-second re-authentication window named in the Lensly manifest comment.

#### Stage 3: Unlocked Device, No Passwords (P2, P3)

- [ ] Walk every route reachable from `CoverNavHost.kt`. Each route that shows vault data without a password is a candidate.
- [ ] Gesture: measure the search space, check whether wrong gestures count toward erase, check for rate limits, and check whether the gate reveals a hidden area.
- [ ] Password entry: check `SecureKeyboard.kt` and every system-keyboard path, including logs, saved state, clipboard, autofill, and suggestions. Repeat with a logging IME (S9).
- [ ] App switcher: read the recents thumbnail on API 31 and 32. `minSdk` is 31, so check the API guard in `SecureWindow.kt`.
- [ ] Capture: try `screencap`, `screenrecord`, and a `MediaProjection` recorder on every vault screen, dialog, and popup.
- [ ] Accessibility: read the window tree of every vault screen with a test service (S8).

#### Stage 4: Rollback and State Restore (P4, P5)

- [ ] On a rooted test device, snapshot app data and Keystore state, make N wrong tries, restore the snapshot, and retry. Record the erase counts and delays, with and without the Keystore restored. Covers items 1, 2, 3, 13, and 14.
- [ ] Restore snapshots taken before the first save, between saves, and during a save. Restore only the primary copy, only the backup copy, or both copies from different times.
- [ ] Move the clock, reboot between tries, and write `BOOT_COUNT` where the device allows it. Check items 4 to 10 and 16.
- [ ] Kill the process at each step of a `FailureStore` save: the primary write, the backup write, key creation, and key deletion. No state may allow more tries than the policy does.

#### Stage 5: Process Memory, Logs, and Dumps (P6)

- [ ] Debuggable test build only: take a heap dump while the vault is open, while a mnemonic is on screen, and after `wipe()`. Search for mnemonic words and seed bytes, and record each surviving copy and how long it lives. A debuggable release build is a finding in its own right. `am dumpheap` needs `<profileable android:shell="true">` or `debuggable`; the `benchmark` APK has `profileable shell="true"` and the release APK has neither, so the dump comes from `benchmark` or a debug build, and installing `benchmark` on a phone is itself a finding (6.3). Prove the search method on a JVM heap dump first: `HotSpotDiagnostic.dumpHeap` keeps a dead local `byte[]` in the file, so a zeroed buffer is not proof of absence.
- [ ] Rooted test device, release build: scan process memory at the same moments.
- [ ] Crash the reveal, sign, import, and unlock flows. Read logcat, tombstones, `dumpsys dropbox --print`, and the bug report. Any secret found is a finding.
- [ ] Release output contains no `Log` calls: verify that the R8 rules strip them (`assumenosideeffects`) and, when a release APK exists, grep its dex and mapping. No debug logging can be switched on in release.

#### Stage 6: Coercion and Decoy (S5)

- [ ] Unlock with the Duress Password. Compare the decoy with the real vault field by field: try timing, the first try after a wrong try, error text, the tries-left warning (item 17), files, photo folder names, key aliases, and wallet names.
- [ ] Compare erase behavior: the count shown, the delay after a wrong try, and when the warning appears.
- [ ] Accepted residual tells of the decoy's erase count, so do not file them as new findings: the vault always erases at the real count, and a decoy that shows another count only moves the tries-left warning. Shown above real: the warning counts against the lower of the two, so it can come earlier than the shown count says. Shown below real: the warning runs down to the shown count and no erase follows. Erasing the real vault early to hide this is unacceptable, because it destroys data.
- [ ] The entry gesture is one device-level setting that the camera reads while the vault is locked. A decoy change writes that one real gesture, so it differs from the real vault's only if the owner changes it from a decoy. Only the lockout (cap and shown erase count) is kept in the decoy's own record.
- [ ] Decoy password changes: every Change App Password in a decoy is one counted wrong try of the real vault (same delay, same erase count, never refunded), and a new password that is the real App Password gets the same answer and time as any other and is not written. Remove Duress Password does nothing in a decoy, and Set Duress Password writes nothing there: it refuses the password that opened the decoy, as the real vault refuses its App Password, and accepts any other strong password without looking at the real one. A decoy Wipe and a self-erase reset the entry gesture like a real Wipe. Accepted residual tell, so do not file it as new: after a decoy Wipe the gate still opens on the Unlock page, since the real vault exists, and after a real Wipe on the Create Password page.
- [ ] The cover saves each photo and video twice, one copy sealed to each vault's key, so both galleries show what the cover shot and a vault with no Duress Password writes the same files (the second copy is sealed to a random placeholder key). A decoy created after photos were taken does not hold them. The two copies of a shot have the same opaque name, a delete in the real gallery also shreds the Decoy Vault's copy, and a delete in a decoy gallery never touches the real copy. A shot whose second copy cannot be written is not saved at all. Accepted residuals, so do not file them as new: with no Duress Password every shot is stored twice and the second copy is unreadable, and it is kept until a decoy is created; after a decoy Wipe the decoy's photo key file holds a placeholder, so shots taken before the decoy is unlocked next never reach its gallery.
- [ ] Accepted residual tell of a decoy password change, so do not file it as new: when the new password is the real App Password, the decoy slot is kept byte for byte, and for any other password it is replaced. Someone who can read `unlock.bin` before and after a change (a rooted device) can tell the two apart. Each such guess still costs one counted try of the real vault, and no cheap fix exists, since the old decoy soft key is not available.
- [ ] Accepted residual tell of a decoy Set Duress Password, so do not file it as new: a decoy password change to X that equals the real App Password reports Created but keeps the old decoy slot, while any other X rewrites it. So a decoy password change to X followed by Set Duress Password X answers SameAsAppPassword when X is not the real password and Enabled when it is. Each such guess costs one counted failure and a Failure Delay, like a wrong try at the gate, so it reveals nothing the gate does not.
- [ ] Record files (`records/`): every file is exactly one size class long (256 B, 1 KiB, 4 KiB or 16 KiB, or a multiple of 16 KiB for a record that outgrew the largest class), as a placeholder, a spare and a written record alike, and the zero padding is inside the sealed bytes. Each name's placeholder and spare start in the class that fits what the record usually holds (`VaultRecordNames`), and a Decoy Vault takes spares of the same classes, so the sorted file sizes do not change when either vault writes. Confirm that, and that the padding cannot be stripped or altered (a record must authenticate and have zero padding). Accepted residual, so do not file it as new: a record that outgrows its class moves to a larger class and stays there, so an unusually large record (a very long Address Book, for example) still shows in the sizes. File times and inodes: every write, create, claim, reset and retire rewrites every file in `records/` in one pass (`VaultRecordNames.rewriteAll`: file name order, same bytes for the untouched files, each replaced atomically), with one modification time on all of them. So no file's modification time, creation time, change time or inode singles out the written records. The whole folder is rewritten on every record write (34 files, about 145 KB, one sync per file), so the Lumio refreshes write each cache once. Every change to the folder (write, rename, delete, wipe) and every pass runs under one lock, so a pass cannot put back a stale or deleted file. Confirm the pass runs after each of those operations, that it needs no key, and that it removes leftover `*.tmp` files. Accepted residuals, so do not file them as new: the files get their fresh creation and change times from the pass itself (the app cannot set them), so they differ from each other by the few milliseconds the pass takes, in a fixed order; a pass that fails or a crash in the middle of one (a full disk, an unreadable file) leaves the files not yet reached with the earlier time and inode until the next write runs a pass, and the write that came before it is saved and reported as saved; the folder's own modification time shows when the last write happened, and it is not hidden; and the other files of the vault (wallets, review pool, photos) keep their own times. A live file watcher (inotify) on `records/` can see the extra rename that comes before each pass.
- [ ] With the file system visible, list every file that differs when a decoy exists.

#### Stage 7: Brute-Force Economics

Write these numbers into the report.

- Per-try cost: Argon2id at the stored parameters. `KdfCalibration.kt` targets about 900 ms per derivation, with 64 MiB or 32 MiB of memory, plus one Keystore operation.
- Tries before erase: the owner's erase count (default 10, allowed 3 to 20). A right password resets the count; rolling the counter back is the attacker's route to more tries.
- Expected time to find the password, starting from the policy floor of 40 estimated bits, for three cases: (a) an honest counter; (b) a counter rolled back after each try; (c) header KDF parameters lowered. The header MAC refuses that edit before a password check (#746), so case (c) must end in an erase, not in a cheaper search.
- Name the assumption that each case depends on.

### 6.2 Cryptography Review, Line by Line

Goal: trace each key, nonce, tag, derivation, and comparison from its origin to its use. Look for errors in the construction, not only in the call sites.

- [ ] Vault key: generated with `SecureRandom`, wrapped by the hardware key and by the Argon2id output. No path where one factor alone opens a sealed blob.
- [ ] Domain separation: every derived key and every MAC has its own label.
- [ ] `Aead.kt`: 12-byte random IV; count encryptions per key; IV reuse after a restore; behavior on tag mismatch.
- [ ] `VaultLayout.kt`: each length (nonce, tag, sealed part) and the fixed slot size; constant-time comparison (`MessageDigest.isEqual`) on every MAC check; `ownerIndex` uses `indexOfFirst`, so measure its first-match loop (6.9).
- [ ] `VaultHeader.kt`: every header field (version, KDF parameters, salt, creation time, flags, key security level) is covered by a MAC under the header key (`k3`), checked before a password (#746). The failure state is MAC'd too, and KDF changes break the wrapped key.
- [ ] `KdfCalibration.kt`: floors and ceilings are enforced on values read from a file.
- [ ] `Argon2Kdf.kt`: BouncyCastle `ARGON2_id`, version 1.3; output and salt lengths; UTF-8 of code points, not UTF-16 code units; password bytes wiped after use.
- [ ] `VaultKeystore.kt` and `KeystorePolicy.kt`: key purposes, key size 256, user-authentication timeout, StrongBox fallback. No release path accepts a software key.
- [ ] No `kotlin.random.Random` or `java.util.Random` on any key, nonce, salt, or mnemonic path, including inside the sandbox.
- [ ] BIP39: wordlist, lowercase words, checksum on import, NFKD normalization of the passphrase, PBKDF2-HMAC-SHA512 with 2048 rounds, empty passphrase. Test with the official vectors.
- [ ] SLIP-10 and BIP32 (`Slip10Ed25519.kt`, `HdWallet.kt`): hardened-only derivation for Ed25519, strict path parsing, official vectors.
- [ ] secp256k1: deterministic nonces, low-S normalization (EIP-2, BIP-146), recovery ID, public key form. Identify which code signs: native libsecp256k1 or Kotlin.
- [ ] Each chain's signing domain: the Algorand transaction prefix; Solana message bytes exactly as reviewed; EIP-191 and EIP-712 prefixes, domain separators, and nested type hashing.
- [ ] Secret lifetime: key material is zeroed in a `finally` block. Every secret `String` is listed, with the reason it cannot be zeroed. Trace `Mnemonic`, `HdWallet`, `PrivateKey`, `EvmKey`, and `WalletKeyAccess`.
- [ ] `BigInteger` work on private scalars in Kotlin code: list each use for the timing review (6.9).

Attack ideas:

1. Flip each header byte. Each flip must fail before a password check, and must count as the owner's try at most once.
2. Swap in a slot from another vault, or from an older format version. Look for a downgrade path.
3. Feed KDF parameters at the floor, and one step below it.
4. Use passwords with combining marks, surrogate pairs, and NFC versus NFD forms. The same visible password must derive the same key, and two different passwords must not collide through normalization.
5. Build mnemonics of every valid and invalid length. Invalid input must be refused.
6. Force a repeated RNG output in a test harness. The code must refuse to reuse a nonce under one key.

Evidence: a trace table with `file:line`, origin, use, and zeroing point, plus the vector runs. Discoverers cite exact, verified `file:line` pairs. Validators re-verify each pointer before they trace, because a wrong pointer costs validator budget.

### 6.3 Android Platform Security

Goal: test the platform controls the attacker meets before any app code runs.

- [ ] Release merged manifest (not the debug merge): `exported` on every component; `isolatedProcess` and `process` on both sandboxes; no providers or receivers unless intended; no `android:debuggable="true"`; `allowBackup="false"` in both apps.
- [ ] `data_extraction_rules.xml` excludes everything. Lumio declares no extraction rules: record what that means at each target API level.
- [ ] Expected release permissions: Lensly holds CAMERA and USE_BIOMETRIC, and no INTERNET or ACCESS_NETWORK_STATE. Lumio's merged permissions match the allow-list in `check-manifest.sh`.
- [ ] Every exported activity and every extra it reads. `run-as` on the release build must fail. Assess what a `profileable` benchmark build exposes when it is installed. That build is signed with the debug key, so it cannot replace a release install, and `<profileable android:shell="true">` lets `adb shell am dumpheap` read the app's heap. Its merged manifest has an exported `androidx.benchmark.IsolationActivity` and permissions that `androidx.benchmark:benchmark-common` adds (`INTERNET`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`). Record whether any CI job publishes that APK.
- [ ] `adb backup`, `bmgr`, and device-to-device transfer carry no vault data. Vault files live only in private directories, and no external storage holds secrets.
- [ ] Keystore: `KeyInfo.securityLevel` per device, StrongBox use, aliases, key deletion on reset, and behavior after an OS update.
- [ ] `FLAG_SECURE` covers every vault screen, dialog, and popup, including Compose windows. Compose dialogs inherit FLAG_SECURE by default (`securePolicy = Inherit`); do not treat them as a gap.
- [ ] Notifications, shortcuts, widgets, share targets, and launcher aliases reveal nothing before unlock (docs 8.5).
- [ ] Clipboard: every call site of `Clipboard.kt` writes no secret, and pasted text reaches a field only after the user has reviewed it.
- [ ] Secret fields that use the system keyboard set the no-suggestions, no-personalized-learning, and no-autofill flags.
- [ ] TalkBack never speaks the displayed recovery phrase or the filled restore slots; each is hidden behind `Word N of 24`. The confirm-word quiz choices, the restore suggestions, and the typed prefix are spoken by design: see `docs/accessibility.md`, "Accepted, Do Not Re-Report". Check `launchMode` and `taskAffinity` on every vault activity.
- [ ] Sandbox processes hold no permissions, no app files, and no Keystore access, and reach no vault file through a shared UID or a Binder file descriptor.

Attack ideas:

1. Start every exported component with crafted extras on the release build (`am start -n`, `am broadcast`). Record each result.
2. Install a test app (S10) that sends the same intents and reads whatever it can reach.
3. Bind to the sandbox services from a second app with no shared permissions.
4. Enumerate Keystore aliases before and after a restore on a rooted test device.

Evidence: `apkanalyzer` and `aapt2` transcripts, the test app and its logcat, and screenshots taken from test harnesses on test devices only.

### 6.4 Input Fuzzing of Parsers

Goal: every parser of attacker-controlled bytes is bounded or sandboxed. None may corrupt the trusted side, or produce a review that differs from the signed bytes.

Targets: camera frames (`QrDecodeService.kt`, `LumaFrames.kt`, `QrDecodeLimits.kt`); UR parts (`UrScanner.kt` and the hummingbird library); CBOR (`Cbor.kt`); request framing (`RequestParser.kt`, `ParserSandbox.kt`); PSBT (`BtcPsbt.kt`); EVM transactions (`UnsignedTx.kt`, `EvmRequestParser.kt`); EIP-712 (`TypedDataParser.kt`); ABI (`abi/`); Solana messages (`SolanaMessageParser.kt`); Algorand msgpack (`AlgorandRequestParser.kt`); the pairing that Lumio reads (`lumio/src/main/java/app/lumio/pair/`); Lumio payment codes (`lumio/src/main/java/app/lumio/send/PaymentCode.kt`); Brotli (`brotli_jni.c` and Lumio's decoder); registry packages (`PackageFormat.kt`).

Existing harness: Jazzer targets in `shared/src/test/java/app/lensly/fuzz/` (`UrScannerFuzzTest`, `CborFuzzTest`, `EthSignRequestFuzzTest`, `UnsignedTxFuzzTest`, `AlgorandRequestFuzzTest`), with seeds in `FuzzSeeds.kt`. The pull request gate replays the committed seeds. `scripts/ci/fuzz-nightly.sh` fuzzes each target for a fixed time, and its header says where crash inputs are written.

- [ ] Each parser has size, depth, and time limits. Record the values, from `ParserLimits.kt` or from local constants.
- [ ] A Jazzer crash file goes to the `<Class>Inputs/<method>/` directory under the test resources. In the run-2 environment it landed in build output, under `shared/build/intermediates/java_res/debugUnitTest/`, so check both `shared/src/test/resources/app/lensly/fuzz/` and that build path. Copy the file to the scratch area, record its name and SHA-256, and replay it twice: once through the Gradle task, and once through a small JVM harness that calls the parser directly. A file that replays clean is not a crash: record it as unexplained and do not claim a finding from it. To make a crash permanent, copy it into `shared/src/test/resources/fuzz-seeds/<target>/` and list it in that directory's `index.txt`, because `FuzzSeeds.kt` reads only that path.
- [ ] Every allocation driven by a length field is checked against the remaining input before it happens.
- [ ] Every error path returns a refusal. No partial parse reaches the review.
- [ ] Duplicate map keys are refused, not last-wins. Canonical key order is enforced where the chain requires it.
- [ ] Unknown fields are refused, including map keys from 1000.
- [ ] Every number is range-checked before use. Every displayed string is valid UTF-8, and control and bidirectional characters are shown or refused.

Attack ideas:

1. Run each Jazzer target well past the nightly budget. Fuzz the Brotli path with sanitizers (ASan and UBSan).
2. Generate valid structures with mutated semantics for each chain. Compare each review with the decoded value (6.5).
3. Differential-test each parser against an independent implementation (web3j, bitcoin-kmp, algosdk, a Solana SDK). Each disagreement is a candidate.
4. Stream oversized and deeply nested QR frames. Measure memory and recovery in the `:qrdecode` process. The vault state must not change.
5. Kill the `:parser` process in the middle of a parse. The trusted side must report a refusal, with no partial state.

Evidence: targets, corpus sizes, coverage numbers, crash files with the commit SHA, and minimized reproducers. A crash inside a sandbox holds no keys, so on its own it is a denial of service (6.10), not a key exposure.

### 6.5 Transaction Display Integrity (Shown Equals Signed)

Goal: what the owner reviews on Lensly is exactly what the signature covers, for every chain, request type, and optional field.

Method:

1. Build a corpus per chain: minimal, typical, and adversarial. Include multiple outputs, approvals, hidden value, batches, groups, nested typed data, a PSBT with a spoofed change output, and lookup-table accounts.
2. For each request, collect the rows that the review model shows, and keep the signed bytes.
3. Decode the signed bytes with an independent decoder in a scratch directory. Every field in the bytes must appear in the rows, or under "Not shown" with a reason. Otherwise it is a finding.
4. Read the real screen through the UI tree on a test device, and compare it with the rows.
5. For blind requests, check the warning, the raw hex, and the hash that Lumio shows (docs 8.4).

Checklist:

- [ ] Amounts, full addresses, chain, fee, nonce, and network name appear for every request.
- [ ] Dangerous fields are rows that warn: approvals (including `setApprovalForAll`), permit signatures, transfers from another account, Algorand close-to fields (rekey and clawback are refused), off-chain venue agent approvals, Bitcoin change to foreign scripts, and Solana authority changes.
- [ ] Token decimals come from the chain or a verified source. A registry change to decimals or labels is visible to the owner.
- [ ] Off-chain venue typed data: domain, version, chain ID, action type, and the destination and amount of any withdrawal or transfer, checked against the venue's published spec. Agent approvals warn as danger; a domain that signs only an action hash says that the action is not shown.
- [ ] Address Book names never replace the full address in a row, and a name cannot make a foreign address read as the owner's own.
- [ ] Every signing, review, summary, last-chance and status screen of both apps that names a party also shows that party's address: Lensly's summary sentence shows the first two and last two groups of 4 after the name (`addressEnds`), with the full address in the details below; Lumio's Review heading does the same, with the full address grid under it, and its Mismatch, Last Chance and On Its Way pages show the full address. Known limit: names are checked by a shape heuristic (`looksLikeAddressName`), so a name such as `9WzD Hk3z` or `H kzqXwBbm kgZTbNMqUxv m` can still imitate a shortened address. Such names are not refused, since the same rule would refuse real names like `S24 5G`. The address shown beside the name is the real check, so compare its groups, not the name (#1249).
- [ ] PSBT: input values come from the UTXO data, the fee equals inputs minus outputs, and change belongs to the wallet's own derivation paths.
- [ ] Hold-to-sign and the final confirmation show the same hash as the signed request.
- [ ] `ReviewPool.kt`: a review built for request A cannot be shown or signed for request B.

Attack ideas:

1. Key smuggling: add map keys from 1000 whose values look like a recipient or an amount. The request must be refused.
2. Label spoofing: labels that disagree with the binary content. The review must show the binary content.
3. Batch hiding: a benign call first and a drain second, in an Algorand group or an EVM multicall. The review must show every call, and an Algorand group must say that the other transactions are not shown.
4. Unit and chain confusion: wei against gwei, lamports against SOL, satoshis against BTC, and legacy transactions without a chain ID.
5. Swap during review: a second UR part arrives while the review is open. The signed request must equal the reviewed one.

Evidence: one table per chain, with the request, the decoded fields, the rows shown, the "Not shown" entries, and the diff against the independent decoder.

### 6.6 Business Logic and State Machines

Goal: every flow reaches only the states its designers intended, and no transition skips a guard.

Model each machine as a table (state, event, guard, next state) before testing it: vault lifecycle (partial, locked, unlocked, decoy-unlocked, erased); the failure counter; the cover screen and gate; signing (received, parsed, reviewed, held, signed, recorded); pairing; bulk signing (`BulkRun.kt`, `BulkPolicy.kt`, `BulkCap.kt`); the registry (verify, install, roll back); key access (scope checks at load time and at each call); wallet folders (key replacement by ID); and the wallet backup flag (`seedBackedUp`, `WalletStore.kt`). "Bulk signing" in this codebase means account creation, not transaction signing.

- [ ] Every transition that spends a try, writes state, or deletes a key has a written guard.
- [ ] Every path that sets `seedBackedUp` (`WalletStore.kt`) is backed by a completed backup or a recoverable copy, because `HomeBackup.kt` trusts the flag.
- [ ] Shared mutable state (session, failure store, registry, review pool) is locked or confined.
- [ ] No re-entrancy from the UI into `unlock` or `sign`. No session object survives a lock, an erase, or a restart.

Attack ideas: race the unlock through every reachable path and count the recorded tries; interrupt the sign flow at each state and re-enter it, looking for a stale review or a signature without a hold; trigger an erase while a signature is in flight; install an older valid registry package after a newer one; split one bulk request into several that each stay under the cap; start a sign on the decoy, then unlock the real vault.

### 6.7 Supply Chain and Build Release

Goal: the APK the owner installs is the APK built from the reviewed commit, with the reviewed toolchain, and no path changes it unseen.

- [ ] `gradle/verification-metadata.xml` covers every artifact the release uses, including plugins. Record any unverified artifact. Reproduce coverage with `./gradlew --offline --dependency-verification=strict :app:assembleRelease --rerun-tasks`; a missing checksum fails the build. `verify-signatures` is `false`, so the file pins bytes, not publishers.
- [ ] The wrapper JAR matches the official checksum (`distributionSha256Sum`). `gradle/libs.versions.toml` pins every version. `ndkVersion` matches the CI image.
- [ ] `CMakeLists.txt` pins the upstream Brotli SHA-256. Check whether the build downloads that tarball or uses the vendored tree, and whether the two match.
- [ ] Every `uses:` in `.github/workflows/` is pinned to a full commit SHA. At this commit every workflow is; check each on every run. Permissions are least-privilege, and checkouts use `persist-credentials: false`.
- [ ] Release path: tags `lensly-v*` and `lumio-v*`; the master guard runs before keystore secrets are read; `fetch-depth: 0`; v2 and v3 signature checks; provenance. Verify the released artifact with `gh attestation verify … --format json`.
- [ ] Rebuild from `COMMIT` in a clean environment and compare the signing-independent content (DEX, resources, native libraries). Record every difference.
- [ ] `comment-run.yml` and `codeql-command.yml` (`issue_comment`): the commenter gate, the code that runs (pull request head or default branch), token permissions, and readable secrets. `master-red.yml` (`workflow_run`) must never run pull-request code with a write token.
- [ ] No pull request can read or poison a cache that release builds use. No workflow publishes a debug or benchmark artifact.
- [ ] Dependency graph and SBOM: the workflow `if:` is `vars.DEPENDENCY_GRAPH_ENABLED == 'true'`. Read the variable with `gh variable list`, read the live document with `gh api repos/:owner/:repo/dependency-graph/sbom`, and record whether the workflow comment still claims the graph is off. The SBOM is branch-pinned (`pkg:github/<owner>/<repo>@master`), so it cannot be tied to a commit; record that as Low and check the package count against the release classpath.

Attack ideas: replace one dependency with a same-version artifact from another source, and verify that verification fails; test the release tag guard locally with `act`, using a tag on a commit off the default branch, and never push tags to the project's repository; test the comment-triggered workflows only in a fork the test team owns, with the project's approval; compare `versionCode` on a shallow clone (code 1, per the comment in `app/build.gradle.kts`), and log it unless an update path accepts it.

Any step that pushes, tags, or runs workflows on the project's repository needs the project's explicit approval for that step.

### 6.8 Privacy Leaks Before Unlock

Goal: nothing reachable without the gesture reveals what the app is for (docs 8.5). Test every observer's view of the device before unlock.

- [ ] [static-traceable] Before unlock, the app label, icons, package name, activity labels, and every string **rendered or exposed before the unlock gesture** in every locale reveal nothing about crypto; strings reachable only in the hidden area are out of scope. The same holds for notifications, shortcuts, widgets, share targets, launcher aliases, and app links.
- [ ] [static-traceable] Cover-screen banners, errors, and toasts for QR scans: an unknown request reads like any unreadable code.
- [ ] [static-traceable] Photo storage (`MediaEnvelope.kt`, `PhotoStore.kt`): the names and sizes a file-system observer sees, and whether padding hides the photo count.
- [ ] [static-traceable] Gallery strings: compare every string the gallery's unlock screen shows with no vault and with a vault. The gallery opens from the camera screen before any password is entered, so a string that differs between the two states shows whether a vault exists.
- [ ] [device] `GalleryGate.kt`: the media folder looks the same after the App Password and after the Duress Password.
- [ ] [device] Gesture reaction timing for the hidden area and for the normal screen (6.9). Lumio's label, icons, and scan banners before unlock.

Attack ideas: a test app on a rooted device lists packages, notifications, shortcuts, and visible file names, and records what a casual observer learns; scan one code per request type on the cover screen and record every string shown.

Evidence: every string visible before unlock, with its source file and line.

### 6.9 Side Channels and Timing

Goal: find whether secret-dependent timing, I/O, or UI behavior tells an observer more than it should. Software channels only, unless the project adds hardware channels.

Channels: the password check (correct, wrong, decoy, wrong length); the save pattern of a passing try against a failing one (item 12); slot selection (`ownerIndex`); gesture reaction; the wait-versus-reject shape of the gate (CoverGateLook on Lensly against Lumio's unlock screen, which can show a countdown where a rejection only shakes); Keystore call time (StrongBox against TEE, real key against decoy); signing time in Kotlin code paths; per-key timing in the in-app keyboard.

Method: take at least 10,000 samples per condition on a quiet test device, with airplane mode on, fixed brightness, and the same app state. Use a fixed-versus-random t-test (dudect style) or a rank test. Report the statistic, the sample count, and the noise floor from a control condition that must show no difference. A difference counts only if it reproduces on two runs on two different days. If the run budget cannot span two days, record device timing channels as Gaps with that reason; do not attempt a one-day Confirmed timing claim.

### 6.10 Denial of Service Leading to Loss

Goal: find any way an attacker who controls only inputs (QR codes, Lumio, a registry package, the environment) can make the owner lose access to funds, through erase, lockout, corruption, or a forced wrong-try count.

Context: the vault erases itself when it cannot verify its state (item 15; the residual risks in `FailureStore.kt`). Check three things: the erase triggers are exactly the documented set; no attacker-controlled input reaches one; and each benign trigger is documented for the owner.

- [ ] List every erase call in `Vault.kt`, `FailureStore.kt`, and `FailureDelay.kt`. Match whole-vault erase calls only; buffer `wipe()` methods are unrelated. Name each trigger, and mark it attacker-controlled (none allowed) or benign.
- [ ] Test the benign triggers: storage full during a save, a transient KeyMint error, a lock-screen credential change, a biometric change, an OS or app update, a reboot during a save, and a clock jump.
- [ ] A sandbox crash or hang never counts as a wrong try. A failed registry update never blocks signing or touches the vault. A Lumio request flood never changes the failure state.

Attack ideas: crash the sandbox N times with crafted input and check the count after each crash; stream QR input for hours; fill the test device's storage during a save; change the lock-screen credential between sessions on a test device.

Evidence: the exact trigger, the observed erase or lockout, and whether an attacker controlled the trigger. An attacker-controlled erase of a vault that has no other copy of its mnemonic is Critical.

### 6.11 Lumio Compromise Impact

Goal: assume Lumio is fully hostile. It runs attacker code, shows any UI, sends any QR code, and reads all watch-only data. Determine what that gains the attacker.

- [ ] Lumio holds no wallet key material (its own vault key seals watch-only data). Verify by code inspection and by a memory scan of the Lumio process. If no clean device is available, record the Lumio memory scan as a Gap; code inspection does not cover it.
- [ ] Record what the watch-only data reveals (xpubs, addresses, balances, history, address book, pairings, provider API keys). This is privacy, not funds.
- [ ] Every request type Lensly accepts from Lumio is reviewed on Lensly before signing (6.5). Lumio cannot make Lensly sign again for a request the owner did not review.
- [ ] Registry packages are trusted only after verification by a trusted key and a higher sequence (S3); Lensly never takes one from Lumio. Lumio derives each account address from the exported public key, but Address Book entries in a pairing are only format-checked, so their names are unverified.
- [ ] The review never depends on Lumio's text. RPC endpoints are the owner's configuration (`RpcPlugins.kt`). Release has no cleartext allowance and no added user CA trust.

Attack ideas: write a hostile Lumio build in the scratch area (never published) and send crafted requests to a test Lensly; replace the pairing with one for an unknown account; send a sign request with extra map keys from 1000 that name a different recipient.

Evidence: the hostile build's source, a screen recording of the Lensly review, and the signed output compared with the reviewed request.

## 7. Validation Standard

### 7.1 What Counts as Confirmed

A finding enters the report only when one of these holds:

1. An executed reproduction (a failing test, a PoC script, or a device transcript) that a validator ran from a clean checkout and saw the same result.
2. A complete line-by-line trace with `file:line` references, checked by a validator who traced it independently and reached the same conclusion. Use this only when execution is impossible, and state why.

Anything else is a Hypothesis, and it goes to the appendix. Findings contain no speculation. Do not write "might", "could", or "possibly" in a finding. State the observed result, or move the item to the appendix.

### 7.2 Reproduction Rules

- A stranger reproduces the finding in under 30 minutes on the stated device or emulator.
- Record the commit, the variant, the build command, the device model, the Android build fingerprint, the KeyMint level, and the tool versions. Record exact commands, paths, expected output, and observed output.
- No full mnemonic or key appears in any evidence. Use a hash, or the first and last word of the throwaway mnemonic.
- PoCs live in the scratch area, named for the finding ID.

### 7.3 Severity

Severity measures the outcome for the mnemonic and funds under the primary scenario (3.2). Justify each rating in one sentence that names the precondition and the outcome.

| Severity | Definition |
|---|---|
| Critical | A non-owner who holds the phone (P0 to P2, no passwords) obtains the mnemonic or a private key; or makes Lensly sign a request the owner did not review; or an attacker-controlled input erases a vault whose mnemonic has no other copy. |
| High | The same outcome, with a precondition a phone-holding attacker can realistically meet: a known credential, a rooted device, a restored older state, or an installed app the owner approved. Also a review defect that hides a dangerous field from the owner. |
| Medium | Materially reduces the password search space; reveals that a decoy exists, or reveals vault contents, to an observer; leaks addresses or xpubs without authorization; defeats a defense-in-depth control. |
| Low | A hardening gap with no direct path to the mnemonic or funds: a missing flag, a weak limit, shipped code that is never used, or a comment that misdescribes a control. |
| Info | An observation for the project. No action in this run. |

### 7.4 Deduplication and Regressions

- Group candidates by root cause. One finding can have several attack paths. Findings with one fix are one finding, unless their impact differs. Keep the earliest ID, and list the others as duplicates in the ledger.
- A fix merged after COMMIT is its own outcome, not a regression and not a missing test. If the fix PR's merge date is later than COMMIT, record the defect as present at COMMIT with upstream fix pending. Exclude tracking issues that have no single fix from the per-fix mapping.
- [ ] List closed issues and merged pull requests labeled `area:security` (the commands in 5.1). For each one, find the fix commit, read its regression test, and run that test at `COMMIT`.
- [ ] Mutation check on the scratch branch: revert the fix and confirm the regression test fails. For a multi-part fix, revert each part separately, because one part can be covered while another is not. Restore the fix, and record the result.
- [ ] A regression is a finding. Its title names the original issue or pull request, and its severity is the original severity or higher.

### 7.5 Accepted Results

Results from offensive audit run 3 (commit `86949a51`) that are accepted and not filed as issues.

| Finding | Decision | Reason |
|---|---|---|
| AV-006 | Accepted | Debug and benchmark builds on an emulator skip FLAG_SECURE on purpose so screenshot testing works; release stays secure, and debug on a real device is secure. |
| AV-012 | Accepted | Releases are cut periodically, so a tag can trail master by design. |
| AV-014 | Accepted | The nightly script passes `--no-build-cache` to both Gradle calls, so the cached-run trap is already mitigated. |
| AV-015 | Accepted | The Brotli difference is macOS-versus-Linux build metadata; `-Wl,--build-id=none` was disproven as a fix (20 of 586 bytes). |
| AV-016 | Accepted | Unoffered bulk-cap values read as 100 by design; the app UI cannot produce them, and `SettingsStoreTest` covers the behavior. |
| Registry package planted with a leaked key | Accepted | The registry seal (MACed under a Keystore key) names the installed package file by SHA-256, and the files that carry the kept whitelist and denylist the same way. A package planted over the installed file, whatever key signed it and whether or not a build lists that key as revoked, does not match the seal: it is not used, it cannot stand in for the package that set the floor (so a public package cannot replace it below the floor), and it cannot pin the floor with a huge sequence. While the seal verifies, it also holds the key IDs that the installed package revokes, so a revocation stands even after a build revokes its signer, and when the installed file is deleted, cut short or replaced by an older package. Every revocation in force is also anchored in the Android Keystore, as a key under the revoked key's ID, when a package makes it stand and when a verified seal that holds it is first read. File write access cannot delete a Keystore key, so deleting the seal, the installed file or both does not drop a revocation, and a package signed by a revoked key is refused whatever its sequence. A store with no verified seal also counts the revocations of an installed file signed by an active recovery key, and none from a file signed by any other key, so a leaked everyday key cannot lock out the legitimate keys. An install is refused when the Keystore cannot list the anchored revocations. What file write access alone can do: force the flagged built-in catalog by planting or deleting a file; deny registry updates until a newer package, because a file planted with a leaked recovery key adds revocations (a revoked key is never accepted this way, and only a leaked recovery key can lock out the legitimate keys, until Lensly's app data is cleared); and, after a restore that also wiped the Keystore, pin the floor with a planted huge sequence, a rare permanent denial of updates. It cannot inject content or lower the floor. What remains: someone who can forge the seal's Keystore MAC, which means code execution inside Lensly, can seal a planted file; and a holder of a leaked key that no build or sealed revocation lists yet can get a package signed by that key installed, as nothing marks the key as leaked. A wiped Keystore takes Lensly's app data with it, which makes the device a fresh install on the built-in catalog. A store sealed by an earlier build reads as unverified until the next package is installed (issue #1614). |
| Lumio Biometric Unlock | Accepted | With biometric unlock on, anyone who forces the owner's finger opens the real vault. The unlock page never opens the prompt by itself (issue #783): the owner asks for it with Done on an empty password field, so the page looks the same on arrival with biometric unlock on or off. The residual tell is pressing Done on an empty field: the system prompt shows only when biometric unlock is on. The vault cannot tell whether a Duress Password exists, so the prompt cannot depend on one. |
| TalkBack reads the confirm-word quiz choices, the restore word suggestions, and the typed prefix | Accepted | Blind owners must hear them to finish backup and restore; the displayed phrase and filled slots stay hidden from accessibility. Scope and residual risk: `docs/accessibility.md`, "Accepted, Do Not Re-Report". |

## 8. Report Template

The report is Markdown. Use this skeleton exactly: keep the section order and the headings, and replace the bracketed fields.

```markdown
# AirVault Offensive Audit Report

| Field | Value |
|---|---|
| Commit | `<full sha>` (`<clean, or dirty with diff sha256>`) |
| Run date | `<yyyy-mm-dd>` |
| Playbook revision | `<number from the Revision History>` |
| Variants tested | `<debug, release, benchmark>` |
| Devices | `<model, Android version, patch level, KeyMint level, StrongBox, root>` |
| Scope | `<full, or the disciplines run>` |
| Run status | `<complete, or partial: reason>` |
| Tools | `<name and version list>` |

## Executive Summary

Primary question: can an attacker who holds the phone get the mnemonic?

- Answer: `<No | Yes | Conditional>`, with one sentence for each condition.
- Strongest path: `<finding ID and one sentence>`, or `none confirmed`.
- Coverage: `<percent of planned disciplines completed>`.

Three to six plain sentences on the most important results.

## Findings Summary

| ID | Title | Area | Severity | Status | Fixed In |
|---|---|---|---|---|---|
| AV-001 | `<title>` | `<area>` | `<severity>` | Confirmed | `<open, or sha>` |

## Finding Details

One block per finding, using the Finding Template in section 8.1 of the playbook.

## Checked and Held

| Area | Attacked | Method | Result | Evidence |
|---|---|---|---|---|
| `<discipline>` | `<target>` | `<method>` | Held | `<path or transcript id>` |

## Hypotheses Not Confirmed

| ID | Hypothesis | Why Not Confirmed | Next Step |
|---|---|---|---|
| H-001 | `<text>` | `<reason>` | `<what would confirm or refute it>` |

## Coverage Gaps

| Discipline or Target | Planned | Done | Gap Reason |
|---|---|---|---|
| `<name>` | `<yes or no>` | `<complete, partial, none>` | `<reason>` |

List every discipline in section 6, including the ones that did not run.

## Tool and Run Log Summary

| Item | Value |
|---|---|
| Workers (discovery, discipline, validator) | `<counts>` |
| Peak concurrent subagents | `<count, cap 5>` |
| Fuzz time per target, device time | `<minutes>` |
| Wrap-up point reached | `<none, 60, 85, or 95 percent>` |
| Logs and scratch area | `<path outside the repository>` |

## Proposed Changes to auditing-airvault.md

| Section | Change (exact text) | Reason | Recurrence Likelihood |
|---|---|---|---|
| `<section>` | `<add, remove, or edit: exact text>` | `<what happened>` | `<high, medium, low>` |

List dead checks (ones that never applied) and new targets as well.
```

### 8.1 Finding Template

Use this block for each Confirmed finding. Keep every field. Write `N/A` only with a reason.

```markdown
### AV-<NNN>: <Title in Title Case>

- Area: <discipline from section 6>
- Severity: <Critical | High | Medium | Low | Info>, because <one-sentence justification>
- Confidence: Confirmed
- Preconditions: <numbered: device state, attacker capability, configuration>
- Attack Steps: <numbered; each step names the action and the observed result>
- Evidence (file:line): <repository paths and line numbers at COMMIT>
- Reproduction: <commands or device steps; PoC path in the scratch area; expected and observed output>
- Impact on the Mnemonic or Funds: <what the attacker obtains, and its effect on funds>
- Fix Suggestion: <the smallest change that closes the path, and the test that proves it>
- Validator: <ID, date, and "reproduced" or "traced and agreed">
```

## 9. Improving This Document

This step is mandatory on every run.

1. Review this playbook against the run before writing the report. Note every check that never applied, every target you had to find yourself, and every instruction that confused a worker or cost time.
2. Put the result in the report's Proposed Changes section, with exact text. Keep only changes likely to recur. Remove outdated targets, and say why.
3. Ask every worker to report anything that caused confusion or wasted time. Estimate how likely it is to recur. Document only significant or unexpected insights.
4. Whoever reads the report applies the accepted changes to this file, or proposes them as a change, and then adds a Revision History row. Each change set raises the revision by one.

The seed list (5.3) names where to look, not what is broken. Add no claim that a control fails without a Confirmed finding. Add no personal paths, no machine-local paths, and no internal planning files.

## Revision History

| Revision | Date | Author | Summary |
|---|---|---|---|
| 1 | 2026-11-21 | AirVault maintainers | First playbook. Seed paths checked at the repository state of 2026-11-21. |
| 2 | 2026-11-21 | AirVault maintainers | Corrected Algorand, Solana, Mnemonic, erase-count, workflow, and review claims; added Address Book, payment code, wallet backup flag, lock policy, and backup exclusion checks. |
| 3 | 2026-11-21 | AirVault maintainers | Added commands for the release APK signature check, the Algorand fuzz gap, the build-cache trap that can skip a nightly fuzz run, the release-manifest network-permission gap, the fuzz crash-file location, verification-metadata coverage, SBOM pinning, and the benchmark APK's profileable and permission surface. |
| 4 | 2026-11-23 | AirVault maintainers | Applied offensive audit run 3 (commit `86949a51`) changes: device pre-check, brief citation rules, pre-unlock string scope, header MAC wording, Compose FLAG_SECURE inheritance, bulk-signing terminology, timing and memory-scan Gaps, attestation command, erase grep scope, seed-path corrections. Recorded accepted results in 7.5. |
| 5 | 2026-11-23 | AirVault maintainers | Issue #750: disclosed the self-erase on unverifiable vault state in the README and the Ledger recovery guide; recorded the Lumio Biometric Unlock accepted result in 7.5 and in the Device Protection help text. |
| 6 | 2026-11-23 | AirVault maintainers | Recorded the accepted TalkBack behavior for the confirm-word quiz and the restore suggestions in 7.5, with the scope in `docs/accessibility.md`. |
| 7 | 2026-11-23 | AirVault maintainers | Issue #746 (AV-007): the header carries a MAC under `k3`, checked before any password, and an unverifiable header erases the vault. Updated the header checks in 5.4 and 6.2 and case (c) in 6.1. |
| 8 | 2026-11-26 | AirVault maintainers | Issue #783: the Lumio unlock page no longer opens the biometric prompt by itself; updated the Lumio Biometric Unlock accepted result in 7.5. |
| 9 | 2026-11-27 | AirVault maintainers | Applied external audit feedback: fixed-after-COMMIT outcome and per-part mutation reverts in 7.4; Lumio gate wait-versus-reject channel in 6.9; static or device tags on 6.8 bullets; file:line re-verification in 6.2; split Log check in 6.1; Lumio FileProvider and registry signing seeds in 5.3; low-memory Gradle flags in section 1. |
| 10 | 2026-11-28 | AirVault maintainers | Issue #1614: the registry seal binds the installed package file and the kept whitelist and denylist files by SHA-256; rewrote the planted-package accepted result in 7.5. |
