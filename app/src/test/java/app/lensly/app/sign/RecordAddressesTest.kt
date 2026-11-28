package app.lensly.app.sign

import app.airvault.chains.algorand.AlgorandNetwork
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.SOLANA_FAMILY
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Which addresses the Activity detail lists for a record, on each chain. A value is listed only when it is an address on the record's own
 * chain, so a legacy Bitcoin address in a Solana row is text, and a number that has the shape of a Solana address is not one.
 */
class RecordAddressesTest {

    private val bitcoinPaid = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
    private val bitcoinChange = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val evmSender = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f"
    private val evmRecipient = "0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2"
    private val evmContract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val evmSpender = "0x1111111254eeb25477b68fb85ed929f73a960582"
    private val solanaSigner = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val solanaProgram = "11111111111111111111111111111111"
    private val solanaMint = "SysvarRent111111111111111111111111111111111"
    private val algorandSender = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
    private val algorandReceiver = "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U"

    private fun record(
        kind: ReviewKind = ReviewKind.TRANSACTION,
        networkName: String? = null,
        address: String? = null,
        to: String? = null,
        tokenContract: String? = null,
        fee: List<Pair<String, String>> = emptyList(),
        params: List<Pair<String, String>> = emptyList(),
        bitcoin: BitcoinRecordFacts? = null,
    ) = ReviewRecord(
        atMs = 1_700_000_000_000L,
        outcome = ReviewOutcome.SIGNED,
        kind = kind,
        networkName = networkName,
        accountIndex = 0,
        address = address,
        function = null,
        to = to,
        amount = null,
        tokenContract = tokenContract,
        fee = fee,
        params = params,
        origin = null,
        refusal = null,
        dataHash = "00",
        bitcoin = bitcoin,
    )

    @Test
    fun aBitcoinRecordListsEachOutputAddressAndMarksItsChange() {
        val facts = BitcoinRecordFacts(
            scriptType = "Native SegWit",
            accountAddress = "tb1qaccount",
            outputs = listOf(
                BitcoinRecordOutput(address = bitcoinPaid, amountSats = 30_000, isOwnChange = false),
                BitcoinRecordOutput(address = null, amountSats = 0, isOwnChange = false),
                BitcoinRecordOutput(address = bitcoinChange, amountSats = 60_000, isOwnChange = true),
            ),
            feeSats = 500,
            feeRateSatPerVb = 2.0,
            notices = emptyList(),
        )
        val rec = record(kind = ReviewKind.BITCOIN_TRANSACTION, networkName = "Bitcoin Testnet4", bitcoin = facts)

        assertEquals(BITCOIN_FAMILY, recordChainOf(rec))
        assertEquals(
            listOf(
                RecordAddress("Output 0", bitcoinPaid, BITCOIN_FAMILY, isOwnChange = false),
                RecordAddress("Output 2", bitcoinChange, BITCOIN_FAMILY, isOwnChange = true),
            ),
            recordAddressesOf(rec),
        )
    }

    @Test
    fun anEvmRecordListsAccountRecipientContractAndEachAddressParameter() {
        val rec = record(
            networkName = "Monad testnet",
            address = evmSender,
            to = evmRecipient,
            tokenContract = evmContract,
            fee = listOf("Gas Limit" to "21000"),
            params = listOf("spender" to evmSpender, "value" to "25000000", "note" to "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"),
        )

        assertEquals(EVM_FAMILY, recordChainOf(rec))
        assertEquals(
            listOf(
                RecordAddress("Account", evmSender, EVM_FAMILY),
                RecordAddress("To", evmRecipient, EVM_FAMILY),
                RecordAddress("Token Contract", evmContract, EVM_FAMILY),
                RecordAddress("spender", evmSpender, EVM_FAMILY),
            ),
            recordAddressesOf(rec),
        )
    }

    @Test
    fun anEvmMessageWithNoChainIsOnTheChainOfItsAddress() {
        val rec = record(kind = ReviewKind.MESSAGE, address = evmSender)

        assertEquals(EVM_FAMILY, recordChainOf(rec))
        assertEquals(listOf(RecordAddress("Account", evmSender, EVM_FAMILY)), recordAddressesOf(rec))
    }

    @Test
    fun aSolanaRecordListsItsSignerAndTheProgramsAndMintsItShows() {
        val rec = record(
            networkName = "Solana",
            address = solanaSigner,
            params = listOf(
                "Program" to solanaProgram,
                "Mint" to solanaMint,
                "Amount" to "1000000",
                // A number of 32 base58 digits has the shape of a Solana address, but it is not a 32-byte key.
                "Lamports" to "12345678912345678912345678912345",
                // A legacy Bitcoin address is not a Solana address, even though it is base58.
                "Memo" to bitcoinPaid,
            ),
        )

        assertEquals(SOLANA_FAMILY, recordChainOf(rec))
        assertEquals(
            listOf(
                RecordAddress("Account", solanaSigner, SOLANA_FAMILY),
                RecordAddress("Program", solanaProgram, SOLANA_FAMILY),
                RecordAddress("Mint", solanaMint, SOLANA_FAMILY),
            ),
            recordAddressesOf(rec),
        )
    }

    @Test
    fun anAlgorandRecordListsItsAccountAndTheAddressesOfItsRows() {
        val rec = record(
            networkName = AlgorandNetwork.TESTNET.displayName,
            address = algorandSender,
            params = listOf(
                "Receiver" to algorandReceiver,
                "Asset creator" to algorandReceiver,
                "Amount" to "1000",
                "Note" to bitcoinPaid,
            ),
        )

        assertEquals(ALGORAND_FAMILY, recordChainOf(rec))
        assertEquals(
            listOf(
                RecordAddress("Account", algorandSender, ALGORAND_FAMILY),
                RecordAddress("Receiver", algorandReceiver, ALGORAND_FAMILY),
                RecordAddress("Asset creator", algorandReceiver, ALGORAND_FAMILY),
            ),
            recordAddressesOf(rec),
        )
    }

    @Test
    fun aRefusedAlgorandRecordWithoutAReviewIsStillOnAlgorand() {
        val rec = record(networkName = "Algorand", address = null, params = emptyList())

        assertEquals(ALGORAND_FAMILY, recordChainOf(rec))
        assertEquals(emptyList(), recordAddressesOf(rec))
    }

    @Test
    fun aRecordWithNoChainAndNoAddressListsNothing() {
        val rec = record(kind = ReviewKind.MESSAGE, params = listOf("text" to "hello"))

        assertEquals(null, recordChainOf(rec))
        assertEquals(emptyList(), recordAddressesOf(rec))
    }
}
