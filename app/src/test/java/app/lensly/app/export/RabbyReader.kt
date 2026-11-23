package app.lensly.app.export

import app.lensly.core.parse.DecodeBudget
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.web3j.crypto.Keys

/** The CBOR tag of a crypto-hdkey, and of its crypto-keypath origin. */
private const val CRYPTO_HDKEY_TAG = 303L
private const val CRYPTO_KEYPATH_TAG = 304L

/** One account as the importer lists it: its EIP-55 address, the path it asks Lensly to sign with, and the key's name. */
data class ImportedAccount(val address: String, val path: String, val name: String?)

/** A pairing code as the importer reads it: the master fingerprint and the accounts in the order it pages through them. */
data class ImportedWallet(val masterFingerprint: Int, val accounts: List<ImportedAccount>)

/**
 * Reads a pairing code the way Rabby's and MetaMask's QR keyring reads a ur:crypto-account (its `__readCryptoAccount`): key 1 is the
 * master fingerprint, key 2 the outputs, whose count must be a multiple of 5. Each output is a crypto-hdkey with no script expression,
 * and is one account: its address is made from the key itself (key 3), its signing path is the origin (key 6), and its note (key 10)
 * marks the m/44'/60'/N'/0/0 layout.
 */
fun importedWallet(parts: List<String>): ImportedWallet {
    val scanner = UrScanner()
    for (part in parts) {
        scanner.receive(part)
        if (scanner.result() != null) break
    }
    val ur = assertNotNull(scanner.result(), "The pairing parts do not decode.")
    assertEquals("crypto-account", ur.type, "The importer reads the accounts only from a crypto-account.")
    val account = fields(Cbor.decode(ur.cborBytes, DecodeBudget("crypto-account", Int.MAX_VALUE)))
    val outputs = (account.getValue(2) as Cbor.Arr).items
    assertTrue(outputs.isNotEmpty() && outputs.size % 5 == 0, "The importer refuses ${outputs.size} outputs.")
    val accounts = outputs.map { output ->
        assertEquals(CRYPTO_HDKEY_TAG, (output as Cbor.Tagged).tag, "Each output is a crypto-hdkey.")
        val key = fields(output.value)
        assertEquals(PairingExport.LEDGER_LIVE_NOTE, (key[10] as? Cbor.Text)?.value)
        val origin = key.getValue(6) as Cbor.Tagged
        assertEquals(CRYPTO_KEYPATH_TAG, origin.tag)
        val path = (fields(origin.value).getValue(1) as Cbor.Arr).items.chunked(2).joinToString("/", prefix = "m/") { (index, hardened) ->
            "${(index as Cbor.Uint).value}" + if ((hardened as Cbor.Bool).value) "'" else ""
        }
        val point = CustomNamedCurves.getByName("secp256k1").curve.decodePoint((key.getValue(3) as Cbor.Bytes).value).getEncoded(false)
        val address = Keys.toChecksumAddress(Keys.getAddress(BigInteger(1, point.copyOfRange(1, point.size))))
        ImportedAccount(address, path, (key[9] as? Cbor.Text)?.value)
    }
    // The importer reads the master fingerprint as a uint32 and signs its requests with it.
    return ImportedWallet((account.getValue(1) as Cbor.Uint).value.toInt(), accounts)
}

/** The accounts of [parts], as [importedWallet] reads them. */
fun importedAccounts(parts: List<String>): List<ImportedAccount> = importedWallet(parts).accounts

private fun fields(map: Cbor): Map<Long, Cbor> = (map as Cbor.Dict).entries.associate { (key, value) -> (key as Cbor.Uint).value to value }
