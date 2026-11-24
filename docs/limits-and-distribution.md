# Limits and Distribution

This page covers three things: who AirVault is a poor fit for, what survives when a vault is erased, and how Lensly reaches a phone. Each claim points to the code or to a platform document. Claims we could not support are left out.

## Who AirVault Makes Less Safe

For most people, a hardware wallet is safer and simpler than AirVault. A hardware wallet is one device built to hold keys and sign. AirVault needs two phones, a sideloaded APK and QR codes in both directions (`README.md`). A hardware wallet from a maker you can check avoids most of that work. If you can use one, use it.

AirVault is a poor fit if any of these apply:

- You are not comfortable installing and checking APKs yourself. Lensly is only distributed as a sideloaded APK, and nothing checks the file for you. See [Installing and Updating](#installing-and-updating).
- You have only one phone. Lensly must stay on a phone that never connects to a network, and Lumio needs a connected phone. If both run on one phone, the phone that holds your keys is online.
- You cannot keep the Lensly phone offline all the time, for example because you need it for daily messages, banking or two-factor codes.
- You may need help recovering. Lensly erases the vault when it cannot verify the vault's state, and when you reach the wrong-password limit you chose (`README.md`, `shared/src/main/java/app/lensly/core/vault/Vault.kt`). The app cannot bring back an erased vault. Your recovery phrase is what restores a wallet.
- A capable adversary with physical access or forensic tools is among the people you need to protect against. The threat model is tested by a project-run playbook, [auditing-airvault.md](auditing-airvault.md). This repository records no independent audit, and the playbook lists assumptions the team does not control.

If you still choose AirVault, keep a separate recovery phrase for every wallet, and keep the Lensly phone offline. [Recover your wallet with a Ledger](recover-with-ledger.md) shows how a Lensly wallet can move to a hardware wallet.

## Residual Traces

Erasing a vault deletes the vault's data. It does not remove every trace that the phone held one. These traces can survive an erase:

- **The app.** The installed app keeps its package name and its launcher icon. Android records the first install time and the last update time of each app (`PackageInfo.firstInstallTime` and `lastUpdateTime`). Erasing the vault does not uninstall the app.
- **Usage history.** Android places each app in an App Standby Bucket based on how recently and how often it is used. Anyone with adb access can read the bucket with `adb shell am get-standby-bucket <package>` ([App Standby Buckets](https://developer.android.com/topic/performance/appstandby)).
- **Recents.** AirVault turns off recents screenshots for its screens (`shared/src/main/java/app/lensly/ui/SecureWindow.kt`).
- **Storage remnants.** On erase, AirVault deletes the Keystore keys first, then overwrites each vault file with random bytes before it deletes the file (`Vault.kt`, `wipeVault`). On flash storage, an overwrite may not reach every old block, because the controller remaps writes. NIST gives the same warning for overwriting wear-levelled flash ([NIST SP 800-88 Rev. 1 bulletin](https://csrc.nist.gov/files/pubs/shared/itlb/itlbul2015-02.pdf)). The overwrite is therefore not a guarantee. What matters is key deletion: the vault's key material is sealed under Keystore keys, so leftover bytes are meant to be unreadable without them. The app cannot prove that a deleted key stays deleted (`FailureStore.kt`).
- **Backups you make.** Lensly's data is excluded from Android cloud backup and device transfer (`allowBackup` is false, and `app/src/main/res/xml/data_extraction_rules.xml` excludes the app's data domains). A copy made by another tool, such as a full image of the phone, is outside those rules.
- **Lumio's network use.** Lumio sends the addresses it checks to RPC providers, block explorers and price services such as CoinGecko and DefiLlama. Those services see the addresses and your IP address. Anyone on the network path can see which hosts you contact, through DNS and the TLS server name field ([RFC 6066, section 3](https://www.rfc-editor.org/rfc/rfc6066)).
- **QR codes.** A code shown on either screen can be photographed with another camera. Lensly's pairing codes carry only public keys (`shared/src/main/java/app/lensly/app/export/PairingExport.kt`), but they show the addresses of your accounts. A signing request shows the transaction it asks you to sign. The Android reference for `FLAG_SECURE` covers screenshots and non-secure displays, not a photo taken with a second camera ([WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)).
- **The download.** Downloading the APK from GitHub sends a request from your network to GitHub, which can see your IP address and the time of the download.

AirVault does not claim that an erased phone looks like one that never held a vault. Two open checks in [auditing-airvault.md](auditing-airvault.md) ask whether a vault can be detected: the on-disk file names, and photo capture before and after setup. Neither check is settled.

## Installing and Updating

Lensly is not on Google Play. It has no account, no push service and no update check. It reaches a phone only as a signed APK from GitHub Releases, or as a build you make from source.

- Lensly's manifest requests no `INTERNET` permission, and it removes `ACCESS_NETWORK_STATE`, which a library would otherwise add (`app/src/main/AndroidManifest.xml`). The release workflow fails if either permission is in the APK (`.github/workflows/release.yml`). Lensly cannot open a network connection, so it cannot check for updates. Its catalog updates arrive as signed packages on removable storage (`shared/src/main/java/app/lensly/core/registry/update/RegistryUpdate.kt`).
- Lumio is released the same way, and it does use the network.
- Each release publishes the APK and `SHA256SUMS`, a build provenance attestation, and the APK and signing-certificate SHA-256 digests in its notes (`docs/releasing.md`).

To install Lensly:

1. On an online computer, download the APK and `SHA256SUMS` from the release page.
2. Check the APK's hash against `SHA256SUMS`: `shasum -a 256 <apk>` on macOS, or `sha256sum <apk>` on Linux.
3. Check the signing certificate with `apksigner verify --print-certs <apk>`, from the Android SDK build-tools. Compare the certificate SHA-256 digest with the one in the release notes.
4. Move the APK to the offline phone by USB with `adb install -r <apk>`, or by file copy and a normal install.

A hash from the same release page shows that the file matches that page. It does not show who signed the file. Only the certificate digest, compared with the digest of an earlier release you installed, shows that the signing key is the same.

For updates:

- Android installs an update only if it is signed with the same certificate as the installed app. A different certificate is refused ([Android app signing](https://developer.android.com/studio/publish/app-signing)).
- Updating with the same key keeps the app's data. `adb install -r` says so: it reinstalls "an existing app, keeping its data" ([adb reference](https://developer.android.com/tools/adb)).

### Keeping Android Open

An offline app depends on sideloading staying possible. If Android stopped people from installing apps outside Google Play, Lensly could not be installed on a phone without a store account.

Google has announced developer verification for sideloaded apps. Its 19 March 2026 post describes an advanced flow for experienced users who want to keep installing apps from unverified developers. The flow needs developer mode, a restart, and a one-day wait before you confirm with a biometric or PIN. It is a one-time process ([Android Developers Blog](https://developer.android.com/blog/posts/android-developer-verification-balancing-openness-and-choice-with-safety)). Check [the developer verification page](https://developer.android.com/developer-verification) for the current schedule.

If Android requires developer verification for sideloaded apps, AirVault will register its signing identity with Android rather than move to Google Play.

Obtainium applies to Lumio only, the companion app on the online phone. It can track Lumio's GitHub releases and install their APKs. It is an optional way to update Lumio, and the checks above are then yours to make for each update.

Never install Obtainium, or any other app that checks for updates over a network, on the Lensly phone. Lensly is updated only by manual sideload transfer: follow the steps above, then move the APK to the offline phone over USB with `adb install -r`, after the hash and signing-certificate checks pass.
