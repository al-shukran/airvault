package app.lensly.app.sign

import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.text
import app.airvault.abi.AbiValue
import app.airvault.abi.CallDecode
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.airvault.chains.evm.signer.AccessListEntry
import app.airvault.chains.evm.signer.ContractCall
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.InnerCall
import app.airvault.chains.evm.signer.Multicall
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.chains.evm.signer.TxType
import app.airvault.chains.evm.signer.UnsignedTx
import app.lensly.core.evm.EvmFixtures
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.InProcessSandbox
import app.lensly.core.parse.ParserWire
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.SandboxTransport
import app.lensly.core.parse.SandboxedParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.ScriptedSandbox
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.registry.DenylistCondition
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.shippedRegistry
import com.sparrowwallet.hummingbird.UR
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
import org.web3j.crypto.AccessListObject
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

private const val MONAD_TESTNET = 10143L
private const val ETHEREUM = 1L
private const val WHOLE_REQUEST = 64 * 1024
private val STORAGE_KEY = "0x" + "22".repeat(32)
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
private const val PULL_SELECTOR = "0x4d0392a8"
private const val L2_NOTE = "L1 data fee is not computable offline; total may be higher"

private val REQUEST_ID = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")
private val ONE_CENT_OF_MON: BigInteger = BigInteger.TEN.pow(16)
private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }

private const val WALLET_ID = "wallet-mnemonic"
private const val IMPORTED_ID = "wallet-single-key"
private const val ORIGIN = "metamask"
private const val UNLIMITED_USDC = "Unlimited approval: this contract can spend all your USDC"
private const val UNLIMITED_UNKNOWN = "Unlimited approval: this contract can spend all your tokens of this contract"

// A whitelisted call Lensly cannot decode: the swap selector is not one ContractCall knows. The proxy is a stake on another contract.
private const val SWAP_SELECTOR = "6e17e9f6"
private const val STAKE_SELECTOR = "7acb7757"
private const val WHITELISTED = "0x5555555555555555555555555555555555555555"
private const val PROXY = "0x6666666666666666666666666666666666666666"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val SWAP_SIGNATURE = "swapExactTokensForTokens(uint256,uint256,address)"
private const val SWAP_MEANING = "Swap an exact amount of one token for another, along the route in the call."
private const val STAKE_SIGNATURE = "stake(uint256,address)"
private const val BOUND_PROXY_REASON = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is " +
    "still the approved version, so your Proxy Policy refuses it."
private const val ANY_PROXY_REASON = "This contract is an upgradeable proxy, and your Proxy Policy refuses every proxy."
private val SWAP_ENTRY = WhitelistEntry(
    chainId = ETHEREUM,
    contract = WHITELISTED,
    selector = "0x$SWAP_SELECTOR",
    signature = SWAP_SIGNATURE,
    meaning = SWAP_MEANING,
    params = listOf(
        WhitelistParam("amountIn", "uint256", tokenRef = true),
        WhitelistParam("amountOutMin", "uint256"),
        WhitelistParam("to", "address"),
    ),
    upgradeable = false,
)
private val PROXY_BINDING = ProxyBinding(implementationAddress = IMPLEMENTATION)
private val PROXY_ENTRY = WhitelistEntry(
    chainId = ETHEREUM,
    contract = PROXY,
    selector = "0x$STAKE_SELECTOR",
    signature = STAKE_SIGNATURE,
    meaning = "Stake for a recipient",
    proxy = PROXY_BINDING,
    params = listOf(
        WhitelistParam("amount", "uint256", tokenRef = true),
        WhitelistParam("recipient", "address"),
    ),
    upgradeable = true,
)

// A multicall: the Uniswap router's batch of calls, the calls the whitelist approves on it, and the Multicall3 contract.
private const val ROUTER = "0xE592427A0AEce92De3Edee1F18E0157C05861564"
private const val MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11"
private const val SELECTOR_MULTICALL = "ac9650d8"
private const val SWEEP_SIGNATURE = "sweepToken(address,uint256,address)"
private const val UNWRAP_SIGNATURE = "unwrapWETH9(uint256,address)"
private const val SWEEP_MEANING = "Send the router's balance of a token to a recipient."
private const val TOO_DEEP = "The calls nest deeper than 3 levels, so Lensly cannot show every call."
private const val TOO_MANY = "The transaction holds more than 32 calls, so Lensly cannot show every call."
private val ROUTER_CHECKSUM = Keys.toChecksumAddress(ROUTER)
private val SWEEP_ENTRY = WhitelistEntry(
    chainId = ETHEREUM,
    contract = ROUTER.lowercase(),
    selector = WhitelistEntry.selectorOf(SWEEP_SIGNATURE),
    signature = SWEEP_SIGNATURE,
    meaning = SWEEP_MEANING,
    params = listOf(
        WhitelistParam("token", "address"),
        WhitelistParam("amountMinimum", "uint256"),
        WhitelistParam("recipient", "address"),
    ),
    upgradeable = false,
)
private val UNWRAP_ENTRY = WhitelistEntry(
    chainId = ETHEREUM,
    contract = ROUTER.lowercase(),
    selector = WhitelistEntry.selectorOf(UNWRAP_SIGNATURE),
    signature = UNWRAP_SIGNATURE,
    meaning = "Unwrap the router's wrapped native currency to a recipient.",
    params = listOf(WhitelistParam("amountMinimum", "uint256"), WhitelistParam("recipient", "address")),
    upgradeable = false,
)

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
private val IMPORTED_PRIVATE: ByteArray =
    Numeric.hexStringToByteArray("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318")
private const val IMPORTED_ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"
private const val IMPORTED_ADDRESS_HEX = "2c7536e3605d9c16a7a3d7b1898e529396a65c23"
private val IMPORTED_BYTES: ByteArray = Numeric.hexStringToByteArray(IMPORTED_ADDRESS)
private val IMPORTED_VIEW by lazy {
    WalletView.singleKey(id = IMPORTED_ID, address = IMPORTED_ADDRESS, autoNameBase = "Imported")
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

/** The registry with the test whitelist: a plain approved call on Ethereum, and an approved proxy on Ethereum. */
private val GUARDED: Registry = REGISTRY.withWhitelist(listOf(SWAP_ENTRY, PROXY_ENTRY))

// The denylist that ships in the APK, on the same networks and tokens as REGISTRY.
private val DENYLISTED: Registry = REGISTRY.withDenylist(shippedRegistry().denylistEntries())

/** The registry of the batch tests: the router's two approved calls, and the approved swap and proxy the other tests use. */
private val BATCH: Registry = REGISTRY.withWhitelist(listOf(SWEEP_ENTRY, UNWRAP_ENTRY, SWAP_ENTRY, PROXY_ENTRY))

private const val BLOCK_REASON = "Sweeps the whole balance of a token to one recipient."
private const val WARN_REASON = "Unlimited approval: the spender can take every token."

/** The batch registry with the denylist the batch tests check: a BLOCK on the router's sweep, and a WARN on an unlimited approval. */
private val BATCH_DENYLIST: Registry = BATCH.withDenylist(
    listOf(
        DenylistEntry(
            chainId = ETHEREUM,
            contract = ROUTER.lowercase(),
            selector = WhitelistEntry.selectorOf(SWEEP_SIGNATURE),
            severity = DenylistSeverity.BLOCK,
            reason = BLOCK_REASON,
        ),
        DenylistEntry(
            chainId = ETHEREUM,
            selector = WhitelistEntry.selectorOf("approve(address,uint256)"),
            condition = DenylistCondition.UNLIMITED,
            severity = DenylistSeverity.WARN,
            reason = WARN_REASON,
        ),
    ),
)

class SignPipelineTest {

    private val pipeline = SignPipeline(REGISTRY, inProcessSandboxParser)

    /** The unlimited threshold is 2^160 - 1: that value and every larger one are warned, and the value one below it is not. */
    @Test
    fun anUndecodableCallIsRefusedWithTheWholeCallOnScreen() {
        // balanceOf with an address and an amount word, then one stray byte, so the last word is shorter than 32 bytes.
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO) + "ab"
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)

        val refused = assertIs<Prepared.Refused>(prepared(requestCbor(tx, chainId = ETHEREUM)))

        assertEquals("Unknown Contract Call", refused.reason)
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

    private fun swapData(): String = "0x" + SWAP_SELECTOR + uintWord(BigInteger.ONE) + uintWord(BigInteger.TEN) +
        addressWord(RECIPIENT)

    private fun swapTx(chainId: Long, to: String, value: BigInteger = BigInteger.ZERO): ByteArray =
        eip1559Tx(chainId = chainId, to = to, value = value, data = swapData())

    private fun stakeTx(): ByteArray = eip1559Tx(
        chainId = ETHEREUM,
        to = PROXY,
        value = BigInteger.ZERO,
        data = "0x" + STAKE_SELECTOR + uintWord(BigInteger.TEN) + addressWord(RECIPIENT),
    )

    private fun guarded(cbor: ByteArray, policy: ProxyPolicy = ProxyPolicy.DEFAULT): Prepared =
        SignPipeline(GUARDED, inProcessSandboxParser, policy).prepare(cbor, VIEW)

    /** Under Warn an unknown call is signable only once the owner ticks that Lensly cannot say what it does, as a Solana unknown is. */
    /** Under Refuse an undecodable call is refused; under Warn it is reviewed with its raw data shown in full and nothing else changed. */
    /** A transaction for another chain than the request is still refused under Warn: the chain check comes before the call check. */
    @Test
    fun aChainIdMismatchIsStillRefusedUnderWarn() {
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO) + "ab"
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)

        val reason = assertIs<Prepared.Refused>(underPolicy(UnknownCallPolicy.WARN, requestCbor(tx, chainId = MONAD_TESTNET))).reason

        assertTrue(reason.startsWith("The transaction is for chain 1, but the request is for chain 10143"), reason)
    }

    @Test
    fun theSameSelectorOnAnotherContractOrChainIsStillRefused() {
        val onOtherContract = requestCbor(swapTx(ETHEREUM, UNREGISTERED), chainId = ETHEREUM)
        val onOtherChain = requestCbor(swapTx(BASE, WHITELISTED), chainId = BASE)

        assertEquals("Unknown Contract Call", assertIs<Prepared.Refused>(guarded(onOtherContract)).reason)
        assertEquals("Unknown Contract Call", assertIs<Prepared.Refused>(guarded(onOtherChain)).reason)
    }

    @Test
    fun aWhitelistedContractWithAnotherSelectorIsStillRefused() {
        val data = callOf(SELECTOR_BALANCE_OF, addressWord(RECIPIENT))
        val tx = eip1559Tx(chainId = ETHEREUM, to = WHITELISTED, value = BigInteger.ZERO, data = data)

        assertEquals("Unknown Contract Call", assertIs<Prepared.Refused>(guarded(requestCbor(tx, chainId = ETHEREUM))).reason)
    }

    @Test
    fun aWhitelistedProxyIsRefusedUnderBindToImplementationAndNamesTheImplementation() {
        val refused = assertIs<Prepared.Refused>(guarded(requestCbor(stakeTx(), chainId = ETHEREUM)))

        assertEquals(BOUND_PROXY_REASON, refused.reason)
        assertEquals(STAKE_SELECTOR, refused.call?.selector)
        assertEquals(ProxyBinding(implementationAddress = IMPLEMENTATION), refused.call?.proxy)
    }

    @Test
    fun aWhitelistedProxyIsRefusedUnderRefuseAllProxies() {
        val refused = assertIs<Prepared.Refused>(
            guarded(requestCbor(stakeTx(), chainId = ETHEREUM), ProxyPolicy.REFUSE_ALL_PROXIES),
        )

        assertEquals(ANY_PROXY_REASON, refused.reason)
        assertEquals(ProxyBinding(implementationAddress = IMPLEMENTATION), refused.call?.proxy)
    }

    @Test
    fun aWhitelistedCallThatIsNotAProxyIsReviewedUnderEveryPolicy() {
        for (policy in ProxyPolicy.entries) {
            val prepared = guarded(requestCbor(swapTx(ETHEREUM, WHITELISTED), chainId = ETHEREUM), policy)

            assertIs<Prepared.Ready>(prepared)
        }
    }

    /** A right-to-left override makes an origin read as another site, so a request that holds one is refused, not shown. */
    @Test
    fun anOriginWithACharacterTheOwnerCannotReadIsRefused() {
        val request = EthSignRequest(
            requestId = REQUEST_ID,
            signData = eip1559Tx(),
            dataType = DataType.TYPED_TRANSACTION,
            chainId = MONAD_TESTNET,
            derivationPath = accountPath(0),
            sourceFingerprint = WALLET.masterFingerprint,
            address = null,
            origin = "app.uniswap.org‮",
        )

        assertEquals(
            "The request names an origin with a character Lensly cannot show.",
            assertIs<Prepared.Refused>(pipeline.prepare(request, VIEW)).reason,
        )
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
    fun typedDataThatDoesNotParseIsRefusedWithTheParsersReason() {
        val typed = "{\"types\":{},\"message\":{}}".toByteArray(Charsets.UTF_8)
        assertEquals("the document is missing primaryType", refusal(requestCbor(typed, dataType = 2)))
    }

    @Test
    fun typedDataForAnotherChainThanTheRequestIsSignableAndItsChainIsNamed() {
        val typed = ("{\"types\":{\"EIP712Domain\":[{\"name\":\"chainId\",\"type\":\"uint256\"}]," +
            "\"Note\":[{\"name\":\"text\",\"type\":\"string\"}]},\"primaryType\":\"Note\",\"domain\":{\"chainId\":1}," +
            "\"message\":{\"text\":\"hi\"}}").toByteArray(Charsets.UTF_8)
        val signable = ready(requestCbor(typed, dataType = 2))

        assertTrue((signable.review.facts + signable.review.sections.flatMap { it.rows }).any { it.label.startsWith("Signature for Chain 1 (") })
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
    fun accountOutsideTheWalletOrOffTheAccountPathIsRefused() {
        val data = eip1559Tx()
        val unknownAccount = "Unknown account"

        assertEquals(unknownAccount, refusal(requestCbor(data, index = 2)))
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 0L to true, 0L to false, 1L to false))),
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
    fun aRequestForAccountOneIsSignedWithAccountOnesKeyAndTheOldPathIsRefused() {
        val ready = ready(requestCbor(eip1559Tx(), index = 1))
        assertEquals(1, ready.accountIndex)

        // The key of m/44'/60'/1'/0/0 signs the request. signResponse checks that the answer is a signature.
        signResponse(ready, WALLET.privateKey(1))

        // The old form, with the account number as the last step, is not an account path now.
        val oldForm = listOf(44L to true, 60L to true, 0L to true, 0L to false, 1L to false)
        assertEquals("Unknown account", refusal(requestCbor(eip1559Tx(), path = oldForm)))
    }

    @Test
    fun unknownCalldataIsRefusedOnAnUnknownTokenAndOnAnUnknownChain() {
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO)
        val onUnknownToken = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)
        val onUnknownChain = eip1559Tx(chainId = UNKNOWN_CHAIN, to = UNREGISTERED, value = BigInteger.ZERO, data = data)

        assertEquals("Unknown Contract Call", refusal(requestCbor(onUnknownToken, chainId = ETHEREUM)))
        assertEquals("Unknown Contract Call", refusal(requestCbor(onUnknownChain, chainId = UNKNOWN_CHAIN)))
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

        assertEquals("Unknown Contract Call", refusal(requestCbor(tx, chainId = ETHEREUM)))
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
    fun aSingleKeyPersonalMessageIsSignedByItsKeyAndRecoversItsAddress() {
        val message = "Sign in to Lensly".toByteArray(Charsets.UTF_8)
        val ready = assertIs<Prepared.Ready>(
            pipeline.prepare(
                requestCbor(message, dataType = 3, fingerprint = null, address = IMPORTED_BYTES),
                IMPORTED_VIEW,
            ),
        )

        val response = signResponse(ready, key = IMPORTED_PRIVATE.copyOf())

        val v = response.signature[64].toInt() and 0xFF
        assertTrue(v == 27 || v == 28, "v must be 27 or 28, was $v")
        val publicKey = Sign.signedPrefixedMessageToKey(
            message,
            Sign.SignatureData(v.toByte(), response.signature.copyOfRange(0, 32), response.signature.copyOfRange(32, 64)),
        )
        assertEquals(IMPORTED_ADDRESS_HEX, EvmFixtures.normalize(Keys.getAddress(publicKey)))
    }

    @Test
    fun aSingleKeyRequestMustNameItsAddress() {
        val reason = assertIs<Prepared.Refused>(
            pipeline.prepare(requestCbor(eip1559Tx(), fingerprint = null), IMPORTED_VIEW),
        ).reason

        assertEquals("The request does not say which address to sign with", reason)
    }

    @Test
    fun aSingleKeyRequestForAnotherAddressIsRefused() {
        val reason = assertIs<Prepared.Refused>(
            pipeline.prepare(requestCbor(eip1559Tx(), fingerprint = null, address = ART_BYTES), IMPORTED_VIEW),
        ).reason

        assertEquals("This request is for a different address", reason)
    }

    @Test
    fun aBlockEntryRefusesTheRequestWithItsReason() {
        val blocking = SignPipeline(
            REGISTRY.withDenylist(
                listOf(
                    DenylistEntry(
                        selector = "0x095ea7b3",
                        condition = DenylistCondition.UNLIMITED,
                        severity = DenylistSeverity.BLOCK,
                        reason = "Blocked for this test.",
                    ),
                ),
            ),
            inProcessSandboxParser,
        )
        val data = erc20Call(SELECTOR_APPROVE, RECIPIENT, ContractCall.MAX_UINT256)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)

        val reason = assertIs<Prepared.Refused>(blocking.prepare(requestCbor(tx, chainId = ETHEREUM), VIEW)).reason

        assertEquals("Blocked for this test.", reason)
    }

    private fun denylisted(cbor: ByteArray): Prepared.Ready =
        assertIs<Prepared.Ready>(SignPipeline(DENYLISTED, inProcessSandboxParser).prepare(cbor, VIEW))

    @Test
    fun aSandboxThatChangesTheRecipientIsRefusedWhileTheHonestParseIsReady() {
        val cbor = requestCbor(eip1559Tx())

        assertIs<Prepared.Ready>(pipeline.prepare(changedParse(cbor) { it }, VIEW))
        val changed = changedParse(cbor) { it.copy(to = UNREGISTERED) }
        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(changed, VIEW))
    }

    @Test
    fun aSandboxThatChangesTheValueIsRefused() {
        val cbor = requestCbor(eip1559Tx())
        val changed = changedParse(cbor) { it.copy(value = it.value + BigInteger.ONE) }

        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(changed, VIEW))
    }

    @Test
    fun aSandboxThatChangesTheChainIsRefused() {
        val cbor = requestCbor(eip1559Tx())
        val changed = changedParse(cbor) { it.copy(chainId = BigInteger.valueOf(ETHEREUM)) }

        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(changed, VIEW))
    }

    @Test
    fun aSandboxThatChangesTheCalldataOfATokenCallIsRefused() {
        val honest = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val cbor = requestCbor(eip1559Tx(to = USDC_ETHEREUM, value = BigInteger.ZERO, data = honest))
        val larger = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(1_000_000))

        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(changedParse(cbor) { it.copy(data = larger) }, VIEW))
    }

    @Test
    fun aTransactionWithAnAccessListIsReviewedAndSignedByItsAccount() {
        val signData = eip1559TxWithAccessList(listOf(AccessListObject(RECIPIENT, listOf(STORAGE_KEY))))
        val response = signResponse(ready(requestCbor(signData)))

        val v = response.signature[64].toInt()
        assertEquals(EvmFixtures.normalize(ART_ADDRESS), recovered(Hash.sha3(signData), response.signature, v))
    }

    /** The access list is shown on the review, with its addresses checksummed as the rest of the review shows them. */
    @Test
    fun aSandboxThatChangesTheAccessListIsRefused() {
        val cbor = requestCbor(eip1559TxWithAccessList(listOf(AccessListObject(RECIPIENT, listOf(STORAGE_KEY)))))
        val dropped = changedParse(cbor) { it.copy(accessList = emptyList()) }
        val swapped = changedParse(cbor) { it.copy(accessList = listOf(AccessListEntry(UNREGISTERED, listOf(STORAGE_KEY)))) }

        assertIs<Prepared.Ready>(pipeline.prepare(changedParse(cbor) { it }, VIEW))
        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(dropped, VIEW))
        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(swapped, VIEW))
    }

    @Test
    fun aSandboxThatChangesTheRecipientOrAmountOfADecodedCallIsRefused() {
        val honest = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val cbor = requestCbor(eip1559Tx(to = USDC_ETHEREUM, value = BigInteger.ZERO, data = honest))
        val recipient = changedCall(cbor) { ContractCall.Transfer(UNREGISTERED, BigInteger.TEN) }
        val amount = changedCall(cbor) { ContractCall.Transfer(RECIPIENT, BigInteger.valueOf(1_000_000)) }

        assertIs<Prepared.Ready>(pipeline.prepare(changedCall(cbor) { it }, VIEW))
        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(recipient, VIEW))
        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(amount, VIEW))
    }

    @Test
    fun aDecodableCallThatTheSandboxHidesIsRefusedByTheGateAsAnUndecodableCallIsOnMaster() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val cbor = requestCbor(eip1559Tx(to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data))
        val hidden = parsedWith(cbor) { it.copy(call = null) }

        assertEquals("Unknown Contract Call", assertIs<Prepared.Refused>(pipeline.prepare(hidden, VIEW)).reason)
    }

    @Test
    fun aDecodableCallThatSendsNativeCurrencyIsRefusedAsOnMaster() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val cbor = requestCbor(eip1559Tx(to = USDC_ETHEREUM, value = BigInteger.ONE, data = data))

        assertEquals("Token calls must not send native currency", refusal(cbor))
    }

    @Test
    fun aSandboxThatChangesAWhitelistedLabelIsRefused() {
        val cbor = requestCbor(swapTx(ETHEREUM, WHITELISTED), chainId = ETHEREUM)
        val lying = object : RequestParser by inProcessSandboxParser {
            override fun decodeCall(calldata: ByteArray, params: List<WhitelistParam>): CallDecode {
                val honest = assertIs<CallDecode.Ok>(inProcessSandboxParser.decodeCall(calldata, params))
                val first = DecodedParam(honest.params.first().param, AbiValue.Word("0".repeat(63) + "7"))
                return CallDecode.Ok(listOf(first) + honest.params.drop(1))
            }
        }

        assertIs<Prepared.Ready>(guarded(cbor))
        val refused = assertIs<Prepared.Refused>(SignPipeline(GUARDED, lying).prepare(cbor, VIEW))
        assertEquals(CANNOT_BE_READ, refused.reason)
    }

    @Test
    fun aSignatureIsNeverMadeOverFieldsThatDifferFromTheReviewedTransaction() {
        val ready = ready(requestCbor(eip1559Tx()))
        val tx = assertNotNull(ready.tx)
        val changed = ready.copy(tx = tx.copy(value = tx.value + BigInteger.ONE))

        assertFailsWith<IllegalArgumentException> { pipeline.sign(changed, WALLET.privateKey(0)) }
    }

    /** Every personal message carries a warning, and a message needs no tick, since it is not a transaction. */
    /** A batch that sends no native currency has no amount to state, so its headline names its calls, not a zero send. */
    // A multicall is read level by level, and each of its calls is decided where it sits.

    @Test
    fun aMulticallNestedDeeperThanTheDepthLimitIsRefused() {
        val transfer = callOf(SELECTOR_TRANSFER, addressWord(RECIPIENT), uintWord(BigInteger.ONE))
        val threeDeep = batchData(listOf(batchData(listOf(batchData(listOf(transfer))))))
        val fourDeep = batchData(listOf(threeDeep))

        assertIs<Prepared.Ready>(batch(threeDeep))
        assertEquals(TOO_DEEP, assertIs<Prepared.Refused>(batch(fourDeep)).reason)
    }

    @Test
    fun aMulticallWithMoreCallsThanTheCountLimitIsRefusedAndOneWithTheLimitIsNot() {
        val approve = callOf(SELECTOR_APPROVE, addressWord(RECIPIENT), uintWord(BigInteger.TEN))
        val approveBytes = Numeric.hexStringToByteArray(approve)
        fun withCalls(count: Int): Prepared = fakeBatch(
            data = batchData(List(count) { approve }),
            calls = List(count) { InnerCall(null, null, null, approveBytes, ContractCall.decode(approveBytes), null) },
        )

        assertIs<Prepared.Ready>(withCalls(32))
        assertEquals(TOO_MANY, assertIs<Prepared.Refused>(withCalls(33)).reason)
    }

    @Test
    fun aTamperedCallInsideAMulticallIsRefusedByTheReEncodeCheck() {
        val approve = callOf(SELECTOR_APPROVE, addressWord(RECIPIENT), uintWord(BigInteger.TEN))
        val cbor = requestCbor(eip1559Tx(chainId = ETHEREUM, to = ROUTER, data = batchData(listOf(approve))), chainId = ETHEREUM)
        assertIs<Prepared.Ready>(pipeline.prepare(parsedWith(cbor) { it }, VIEW))

        val relabelled = batchParse(cbor) { batch ->
            batch.copy(calls = listOf(batch.calls.single().copy(call = ContractCall.Approve(UNREGISTERED, BigInteger.TEN))))
        }
        val flipped = batchParse(cbor) { batch ->
            val bytes = batch.calls.single().calldata.copyOf().also { it[it.size - 1] = 0x0b }
            batch.copy(calls = listOf(batch.calls.single().copy(calldata = bytes)))
        }

        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(relabelled, VIEW))
        assertEquals(Prepared.Refused(CANNOT_BE_READ), pipeline.prepare(flipped, VIEW))
        assertEquals(
            "Unknown Contract Call",
            assertIs<Prepared.Refused>(pipeline.prepare(parsedWith(cbor) { it.copy(multicall = null) }, VIEW)).reason,
        )
    }

    @Test
    fun anUndecodableCallInsideAMulticallIsRefusedAsAnOuterUndecodableCallIsRefused() {
        val unknown = callOf("70a08231", addressWord(RECIPIENT))

        val refused = assertIs<Prepared.Refused>(batch(batchData(listOf(unknown))))

        assertEquals("Unknown Contract Call", refused.reason)
        assertEquals(ROUTER_CHECKSUM, refused.call?.target)
        assertEquals("70a08231", refused.call?.selector)
    }

    @Test
    fun aBlockedCallInsideAMulticallRefusesTheWholeRequestWithItsReason() {
        val sweep = callOf(
            WhitelistEntry.selectorOf(SWEEP_SIGNATURE).removePrefix("0x"),
            addressWord(USDC_ETHEREUM),
            uintWord(BigInteger.TEN),
            addressWord(RECIPIENT),
        )

        val refused = assertIs<Prepared.Refused>(batch(batchData(listOf(sweep)), registry = BATCH_DENYLIST))

        assertEquals(BLOCK_REASON, refused.reason)
    }

    @Test
    fun aMalformedBatchIsAnUndecodableCall() {
        val malformed = "0x" + SELECTOR_MULTICALL + uintWord(BigInteger.valueOf(4096)) + uintWord(BigInteger.ZERO)

        assertEquals("Unknown Contract Call", assertIs<Prepared.Refused>(batch(malformed)).reason)
    }

    @Test
    fun aMulticallReplyTamperedInTheSandboxIsRefusedAndTheHonestOneIsNot() {
        val approve = callOf(SELECTOR_APPROVE, addressWord(RECIPIENT), uintWord(BigInteger.TEN))
        val cbor = requestCbor(eip1559Tx(chainId = ETHEREUM, to = ROUTER, data = batchData(listOf(approve))), chainId = ETHEREUM)
        val honest = ScriptedSandbox { method, payload -> InProcessSandbox.call(method, payload) }
        val tampering = ScriptedSandbox { method, payload ->
            val parsed = assertNotNull(evmParsedOf(ParserWire.decodeScanReply(InProcessSandbox.call(method, payload))))
            val batch = assertNotNull(parsed.multicall)
            val lying = batch.copy(calls = listOf(batch.calls.single().copy(call = ContractCall.Approve(UNREGISTERED, BigInteger.TEN))))
            ParserWire.encodeScanReply(evmReplyOf(parsed.copy(multicall = lying)))
        }

        assertIs<Prepared.Ready>(throughSandbox(cbor, honest))
        assertEquals(Prepared.Refused(CANNOT_BE_READ), throughSandbox(cbor, tampering))
    }

    @Test
    fun eip1559FeeRowsShowGasLimitGasPricesAndMaxFee() {
        val review = ready(requestCbor(eip1559Tx())).review

        assertEquals(
            listOf(
                "Gas Limit" to "21000",
                "Max Fee per Gas" to "30 gwei",
                "Max Priority Fee" to "1.5 gwei",
                "Max Fee" to "0.00063 MON",
            ),
            review.facts.map { it.label to it.value.text() },
        )
    }

    @Test
    fun legacyTransferShowsGasPriceAndLegacyType() {
        val review = ready(requestCbor(legacyTx(), dataType = 1)).review

        assertEquals("Legacy", review.transactionRow("Transaction Type"))
        assertEquals(
            listOf("Gas Limit" to "60000", "Gas Price" to "20 gwei", "Max Fee" to "0.0012 MON"),
            review.facts.map { it.label to it.value.text() },
        )
    }

    @Test
    fun layer2NetworkAddsTheL1FeeNote() {
        val review = ready(requestCbor(eip1559Tx(chainId = BASE), chainId = BASE)).review

        assertEquals("Sends 0.01 ETH to $RECIPIENT.", review.summary.text())
        assertEquals(L2_NOTE, review.fact("Fee Note"))
    }

    @Test
    fun erc20UsdcTransferUsesTokenDecimals() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(1_500_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("transfer", review.title)
        assertEquals("Sends 1.5 USDC to $RECIPIENT.", review.summary.text())
        assertEquals(
            listOf("to" to RECIPIENT, "value" to "1500000 (1.5 USDC)"),
            review.sections.single { it.heading == "Arguments" }.rows.map { it.label to it.value.text() },
        )
    }

    /**
     * A chain outside the registry: its amount is in base units, read with 18 decimals as an assumption, and its fee is too. The registry
     * warning carries the check the owner ticks.
     */
    @Test
    fun unknownChainNativeTransferIsReviewedWithAWarning() {
        val tx = eip1559Tx(chainId = UNKNOWN_CHAIN, value = BigInteger.TEN.pow(18))
        val review = ready(requestCbor(tx, chainId = UNKNOWN_CHAIN)).review

        assertEquals("Unknown network (chain id $UNKNOWN_CHAIN)", review.network.name)
        assertFalse(review.network.testnet)
        assertNull(review.network.detail)
        assertEquals("1000000000000000000 base units", review.transactionRow("Amount"))
        assertEquals("≈ 1.0 if 18 decimals (assumption, not verified)", review.transactionRow("Amount Note"))
        assertEquals(
            "≈ 0.00063 if 18 decimals (assumption, not verified). The fee model is unknown, so the total may be higher.",
            review.fact("Fee Note"),
        )
        val registry = review.warnings.single { it.code == "evm.registry" }
        assertEquals("This network is not in Lensly's registry, so Lensly cannot check its coin or decimals.", registry.message)
        assertNotNull(registry.acknowledge)
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
        assertEquals("Grants $RECIPIENT control of all tokens of $UNREGISTERED.", granted.summary.text())
        assertEquals(
            "Grants control of ALL your NFTs in this collection",
            granted.warnings.single { it.code == "evm.approval-for-all" }.message,
        )
        assertEquals(
            listOf("operator" to RECIPIENT, "approved" to "true"),
            granted.sections.single { it.heading == "Arguments" }.rows.map { it.label to it.value.text() },
        )

        val revoked = ready(requestCbor(revoke, chainId = ETHEREUM)).review
        assertEquals("Removes $RECIPIENT as operator of $UNREGISTERED.", revoked.summary.text())
        assertTrue(revoked.warnings.none { it.code == "evm.approval-for-all" })
    }

    /** The text of the row [label] in the Transaction section of [this] review. */
    private fun Review.transactionRow(label: String): String = sections.first().rows.single { it.label == label }.value.text()

    /** The text of the fact [label] under the summary of [this] review. */
    private fun Review.fact(label: String): String = facts.single { it.label == label }.value.text()

    @Test
    fun theReviewNamesTheAccountThatSignsAndTheTransactionThatIsSigned() {
        val ready = ready(requestCbor(eip1559Tx()))
        val tx = assertNotNull(ready.tx)
        // The signed bytes are the transaction the review reads, so the owner reads exactly what gets signed.
        assertTrue(tx.encode()?.contentEquals(ready.request.signData) == true)
        val header = assertNotNull(ready.review.sections.firstOrNull()).rows
        val from = assertIs<DisplayValue.Address>(header.single { it.label == "From" }.value)
        assertEquals(VIEW.addressAt(ready.accountIndex), from.address)
        assertEquals(ready.signer.address, from.address)
        val to = assertIs<DisplayValue.Address>(header.single { it.label == "To" }.value)
        assertTrue(to.address.equals(tx.to, ignoreCase = true))
    }

    private fun parsedWith(cbor: ByteArray, change: (ParsedRequest) -> ParsedRequest): ParsedRequest {
        val parts = listOf(framesOf(UR(EthSignRequest.UR_TYPE, cbor), WHOLE_REQUEST).first())
        return change(assertNotNull(evmParsedOf(inProcessSandboxParser.scan(parts))))
    }

    /** The parse a compromised sandbox would send for [cbor], with the transaction it decoded changed by [change]. */
    private fun changedParse(cbor: ByteArray, change: (UnsignedTx) -> UnsignedTx): ParsedRequest =
        parsedWith(cbor) { parsed -> parsed.copy(tx = UnsignedTx.Result.Ok(change(assertIs<UnsignedTx.Result.Ok>(parsed.tx).tx))) }

    /** The parse a compromised sandbox would send for [cbor], with the call it decoded changed by [change]. */
    private fun changedCall(cbor: ByteArray, change: (ContractCall) -> ContractCall): ParsedRequest =
        parsedWith(cbor) { parsed -> parsed.copy(call = change(assertNotNull(parsed.call))) }

    /** A typed EIP-1559 transaction like [eip1559Tx], with the access list [entries]. */
    private fun eip1559TxWithAccessList(entries: List<AccessListObject>): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            MONAD_TESTNET,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            RECIPIENT,
            ONE_CENT_OF_MON,
            "0x",
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
            entries,
        ),
    )

    /** An EIP-2930 transaction with the access list [entries]. */
    private fun eip2930TxWithAccessList(entries: List<AccessListObject>): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            MONAD_TESTNET,
            BigInteger.valueOf(7),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(21_000),
            RECIPIENT,
            ONE_CENT_OF_MON,
            "0x",
            entries,
        ),
    )

    /** The request for a transaction to [to] that carries [data], prepared through [registry] and [policy] as the sign flow does. */
    private fun batch(
        data: String,
        to: String = ROUTER,
        value: BigInteger = BigInteger.ZERO,
        registry: Registry = BATCH,
        policy: ProxyPolicy = ProxyPolicy.DEFAULT,
    ): Prepared = SignPipeline(registry, inProcessSandboxParser, policy).prepare(
        requestCbor(eip1559Tx(chainId = ETHEREUM, to = to, value = value, data = data), chainId = ETHEREUM),
        VIEW,
    )

    /** The parse a compromised sandbox would send for [cbor], with the multicall it read changed by [change]. */
    private fun batchParse(cbor: ByteArray, change: (Multicall) -> Multicall): ParsedRequest =
        parsedWith(cbor) { it.copy(multicall = change(assertNotNull(it.multicall))) }

    /**
     * A request whose transaction carries [data] and whose sandbox read [calls] from it. It skips the transaction's size cap, which
     * an honest sandbox cannot get past, so a batch larger than the cap can still be checked against its limits.
     */
    private fun fakeBatch(data: String, calls: List<InnerCall>): Prepared {
        val tx = UnsignedTx(
            type = TxType.EIP1559,
            chainId = BigInteger.valueOf(ETHEREUM),
            nonce = BigInteger.valueOf(7),
            to = ROUTER_CHECKSUM,
            value = BigInteger.ZERO,
            data = data,
            gasLimit = BigInteger.valueOf(21_000),
            gasPrice = null,
            maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
            maxFeePerGas = BigInteger.valueOf(30_000_000_000L),
        )
        val request = EthSignRequest(
            requestId = REQUEST_ID,
            signData = assertNotNull(tx.encode()),
            dataType = DataType.TYPED_TRANSACTION,
            chainId = ETHEREUM,
            derivationPath = accountPath(0),
            sourceFingerprint = WALLET.masterFingerprint,
            address = null,
            origin = ORIGIN,
        )
        return pipeline.prepare(ParsedRequest(request, UnsignedTx.Result.Ok(tx), null, Multicall(null, calls)), VIEW)
    }

    /** The request of a scanned [cbor] as a sandbox answers it. A sandbox that refuses the scan refuses the request. */
    private fun throughSandbox(cbor: ByteArray, sandbox: SandboxTransport): Prepared {
        val parts = listOf(framesOf(UR(EthSignRequest.UR_TYPE, cbor), WHOLE_REQUEST).first())
        return when (val scan = SandboxedParser(sandbox).scan(parts)) {
            is ScanReply.App -> pipeline.prepare(assertNotNull(evmParsedOf(scan)), VIEW)
            is ScanReply.Refused -> Prepared.Refused(scan.reason)
            else -> error("A single frame is a whole request or a refusal")
        }
    }

    /** The calldata of `multicall(bytes[])` over [calls], each call with 0x. */
    private fun batchData(calls: List<String>): String =
        "0x" + SELECTOR_MULTICALL + uintWord(BigInteger.valueOf(32)) + arrayHex(calls.map { bytesHex(it) })

    /** The calldata of `aggregate3((address,bool,bytes)[])` over (target, allowFailure, calldata) calls. */
    private fun aggregate3Data(calls: List<Triple<String, Boolean, String>>): String {
        val tuples = calls.map { (target, allowFailure, data) ->
            addressWord(target) + flagWord(allowFailure) + uintWord(BigInteger.valueOf(96)) + bytesHex(data)
        }
        return "0x" + WhitelistEntry.selectorOf("aggregate3((address,bool,bytes)[])").removePrefix("0x") +
            uintWord(BigInteger.valueOf(32)) + arrayHex(tuples)
    }

    /** The calldata of `aggregate3Value((address,bool,uint256,bytes)[])` over one call, with its target, flag, value and calldata. */
    private fun aggregate3ValueData(target: String, allowFailure: Boolean, value: BigInteger, data: String): String {
        val tuple = addressWord(target) + flagWord(allowFailure) + uintWord(value) + uintWord(BigInteger.valueOf(128)) + bytesHex(data)
        return "0x" + WhitelistEntry.selectorOf("aggregate3Value((address,bool,uint256,bytes)[])").removePrefix("0x") +
            uintWord(BigInteger.valueOf(32)) + arrayHex(listOf(tuple))
    }

    private fun flagWord(flag: Boolean): String = uintWord(if (flag) BigInteger.ONE else BigInteger.ZERO)

    /** A dynamic byte string, without 0x: its length, then its bytes padded to a whole word. [hex] has 0x. */
    private fun bytesHex(hex: String): String {
        val digits = hex.removePrefix("0x")
        return uintWord(BigInteger.valueOf((digits.length / 2).toLong())) + digits.padEnd((digits.length / 2 + 31) / 32 * 64, '0')
    }

    /** A dynamic array, without 0x: its length, the offset of each element, then the elements, each one already hex. */
    private fun arrayHex(elements: List<String>): String {
        var offset = 32 * elements.size
        val offsets = elements.joinToString("") { element ->
            uintWord(BigInteger.valueOf(offset.toLong())).also { offset += element.length / 2 }
        }
        return uintWord(BigInteger.valueOf(elements.size.toLong())) + offsets + elements.joinToString("")
    }

    private fun prepared(cbor: ByteArray): Prepared = pipeline.prepare(cbor, VIEW)

    /** The request of [cbor] prepared under [policy], which is how the owner's Unknown contract calls setting reaches the pipeline. */
    private fun underPolicy(policy: UnknownCallPolicy, cbor: ByteArray): Prepared =
        SignPipeline(REGISTRY, inProcessSandboxParser, unknownCalls = policy).prepare(cbor, VIEW)

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
        listOf(44L to true, 60L to true, index to true, 0L to false, 0L to false)

    private fun accountPath(index: Int): List<PathStep> =
        listOf(PathStep(44, true), PathStep(60, true), PathStep(index, true), PathStep(0, false), PathStep(0, false))

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
