package app.lumio.pair

import app.lensly.app.export.PairingExport
import app.lensly.app.export.partsOf
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.CryptoHdKeyEncoder
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

/** Codes that Lumio refuses, with the reason it shows. Each one is a code Lensly never exports, or one that holds more than public data. */
class PairRefusalTest {

    @Test
    fun aPrivateKeyFlagIsRefusedEvenOnAPublicKey() {
        val hdKey = hdKey(privateFlag = true, key = publicKey())
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(hdKey.toUR()))
    }

    @Test
    fun a32ByteKeyIsRefusedAsPrivateMaterial() {
        val hdKey = hdKey(privateFlag = null, key = ByteArray(32) { 7 })
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(hdKey.toUR()))
    }

    @Test
    fun aSeedIsRefusedAsPrivateMaterial() {
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(UR("crypto-seed", byteArrayOf(0x01, 0x02))))
    }

    @Test
    fun aBitcoinOutputWithAPrivateKeyIsRefused() {
        val output = CryptoOutput(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), hdKey(privateFlag = null, key = ByteArray(32) { 9 }))
        val ur = CryptoAccount(fingerprintBytes(0x1a2b3c4d), listOf(output)).toUR()
        assertEquals(PRIVATE_KEY_REFUSAL, refusalOf(ur))
    }

    @Test
    fun anUnknownUrTypeIsRefused() {
        val reason = refusalOf(UR("eth-sign-request", byteArrayOf(0x01)))
        assertTrue(reason, reason.contains("not supported"))
    }

    @Test
    fun aKeyOnAnAddressPathIsRefusedBecauseOnlyTheAccountKeyPairs() {
        val wallet = HdWallet(PairFixtures.seed)
        val address = wallet.accountXpub()
        val parts = partsOf(
            CryptoHdKeyEncoder.urParts(
                pubkey33 = address.publicKey,
                chainCode32 = address.chainCode,
                masterFp = wallet.masterFingerprint,
                parentFp = address.parentFingerprint,
                name = "Address",
                origin = CryptoHdKeyEncoder.ACCOUNT_ORIGIN + listOf(0, 0),
                depth = 5,
            ),
        )
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(parts))
        assertTrue(refusal.reason, refusal.reason.contains("m/44'/60'/0'"))
    }

    @Test
    fun aMalformedHdKeyIsRefused() {
        val reason = refusalOf(UR("crypto-hdkey", byteArrayOf(0x01, 0x02)))
        assertTrue(reason, reason.contains("crypto-hdkey"))
    }

    @Test
    fun aNameWithAControlCharacterIsRefused() {
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(PairingExport.urParts(HdWallet(PairFixtures.seed), name = "De\nmo")))
        assertTrue(refusal.reason, refusal.reason.contains("plain text"))
    }

    @Test
    fun aNameLongerThanTheRecordHoldsIsRefused() {
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(PairingExport.urParts(HdWallet(PairFixtures.seed), name = "x".repeat(65))))
        assertTrue(refusal.reason, refusal.reason.contains("longer than"))
    }

    @Test
    fun nineBitcoinAccountsAreRefused() {
        val accounts = PairFixtures.allAccounts(PairFixtures.seed) + PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.LEGACY)
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(BtcWatchOnly.cryptoAccountParts(accounts)))
        assertTrue(refusal.reason, refusal.reason.contains("9 Bitcoin accounts"))
    }

    @Test
    fun aBitcoinAccountListedTwiceIsRefused() {
        val account = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(BtcWatchOnly.cryptoAccountParts(listOf(account, account))))
        assertTrue(refusal.reason, refusal.reason.contains("more than once"))
    }

    @Test
    fun aBitcoinAccountOnTheWrongPurposeIsRefused() {
        val native = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        val relabelled = native.copy(scriptType = ScriptType.LEGACY)
        val refusal = assertIs<ScanStep.Refused>(scanToEnd(BtcWatchOnly.cryptoAccountParts(listOf(relabelled))))
        assertTrue(refusal.reason, refusal.reason.contains("Bitcoin account path"))
    }

    @Test
    fun aBitcoinPayloadThatDoesNotDecodeIsRefused() {
        val reason = refusalOf(UR("crypto-account", byteArrayOf(0x01, 0x02, 0x03)))
        assertTrue(reason, reason.contains("crypto-account"))
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
