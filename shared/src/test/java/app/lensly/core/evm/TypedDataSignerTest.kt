package app.lensly.core.evm

import app.lensly.core.ur.DataType
import app.lensly.core.wallet.AccountAddresses
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.web3j.utils.Numeric

/** A key that has never held funds. It exists only in tests. */
private val KEY = Numeric.hexStringToByteArray(TypedDataVectors.value("signingKey"))

private const val TYPED_MESSAGE = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"chainId","type":"uint256"}],""" +
    """"Note":[{"name":"text","type":"string"}]},"primaryType":"Note","domain":{"name":"Lensly","chainId":1},""" +
    """"message":{"text":"hello"}}"""

class TypedDataSignerTest {

    private val data: TypedData =
        (TypedDataParser.parse(TYPED_MESSAGE.toByteArray(Charsets.UTF_8)) as TypedDataParser.Result.Ok).data

    @Test
    fun aSignatureOverTheDigestRecoversToTheKeysAddressWithVOf27Or28() {
        val signed = assertIs<TypedDataSigner.Result.Signed>(TypedDataSigner.sign(DataType.TYPED_DATA, data, 1, KEY))
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
    fun aDomainOnAnotherChainThanTheRequestIsNeverSigned() {
        val refused = assertIs<TypedDataSigner.Result.Refused>(TypedDataSigner.sign(DataType.TYPED_DATA, data, 10, KEY))

        assertEquals("The typed data is for another chain than the request names", refused.reason)
    }

    @Test
    fun onlyTypedDataIsSignedAsTypedData() {
        val refused = assertIs<TypedDataSigner.Result.Refused>(
            TypedDataSigner.sign(DataType.PERSONAL_MESSAGE, data, 1, KEY),
        )

        assertTrue(refused.reason.contains("dataType 3"))
    }
}
