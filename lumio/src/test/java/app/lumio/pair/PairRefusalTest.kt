package app.lumio.pair

import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PRIVATE_KEY_REFUSAL
import app.lensly.core.wallet.HdWallet
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codes that Lumio refuses, with the reason it shows. Each one holds more than public data, breaks a rule of the pairing, or is a code Lensly no longer sends. */
class PairRefusalTest {

    @Test
    fun aPrivateKeyFlagIsRefusedEvenOnAPublicKey() {
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(hdKey(privateFlag = true, key = publicKey()).toUR()))
    }

    @Test
    fun a32ByteKeyIsRefusedAsPrivateMaterial() {
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(hdKey(privateFlag = null, key = ByteArray(32) { 7 }).toUR()))
    }

    @Test
    fun aSeedIsRefusedAsPrivateMaterial() {
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(UR("crypto-seed", byteArrayOf(0x01, 0x02))))
    }

    @Test
    fun aBitcoinOutputWithAPrivateKeyIsRefused() {
        val output = CryptoOutput(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), hdKey(privateFlag = null, key = ByteArray(32) { 9 }))
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(CryptoAccount(fingerprintBytes(0x1a2b3c4d), listOf(output)).toUR()))
    }

    @Test
    fun anUnknownUrTypeIsRefused() {
        val reason = refusalOf(UR("eth-sign-request", byteArrayOf(0x01)))
        assertTrue(reason, reason.contains("not supported"))
    }

    @Test
    fun anOlderLenslyEthereumCodeIsRefusedWithTheReasonToPairAgain() {
        val reason = refusalOf(hdKey(privateFlag = null, key = publicKey()).toUR())
        assertTrue(reason, reason.contains("older Lensly"))
    }

    @Test
    fun anOlderLenslyBitcoinCodeIsRefusedWithTheReasonToPairAgain() {
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(BtcWatchOnly.cryptoAccountParts(PairFixtures.allAccounts(PairFixtures.seed))))
        assertTrue(refusal.reason, refusal.reason.contains("older Lensly"))
    }

    @Test
    fun aMalformedOlderHdKeyIsRefused() {
        val reason = refusalOf(UR("crypto-hdkey", byteArrayOf(0x01, 0x02)))
        assertTrue(reason, reason.contains("crypto-hdkey"))
    }

    @Test
    fun aNewerVersionOfThePairingIsRefusedByItsVersion() {
        val newer = Cbor.encode(
            Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Uint(6), Cbor.Uint(9) to Cbor.Text("a field of a later format"))),
        )

        val reason = refusalOf(UR(PairingCodec.UR_TYPE, newer))

        assertTrue(reason, reason.contains("format 6"))
        assertTrue(reason, reason.contains("Update the app"))
    }

    @Test
    fun aPairingThatIsNotAPairingIsRefused() {
        assertEquals("This is not a pairing code from Lensly.", refusalOf(UR(PairingCodec.UR_TYPE, byteArrayOf(0x01, 0x02, 0x03))))
    }

    @Test
    fun aNameWithAControlCharacterIsRefused() {
        val body = Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(2) to Cbor.Uint(1),
                    Cbor.Uint(3) to Cbor.Text("De\nmo"),
                    Cbor.Uint(4) to Cbor.Arr(emptyList()),
                ),
            ),
        )
        val name = Cbor.encode(
            Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Uint(PairingCodec.VERSION), Cbor.Uint(6) to Cbor.Uint(0), Cbor.Uint(7) to Cbor.Bytes(body))),
        )

        assertEquals("This is not a pairing code from Lensly.", refusalOf(UR(PairingCodec.UR_TYPE, name)))
    }

    @Test
    fun aPairingWithoutAnEthereumAppIsRefused() {
        val reason = refusalOf(codeOf(pairing(bitcoinApp(emptyList()))))

        assertTrue(reason, reason.contains("no Ethereum account key"))
    }

    @Test
    fun anEthereumKeyOffTheAccountPathIsRefusedBecauseOnlyTheAccountKeyPairs() {
        val address = PairingKey.ethereumAddress(0, PairFixtures.addressKey(PairFixtures.seed, 0)).copy(
            path = listOf(0x8000002CL, 0x8000003CL, 0x80000000L),
            depth = 3,
        )

        val reason = refusalOf(codeOf(pairing(ethereumApp(key = address, accounts = listOf(account(0))))))

        assertTrue(reason, reason.contains("m/44'/60'/N'"))
    }

    @Test
    fun anEthereumKeyOfAnotherAccountIsRefused() {
        val otherAccount = PairingKey.ethereumAddress(1, PairFixtures.addressKey(PairFixtures.seed, 1))

        val reason = refusalOf(codeOf(pairing(ethereumApp(key = otherAccount, accounts = listOf(account(0))))))

        assertTrue(reason, reason.contains("address key of its account"))
    }

    @Test
    fun ethereumAccountsNotNumberedFromZeroAreRefused() {
        val reason = refusalOf(codeOf(pairing(ethereumApp(accounts = listOf(account(1), account(0))))))

        assertTrue(reason, reason.contains("not numbered 0 to n-1"))
    }

    @Test
    fun moreEthereumAccountsThanLumioKeepsAreRefused() {
        val accounts = (0..PairImport.MAX_ETHEREUM_ACCOUNTS).map { account(it) }

        val reason = refusalOf(codeOf(pairing(ethereumApp(accounts = accounts))))

        assertTrue(reason, reason.contains("Lumio pairs up to ${PairImport.MAX_ETHEREUM_ACCOUNTS}"))
    }

    @Test
    fun aBitcoinKeyOnAPathThatIsNotAnAccountIsRefused() {
        val key = PairingKey.bitcoinAccount(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT))
            .copy(path = listOf(0x8000002CL, 0x80000000L, 0x80000000L, 0L))

        val reason = refusalOf(codeOf(pairing(ethereumApp(), bitcoinKeys(listOf(key)))))

        assertTrue(reason, reason.contains("Bitcoin account path"))
    }

    @Test
    fun aBitcoinAccountListedTwiceIsRefused() {
        val account = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        val key = PairingKey.bitcoinAccount(account)

        val reason = refusalOf(codeOf(pairing(ethereumApp(), bitcoinKeys(listOf(key, key)))))

        assertTrue(reason, reason.contains("more than once"))
    }

    @Test
    fun moreBitcoinKeysThanLumioKeepsAreRefused() {
        val key = PairingKey.bitcoinAccount(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT))
        val keys = (0..PairImport.MAX_BITCOIN_KEYS).map { index -> key.copy(path = listOf(0x80000054L, 0x80000000L, 0x80000000L + index)) }

        val reason = refusalOf(codeOf(pairing(ethereumApp(), bitcoinKeys(keys))))

        assertTrue(reason, reason.contains("Lumio pairs up to ${PairImport.MAX_BITCOIN_KEYS}"))
    }

    @Test
    fun textThatIsNotAUrIsIgnoredByTheScan() {
        assertEquals(ScanStep.Ignored, PairScan().onText("https://example.com"))
    }

    @Test
    fun aRefusedPairingImportsNothing() {
        assertFailsWith<PairingRefusal> { PairImport.of(UR("crypto-seed", byteArrayOf(0x01))) }
    }

    private fun refusalOf(ur: UR): String {
        val error = assertFailsWith<PairingRefusal> { PairImport.of(ur) }
        return error.message.orEmpty()
    }

    private fun codeOf(payload: PairingPayload): UR = UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, brotli = null))

    private fun pairing(vararg apps: PairingApp) = PairingPayload(
        fingerprint = HdWallet(PairFixtures.seed).masterFingerprint.toLong() and 0xFFFFFFFFL,
        name = "Demo",
        apps = apps.toList(),
    )

    private fun ethereumApp(
        key: PairingKey? = null,
        accounts: List<PairingAccount> = listOf(account(0)),
    ) = PairingApp(
        app = "ethereum",
        keys = key?.let { listOf(it) } ?: accounts.indices.map { PairingKey.ethereumAddress(it, PairFixtures.addressKey(PairFixtures.seed, it)) },
        accounts = accounts,
    )

    private fun bitcoinApp(accounts: List<PairingAccount>) = PairingApp(app = "bitcoin", keys = emptyList(), accounts = accounts)

    private fun bitcoinKeys(keys: List<PairingKey>) = PairingApp(
        app = "bitcoin",
        keys = keys,
        accounts = keys.indices.map { PairingAccount(key = it, child = null, name = null, label = null, group = null) },
    )

    private fun account(index: Int) = PairingAccount(key = index, child = null, name = "Demo $index", label = null, group = null)

    /** An account key on the Ethereum account path, with [privateFlag] and [key] as given. */
    private fun hdKey(privateFlag: Boolean?, key: ByteArray): CryptoHDKey = CryptoHDKey(
        privateFlag,
        key,
        ByteArray(32) { 3 },
        null,
        CryptoKeypath(
            listOf(IndexPathComponent(44, true), IndexPathComponent(60, true), IndexPathComponent(0, true)),
            fingerprintBytes(0x1a2b3c4d),
            3,
        ),
        null,
        fingerprintBytes(0x5e6f7a8b),
        "Demo",
        null,
    )

    private fun publicKey(): ByteArray = HdWallet(PairFixtures.seed).accountXpub().publicKey

    private fun fingerprintBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
