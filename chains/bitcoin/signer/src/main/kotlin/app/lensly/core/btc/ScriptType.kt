package app.lensly.core.btc

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script

/**
 * The address kind a Bitcoin Account commits to. Each kind has one mainstream BIP43 purpose, and the account path starts
 * with that purpose: m/purpose'/coin'/account'.
 */
enum class ScriptType(
    /** The BIP43 purpose, the first hardened level of the account path. */
    val purpose: Int,
) {
    /** P2PKH, BIP44. */
    LEGACY(44),

    /** P2SH wrapping P2WPKH, BIP49. */
    NESTED_SEGWIT(49),

    /** P2WPKH (bech32), BIP84. The default for new Accounts. */
    NATIVE_SEGWIT(84),

    /** P2TR key path (bech32m), BIP86. */
    TAPROOT(86);

    /** The account path m/purpose'/coin'/[account]' on [network]. */
    fun accountPath(network: BtcNetwork, account: Int): KeyPath {
        require(account >= 0) { "account index must be 0 or more" }
        return KeyPath(
            listOf(
                DeterministicWallet.hardened(purpose.toLong()),
                DeterministicWallet.hardened(network.coinType.toLong()),
                DeterministicWallet.hardened(account.toLong()),
            ),
        )
    }
}

/**
 * The scriptPubKey of the address that [key] gives for [scriptType]. The Account and the signer both derive it this way, so a key the
 * signer gets from KeyAccess gives the same script the Account does.
 */
fun scriptPubKeyOf(scriptType: ScriptType, key: PublicKey): ByteArray {
    val script = when (scriptType) {
        ScriptType.LEGACY -> Script.pay2pkh(key)
        ScriptType.NESTED_SEGWIT -> Script.pay2sh(Script.pay2wpkh(key))
        ScriptType.NATIVE_SEGWIT -> Script.pay2wpkh(key)
        ScriptType.TAPROOT -> Script.pay2tr(key.xOnly(), Crypto.TaprootTweak.KeyPathTweak)
    }
    return Script.write(script)
}
