package app.lumio.pair

import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.Cbor
import app.lensly.core.wallet.HdWallet
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codes that Lumio refuses, with the reason it shows. Each one holds more than public data, or breaks a rule of the pairing. */
class PairRefusalTest {

    @Test
    fun aSeedIsRefusedAsPrivateMaterial() {
        assertEquals(PairImport.PRIVATE_KEY_REFUSAL, refusalOf(UR("crypto-seed", byteArrayOf(0x01, 0x02))))
    }

    @Test
    fun anUnknownUrTypeIsRefused() {
        val reason = refusalOf(UR("eth-sign-request", byteArrayOf(0x01)))
        assertTrue(reason, reason.contains("not supported"))
    }

    @Test
    fun theOldPairingNameIsRefusedEvenWhenItsCodeIsWhole() {
        val code = PairingCodec.encode(pairing(ethereumApp()), brotli = null)

        assertEquals(
            "This is not a pairing code from Lensly. ur:x-lensly-pairing is not supported.",
            refusalOf(UR("x-lensly-pairing", code)),
        )
    }

    @Test
    fun aScannedOldPairingNameEndsTheScanAsRefused() {
        val code = PairingCodec.encode(pairing(ethereumApp()), brotli = null)

        val step = scanToEnd(listOf(UREncoder.encode(UR("x-lensly-pairing", code))))

        assertEquals(ScanStep.Refused("This is not a pairing code from Lensly. ur:x-lensly-pairing is not supported."), step)
    }

    @Test
    fun aCodeOfAnOlderLayoutIsRefusedByItsVersion() {
        val older = Cbor.encode(
            Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Uint(5), Cbor.Uint(6) to Cbor.Uint(0), Cbor.Uint(7) to Cbor.Bytes(byteArrayOf(0x01)))),
        )

        assertEquals(
            "This pairing uses format 5, and this app reads format ${PairingCodec.VERSION}. Update the app, then pair again.",
            refusalOf(UR(PairingCodec.UR_TYPE, older)),
        )
    }

    @Test
    fun aNewerVersionOfThePairingIsRefusedByItsVersion() {
        val newer = Cbor.encode(
            Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Uint(7), Cbor.Uint(9) to Cbor.Text("a field of a later format"))),
        )

        val reason = refusalOf(UR(PairingCodec.UR_TYPE, newer))

        assertTrue(reason, reason.contains("format 7"))
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
                    Cbor.Uint(4) to Cbor.Text(""),
                    Cbor.Uint(5) to Cbor.Arr(emptyList()),
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
        val address = PairingKey.ethereumAddress(0, PairFixtures.address(PairFixtures.seed, 0)).copy(
            path = listOf(0x8000002CL, 0x8000003CL, 0x80000000L),
        )

        val reason = refusalOf(codeOf(pairing(ethereumApp(key = address, accounts = listOf(account(0))))))

        assertTrue(reason, reason.contains("m/44'/60'/N'"))
    }

    @Test
    fun anEthereumKeyOfAnotherAccountIsRefused() {
        val otherAccount = PairingKey.ethereumAddress(1, PairFixtures.address(PairFixtures.seed, 1))

        val reason = refusalOf(codeOf(pairing(ethereumApp(key = otherAccount, accounts = listOf(account(0))))))

        assertTrue(reason, reason.contains("the address of its account"))
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
        autoNameBase = "Demo",
        apps = apps.toList(),
    )

    private fun ethereumApp(
        key: PairingKey? = null,
        accounts: List<PairingAccount> = listOf(account(0)),
    ) = PairingApp(
        app = "ethereum",
        keys = key?.let { listOf(it) } ?: accounts.indices.map { PairingKey.ethereumAddress(it, PairFixtures.address(PairFixtures.seed, it)) },
        accounts = accounts,
    )

    private fun bitcoinApp(accounts: List<PairingAccount>) = PairingApp(app = "bitcoin", keys = emptyList(), accounts = accounts)

    private fun bitcoinKeys(keys: List<PairingKey>) = PairingApp(
        app = "bitcoin",
        keys = keys,
        accounts = keys.indices.map { PairingAccount(key = it, name = autoAccountName("Demo", it), label = null, group = null) },
    )

    private fun account(index: Int) = PairingAccount(key = index, name = "Demo $index", label = null, group = null)
}
