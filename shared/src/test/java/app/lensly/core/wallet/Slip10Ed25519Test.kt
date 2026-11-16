package app.lensly.core.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.web3j.utils.Numeric

/**
 * SLIP-0010 Ed25519 test vectors 1 and 2, read from `vectors/slip10/vectors.txt`. Every chain step of both vectors is derived from
 * its parent and compared with the published private key, public key (with its `00` prefix) and chain code.
 */
class Slip10Ed25519Test {

    private class Row(
        val vector: Int,
        val path: String,
        val chainCode: String,
        val privateKey: String,
        val publicKey: String,
    )

    /** Seed (hex) of vector 1 and vector 2, from the headings of the specification. */
    private val seeds = mapOf(
        1 to "000102030405060708090a0b0c0d0e0f",
        2 to "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542",
    )

    @Test
    fun everyChainStepMatchesThePublishedKeysAndChainCode() {
        val rows = rows()
        assertEquals(12, rows.size, "six chain steps in each of vectors 1 and 2")
        val nodes = HashMap<String, Slip10Node>()
        try {
            for (row in rows) {
                val id = "vector ${row.vector} ${row.path}"
                val node = if (row.path == "m") {
                    Slip10Ed25519.master(seed(row.vector))
                } else {
                    val parentId = "vector ${row.vector} ${row.path.substringBeforeLast('/')}"
                    val parent = checkNotNull(nodes[parentId]) { "$parentId must come before $id" }
                    Slip10Ed25519.child(parent, childNumber(row.path.substringAfterLast('/')))
                }
                nodes[id] = node
                assertEquals(row.privateKey, node.privateKey().toHex(), "private key at $id")
                assertEquals(row.publicKey, "00" + node.publicKey().toHex(), "public key at $id")
                assertEquals(row.chainCode, node.chainCode().toHex(), "chain code at $id")
            }
        } finally {
            nodes.values.forEach { it.wipe() }
        }
    }

    @Test
    fun deriveReachesTheDeepestPublishedNodeOfEachVector() {
        for (vector in listOf(1, 2)) {
            val last = rows().last { it.vector == vector }
            val path = last.path.split('/').drop(1).map { childNumber(it) }.toIntArray()
            Slip10Ed25519.derive(seed(vector), path).use { node ->
                assertEquals(last.privateKey, node.privateKey().toHex(), "private key at ${last.path}")
                assertEquals(last.publicKey, "00" + node.publicKey().toHex(), "public key at ${last.path}")
                assertEquals(last.chainCode, node.chainCode().toHex(), "chain code at ${last.path}")
            }
        }
    }

    @Test
    fun nonHardenedChildNumbersAreRefused() {
        val seed = seed(1)
        Slip10Ed25519.master(seed).use { master ->
            for (childNumber in listOf(0, 1, 44, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException>("child number $childNumber") {
                    Slip10Ed25519.child(master, childNumber)
                }
            }
            assertFailsWith<IllegalArgumentException> {
                Slip10Ed25519.derive(seed, intArrayOf(Slip10Ed25519.hardened(44), 501))
            }
        }
    }

    @Test
    fun hardenedSetsTheTopBitAndRefusesNegativeIndexes() {
        assertEquals(Int.MIN_VALUE, Slip10Ed25519.hardened(0))
        assertEquals(44 or Int.MIN_VALUE, Slip10Ed25519.hardened(44))
        assertEquals(-1, Slip10Ed25519.hardened(Int.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { Slip10Ed25519.hardened(-1) }
    }

    @Test
    fun seedMustBeSixteenToSixtyFourBytes() {
        assertFailsWith<IllegalArgumentException> { Slip10Ed25519.master(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { Slip10Ed25519.master(ByteArray(65)) }
        Slip10Ed25519.master(ByteArray(16)).close()
        Slip10Ed25519.master(ByteArray(64)).close()
    }

    @Test
    fun wipedNodeGivesNoKeyOrChainCode() {
        val node = Slip10Ed25519.master(seed(1))
        node.wipe()
        node.wipe()
        assertFailsWith<IllegalStateException> { node.privateKey() }
        assertFailsWith<IllegalStateException> { node.chainCode() }
        assertFailsWith<IllegalStateException> { node.publicKey() }
    }

    private fun seed(vector: Int): ByteArray = Numeric.hexStringToByteArray(seeds.getValue(vector))

    /** A path element such as `44H` as a child number with the hardened bit set. */
    private fun childNumber(element: String): Int {
        require(element.endsWith("H")) { "vector paths are hardened, found $element" }
        return Slip10Ed25519.hardened(element.dropLast(1).toInt())
    }

    private fun rows(): List<Row> {
        val path = "/vectors/slip10/vectors.txt"
        val text = checkNotNull(Slip10Ed25519Test::class.java.getResource(path)) { "missing vector $path" }.readText()
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .map { row ->
                require(row.size == 5) { "SLIP-0010 row must have 5 columns, found ${row.size}" }
                Row(row[0].toInt(), row[1], row[2], row[3], row[4])
            }
            .toList()
    }
}
