package app.lumio.pair

import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.app.export.accountSteps
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lumio.data.net.Networks
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
        val code = PairingCodec.encode(pairing(listOf(account(0))), brotli = null)

        assertEquals(
            "This is not a pairing code from Lensly. ur:x-lensly-pairing is not supported.",
            refusalOf(UR("x-lensly-pairing", code)),
        )
    }

    @Test
    fun aScannedOldPairingNameEndsTheScanAsRefused() {
        val code = PairingCodec.encode(pairing(listOf(account(0))), brotli = null)

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
            Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Uint(9), Cbor.Uint(9) to Cbor.Text("a field of a later format"))),
        )

        val reason = refusalOf(UR(PairingCodec.UR_TYPE, newer))

        assertTrue(reason, reason.contains("format 9"))
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
    fun aNameWithAFormatCharacterSuchAsARightToLeftOverrideIsRefused() {
        val body = Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(2) to Cbor.Uint(1),
                    Cbor.Uint(3) to Cbor.Text("De‮mo"),
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
    fun aPairingWithNoAccountOfAnyChainIsRefused() {
        val reason = refusalOf(codeOf(pairing(emptyList())))

        assertTrue(reason, reason.contains("no account key"))
    }

    @Test
    fun anEthereumKeyOffTheAccountPathIsRefusedBecauseOnlyTheAccountKeyPairs() {
        // m/44'/60'/0' is an account level, not the address key m/44'/60'/N'/0/0, so no key scope of Ethereum covers it.
        val atAccount = withPath(account(0), listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true)))

        val reason = refusalOf(codeOf(pairing(listOf(atAccount))))

        assertTrue(reason, reason.contains("outside the key scopes"))
    }

    @Test
    fun anEthereumKeyOfAnotherAccountIsRefused() {
        // The key of account 1 at the first position: an account's index must be its position among the accounts of its chain.
        val reason = refusalOf(codeOf(pairing(listOf(account(1)))))

        assertTrue(reason, reason.contains("numbered 0 to n-1"))
    }

    @Test
    fun anEthereumAddressKeyIsRefusedBecauseOnlyAnAccountKeyPairsAtItsPosition() {
        // m/44'/60'/0'/0/1 is in a key scope of Ethereum, but it is the key of an address: an Ethereum account is m/44'/60'/N'/0/0.
        val address = PairFixtures.ethereumAddressAccount(1, "Address 1")

        val reason = refusalOf(codeOf(pairing(listOf(address))))

        assertTrue(reason, reason.contains("not an account key of its chain"))
    }

    @Test
    fun aBitcoinKeyOffTheCurveIsRefusedAtImport() {
        // 0x02 then x = 5: y squared is 5 cubed plus 7, which is 132, and 132 has no square root modulo the field prime, so the point is off the curve.
        val key = PairFixtures.bitcoinAccount(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT), "Bitcoin 0")
        val offCurve = withKey(key, ByteArray(33).also { it[0] = 0x02; it[32] = 5 })

        assertEquals("The code's public key is not valid.", refusalOf(codeOf(pairing(listOf(account(0), offCurve)))))
    }

    @Test
    fun aBitcoinKeyOfTheCoinTypeOfAnotherNetworkIsRefusedForItsChain() {
        // A testnet key carried as a mainnet account: its path names coin type 1, and the mainnet chain's coin type is 0.
        val testnet = PairFixtures.bitcoinAccount(PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT), "Testnet 0")
        val mislabelled = withChain(testnet, bitcoinMainnet)

        val reason = refusalOf(codeOf(pairing(listOf(account(0), mislabelled))))

        assertTrue(reason, reason.contains("another network"))
    }

    @Test
    fun moreEthereumAccountsThanLumioKeepsAreRefused() {
        val accounts = (0..PairingCodec.MAX_ETHEREUM_ACCOUNTS).map { account(it) }

        val reason = refusalOf(codeOf(pairing(accounts)))

        assertTrue(reason, reason.contains("Lumio pairs up to ${PairingCodec.MAX_ETHEREUM_ACCOUNTS}"))
    }

    @Test
    fun aBitcoinKeyOnAPathThatIsNotAnAccountIsRefused() {
        // A watch-only key is the key of an account, at m/purpose'/coin'/N'. A fourth level is a key of an address, which Lumio does not pair.
        val key = PairFixtures.bitcoinAccount(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT), "Bitcoin 0")
        val notAnAccount = withPath(key, listOf(PathStep(44, true), PathStep(0, true), PathStep(0, true), PathStep(0, false)))

        val reason = refusalOf(codeOf(pairing(listOf(account(0), notAnAccount))))

        assertTrue(reason, reason.contains("is not the key of an account"))
    }

    @Test
    fun aBitcoinAccountListedTwiceIsRefused() {
        val key = PairFixtures.bitcoinAccount(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT), "Bitcoin 0")

        val reason = refusalOf(codeOf(pairing(listOf(account(0), key, key))))

        assertTrue(reason, reason.contains("more than once"))
    }

    @Test
    fun aWalletOfTheMostBitcoinAccountsIsPaired() {
        val payload = PairImport.of(codeOf(pairing(listOf(account(0)) + bitcoinKeys(PairImport.MAX_BITCOIN_KEYS))))

        assertEquals(PairImport.MAX_BITCOIN_KEYS, PairImport.accountsOf(payload, Networks.BIP122).size)
    }

    @Test
    fun moreBitcoinKeysThanLumioKeepsAreRefused() {
        val reason = refusalOf(codeOf(pairing(listOf(account(0)) + bitcoinKeys(PairImport.MAX_BITCOIN_KEYS + 1))))

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

    private fun pairing(accounts: List<PairingAccount>): PairingPayload = PairFixtures.payloadOf(accounts)

    private fun account(index: Int): PairingAccount = PairFixtures.ethereumAccount(index, "Demo $index")

    /** The first [count] Bitcoin accounts of the demo seed, each at its own index on the Native SegWit path. */
    private fun bitcoinKeys(count: Int): List<PairingAccount> {
        val template = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        return (0 until count).map { index -> withPath(PairFixtures.bitcoinAccount(template, "Bitcoin $index"), accountSteps(84, 0, index)) }
    }

    /** The account [account] with its path replaced by [path], which is the path a pairing of another key would carry. */
    private fun withPath(account: PairingAccount, path: List<PathStep>): PairingAccount = account.copy(
        export = AccountExport(
            account.export.chain,
            SignerRef(account.export.signer.fingerprint, path),
            account.export.publicKey,
            account.export.chainCode,
            account.export.kind,
        ),
    )

    /** The account [account] with its key replaced by [key], as a pairing of a key that is not on its curve would carry it. */
    private fun withKey(account: PairingAccount, key: ByteArray): PairingAccount = account.copy(
        export = AccountExport(
            account.export.chain,
            account.export.signer,
            PublicKey(account.export.publicKey.curve, key),
            account.export.chainCode,
            account.export.kind,
        ),
    )

    /** The account [account] named as an account of [chain], as a pairing that names the chain of another network would carry it. */
    private fun withChain(account: PairingAccount, chain: ChainId): PairingAccount = account.copy(
        export = AccountExport(
            chain,
            account.export.signer,
            account.export.publicKey,
            account.export.chainCode,
            account.export.kind,
        ),
    )
}
