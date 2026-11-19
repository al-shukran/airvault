package app.lensly.chains

import app.airvault.chains.algorand.signer.AlgorandSigner
import app.airvault.chains.bitcoin.signer.BitcoinSigner
import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.chains.solana.signer.SolanaSigner
import app.airvault.core.model.ChainRegistry
import app.airvault.core.signer.SignerApp

/**
 * The chain apps Lensly is built with (spec 2.4). Adding a chain adds its signer here and nowhere else. The list is in :shared, not
 * :app, because the Parser Sandbox routes scanned requests by it, and the sandbox is in :shared.
 */
val installedSigners: List<SignerApp> = listOf(AlgorandSigner, BitcoinSigner, SolanaSigner, EvmSigner)

/** The registry of [installedSigners], which the Parser Sandbox asks which app parses a scanned request's UR type. */
val signerRegistry: ChainRegistry<SignerApp> = ChainRegistry(installedSigners)
