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

3. The workflow runs three kinds of job on separate runners:
   - `build` builds `:app:assembleRelease` or `:lumio:assembleRelease` with R8. It has no secrets and read-only permissions, and
     uploads the unsigned APK with its SHA-256.
   - `sign_lensly` or `sign_lumio` (the one for the tag) runs no Gradle. It checks the APK against that SHA-256, refuses version
     code 1, then aligns and signs it with the keystore of the `release` environment, using `scripts/ci/sign-release.sh`.
   - `publish` has no secrets. It verifies the signed APK and creates a GitHub release with the APK, `SHA256SUMS`, a build
     provenance attestation, and the APK and signing-certificate SHA-256 digests in the notes.

The workflow fails before it builds if the tag version differs from the module's `versionName`. A version that starts with `0.`
or has a suffix such as `-rc.1` is published as a prerelease. Lensly's APK must not request `android.permission.INTERNET`, so
the workflow fails if it does.

The version code is the commit count of HEAD, so the workflow clones the full history. Do not change its `fetch-depth`.

The release notes come from `.github/release-notes/lensly.md` and `.github/release-notes/lumio.md`.

## Signing Secrets

The four secrets are secrets of the `release` environment (Settings > Environments > release), not repository secrets. The
environment allows deployments only from the tags `lensly-v*` and `lumio-v*`, so a workflow pushed on any branch cannot read
them. Do not add them as repository secrets: every workflow run on a branch would receive those. The sign jobs read them and
never print them, and each sign job gets the two secrets of its own app only.

| Secret | Contents |
| --- | --- |
| `LENSLY_KEYSTORE_B64` | The Lensly keystore, base64 encoded (`base64 -i lensly.keystore`). Its key alias is `lensly`. |
| `LENSLY_KEYSTORE_PASS` | The Lensly keystore password, with no trailing newline. The key password must be the same. |
| `LUMIO_KEYSTORE_B64` | The Lumio keystore, base64 encoded. Its key alias is `lumio`. |
| `LUMIO_KEYSTORE_PASS` | The Lumio keystore password, with no trailing newline. The key password must be the same. |

`scripts/ci/check-workflow-guards.sh` fails a pull request when a job that reads one of these secrets lacks `environment: release`,
has more than `contents: read`, names Gradle, reads the secrets of both apps, or has any step other than these six, in this order:
a checkout pinned by SHA, the refusal of a tag that is not on master, `actions/download-artifact` of the unsigned APK,
`bash scripts/ci/sign-release.sh`, the keystore cleanup and `actions/upload-artifact` of the signed APK. Changing a sign job
means changing the allowlist in the guard (`SIGN_STEPS`) in the same pull request.

## What the Build and Sign Split Protects

The split keeps the signing keys away from project build code. Gradle, the build plugins and every dependency run in the `build`
job, which has no secrets. A sign job reads the keys but runs no Gradle and only the allowlisted steps above (its one script is
`scripts/ci/sign-release.sh`), so a poisoned dependency or build script cannot read a keystore.

The split does not protect the integrity of the APK:

- The `build` job controls both the unsigned APK and the SHA-256 it reports. The sign job compares the two, which catches a
  swap in transit and nothing else. A malicious build can produce a malicious APK with a matching hash, and the sign job signs it.
- `aapt2`, `zipalign` and `apksigner` parse that APK on the key-holding runner. A malicious APK reaches those tools while the
  keystore is on disk.
- The `publish` job checks that the APK is signed with the v2 and v3 schemes by a single certificate. It does not compare the
  certificate with an expected fingerprint.

What keeps the build honest is review: only commits on master are released, and the guard refuses a tag whose commit is not.

Keep the keystore files and their passwords in a password manager or another secure store, never in the repository, a
release note, an issue or a pull request. A lost keystore means installed copies can never be updated, because Android
accepts an update only when it carries the same signing key.
