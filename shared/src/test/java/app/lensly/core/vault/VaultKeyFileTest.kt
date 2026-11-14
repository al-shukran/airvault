package app.lensly.core.vault

import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class VaultKeyFileTest {
    private val random = SecureRandom()
    private val keyA = ByteArray(32) { (it + 1).toByte() }
    private val keyB = ByteArray(32) { (it + 101).toByte() }

    /** A sealed blob of the size a slot carries: a software IV, a hardware blob, and a tag. */
    private fun sealed(): ByteArray = ByteArray(VaultKeyFile.SLOT_BYTES - 32) { random.nextInt().toByte() }

    @Test
    fun theFormatSizesAreFixed() {
        // Changing these sizes changes the file every vault holds, so a change must be a deliberate format change.
        assertEquals(121, VaultKeyFile.SLOT_BYTES)
        assertEquals(247, VaultKeyFile.FILE_BYTES)
    }

    @Test
    fun aFileHoldsTwoSlotsOfTheSameSize() {
        val file = VaultKeyFile.encode(listOf(VaultKeyFile.slotOf(keyA, sealed(), random), randomSlot()), random)

        assertEquals(VaultKeyFile.FILE_BYTES, file.size)
        val contents = VaultKeyFile.decode(file)
        assertFalse(contents.legacy)
        assertEquals(2, contents.slots.size)
        assertTrue(contents.slots.all { it.size == VaultKeyFile.SLOT_BYTES })
    }

    @Test
    fun theTwoSlotsAreWrittenInEitherOrder() {
        val real = VaultKeyFile.slotOf(keyA, sealed(), random)
        val decoy = randomSlot()

        val firstIsReal = (1..40).map { _ ->
            val contents = VaultKeyFile.decode(VaultKeyFile.encode(listOf(real, decoy), random))
            contents.slots[0].contentEquals(real)
        }

        assertTrue(firstIsReal.any { it }, "The real slot is never first.")
        assertTrue(firstIsReal.any { !it }, "The real slot is never second.")
    }

    @Test
    fun theSlotOfAVaultIsFoundByItsKeyAndByNoOther() {
        val real = VaultKeyFile.slotOf(keyA, sealed(), random)
        val file = VaultKeyFile.encode(listOf(real, randomSlot()), random)
        val contents = VaultKeyFile.decode(file)

        val index = VaultKeyFile.ownerIndex(contents, keyA)
        assertEquals(index, contents.slots.indexOfFirst { it.contentEquals(real) })
        assertEquals(-1, VaultKeyFile.ownerIndex(contents, keyB))
    }

    @Test
    fun aRandomSlotBelongsToNoVault() {
        val contents = VaultKeyFile.decode(VaultKeyFile.encode(listOf(randomSlot(), randomSlot()), random))

        assertEquals(-1, VaultKeyFile.ownerIndex(contents, keyA))
        assertEquals(-1, VaultKeyFile.ownerIndex(contents, keyB))
    }

    @Test
    fun theSealedBlobIsWhatFollowsTheNonceAndTag() {
        val blob = sealed()
        val slot = VaultKeyFile.slotOf(keyA, blob, random)

        assertContentEquals(blob, VaultKeyFile.sealedPartOf(slot))
        val contents = VaultKeyFile.decode(VaultKeyFile.encode(listOf(slot, randomSlot()), random))
        assertContentEquals(blob, contents.sealedOf(VaultKeyFile.ownerIndex(contents, keyA)))
    }

    @Test
    fun aRandomDecoySlotIsTheSameSizeAsARealSlot() {
        val real = VaultKeyFile.slotOf(keyA, sealed(), random)

        assertEquals(real.size, randomSlot().size)
    }

    @Test
    fun aLegacyFileIsReadAsOneBlobWithNoOwner() {
        val blob = ByteArray(88) { random.nextInt().toByte() }
        val contents = VaultKeyFile.decode(VaultKeyFile.encodeLegacy(blob))

        assertTrue(contents.legacy)
        assertContentEquals(blob, contents.sealedOf(0))
        assertEquals(-1, VaultKeyFile.ownerIndex(contents, keyA))
    }

    @Test
    fun aFileOfAnyOtherSizeIsDamaged() {
        val file = VaultKeyFile.encode(listOf(randomSlot(), randomSlot()), random)

        assertFailsWith<VaultException> { VaultKeyFile.decode(file.copyOf(file.size - 1)) }
        assertFailsWith<VaultException> { VaultKeyFile.decode(file + byteArrayOf(0)) }
    }

    @Test
    fun aFileWithAnotherMagicIsDamaged() {
        val file = VaultKeyFile.encode(listOf(randomSlot(), randomSlot()), random)
        file[0] = 'X'.code.toByte()

        assertFailsWith<VaultException> { VaultKeyFile.decode(file) }
    }

    @Test
    fun encodingNeedsExactlyTwoSlotsOfSlotSize() {
        assertFailsWith<IllegalArgumentException> { VaultKeyFile.encode(listOf(randomSlot()), random) }
        assertFailsWith<IllegalArgumentException> {
            VaultKeyFile.encode(listOf(randomSlot(), ByteArray(10)), random)
        }
    }

    private fun randomSlot(): ByteArray = VaultKeyFile.randomSlot(random)
}
