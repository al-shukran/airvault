package app.lumio.ui.wallets

import app.airvault.core.model.AccountId
import app.lumio.network.NetworkMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/**
 * The sheets over the Wallets home: what opens and closes, which sheet the system Back closes, and how a drag settles. Under reduced motion the stack
 * runs on an unconfined scope, so each step is done when its call returns.
 */
class WalletSheetsTest {
    private val wallet = WalletSheet.Wallet("wallet-demo")
    private val account = WalletSheet.Account("wallet-demo", AccountId("eip155:1:0xab"))

    private fun sheets() = WalletSheets(reducedMotion = { true }, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun openStacksTheAccountOverTheWalletAndOpensItOpen() {
        val stack = sheets()
        stack.open(wallet)
        stack.open(account)
        assertEquals(listOf<WalletSheet>(wallet, account), stack.sheets.map { it.sheet })
        assertEquals(1f, stack.sheets.last().progress.value)
    }

    @Test
    fun closeTopPopsTheTopSheetOnly() {
        val stack = sheets()
        stack.open(wallet)
        stack.open(account)
        assertTrue(stack.closeTop())
        assertEquals(listOf<WalletSheet>(wallet), stack.sheets.map { it.sheet })
        assertTrue(stack.closeTop())
        assertTrue(stack.sheets.isEmpty())
        assertFalse(stack.closeTop())
    }

    @Test
    fun closeSheetDropsThatSheetWhereverItIs() {
        val stack = sheets()
        stack.open(wallet)
        stack.open(account)
        stack.closeSheet(stack.sheets.first())
        assertEquals(listOf<WalletSheet>(account), stack.sheets.map { it.sheet })
    }

    @Test
    fun closableNeedsASheetOpen() {
        val stack = sheets()
        assertFalse(stack.closable)
        stack.open(wallet)
        assertTrue(stack.closable)
        stack.closeTop()
        assertFalse(stack.closable)
    }

    @Test
    fun reducedMotionCutsTheRiseAndTheExit() {
        val stack = sheets()
        stack.open(wallet)
        assertEquals(1f, stack.sheets.single().progress.value)
        stack.closeTop()
        assertTrue(stack.sheets.isEmpty())
    }

    @Test
    fun dragMovesTheSheetWithTheFingerWithinItsPlaces() {
        val stack = sheets()
        stack.open(wallet)
        val sheet = stack.sheets.single()
        sheet.heightPx = 1000f
        assertEquals(200f, stack.dragBy(sheet, 200f), 0.01f)
        assertEquals(0.8f, sheet.progress.value, 0.0001f)
        assertEquals(-100f, stack.dragBy(sheet, -100f), 0.01f)
        assertEquals(0.9f, sheet.progress.value, 0.0001f)
        assertEquals(-100f, stack.dragBy(sheet, -500f), 0.01f)
        assertEquals(1f, sheet.progress.value, 0.0001f)
        assertEquals(0f, stack.dragBy(sheet, -50f), 0.01f)
    }

    @Test
    fun releaseSettlesOpenFromHalfWayUpWithoutAFling() {
        val stack = sheets()
        stack.open(wallet)
        val sheet = stack.sheets.single()
        sheet.heightPx = 1000f
        stack.dragBy(sheet, 400f)
        stack.release(sheet, velocityDownPx = 0f)
        assertEquals(1f, sheet.progress.value, 0.0001f)
        assertEquals(1, stack.sheets.size)
    }

    @Test
    fun releaseClosesPastHalfWayWithoutAFling() {
        val stack = sheets()
        stack.open(wallet)
        val sheet = stack.sheets.single()
        sheet.heightPx = 1000f
        stack.dragBy(sheet, 600f)
        stack.release(sheet, velocityDownPx = 0f)
        assertTrue(stack.sheets.isEmpty())
    }

    @Test
    fun aFlingDecidesTheReleaseWhateverThePlace() {
        val down = sheets()
        down.open(wallet)
        down.sheets.single().heightPx = 1000f
        down.dragBy(down.sheets.single(), 100f)
        down.release(down.sheets.single(), velocityDownPx = 1000f)
        assertTrue(down.sheets.isEmpty())

        val up = sheets()
        up.open(wallet)
        val rising = up.sheets.single()
        rising.heightPx = 1000f
        up.dragBy(rising, 800f)
        up.release(rising, velocityDownPx = -1000f)
        assertEquals(1f, rising.progress.value, 0.0001f)
        assertEquals(1, up.sheets.size)
    }

    @Test
    fun sheetSettlesOpenByFlingOtherwiseByHalfWay() {
        assertTrue(sheetSettlesOpen(progress = 0.1f, velocity = 1f))
        assertFalse(sheetSettlesOpen(progress = 0.9f, velocity = -1f))
        assertTrue(sheetSettlesOpen(progress = 0.5f, velocity = 0f))
        assertFalse(sheetSettlesOpen(progress = 0.49f, velocity = 0f))
    }

    @Test
    fun keepSideDropsTheSheetsOnlyWhenTheSideChanges() {
        val stack = sheets()
        stack.keepSide(NetworkMode.TESTNET)
        stack.open(wallet)
        stack.keepSide(NetworkMode.TESTNET)
        assertEquals(1, stack.sheets.size)
        stack.keepSide(NetworkMode.MAINNET)
        assertTrue(stack.sheets.isEmpty())
    }

    @Test
    fun clearDropsEverySheetAtOnce() {
        val stack = sheets()
        stack.open(wallet)
        stack.open(account)
        stack.clear()
        assertTrue(stack.sheets.isEmpty())
    }
}
