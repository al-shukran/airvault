package app.lensly.app.sign

import app.lensly.core.evm.EvmFixtures
import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

private const val MONAD_TESTNET = 10143L
private const val ETHEREUM = 1L
private const val BASE = 8453L
private const val UNKNOWN_CHAIN = 999_999L

private const val ART_ADDRESS = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val USDC_ETHEREUM = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val UNREGISTERED = "0x1111111111111111111111111111111111111111"

private const val SELECTOR_TRANSFER = "a9059cbb"
private const val SELECTOR_APPROVE = "095ea7b3"
private const val SELECTOR_TRANSFER_FROM = "23b872dd"
private const val SELECTOR_INCREASE_ALLOWANCE = "39509351"
private const val SELECTOR_SET_APPROVAL_FOR_ALL = "a22cb465"
private const val SELECTOR_SAFE_TRANSFER_FROM = "42842e0e"
private const val SELECTOR_BALANCE_OF = "70a08231"
private const val L2_NOTE = "L1 data fee is not computable offline; total may be higher"

private val REQUEST_ID = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")
private val ONE_CENT_OF_MON: BigInteger = BigInteger.TEN.pow(16)
private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }

private const val WALLET_ID = "wallet-mnemonic"
private const val KEY_ID = "wallet-single-key"
private const val ORIGIN = "metamask"
private const val UNLIMITED_USDC = "Unlimited approval: this contract can spend all your USDC"
private const val UNLIMITED_UNKNOWN = "Unlimited approval: this contract can spend all your tokens of this contract"

/** Two accounts, so index 1 is valid and index 2 is not. */
private val VIEW by lazy {
    WalletView(
        id = WALLET_ID,
        masterFingerprint = WALLET.masterFingerprint,
        accountCount = 2,
        autoNameBase = "Seed Wallet",
        addressAt = { WALLET.address(it) },
    )
}

/** The well-known test key of the Single Key tests. It has never held funds, and no other fixture uses it. */
private val SINGLE_KEY: ByteArray =
    Numeric.hexStringToByteArray("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318")
private const val SINGLE_KEY_ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"
private const val SINGLE_KEY_ADDRESS_HEX = "2c7536e3605d9c16a7a3d7b1898e529396a65c23"
private val SINGLE_KEY_BYTES: ByteArray = Numeric.hexStringToByteArray(SINGLE_KEY_ADDRESS)
private val SINGLE_KEY_VIEW by lazy {
    WalletView.singleKey(id = KEY_ID, address = SINGLE_KEY_ADDRESS, autoNameBase = "Imported")
}
private val ART_BYTES: ByteArray = Numeric.hexStringToByteArray(ART_ADDRESS)

private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(
            chainId = MONAD_TESTNET,
            name = "Monad Testnet",
            nativeSymbol = "MON",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "MonadVision",
            testnet = true,
        ),
        NetworkEntry(
            chainId = ETHEREUM,
            name = "Ethereum",
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "Etherscan",
            testnet = false,
        ),
        NetworkEntry(
            chainId = BASE,
            name = "Base",
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            feeModel = FeeModel.L2_ROLLUP,
            explorerName = "Basescan",
            testnet = false,
        ),
    ),
    tokens = listOf(
        TokenEntry(ETHEREUM, USDC_ETHEREUM.lowercase(), "USDC", "USD Coin", 6),
    ),
)

class SignPipelineTest {

    private val pipeline = SignPipeline(REGISTRY)

    @Test
    fun eip1559MonTransferIsReviewedAsNativeTransfer() {
        val ready = ready(requestCbor(eip1559Tx()))
        val review = ready.review

        assertEquals("0.01 MON", "${review.amount} ${review.symbol}")
        assertEquals("Native transfer", review.function)
        assertEquals(ART_ADDRESS, review.fromAddress)
        assertEquals("SEED WALLET 0", review.fromAccountLabel)
        assertEquals(RECIPIENT, review.toAddress)
        assertNull(review.tokenContract)
        assertTrue(review.params.isEmpty())
        assertTrue(review.warnings.isEmpty())
        assertEquals(MONAD_TESTNET, review.chainId)
        assertEquals("Monad Testnet", review.networkName)
        assertEquals(REGISTRY.network(MONAD_TESTNET), review.network)
        assertNull(review.token)
        assertEquals(7L, review.nonce)
        assertEquals("EIP-1559", review.txType)
        assertNull(review.feeNote)
        assertEquals(0, ready.accountIndex)
    }

    @Test
    fun eip1559FeeRowsShowGasLimitGasPricesAndMaxFee() {
        val review = ready(requestCbor(eip1559Tx())).review

        assertEquals(
            listOf(
                "Gas limit" to "21000",
                "Max fee per gas" to "30 gwei",
                "Max priority fee" to "1.5 gwei",
                "Max fee" to "0.00063 MON",
            ),
            review.fee,
        )
    }

    @Test
    fun layer2NetworkAddsTheL1FeeNote() {
        val review = ready(requestCbor(eip1559Tx(chainId = BASE), chainId = BASE)).review

        assertEquals("0.01 ETH", "${review.amount} ${review.symbol}")
        assertEquals(L2_NOTE, review.feeNote)
    }

    @Test
    fun legacyTransferShowsGasPriceAndLegacyType() {
        val review = ready(requestCbor(legacyTx(), dataType = 1)).review

        assertEquals("Legacy", review.txType)
        assertEquals(
            listOf("Gas limit" to "60000", "Gas price" to "20 gwei", "Max fee" to "0.0012 MON"),
            review.fee,
        )
    }

    @Test
    fun erc20UsdcTransferUsesTokenDecimals() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(1_500_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("1.5 USDC", "${review.amount} ${review.symbol}")
        assertEquals("transfer", review.function)
        assertEquals("transfer(address,uint256)", review.signature)
        assertEquals(RECIPIENT, review.toAddress)
        assertEquals(USDC_ETHEREUM, review.tokenContract)
        assertEquals(
            listOf("to" to RECIPIENT, "value" to "1500000 (1.5 USDC)"),
            review.params,
        )
        assertEquals(REGISTRY.network(ETHEREUM), review.network)
        assertEquals(REGISTRY.token(ETHEREUM, USDC_ETHEREUM), review.token)
    }

    @Test
    fun unlimitedErc20ApprovalIsWarned() {
        val data = erc20Call(SELECTOR_APPROVE, RECIPIENT, ContractCall.MAX_UINT256)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("approve", review.function)
        assertEquals(listOf(UNLIMITED_USDC), review.warnings)
        assertEquals(listOf("spender", "value"), review.params.map { it.first })
        assertEquals(RECIPIENT, review.params.first().second)
    }

    @Test
    fun fixedErc20ApprovalHasNoWarning() {
        val data = erc20Call(SELECTOR_APPROVE, RECIPIENT, BigInteger.valueOf(100_000_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertTrue(review.warnings.isEmpty())
        assertEquals("100000000 (100 USDC)", review.params.last().second)
    }

    @Test
    fun approvalOfTwoToTheTo255IsUnlimitedAndOneBelowIsNot() {
        val threshold = BigInteger.ONE.shiftLeft(255)
        val atThreshold = erc20Call(SELECTOR_APPROVE, RECIPIENT, threshold)
        val below = erc20Call(SELECTOR_APPROVE, RECIPIENT, threshold.subtract(BigInteger.ONE))

        val unlimited = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = atThreshold)
        val limited = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = below)

        assertEquals(listOf(UNLIMITED_USDC), ready(requestCbor(unlimited, chainId = ETHEREUM)).review.warnings)
        assertTrue(ready(requestCbor(limited, chainId = ETHEREUM)).review.warnings.isEmpty())
    }

    @Test
    fun transferFromAKnownTokenIsReviewedAsAnErc20Transfer() {
        val data = transferFromCall(ART_ADDRESS, RECIPIENT, BigInteger.valueOf(1_500_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("transferFrom", review.function)
        assertEquals("1.5 USDC", "${review.amount} ${review.symbol}")
        assertEquals(
            listOf("from" to ART_ADDRESS, "to" to RECIPIENT, "value" to "1500000 (1.5 USDC)"),
            review.params,
        )
        assertTrue(review.warnings.isEmpty())
    }

    @Test
    fun transferFromAnUnknownContractIsNamedAsATokenOrAnNft() {
        val data = transferFromCall(ART_ADDRESS, RECIPIENT, BigInteger.valueOf(7))
        val tx = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("transferFrom (token or NFT)", review.function)
        assertEquals("", review.amount)
        assertEquals(
            listOf("from" to ART_ADDRESS, "to" to RECIPIENT, "amount or tokenId" to "7"),
            review.params,
        )
        assertEquals(UNREGISTERED, review.tokenContract)
    }

    @Test
    fun increaseAllowanceAtTheUnlimitedThresholdIsWarned() {
        val data = erc20Call(SELECTOR_INCREASE_ALLOWANCE, RECIPIENT, ContractCall.MAX_UINT256)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("increaseAllowance", review.function)
        assertEquals(listOf(UNLIMITED_USDC), review.warnings)
        assertEquals(listOf("spender", "addedValue"), review.params.map { it.first })
    }

    @Test
    fun setApprovalForAllGrantIsWarnedAndRevokeIsNot() {
        val grant = eip1559Tx(
            chainId = ETHEREUM,
            to = UNREGISTERED,
            value = BigInteger.ZERO,
            data = erc20Call(SELECTOR_SET_APPROVAL_FOR_ALL, RECIPIENT, BigInteger.ONE),
        )
        val revoke = eip1559Tx(
            chainId = ETHEREUM,
            to = UNREGISTERED,
            value = BigInteger.ZERO,
            data = erc20Call(SELECTOR_SET_APPROVAL_FOR_ALL, RECIPIENT, BigInteger.ZERO),
        )

        val granted = ready(requestCbor(grant, chainId = ETHEREUM)).review
        assertEquals("setApprovalForAll", granted.function)
        assertEquals(listOf("Grants control of ALL your NFTs in this collection"), granted.warnings)
        assertEquals(listOf("operator" to RECIPIENT, "approved" to "true"), granted.params)
        assertEquals("", granted.amount)

        val revoked = ready(requestCbor(revoke, chainId = ETHEREUM)).review
        assertEquals(listOf("operator" to RECIPIENT, "approved" to "false"), revoked.params)
        assertTrue(revoked.warnings.isEmpty())
    }

    @Test
    fun safeTransferFromShowsItsTokenIdAsTheHeadline() {
        val data = callOf(SELECTOR_SAFE_TRANSFER_FROM, addressWord(ART_ADDRESS), addressWord(RECIPIENT), uintWord(BigInteger.valueOf(7)))
        val tx = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("safeTransferFrom", review.function)
        assertEquals("#7", review.amount)
        assertEquals(listOf("from" to ART_ADDRESS, "to" to RECIPIENT, "tokenId" to "7"), review.params)
    }

    @Test
    fun anUndecodableCallIsRefusedWithTheWholeCallOnScreen() {
        // balanceOf with an address and an amount word, then one stray byte, so the last word is shorter than 32 bytes.
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO) + "ab"
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)

        val refused = assertIs<Prepared.Refused>(prepared(requestCbor(tx, chainId = ETHEREUM)))

        assertEquals("Unknown contract call", refused.reason)
        val call = assertNotNull(refused.call)
        assertEquals(USDC_ETHEREUM, call.target)
        assertEquals("Ethereum", call.networkName)
        assertEquals(ETHEREUM, call.chainId)
        assertEquals("0 ETH", call.value)
        assertEquals(SELECTOR_BALANCE_OF, call.selector)
        assertEquals(
            listOf(
                "000000000000000000000000" + RECIPIENT.removePrefix("0x").lowercase(),
                "0".repeat(64),
                "ab",
            ),
            call.words,
        )
    }

    @Test
    fun theReviewCarriesTheWalletAndTheRequestOrigin() {
        val review = ready(requestCbor(eip1559Tx())).review

        assertEquals("Seed Wallet", review.walletName)
        assertEquals(ORIGIN, review.origin)
        assertEquals(WALLET_ID, ready(requestCbor(eip1559Tx())).walletId)
    }

    @Test
    fun aRequestWithoutAnOriginHasNoOrigin() {
        val noOrigin = EthSignRequest(
            requestId = REQUEST_ID,
            signData = eip1559Tx(),
            dataType = DataType.TYPED_TRANSACTION,
            chainId = MONAD_TESTNET,
            derivationPath = accountPath(0),
            sourceFingerprint = WALLET.masterFingerprint,
            address = null,
            origin = null,
        )

        assertNull(assertIs<Prepared.Ready>(pipeline.prepare(noOrigin, VIEW)).review.origin)
    }

    @Test
    fun personalMessageIsReviewedAsTextWhenPrintableAndHexOtherwise() {
        val text = ready(requestCbor("Sign in to Lensly".toByteArray(Charsets.UTF_8), dataType = 3)).review
        assertEquals("Sign message", text.function)
        assertEquals(
            listOf("message" to "Sign in to Lensly", "size" to "17 characters, 17 bytes"),
            text.params,
        )
        assertEquals("Personal message", text.txType)
        assertTrue(text.fee.isEmpty())

        val binary = byteArrayOf(0x00, 0x01, 0xff.toByte())
        assertEquals("0x0001ff", ready(requestCbor(binary, dataType = 3)).review.params.first().second)
        assertEquals("3 bytes", ready(requestCbor(binary, dataType = 3)).review.params.last().second)

        val withControl = "a\u0001b".toByteArray(Charsets.UTF_8)
        assertEquals("0x610162", ready(requestCbor(withControl, dataType = 3)).review.params.first().second)
    }

    @Test
    fun aHexMessageIsGroupedInEightDigitGroupsAndKeepsEveryByte() {
        val bytes = ByteArray(10) { it.toByte() }

        val review = ready(requestCbor(bytes, dataType = 3)).review

        assertEquals("0x00010203 04050607 0809", review.params.first().second)
        assertEquals("10 bytes", review.params.last().second)
    }

    @Test
    fun aMessageWithAnInvisibleFormatCharacterIsShownAsHex() {
        // U+202E, a right-to-left override, is invisible in text. Its bytes are shown instead.
        val hidden = "a‮b".toByteArray(Charsets.UTF_8)

        assertEquals("0x61e280ae 62", ready(requestCbor(hidden, dataType = 3)).review.params.first().second)
    }

    @Test
    fun aLongerMessageReportsItsCharactersAndItsBytes() {
        // Two characters take three bytes in UTF-8, so the counts differ.
        val accented = "héllo".toByteArray(Charsets.UTF_8)

        assertEquals("5 characters, 6 bytes", ready(requestCbor(accented, dataType = 3)).review.params.last().second)
    }

    @Test
    fun secondAccountIsReviewedWithItsOwnAddress() {
        val signable = ready(requestCbor(eip1559Tx(), index = 1))

        assertEquals(1, signable.accountIndex)
        assertEquals("SEED WALLET 1", signable.review.fromAccountLabel)
        assertEquals(WALLET.address(1), signable.review.fromAddress)
    }

    @Test
    fun aRenamedAccountIsReviewedUnderItsDisplayNameInCapitals() {
        val named = WalletView(
            id = WALLET_ID,
            masterFingerprint = WALLET.masterFingerprint,
            accountCount = 2,
            autoNameBase = "Seed Wallet",
            accountNames = mapOf(1 to "Rent"),
            addressAt = { WALLET.address(it) },
        )

        val review = assertIs<Prepared.Ready>(pipeline.prepare(requestCbor(eip1559Tx(), index = 1), named)).review

        assertEquals("RENT", review.fromAccountLabel)
        assertEquals(WALLET.address(1), review.fromAddress)
        assertEquals("SEED WALLET 0", ready(requestCbor(eip1559Tx())).review.fromAccountLabel)
    }

    @Test
    fun requestedAddressMustMatchTheAccountCaseInsensitively() {
        val data = eip1559Tx()

        assertEquals(0, ready(requestCbor(data, address = Numeric.hexStringToByteArray(ART_ADDRESS))).accountIndex)
        assertEquals(
            "This request is for a different address",
            refusal(requestCbor(data, address = Numeric.hexStringToByteArray(WALLET.address(1)))),
        )
    }

    @Test
    fun signedEip1559TransferRecoversToAccountZeroAndEchoesTheRequestId() {
        val signData = eip1559Tx()
        val response = signResponse(ready(requestCbor(signData)))

        assertContentEquals(REQUEST_ID, response.requestId)
        assertEquals(65, response.signature.size)
        val v = response.signature[64].toInt()
        assertTrue(v == 0 || v == 1, "v must be a recovery id, was $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Hash.sha3(signData), response.signature, v),
        )
    }

    @Test
    fun signedLegacyTransferCarriesEip155V() {
        val signData = legacyTx()
        val response = signResponse(ready(requestCbor(signData, dataType = 1)))

        // v = 10143 * 2 + 35 + recId is 20321 or 20322, which needs two bytes.
        assertEquals(66, response.signature.size)
        val v = BigInteger(1, response.signature.copyOfRange(64, 66)).toInt()
        assertTrue(v == 20321 || v == 20322, "unexpected v $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Hash.sha3(signData), response.signature, v - 20321),
        )
    }

    @Test
    fun signedPersonalMessageRecoversWithTheEthereumMessageHash() {
        val message = "Sign in to Lensly".toByteArray(Charsets.UTF_8)
        val response = signResponse(ready(requestCbor(message, dataType = 3)))

        val v = response.signature[64].toInt()
        assertTrue(v == 27 || v == 28, "personal message v must be 27 or 28, was $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Sign.getEthereumMessageHash(message), response.signature, v - 27),
        )
    }

    @Test
    fun signedUnknownChainEip1559TransferRecoversToAccountZero() {
        val signData = eip1559Tx(chainId = UNKNOWN_CHAIN)
        val response = signResponse(ready(requestCbor(signData, chainId = UNKNOWN_CHAIN)))

        assertEquals(65, response.signature.size)
        val v = response.signature[64].toInt()
        assertTrue(v == 0 || v == 1, "v must be a recovery id, was $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Hash.sha3(signData), response.signature, v),
        )
    }

    @Test
    fun signedUnknownChainLegacyTransferRecoversToAccountZero() {
        val signData = legacyTx(chainId = UNKNOWN_CHAIN)
        val response = signResponse(ready(requestCbor(signData, dataType = 1, chainId = UNKNOWN_CHAIN)))

        // v = 999999 * 2 + 35 + recId is 2000033 or 2000034, which needs three bytes.
        assertEquals(67, response.signature.size)
        val v = BigInteger(1, response.signature.copyOfRange(64, 67)).toLong()
        val recId = (v - (UNKNOWN_CHAIN * 2 + 35)).toInt()
        assertTrue(recId == 0 || recId == 1, "unexpected v $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Hash.sha3(signData), response.signature, recId),
        )
    }

    @Test
    fun signRefusesARequestWithoutAnIdAndStillZeroesTheKey() {
        val signable = ready(requestCbor(eip1559Tx(), requestId = null))
        val key = WALLET.privateKey(0)

        assertFailsWith<IllegalArgumentException> { pipeline.sign(signable, key) }
        assertTrue(key.all { it == 0.toByte() }, "the private key must be zeroed even when signing fails")
    }

    @Test
    fun typedDataIsRefused() {
        val typed = "{\"types\":{},\"message\":{}}".toByteArray(Charsets.UTF_8)
        assertEquals("Typed data not supported yet", refusal(requestCbor(typed, dataType = 2)))
    }

    @Test
    fun typedDataWithAnUnknownDomainChainIsRefusedToo() {
        // The domain names chain 999999, which is not in the registry. Typed data is refused before any chain check.
        val typed = "{\"types\":{},\"domain\":{\"chainId\":999999},\"message\":{}}".toByteArray(Charsets.UTF_8)
        assertEquals("Typed data not supported yet", refusal(requestCbor(typed, dataType = 2, chainId = UNKNOWN_CHAIN)))
    }

    @Test
    fun requestForAnotherWalletIsRefused() {
        val otherWallet = WALLET.masterFingerprint xor 1
        assertEquals(
            "This request is for a different wallet",
            refusal(requestCbor(eip1559Tx(), fingerprint = otherWallet)),
        )
    }

    @Test
    fun unknownChainNativeTransferIsReviewedWithAWarning() {
        val tx = eip1559Tx(chainId = UNKNOWN_CHAIN, value = BigInteger.TEN.pow(18))
        val review = ready(requestCbor(tx, chainId = UNKNOWN_CHAIN)).review

        assertEquals("Unknown Chain", review.networkName)
        assertEquals(UNKNOWN_CHAIN, review.chainId)
        assertNull(review.network)
        assertEquals("1000000000000000000 base units", "${review.amount} ${review.symbol}")
        assertEquals("≈ 1.0 if 18 decimals (assumption, not verified)", review.amountNote)
        assertEquals("Native transfer", review.function)
        assertEquals(RECIPIENT, review.toAddress)
        assertNull(review.tokenContract)
        assertNull(review.token)
        assertTrue(review.warnings.isEmpty())
        assertTrue(review.needsRegistryConfirmation)
        assertEquals(
            listOf(
                "Gas limit" to "21000",
                "Max fee per gas" to "30 gwei",
                "Max priority fee" to "1.5 gwei",
                "Max fee" to "630000000000000 base units",
            ),
            review.fee,
        )
        assertEquals(
            "≈ 0.00063 if 18 decimals (assumption, not verified). " +
                "The fee model is unknown, so the total may be higher.",
            review.feeNote,
        )
    }

    @Test
    fun unknownChainLegacyTransferShowsItsFeeInBaseUnits() {
        val review = ready(requestCbor(legacyTx(chainId = UNKNOWN_CHAIN), dataType = 1, chainId = UNKNOWN_CHAIN)).review

        assertEquals("Legacy", review.txType)
        assertEquals(
            listOf("Gas limit" to "60000", "Gas price" to "20 gwei", "Max fee" to "1200000000000000 base units"),
            review.fee,
        )
        assertTrue(review.needsRegistryConfirmation)
    }

    @Test
    fun unknownChainPersonalMessageIsReviewedWithAWarning() {
        val review = ready(
            requestCbor("Sign in to Lensly".toByteArray(Charsets.UTF_8), dataType = 3, chainId = UNKNOWN_CHAIN),
        ).review

        assertEquals("Unknown Chain", review.networkName)
        assertEquals(UNKNOWN_CHAIN, review.chainId)
        assertNull(review.network)
        assertEquals("Sign message", review.function)
        assertEquals(listOf("message" to "Sign in to Lensly", "size" to "17 characters, 17 bytes"), review.params)
        assertTrue(review.needsRegistryConfirmation)
    }

    @Test
    fun unknownChainAndUnknownTokenAreWarnedTogether() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(42))
        val tx = eip1559Tx(chainId = UNKNOWN_CHAIN, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = UNKNOWN_CHAIN)).review

        assertEquals("Unknown Chain", review.networkName)
        assertNull(review.network)
        assertEquals("transfer", review.function)
        assertNull(review.token)
        assertEquals(UNREGISTERED, review.tokenContract)
        assertEquals("42 base units", "${review.amount} ${review.symbol}")
        assertEquals("Decimals unknown: raw integer amount, not verified", review.amountNote)
        assertTrue(review.needsRegistryConfirmation)
    }

    @Test
    fun knownChainsAndTokensAreReviewedWithoutAWarning() {
        val usdcTransfer = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(1_500_000))
        val usdcApproval = erc20Call(SELECTOR_APPROVE, RECIPIENT, ContractCall.MAX_UINT256)
        val reviews = listOf(
            ready(requestCbor(eip1559Tx())).review,
            ready(requestCbor(eip1559Tx(chainId = BASE), chainId = BASE)).review,
            ready(requestCbor(legacyTx(), dataType = 1)).review,
            ready(
                requestCbor(
                    eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = usdcTransfer),
                    chainId = ETHEREUM,
                ),
            ).review,
            ready(
                requestCbor(
                    eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = usdcApproval),
                    chainId = ETHEREUM,
                ),
            ).review,
            ready(requestCbor("Sign in to Lensly".toByteArray(Charsets.UTF_8), dataType = 3)).review,
        )
        reviews.forEach { review ->
            val label = "${review.function} on ${review.networkName}"
            assertFalse(review.needsRegistryConfirmation, label)
            assertNull(review.amountNote, label)
        }
        assertEquals(listOf(UNLIMITED_USDC), reviews[4].warnings)
    }

    @Test
    fun accountOutsideTheWalletOrOffTheAccountPathIsRefused() {
        val data = eip1559Tx()
        val unknownAccount = "Unknown account"

        assertEquals(unknownAccount, refusal(requestCbor(data, index = 2)))
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 1L to true, 0L to false, 0L to false))),
        )
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 0L to true, 1L to false, 0L to false))),
        )
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 0L to true, 0L to false, 0L to true))),
        )
    }

    @Test
    fun unknownTokenTransferOnAKnownChainIsReviewedWithAWarning() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(1_500_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("transfer", review.function)
        assertEquals("Ethereum", review.networkName)
        assertEquals(REGISTRY.network(ETHEREUM), review.network)
        assertNull(review.token)
        assertEquals(UNREGISTERED, review.tokenContract)
        assertEquals("1500000 base units", "${review.amount} ${review.symbol}")
        assertEquals("Decimals unknown: raw integer amount, not verified", review.amountNote)
        assertEquals(RECIPIENT, review.toAddress)
        assertTrue(review.warnings.isEmpty())
        assertTrue(review.needsRegistryConfirmation)
    }

    @Test
    fun unknownTokenApprovalKeepsTheUnlimitedWarning() {
        val data = erc20Call(SELECTOR_APPROVE, RECIPIENT, ContractCall.MAX_UINT256)
        val tx = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("approve", review.function)
        assertEquals(listOf(UNLIMITED_UNKNOWN), review.warnings)
        assertEquals(listOf("spender", "value"), review.params.map { it.first })
        assertEquals(ContractCall.MAX_UINT256.toString(), review.params.last().second)
        assertNull(review.token)
        assertTrue(review.needsRegistryConfirmation)
    }

    @Test
    fun unknownCalldataIsRefusedOnAnUnknownTokenAndOnAnUnknownChain() {
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO)
        val onUnknownToken = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val onUnknownChain = eip1559Tx(chainId = UNKNOWN_CHAIN, to = UNREGISTERED, value = BigInteger.ZERO, data = data)

        assertEquals("Unknown contract call", refusal(requestCbor(onUnknownToken, chainId = ETHEREUM)))
        assertEquals("Unknown contract call", refusal(requestCbor(onUnknownChain, chainId = UNKNOWN_CHAIN)))
    }

    @Test
    fun contractCreationIsStillRefusedOnAnUnknownChain() {
        val deploy = TransactionEncoder.encode(
            RawTransaction.createContractTransaction(
                BigInteger.ZERO,
                BigInteger.valueOf(1_000_000_000L),
                BigInteger.valueOf(100_000),
                BigInteger.ZERO,
                "0x6080",
            ),
            UNKNOWN_CHAIN,
        )

        assertEquals(
            "Contract creation is not supported",
            refusal(requestCbor(deploy, dataType = 1, chainId = UNKNOWN_CHAIN)),
        )
    }

    @Test
    fun unknownCalldataOnARegisteredTokenIsRefused() {
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)

        assertEquals("Unknown contract call", refusal(requestCbor(tx, chainId = ETHEREUM)))
    }

    @Test
    fun transactionChainMustMatchTheRequestChain() {
        val tx = legacyTx(chainId = ETHEREUM)

        assertEquals(
            "The transaction is for chain 1, but the request is for chain 10143",
            refusal(requestCbor(tx, dataType = 1, chainId = MONAD_TESTNET)),
        )
    }

    @Test
    fun tokenCallMayNotAlsoSendNativeValue() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ONE, data = data)

        assertEquals("Token calls must not send native currency", refusal(requestCbor(tx, chainId = ETHEREUM)))
    }

    @Test
    fun signDataOverSixtyFourKibIsRefusedBeforeDecoding() {
        val oversized = EthSignRequest(
            requestId = REQUEST_ID,
            signData = ByteArray(EthSignRequest.MAX_SIGN_DATA_BYTES + 1),
            dataType = DataType.TYPED_TRANSACTION,
            chainId = MONAD_TESTNET,
            derivationPath = accountPath(0),
            sourceFingerprint = WALLET.masterFingerprint,
            address = null,
            origin = null,
        )

        assertEquals(
            "The data to sign is larger than 64 KiB",
            assertIs<Prepared.Refused>(pipeline.prepare(oversized, VIEW)).reason,
        )
        // The same payload arriving as CBOR is refused by the decoder before any transaction parsing.
        assertIs<Prepared.Refused>(prepared(requestCbor(ByteArray(EthSignRequest.MAX_SIGN_DATA_BYTES + 1))))
    }

    @Test
    fun aSingleKeyTransactionIsSignedByItsKeyAndRecoversItsAddress() {
        val tx = eip1559Tx()
        val ready = assertIs<Prepared.Ready>(
            pipeline.prepare(requestCbor(tx, fingerprint = null, address = SINGLE_KEY_BYTES), SINGLE_KEY_VIEW),
        )
        assertEquals(SINGLE_KEY_ADDRESS, ready.review.fromAddress)
        assertEquals("IMPORTED 0", ready.review.fromAccountLabel)

        val response = signResponse(ready, key = SINGLE_KEY.copyOf())

        assertContentEquals(REQUEST_ID, response.requestId)
        val v = response.signature[64].toInt()
        assertTrue(v == 0 || v == 1, "v must be a recovery id, was $v")
        assertEquals(SINGLE_KEY_ADDRESS_HEX, recovered(Hash.sha3(tx), response.signature, v))
    }

    @Test
    fun aSingleKeyPersonalMessageIsSignedByItsKeyAndRecoversItsAddress() {
        val message = "Sign in to Lensly".toByteArray(Charsets.UTF_8)
        val ready = assertIs<Prepared.Ready>(
            pipeline.prepare(
                requestCbor(message, dataType = 3, fingerprint = null, address = SINGLE_KEY_BYTES),
                SINGLE_KEY_VIEW,
            ),
        )

        val response = signResponse(ready, key = SINGLE_KEY.copyOf())

        val v = response.signature[64].toInt() and 0xFF
        assertTrue(v == 27 || v == 28, "v must be 27 or 28, was $v")
        val publicKey = Sign.signedPrefixedMessageToKey(
            message,
            Sign.SignatureData(v.toByte(), response.signature.copyOfRange(0, 32), response.signature.copyOfRange(32, 64)),
        )
        assertEquals(SINGLE_KEY_ADDRESS_HEX, EvmFixtures.normalize(Keys.getAddress(publicKey)))
    }

    @Test
    fun aSingleKeyRequestMustNameItsAddress() {
        val reason = assertIs<Prepared.Refused>(
            pipeline.prepare(requestCbor(eip1559Tx(), fingerprint = null), SINGLE_KEY_VIEW),
        ).reason

        assertEquals("The request does not say which address to sign with", reason)
    }

    @Test
    fun aSingleKeyRequestForAnotherAddressIsRefused() {
        val reason = assertIs<Prepared.Refused>(
            pipeline.prepare(requestCbor(eip1559Tx(), fingerprint = null, address = ART_BYTES), SINGLE_KEY_VIEW),
        ).reason

        assertEquals("This request is for a different address", reason)
    }

    private fun prepared(cbor: ByteArray): Prepared = pipeline.prepare(cbor, VIEW)

    private fun ready(cbor: ByteArray): Prepared.Ready = assertIs<Prepared.Ready>(prepared(cbor))

    private fun refusal(cbor: ByteArray): String = assertIs<Prepared.Refused>(prepared(cbor)).reason

    /** The fields of the ur:eth-signature that answers a signed request. */
    private class Response(val requestId: ByteArray, val signature: ByteArray)

    /**
     * Signs [ready] with [key], which defaults to the account key from the wallet, then reads back the single eth-signature
     * frame.
     */
    private fun signResponse(ready: Prepared.Ready, key: ByteArray = WALLET.privateKey(ready.accountIndex)): Response {
        val frames = pipeline.sign(ready, key)
        assertTrue(key.all { it == 0.toByte() }, "the private key must be zeroed after signing")

        assertEquals(1, frames.size)
        val scanner = UrScanner()
        assertTrue(scanner.receive(frames.single()))
        val ur = assertNotNull(scanner.result())
        assertEquals("eth-signature", ur.type)

        val fields = (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (field, value) ->
            (field as Cbor.Uint).value to value
        }
        assertEquals("Lensly", (fields.getValue(3L) as Cbor.Text).value)
        return Response(
            requestId = ((fields.getValue(1L) as Cbor.Tagged).value as Cbor.Bytes).value,
            signature = (fields.getValue(2L) as Cbor.Bytes).value,
        )
    }

    private fun recovered(hash: ByteArray, signature: ByteArray, recId: Int): String =
        EvmFixtures.recoveredAddress(
            hash,
            signature.copyOfRange(0, 32),
            signature.copyOfRange(32, 64),
            recId,
        )

    /** A valid eth-sign-request as a wallet sends it. Pass null for a field to leave it out. */
    private fun requestCbor(
        signData: ByteArray,
        dataType: Long = 4,
        chainId: Long? = MONAD_TESTNET,
        index: Long = 0,
        fingerprint: Int? = WALLET.masterFingerprint,
        address: ByteArray? = null,
        requestId: ByteArray? = REQUEST_ID,
        path: List<Pair<Long, Boolean>> = accountPathPairs(index),
    ): ByteArray = Cbor.encode(
        dict(
            1L to requestId?.let { Cbor.Tagged(37, Cbor.Bytes(it)) },
            2L to Cbor.Bytes(signData),
            3L to Cbor.Uint(dataType),
            4L to chainId?.let { Cbor.Uint(it) },
            5L to Cbor.Tagged(
                304,
                dict(
                    1L to Cbor.Arr(path.flatMap { (step, hardened) -> listOf(Cbor.Uint(step), Cbor.Bool(hardened)) }),
                    2L to fingerprint?.let { Cbor.Uint(it.toLong() and 0xFFFFFFFFL) },
                ),
            ),
            6L to address?.let { Cbor.Bytes(it) },
            7L to Cbor.Text("metamask"),
        ),
    )

    private fun accountPathPairs(index: Long): List<Pair<Long, Boolean>> =
        listOf(44L to true, 60L to true, 0L to true, 0L to false, index to false)

    private fun accountPath(index: Int): List<PathStep> =
        listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(index, false))

    private fun dict(vararg fields: Pair<Long, Cbor?>): Cbor.Dict =
        Cbor.Dict(fields.mapNotNull { (key, value) -> value?.let { Cbor.Uint(key) to it } })

    private fun eip1559Tx(
        chainId: Long = MONAD_TESTNET,
        to: String = RECIPIENT,
        value: BigInteger = ONE_CENT_OF_MON,
        data: String = "0x",
    ): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            chainId,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            to,
            value,
            data,
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
        ),
    )

    private fun legacyTx(
        chainId: Long = MONAD_TESTNET,
        to: String = RECIPIENT,
        value: BigInteger = ONE_CENT_OF_MON,
        data: String = "0x",
    ): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            BigInteger.valueOf(3),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(60_000),
            to,
            value,
            data,
        ),
        chainId,
    )

    /** ABI calldata for a call that takes an address and a uint256, such as transfer or approve. */
    private fun erc20Call(selector: String, address: String, amount: BigInteger): String =
        callOf(selector, addressWord(address), uintWord(amount))

    /** ABI calldata for transferFrom(from, to, amount). */
    private fun transferFromCall(from: String, to: String, amount: BigInteger): String =
        callOf(SELECTOR_TRANSFER_FROM, addressWord(from), addressWord(to), uintWord(amount))

    /** Calldata: the selector, then each argument as one 64-digit word. */
    private fun callOf(selector: String, vararg words: String): String = "0x" + selector + words.joinToString("")

    private fun addressWord(address: String): String = address.removePrefix("0x").lowercase().padStart(64, '0')

    private fun uintWord(amount: BigInteger): String = amount.toString(16).padStart(64, '0')
}
