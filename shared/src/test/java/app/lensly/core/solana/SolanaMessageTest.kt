package app.lensly.core.solana

import app.airvault.chains.solana.signer.MalformedSolanaMessageException
import app.airvault.chains.solana.signer.SolanaMessage
import app.airvault.chains.solana.signer.SolanaMessageAccount
import app.airvault.chains.solana.signer.SolanaMessageReader
import app.airvault.chains.solana.signer.parseSolanaMessage
import app.lensly.core.wallet.toHex
import fr.acinq.bitcoin.Base58
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SolanaMessageTest {

    /** The message [name] parses to, through the public [SolanaMessage.decode]. */
    private fun parsed(name: String): SolanaMessage =
        assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(SolanaVectors[name]), "$name should parse").message

    /** The reason [bytes] are refused for. Any exception other than the parser's own fails the test. */
    private fun refusal(bytes: ByteArray): String =
        assertFailsWith<MalformedSolanaMessageException> { parseSolanaMessage(bytes) }.message.orEmpty()

    private fun keys(message: SolanaMessage): List<String> = message.accountKeys.map { Base58.encode(it) }

    @Test
    fun legacyTransferParsesToItsFields() {
        val message = parsed("legacy-sol-transfer")
        assertEquals(SolanaMessage.Version.LEGACY, message.version)
        assertEquals(1, message.header.numRequiredSignatures)
        assertEquals(0, message.header.numReadonlySignedAccounts)
        assertEquals(1, message.header.numReadonlyUnsignedAccounts)
        assertEquals(listOf(SolanaVectors.PAYER, SolanaVectors.RECIPIENT, SolanaVectors.SYSTEM), keys(message))
        assertEquals(SolanaVectors.PAYER, message.feePayer)
        assertEquals(SolanaVectors.BLOCKHASH, Base58.encode(message.recentBlockhash))
        assertEquals(3, message.accountCount)
        assertEquals(emptyList(), message.addressTableLookups)
        val instruction = message.instructions.single()
        assertEquals(2, instruction.programIdIndex)
        assertEquals(listOf(0, 1), instruction.accountIndices)
        assertEquals("02000000002f685900000000", instruction.data.toHex())
    }

    @Test
    fun v0TransferLoadsTheRecipientFromOneLookupTable() {
        val message = parsed("v0-sol-transfer-alt")
        assertEquals(SolanaMessage.Version.V0, message.version)
        assertEquals(listOf(SolanaVectors.PAYER, SolanaVectors.SYSTEM), keys(message))
        val lookup = message.addressTableLookups.single()
        assertEquals(SolanaVectors.TABLE, Base58.encode(lookup.tableKey))
        assertEquals(listOf(1), lookup.writableIndexes)
        assertEquals(emptyList(), lookup.readonlyIndexes)
        assertEquals(3, message.accountCount)
        // Index 2 is the first loaded account, the table's writable entry at index 1.
        assertEquals(SolanaMessageAccount.Lookup(SolanaVectors.TABLE, 1, writable = true), message.account(2))
        assertEquals(listOf(0, 2), message.instructions.single().accountIndices)
    }

    @Test
    fun loadedAccountsAreWritableThenReadonly() {
        val message = parsed("v0-alt-readonly-raw")
        assertEquals(listOf(SolanaVectors.PAYER, SolanaVectors.SYSTEM, SolanaVectors.UNKNOWN), keys(message))
        assertEquals(5, message.accountCount)
        assertEquals(SolanaMessageAccount.Lookup(SolanaVectors.TABLE, 0, writable = true), message.account(3))
        assertEquals(SolanaMessageAccount.Lookup(SolanaVectors.TABLE, 1, writable = false), message.account(4))
    }

    @Test
    fun theWellFormedVectorsParseWithTheirVersion() {
        for (name in SolanaVectors.WELL_FORMED) {
            val expected = if (name.startsWith("v0")) SolanaMessage.Version.V0 else SolanaMessage.Version.LEGACY
            assertEquals(expected, parsed(name).version, name)
        }
        assertEquals(0, parsed("v0-token-programs").addressTableLookups.size)
        assertEquals(1232, SolanaVectors["legacy-max-size"].size)
    }

    @Test
    fun aVersionPrefixOtherThanZeroIsRefused() {
        for (prefix in 0x81..0xFF) {
            val bytes = SolanaVectors["v0-sol-transfer-alt"]
            bytes[0] = prefix.toByte()
            assertContains(refusal(bytes), "unsupported message version ${prefix and 0x7F}")
        }
    }

    @Test
    fun everyTruncationIsRefused() {
        for (name in listOf("legacy-sol-transfer", "v0-sol-transfer-alt")) {
            val full = SolanaVectors[name]
            for (length in 0 until full.size) {
                refusal(full.copyOf(length))
            }
        }
    }

    @Test
    fun aTrailingByteIsRefused() {
        val bytes = SolanaVectors["legacy-sol-transfer"] + byteArrayOf(0)
        assertContains(refusal(bytes), "1 byte(s) follow the end of the message")
    }

    @Test
    fun anAccountIndexPastTheAccountsIsRefused() {
        // The legacy transfer ends with its only instruction: program index, account count, two account indices, data length, data.
        // The second account index is 14 bytes from the end.
        val bytes = SolanaVectors["legacy-sol-transfer"]
        assertEquals(1, bytes[bytes.size - 14].toInt())
        bytes[bytes.size - 14] = 3
        assertContains(refusal(bytes), "account index 3 is out of range (3 accounts)")
    }

    @Test
    fun aLoadedAccountIndexIsCountedFromTheLookups() {
        // The v0 transfer ends with its lookup section, 36 bytes, and its instruction is the 17 bytes before that. The recipient is
        // the second account index of the instruction, index 2: the first loaded account.
        val bytes = SolanaVectors["v0-sol-transfer-alt"]
        val secondAccount = bytes.size - 36 - 17 + 3
        assertEquals(2, bytes[secondAccount].toInt())
        bytes[secondAccount] = 3
        assertContains(refusal(bytes), "account index 3 is out of range (3 accounts)")
    }

    @Test
    fun theFeePayerCannotBeTheProgram() {
        val bytes = SolanaVectors["legacy-sol-transfer"]
        bytes[bytes.size - 17] = 0
        assertContains(refusal(bytes), "instruction 0: the fee payer cannot be the program")
    }

    @Test
    fun aProgramIndexPastTheAccountsIsRefused() {
        val bytes = SolanaVectors["legacy-sol-transfer"]
        bytes[bytes.size - 17] = 3
        assertContains(refusal(bytes), "program index 3 is out of range (3 accounts)")
    }

    @Test
    fun theFeePayerMustBeAWritableSigner() {
        assertContains(refusal(SolanaVectors["legacy-sol-transfer"].also { it[0] = 0 }), "the fee payer must be a writable signer")
        assertContains(refusal(SolanaVectors["legacy-sol-transfer"].also { it[1] = 1 }), "the fee payer must be a writable signer")
    }

    @Test
    fun theHeaderCannotNameMoreAccountsThanTheStaticKeys() {
        val bytes = SolanaVectors["legacy-sol-transfer"].also { it[2] = 3 }
        assertContains(refusal(bytes), "the header names more accounts than the message lists")
    }

    @Test
    fun theSizeLimitIsInclusive() {
        assertEquals(1233, SolanaVectors["legacy-over-size"].size)
        parsed("legacy-max-size")
        assertContains(refusal(SolanaVectors["legacy-over-size"]), "the message is 1233 bytes; the limit is 1232")
        assertIs<SolanaMessage.Result.Rejected>(SolanaMessage.decode(SolanaVectors["legacy-over-size"]))
    }

    @Test
    fun aMessageNamingMoreThan256AccountsIsRefused() {
        // One static key and one lookup table with 200 writable and 60 readonly indexes: 261 accounts and no instruction.
        val out = ByteArrayOutputStream()
        out.write(0x80)
        out.write(byteArrayOf(1, 0, 0))
        out.write(1)
        out.write(ByteArray(32) { 1 })
        out.write(ByteArray(32))
        out.write(0)
        out.write(1)
        out.write(ByteArray(32) { 2 })
        out.write(byteArrayOf(0xC8.toByte(), 0x01))
        out.write(ByteArray(200) { it.toByte() })
        out.write(60)
        out.write(ByteArray(60) { it.toByte() })
        assertContains(refusal(out.toByteArray()), "the message names 261 accounts")
    }

    @Test
    fun compactU16IsReadInItsShortestForm() {
        assertEquals(0, compactU16(0x00))
        assertEquals(127, compactU16(0x7F))
        assertEquals(128, compactU16(0x80, 0x01))
        assertEquals(16383, compactU16(0xFF, 0x7F))
        assertEquals(16384, compactU16(0x80, 0x80, 0x01))
        assertEquals(65535, compactU16(0xFF, 0xFF, 0x03))
    }

    @Test
    fun compactU16RefusesOtherForms() {
        assertFailsWith<MalformedSolanaMessageException> { compactU16(0x80, 0x00) }  // a longer form of 0
        assertFailsWith<MalformedSolanaMessageException> { compactU16(0x80, 0x80, 0x00) }  // a longer form of 0
        assertFailsWith<MalformedSolanaMessageException> { compactU16(0xFF, 0xFF, 0x04) }  // 65536 and up
        assertFailsWith<MalformedSolanaMessageException> { compactU16(0x80, 0x80, 0x80, 0x01) }  // a fourth byte
        assertFailsWith<MalformedSolanaMessageException> { compactU16(0x80) }  // truncated
    }

    @Test
    fun aSingleChangedByteNeverEscapesTheParser() {
        // Every byte of every well-formed vector, set to a few boundary values. Refusals are fine; any other exception fails the test.
        for (name in SolanaVectors.WELL_FORMED) {
            val original = SolanaVectors[name]
            for (index in original.indices) {
                for (value in listOf(0x00, 0x01, 0x7F, 0x80, 0xFF)) {
                    val bytes = original.copyOf()
                    bytes[index] = value.toByte()
                    try {
                        parseSolanaMessage(bytes).decodeInstructions()
                    } catch (refused: MalformedSolanaMessageException) {
                        // Refused with a reason, as intended.
                    }
                }
            }
        }
    }

    private fun compactU16(vararg bytes: Int): Int =
        SolanaMessageReader(ByteArray(bytes.size) { bytes[it].toByte() }).shortU16("a length")
}
