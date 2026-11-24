package app.lumio.data.electrum

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class ElectrumMethodsTest {
    private val identity = TestIdentities.server

    private val txidA = "a".repeat(64)
    private val txidB = "b".repeat(64)

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun scripthashIsTheSha256OfTheScriptWithItsBytesReversed() {
        // The P2PKH script of 1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2, and the P2WPKH script of the first BIP84 address of the abandon wallet.
        assertEquals(
            "eafd9bc024177ba93572c1cc3a83f555dadbb81ca94cd9761ef5211ce794cea9",
            scripthash(hex("76a91477bff20c60e522dfaa3350c39b030a5d004e839a88ac")),
        )
        assertEquals(
            "6e4f16236139f15046b38f399a683fb2aa8edf5fd128b3e5db017fb0ac74078a",
            scripthash(hex("0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2")),
        )
    }

    @Test
    fun typedReadsParseTheShapeOfEachReply(): Unit = runBlocking {
        val answer: (String, JsonArray) -> Answer = { method, _ ->
            when (method) {
                "blockchain.scripthash.get_balance" -> Answer.Reply(buildJsonObject {
                    put("confirmed", JsonPrimitive(5000))
                    put("unconfirmed", JsonPrimitive(-100))
                })
                "blockchain.scripthash.get_history" -> Answer.Reply(buildJsonArray {
                    add(buildJsonObject {
                        put("tx_hash", JsonPrimitive(txidA))
                        put("height", JsonPrimitive(100))
                    })
                    add(buildJsonObject {
                        put("tx_hash", JsonPrimitive(txidB))
                        put("height", JsonPrimitive(0))
                        put("fee", JsonPrimitive(200))
                    })
                })
                "blockchain.scripthash.listunspent" -> Answer.Reply(buildJsonArray {
                    add(buildJsonObject {
                        put("tx_hash", JsonPrimitive(txidA))
                        put("tx_pos", JsonPrimitive(1))
                        put("height", JsonPrimitive(100))
                        put("value", JsonPrimitive(7000))
                    })
                })
                "blockchain.headers.subscribe" -> Answer.Reply(buildJsonObject {
                    put("height", JsonPrimitive(900000))
                    put("hex", JsonPrimitive("0000"))
                })
                "blockchain.transaction.get" -> Answer.Reply(JsonPrimitive("0200"))
                else -> Answer.Fail("unknown method $method")
            }
        }
        FakeElectrum(identity, answer = answer).use { fake ->
            val connection = ElectrumConnection.open(ElectrumServer("localhost", Trust.Pinned(identity.spkiSha256), 100, fake.port), 5_000, 5_000)
            assertEquals(listOf(ScripthashBalance(5000, -100)), connection.scripthashBalances(listOf("s")).map { it.getOrThrow() })
            assertEquals(
                listOf(ScripthashHistoryItem(txidA, 100, null), ScripthashHistoryItem(txidB, 0, 200)),
                connection.scripthashHistories(listOf("s")).single().getOrThrow(),
            )
            assertEquals(listOf(ScripthashUnspent(txidA, 1, 100, 7000)), connection.scripthashUnspent(listOf("s")).single().getOrThrow())
            assertEquals(HeaderTip(900000, "0000"), connection.headerTip())
            assertEquals("0200", connection.transactionHex(txidA))
            connection.close()
        }
    }
}
