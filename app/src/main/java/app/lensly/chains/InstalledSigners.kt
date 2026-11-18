package app.lensly.chains

import app.airvault.chains.algorand.signer.AlgorandSigner
import app.airvault.core.signer.SignerApp

/**
 * The chain apps Lensly is built with (spec 2.4). Adding a chain adds its signer here and nowhere else. Nothing reads
 * this list yet: the registry that validates it is built when the first chain uses it.
 */
val installedSigners: List<SignerApp> = listOf(AlgorandSigner)
