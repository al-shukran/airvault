package app.lensly.core.evm

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.TypedData
import app.airvault.chains.evm.signer.TypedDataParser
import app.lensly.core.wallet.AccountAddresses
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

/** A key that has never held funds. It exists only in tests. */
private val KEY = Numeric.hexStringToByteArray(TypedDataVectors.value("signingKey"))

/** The test key of hyperliquid-python-sdk, tests/signing_test.py. It has never held funds. */
private val SDK_KEY = Numeric.hexStringToByteArray("0x0123456789012345678901234567890123456789012345678901234567890123")

private const val TYPED_MESSAGE = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"chainId","type":"uint256"}],""" +
    """"Note":[{"name":"text","type":"string"}]},"primaryType":"Note","domain":{"name":"Lensly","chainId":1},""" +
    """"message":{"text":"hello"}}"""

private const val ZERO = "0x0000000000000000000000000000000000000000"
private const val SDK_DESTINATION = "0x5e9ee1089755c3435139848e47e6635505d5a13a"
private const val DUMMY_CONNECTION_ID = "0xf528daee6a0bd11407b483cfcd9a48c56884180b70ee86f124053e5fc1bf4d57"
private const val EIP712_DOMAIN_TYPE = """"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
    """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}]"""

/** The phantom agent of the SDK's dummy L1 action on [source]: "a" on mainnet, "b" on testnet. Its domain is Exchange on chain 1337. */
private fun l1Dummy(source: String): String =
    """{"types":{"Agent":[{"name":"source","type":"string"},{"name":"connectionId","type":"bytes32"}],$EIP712_DOMAIN_TYPE},""" +
        """"primaryType":"Agent","domain":{"name":"Exchange","version":"1","chainId":1337,"verifyingContract":"$ZERO"},""" +
        """"message":{"source":"$source","connectionId":"$DUMMY_CONNECTION_ID"}}"""

/** The SDK's user-signed [type] on testnet, as exchange.py posts it: its domain is HyperliquidSignTransaction on chain 421614. */
private fun userSigned(primaryType: String, type: String): String =
    """{"types":{"$primaryType":[{"name":"hyperliquidChain","type":"string"},{"name":"destination","type":"string"},""" +
        """{"name":"amount","type":"string"},{"name":"time","type":"uint64"}],$EIP712_DOMAIN_TYPE},""" +
        """"primaryType":"$primaryType","domain":{"name":"HyperliquidSignTransaction","version":"1","chainId":421614,""" +
        """"verifyingContract":"$ZERO"},""" +
        """"message":{"type":"$type","destination":"$SDK_DESTINATION","amount":"1","time":1687816341423,""" +
        """"signatureChainId":"0x66eee","hyperliquidChain":"Testnet"}}"""

/** The AsterDex addresses and messages of github.com/asterdex/api-docs, V3 futures: "Register and Approve Agent" and "Supported Signing Algorithms". */
private const val ASTER_SENDER = "0x14791697260E4c9A71f18484C9f997B308e59325"
private const val ASTER_APPROVE_MESSAGE = "user=$ASTER_SENDER&nonce=1748310859508867&agentName=example&agentAddress=$SDK_DESTINATION" +
    "&expired=1800000000000&signatureChainId=56&canSpotTrade=true&canPerpTrade=true&canWithdraw=false&ipWhitelist="
private const val ASTER_MASTER_MESSAGE = "childAddress=$SDK_DESTINATION&name=example&nonce=1748310859508867&user=$ASTER_SENDER" +
    "&childSignature=0xa7a7467a09f42c44e5b05a6514d3a061edf058902fee3eee71b96309c98abd4157e489047f4f7ee69b582554576c551387d1229604345811d2721a1f116a8ac01c"

/** An AsterDex message of [msg] on the AsterSignTransaction domain of [chainId]: one string member, msg. */
private fun asterMessage(chainId: Int, msg: String): String =
    """{"types":{$EIP712_DOMAIN_TYPE,"Message":[{"name":"msg","type":"string"}]},"primaryType":"Message",""" +
        """"domain":{"name":"AsterSignTransaction","version":"1","chainId":$chainId,"verifyingContract":"$ZERO"},""" +
        """"message":{"msg":"$msg"}}"""

/** The signature of [json] by [key], as r || s || v in hex. */
private fun signedHex(json: String, key: ByteArray = SDK_KEY): String {
    val typed = (TypedDataParser.parse(json.toByteArray(Charsets.UTF_8)) as TypedDataParser.Result.Ok).data
    return Numeric.toHexString(assertIs<TypedDataSigner.Result.Signed>(TypedDataSigner.sign(DataType.TYPED_DATA, typed, key)).signature)
}

class TypedDataSignerTest {

    private val data: TypedData =
        (TypedDataParser.parse(TYPED_MESSAGE.toByteArray(Charsets.UTF_8)) as TypedDataParser.Result.Ok).data

    @Test
    fun aSignatureOverTheDigestRecoversToTheKeysAddressWithVOf27Or28() {
        val signed = assertIs<TypedDataSigner.Result.Signed>(TypedDataSigner.sign(DataType.TYPED_DATA, data, KEY))
        val signature = signed.signature

        assertEquals(65, signature.size)
        assertTrue(signature[64] == 27.toByte() || signature[64] == 28.toByte())
        val recovered = EvmFixtures.recoveredAddress(
            data.digest(),
            signature.copyOfRange(0, 32),
            signature.copyOfRange(32, 64),
            signature[64].toInt() - 27,
        )
        assertEquals(EvmFixtures.normalize(AccountAddresses.addressOfPrivateKey(KEY)), recovered)
    }

    @Test
    fun theSdkL1DummyActionOnMainnetHasItsSdkSignature() {
        // hyperliquid-python-sdk, tests/signing_test.py, test_l1_action_signing_matches (mainnet: source "a", v 27).
        assertEquals(
            "0x053749d5b30552aeb2fca34b530185976545bb22d0b3ce6f62e31be961a59298755c40ba9bf05223521753995abb2f73ab3229be8ec921f350cb447e384d8ed81b",
            signedHex(l1Dummy("a")),
        )
    }

    @Test
    fun theSdkL1DummyActionOnTestnetHasItsSdkSignature() {
        // The same test: testnet, source "b", v 28.
        assertEquals(
            "0x542af61ef1f429707e3c76c5293c80d01f74ef853e34b76efffcb57e574f951017b8b32f086e8cdede991f1e2c529f5dd5297cbe8128500e00cbaf766204a6131c",
            signedHex(l1Dummy("b")),
        )
    }

    @Test
    fun theSdkUsdSendOnTestnetHasItsSdkSignature() {
        // hyperliquid-python-sdk, tests/signing_test.py, test_sign_usd_transfer_action (is_mainnet False, v 27).
        assertEquals(
            "0x637b37dd731507cdd24f46532ca8ba6eec616952c56218baeff04144e4a7707311a6a24900e6e314136d2592e2f8d502cd89b7c15b198e1bee043c9589f9fad71b",
            signedHex(userSigned("HyperliquidTransaction:UsdSend", "usdSend")),
        )
    }

    @Test
    fun theSdkWithdrawOnTestnetHasItsSdkSignature() {
        // hyperliquid-python-sdk, tests/signing_test.py, test_sign_withdraw_from_bridge_action (is_mainnet False, v 28).
        assertEquals(
            "0x8363524c799e90ce9bc41022f7c39b4e9bdba786e5f9c72b20e43e1462c37cf958b1411a775938b83e29182e8ef74975f9054c8e97ebf5ec2dc8d51bfc8938811c",
            signedHex(userSigned("HyperliquidTransaction:Withdraw", "withdraw3")),
        )
    }

    @Test
    fun aDomainOnAnUnknownChainIsSignedWithTheReferenceSignatureOfThatChain() {
        // Reference: `cast wallet sign --private-key <the SDK key> --data <this JSON>` (Foundry 1.7.1, alloy EIP-712 hashing).
        val json = """{"types":{"Note":[{"name":"text","type":"string"}],$EIP712_DOMAIN_TYPE},"primaryType":"Note",""" +
            """"domain":{"name":"Lensly Test","version":"1","chainId":999,"verifyingContract":"$ZERO"},"message":{"text":"hi"}}"""

        assertEquals(
            "0x355b7a827bd5c8199aada7ffba358aa945b85ec7fd3740de0633d90d19fa281f2d401b7561aeba34432d6c32d9add999bc95d24ea9e3dcc3a08296011bab38cf1b",
            signedHex(json),
        )
    }

    @Test
    fun aDomainThatNamesNoChainIsSignedWithTheReferenceSignature() {
        // The same reference tool, with a domain that has no chainId member.
        val json = """{"types":{"Note":[{"name":"text","type":"string"}],"EIP712Domain":[{"name":"name","type":"string"},""" +
            """{"name":"version","type":"string"}]},"primaryType":"Note","domain":{"name":"Lensly Test","version":"1"},""" +
            """"message":{"text":"hi"}}"""

        assertEquals(
            "0x9ac36c39f5cda304207a58365d39acff802db78260d8bf817d25701347b0122a504192c3ea57456e6bbb4d7c605f7229ec869262e0b7d077367146c6dd5a5a4d1c",
            signedHex(json),
        )
    }

    @Test
    fun theAsterDexApprovalOnChain56HasItsReferenceSignature() {
        // Reference: `cast wallet sign --data` of the same typed message (Foundry 1.7.1), with the SDK test key.
        assertEquals(
            "0x9c45a5812a6a880c232503c76ea141b9b6667300b180fb29812929562d7346143bfdad93eb7f2a70c39e00de157fa896f3dc97301c9f047f8661da8ef411175f1c",
            signedHex(asterMessage(56, ASTER_APPROVE_MESSAGE)),
        )
    }

    @Test
    fun theAsterDexMasterAccountSignatureOnChain1666HasItsReferenceSignature() {
        // The same reference, on chain 1666 with the master-account message.
        assertEquals(
            "0x9d157c0231140b3fe109f3e22c80cae987c95b2653b54cd0a7005d9939e211905f55fb3a2df4f05d4855a85833a04378a9e681e1dcfd2dbc0f6ea2c41838cc481c",
            signedHex(asterMessage(1666, ASTER_MASTER_MESSAGE)),
        )
    }

    @Test
    fun onlyTypedDataIsSignedAsTypedData() {
        val refused = assertIs<TypedDataSigner.Result.Refused>(
            TypedDataSigner.sign(DataType.PERSONAL_MESSAGE, data, KEY),
        )

        assertTrue(refused.reason.contains("dataType 3"))
    }
}
