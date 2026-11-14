package app.lensly.core.evm

import java.math.BigInteger
import org.junit.Test
import kotlin.test.assertEquals
import org.web3j.utils.Numeric

/** The Mail example of the EIP-712 standard, exactly as eth_signTypedData receives it. */
private const val MAIL_JSON = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
    """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
    """"Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],""" +
    """"Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},""" +
    """"primaryType":"Mail","domain":{"name":"Ether Mail","version":"1","chainId":1,""" +
    """"verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},""" +
    """"message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},""" +
    """"to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},"contents":"Hello, Bob!"}}"""

/** The signature the standard gives for the Mail example, made by the account 0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826. */
private val MAIL_SIGNATURE = TypedDataVectors.value("mailSignature")
private const val MAIL_SIGNER = "cd2a3d9f938e13cd947ec05abc7fe734df8dd826"

/** The key of the eth-sig-util V4 signing vectors. */
private val SIGNING_KEY = TypedDataVectors.value("signingKey")

/** The token of the Permit2 example: USDC on Ethereum. */
private const val USDC_ADDRESS = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

private fun bytes(hex: String) = TypedValue.BytesValue(Numeric.hexStringToByteArray(hex))

private fun text(value: String) = TypedValue.TextValue(value)

private fun struct(vararg fields: Pair<String, TypedValue>) = TypedValue.StructValue(linkedMapOf(*fields))

private fun array(vararg items: TypedValue) = TypedValue.ArrayValue(items.toList())

private fun hex(bytes: ByteArray) = Numeric.toHexStringNoPrefix(bytes)

/**
 * The types and message of eth-sig-util's V4 array vector: Mail with a list of addresses for its sender, a list of recipients, and
 * Person structs inside both lists.
 */
private val ARRAY_TYPES: Map<String, List<TypedField>> = linkedMapOf(
    EIP712_DOMAIN to emptyList(),
    "Person" to listOf(TypedField("name", "string"), TypedField("wallet", "address[]")),
    "Mail" to listOf(TypedField("from", "Person"), TypedField("to", "Person[]"), TypedField("contents", "string")),
)

private fun arrayMail() = struct(
    "from" to struct(
        "name" to text("Cow"),
        "wallet" to array(
            bytes("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
            bytes("0xDD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
        ),
    ),
    "to" to array(
        struct("name" to text("Bob"), "wallet" to array(bytes("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"))),
    ),
    "contents" to text("Hello, Bob!"),
)

class TypedDataHashTest {

    private fun parsedMail(): TypedData =
        (TypedDataParser.parse(MAIL_JSON.toByteArray(Charsets.UTF_8)) as TypedDataParser.Result.Ok).data

    @Test
    fun theStandardsMailSignatureRecoversToItsSignerOverTheDigestWeCompute() {
        val signature = Numeric.hexStringToByteArray(MAIL_SIGNATURE)
        assertEquals(65, signature.size)

        val recovered = EvmFixtures.recoveredAddress(
            parsedMail().digest(),
            signature.copyOfRange(0, 32),
            signature.copyOfRange(32, 64),
            signature[64].toInt() - 27,
        )

        assertEquals(MAIL_SIGNER, recovered)
    }

    @Test
    fun theHashOfAMailWithArraysAndNestedStructsMatchesEthSigUtilV4() {
        val data = TypedData(ARRAY_TYPES, "Mail", struct(), arrayMail())

        assertEquals(
            "ac26cc7aa2cb9a8a445fae4e48b33f978b558da9b16e26381c53814d3317f541",
            hex(data.hashStruct("Mail", arrayMail())),
        )
    }

    @Test
    fun theSignatureOfTheArrayMailMatchesEthSigUtilV4ByteForByte() {
        // eth-sig-util signTypedData, V4, with an empty domain: the signature is over the digest, with v of 27 or 28.
        val data = TypedData(ARRAY_TYPES, "Mail", struct(), arrayMail())
        val signature = Secp256k1.sign(data.digest(), Numeric.hexStringToByteArray(SIGNING_KEY))
        val bytes = signature.r + signature.s + byteArrayOf((27 + signature.recId).toByte())

        assertEquals(TypedDataVectors.value("arrayMailSignature"), Numeric.toHexString(bytes))
    }

    @Test
    fun encodeTypeListsEveryReferencedStructSortedByNameAfterThePrimaryType() {
        // The standard's example: Transaction uses Person and Asset, which follow it sorted by name.
        val types = linkedMapOf(
            EIP712_DOMAIN to emptyList(),
            "Transaction" to listOf(TypedField("from", "Person"), TypedField("to", "Person"), TypedField("tx", "Asset")),
            "Asset" to listOf(TypedField("token", "address"), TypedField("amount", "uint256")),
            "Person" to listOf(TypedField("wallet", "address"), TypedField("name", "string")),
        )
        val person = struct("wallet" to bytes("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"), "name" to text("Cow"))
        val message = struct(
            "from" to person,
            "to" to person,
            "tx" to struct("token" to bytes("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"), "amount" to TypedValue.IntValue(BigInteger.TEN)),
        )
        val data = TypedData(types, "Transaction", struct(), message)

        assertEquals(
            "Transaction(Person from,Person to,Asset tx)Asset(address token,uint256 amount)Person(address wallet,string name)",
            data.encodeType("Transaction"),
        )
    }

    @Test
    fun encodeTypeOfThePermit2SingleAndTheErc2612PermitMatchTheirStandards() {
        val permit2 = """{"types":{"PermitSingle":[{"name":"details","type":"PermitDetails"},{"name":"spender","type":"address"},""" +
            """{"name":"sigDeadline","type":"uint256"}],"PermitDetails":[{"name":"token","type":"address"},""" +
            """{"name":"amount","type":"uint160"},{"name":"expiration","type":"uint48"},{"name":"nonce","type":"uint48"}]},""" +
            """"primaryType":"PermitSingle","domain":{"name":"Permit2","chainId":1,""" +
            """"verifyingContract":"0x000000000022d473030f116ddee9f6b43ac78ba3"},""" +
            """"message":{"details":{"token":"$USDC_ADDRESS","amount":"1000","expiration":"1700000000",""" +
            """"nonce":"0"},"spender":"0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad","sigDeadline":"1700000000"}}"""
        val data = (TypedDataParser.parse(permit2.toByteArray(Charsets.UTF_8)) as TypedDataParser.Result.Ok).data

        assertEquals(
            "PermitSingle(PermitDetails details,address spender,uint256 sigDeadline)" +
                "PermitDetails(address token,uint160 amount,uint48 expiration,uint48 nonce)",
            data.encodeType("PermitSingle"),
        )
        val erc2612 = TypedData(
            types = linkedMapOf(
                EIP712_DOMAIN to listOf(TypedField("chainId", "uint256")),
                "Permit" to listOf(
                    TypedField("owner", "address"),
                    TypedField("spender", "address"),
                    TypedField("value", "uint256"),
                    TypedField("nonce", "uint256"),
                    TypedField("deadline", "uint256"),
                ),
            ),
            primaryType = "Permit",
            domain = struct("chainId" to TypedValue.IntValue(BigInteger.ONE)),
            message = struct(
                "owner" to bytes("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
                "spender" to bytes("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"),
                "value" to TypedValue.IntValue(BigInteger.TEN),
                "nonce" to TypedValue.IntValue(BigInteger.ZERO),
                "deadline" to TypedValue.IntValue(BigInteger.valueOf(1_700_000_000L)),
            ),
        )
        assertEquals(
            "Permit(address owner,address spender,uint256 value,uint256 nonce,uint256 deadline)",
            erc2612.encodeType("Permit"),
        )
    }
}
