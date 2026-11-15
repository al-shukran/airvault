package app.lensly.core.registry

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.web3j.crypto.Keys

/**
 * Checks the candidate Registry Whitelist in tools/registry-data/approved-contracts: the approvals that are not shipped, and
 * that an owner signs into a package. It must load through the same loader the application uses, every contract address must
 * carry its EIP-55 checksum as the source published it, and every entry must cite the source it was read from.
 */
class CandidateWhitelistTest {
    private val networks = File("src/main/assets/registry/networks.json").readText()
    private val tokens = File("src/main/assets/registry/tokens.json").readText()
    private val candidate = File("../tools/registry-data/approved-contracts/candidate-whitelist.json").readText()
    private val sources: List<SourceRow> = Json.decodeFromString(
        File("../tools/registry-data/approved-contracts/candidate-whitelist-sources.json").readText(),
    )
    private val registry: Registry = RegistryLoader.load { path ->
        when (path) {
            RegistryLoader.NETWORKS_PATH -> networks
            RegistryLoader.TOKENS_PATH -> tokens
            RegistryLoader.WHITELIST_PATH -> candidate
            else -> null
        }
    }
    private val entries: List<WhitelistEntry> = registry.whitelistEntries()

    @Serializable
    private data class SourceRow(
        val chainId: Long,
        val contract: String,
        val selector: String,
        val contractChecksum: String,
        val source: String,
        val implementationChecksum: String? = null,
        val implementationEvidence: String? = null,
    )

    @Test
    fun theCandidateLoadsThroughTheRegistryLoaderAndEveryChainIsInTheCatalog() {
        // Registry's constructor refuses a whitelist entry on an unknown chain, so a load that succeeds already proves it.
        assertTrue(entries.isNotEmpty(), "the candidate whitelist has no entries")
        assertEquals(emptyList(), entries.filter { registry.network(it.chainId) == null }.map { it.signature })
    }

    @Test
    fun noChainAndAddressPairIsListedTwiceForOneSelector() {
        val keys = entries.map { Triple(it.chainId, it.contract, it.selector) }

        assertEquals(keys.toSet().size, keys.size, "duplicate (chainId, contract, selector) in the candidate")
    }

    @Test
    fun everyContractAndImplementationCarriesItsEip55ChecksumAsPublished() {
        val bad = sources.filterNot { row ->
            Keys.toChecksumAddress(row.contract) == row.contractChecksum &&
                row.contractChecksum.lowercase() == row.contract
        }.map { "${it.chainId} ${it.contract}" }
        assertEquals(emptyList(), bad, "contract checksum does not match its address")

        val badImplementations = sources.filter { it.implementationChecksum != null }.filterNot { row ->
            val entry = entries.single { it.chainId == row.chainId && it.contract == row.contract && it.selector == row.selector }
            val implementation = entry.proxy?.implementationAddress
            implementation != null && Keys.toChecksumAddress(implementation) == row.implementationChecksum
        }.map { "${it.chainId} ${it.contract}" }
        assertEquals(emptyList(), badImplementations, "implementation checksum does not match the proxy binding")
    }

    @Test
    fun everyEntryCitesOneSourceAndTheSourcesMatchTheEntriesOneToOne() {
        val entryKeys = entries.map { Triple(it.chainId, it.contract, it.selector) }.toSet()
        val sourceKeys = sources.map { Triple(it.chainId, it.contract, it.selector) }

        assertEquals(sourceKeys.toSet().size, sourceKeys.size, "a source row is listed twice")
        assertEquals(entryKeys, sourceKeys.toSet(), "source rows and entries differ")
        assertTrue(sources.all { it.source.startsWith("https://github.com/") }, "every source must be a GitHub page")
    }

    @Test
    fun aProxyEntryIsBoundAndANonProxyEntryIsNot() {
        val unbound = entries.filter { it.upgradeable && it.proxy == null }
        val bound = entries.filter { !it.upgradeable && it.proxy != null }

        assertEquals(emptyList(), unbound.map { it.signature }, "an upgradeable entry has no proxy binding")
        assertEquals(emptyList(), bound.map { it.signature }, "a non-upgradeable entry names a proxy binding")
    }

    @Test
    fun eachContractOnEachChainHasOneProtocolAndTheMeaningNamesIt() {
        val protocols = entries.groupBy { it.chainId to it.contract }
            .mapValues { (_, group) -> group.map { protocolOf(it) }.toSet() }
        val split = protocols.filterValues { it.size != 1 }.keys

        assertEquals(emptySet(), split, "a contract on one chain names two protocols")
        val unnamed = entries.filter { protocolOf(it).isEmpty() }.map { it.signature }
        assertEquals(emptyList(), unnamed, "a meaning must start with its protocol, then a colon")
    }

    @Test
    fun everyMeaningFitsThePackageRuleOfPrintableTextUpTo240Characters() {
        val bad = entries.filterNot { entry ->
            entry.meaning.length <= MEANING_LIMIT && entry.meaning.all { it.code in PRINTABLE_FROM..PRINTABLE_TO }
        }.map { it.signature }

        assertEquals(emptyList(), bad, "a meaning is longer than 240 characters or is not printable")
    }

    /** The protocol of an entry, as the application reads it: the meaning's text before its first colon. */
    private fun protocolOf(entry: WhitelistEntry): String = entry.meaning.substringBefore(':', "").trim()

    private companion object {
        const val MEANING_LIMIT = 240
        const val PRINTABLE_FROM = 0x20
        const val PRINTABLE_TO = 0x7E
    }
}
