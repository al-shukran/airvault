# chains/_template

A stub chain app to copy when a new chain is added (spec 2.5, walkthrough 10.1). It is not
registered in `InstalledSigners.kt` or `InstalledWallets.kt`, and `:app` and `:lumio` do not
depend on it. It builds only so that it stays compiling.

Every member that does real work is a `TODO("template: …")`. The conformance test
`ConformanceKitTest.theTemplateFailsTheKitOnlyAtItsTodoStubs` pins that the kit stops at those stubs.

To add a chain `<id>`:

1. Copy the three modules: `cp -R chains/_template chains/<id>`.
2. Rename the package `app.airvault.chains.template` to `app.airvault.chains.<id>` in every file,
   and rename `templateManifest`, `TemplateSigner`, `TemplateWallet`, `TemplateAddressCodec` and
   `TemplateRequestParser` to match. Change the `:chains:_template:*` project paths in the three
   `build.gradle.kts` files.
3. Replace the three `include(":chains:_template:…")` lines in `settings.gradle.kts` with the copy's.
4. Fill each `TODO` (manifest first, spec 2.1), then the parser, signing, addresses and pairing.
5. Remove `theTemplateFailsTheKitOnlyAtItsTodoStubs` from `ConformanceKitTest.kt`. The copy's
   signer is on the conformance test classpath already (`:chains:<id>:signer` matches the filter in
   `core/conformance/build.gradle.kts`), so add it to `everySignerPassesTheManifestChecks` and the
   key-scope test instead.
6. Add the signer to `InstalledSigners.kt` and the wallet to `InstalledWallets.kt`, and the wallet
   dependency to `lumio/build.gradle.kts` (spec 2.5 step 4). Add the CI partition arm for the new
   test modules (`scripts/ci/run-partition.sh`).

There is no generator (spec 12, item 6). Copy by hand.
