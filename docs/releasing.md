# Releasing

GitHub Actions builds, signs and publishes every release: `.github/workflows/release.yml`. Nothing is built or uploaded from a
laptop, and the signing keys never enter the repository.

## Cut a Release

1. Set `versionName` in `app/build.gradle.kts` (Lensly) or `lumio/build.gradle.kts` (Lumio), and merge that change to master.
2. Tag the master commit you are releasing, then push only that tag:

   ```sh
   git tag lensly-v0.1.0      # or lumio-v0.1.0
   git push origin lensly-v0.1.0
   ```

3. The workflow builds `:app:assembleRelease` or `:lumio:assembleRelease` with R8, aligns and signs the APK, verifies it, and
   creates a GitHub release with the APK, `SHA256SUMS`, a build provenance attestation, and the APK and signing-certificate
   SHA-256 digests in the notes.

The workflow fails before it builds if the tag version differs from the module's `versionName`. A version that starts with `0.`
or has a suffix such as `-rc.1` is published as a prerelease. Lensly's APK must not request `android.permission.INTERNET`, so
the workflow fails if it does.

The version code is the commit count of HEAD, so the workflow clones the full history. Do not change its `fetch-depth`.

The release notes come from `.github/release-notes/lensly.md` and `.github/release-notes/lumio.md`.

## Signing Secrets

Store these under Settings > Secrets and variables > Actions. The workflow reads them and never prints them.

| Secret | Contents |
| --- | --- |
| `LENSLY_KEYSTORE_B64` | The Lensly keystore, base64 encoded (`base64 -i lensly.keystore`). Its key alias is `lensly`. |
| `LENSLY_KEYSTORE_PASS` | The Lensly keystore password. The key password must be the same. |
| `LUMIO_KEYSTORE_B64` | The Lumio keystore, base64 encoded. Its key alias is `lumio`. |
| `LUMIO_KEYSTORE_PASS` | The Lumio keystore password. The key password must be the same. |

Keep the keystore files and their passwords in a password manager or another secure store, never in the repository, a
release note, an issue or a pull request. A lost keystore means installed copies can never be updated, because Android
accepts an update only when it carries the same signing key.
