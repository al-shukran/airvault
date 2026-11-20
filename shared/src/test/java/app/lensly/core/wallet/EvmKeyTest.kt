package app.lensly.core.wallet

import app.lensly.core.ur.PathStep
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class EvmKeyTest {

    /** The steps of a path, each a number and whether it is hardened. */
    private fun path(vararg steps: Pair<Int, Boolean>): List<PathStep> = steps.map { (index, hardened) -> PathStep(index, hardened) }

    /** m/44'/60'/[account]'/0/0, the Ledger Live key of account [account]. */
    private fun ledgerLive(account: Int): List<PathStep> = path(44 to true, 60 to true, account to true, 0 to false, 0 to false)

    /** m/44'/60'/0'/0/[index], the BIP44 address key that Rabby derives. */
    private fun bip44(index: Int): List<PathStep> = path(44 to true, 60 to true, 0 to true, 0 to false, index to false)

    @Test
    fun aLedgerLiveAccountSignsWithItsOwnKey() {
        val key = EvmKey.of(ledgerLive(2), accountCount = 3)
        assertEquals(EvmKey(2, 0), key)
        assertEquals(2, key?.accountIndex)
    }

    @Test
    fun aBip44AddressKeyBelongsToNoAccount() {
        val key = EvmKey.of(bip44(2), accountCount = 3)
        assertEquals(EvmKey(0, 2), key)
        assertNull(key?.accountIndex)
    }

    @Test
    fun accountZeroIsTheSameKeyOnBothPaths() {
        assertEquals(EvmKey(0, 0), EvmKey.of(ledgerLive(0), accountCount = 1))
        assertEquals(EvmKey(0, 0), EvmKey.of(bip44(0), accountCount = 1))
        assertEquals(0, EvmKey(0, 0).accountIndex)
    }

    @Test
    fun aNumberPastTheLastAccountIsNoKey() {
        assertNull(EvmKey.of(ledgerLive(3), accountCount = 3))
        assertNull(EvmKey.of(bip44(3), accountCount = 3))
    }

    @Test
    fun anyOtherPathIsNoKey() {
        // The account number as the last step under a non-zero account, a change step other than 0, a hardened last step,
        // another coin type, a non-hardened purpose, and a path that is too short.
        assertNull(EvmKey.of(path(44 to true, 60 to true, 1 to true, 0 to false, 1 to false), accountCount = 3))
        assertNull(EvmKey.of(path(44 to true, 60 to true, 0 to true, 1 to false, 0 to false), accountCount = 3))
        assertNull(EvmKey.of(path(44 to true, 60 to true, 0 to true, 0 to false, 0 to true), accountCount = 3))
        assertNull(EvmKey.of(path(44 to true, 283 to true, 0 to true, 0 to false, 0 to false), accountCount = 3))
        assertNull(EvmKey.of(path(44 to false, 60 to true, 0 to true, 0 to false, 0 to false), accountCount = 3))
        assertNull(EvmKey.of(path(44 to true, 60 to true, 0 to true, 0 to false), accountCount = 3))
        // An account level that is not hardened, and an account number under an address key's account level, are no key either.
        assertNull(EvmKey.of(path(44 to true, 60 to true, 0 to false, 0 to false, 0 to false), accountCount = 3))
        assertNull(EvmKey.of(path(44 to true, 60 to true, 1 to true, 0 to false, 2 to false), accountCount = 3))
    }

    @Test
    fun theReviewShowsTheDerivationPathOfTheKey() {
        assertEquals("m/44'/60'/0'/0/3", EvmKey(0, 3).path)
        assertEquals("m/44'/60'/4'/0/0", EvmKey(4, 0).path)
    }
}
