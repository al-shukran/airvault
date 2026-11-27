package app.lensly.app

import app.lensly.core.vault.VaultLayout
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A plain video that an interrupted recording left in the cache must not outlive the next start of the app. */
@RunWith(RobolectricTestRunner::class)
class LenslyAppStartupTest {

    @Test
    fun startingTheAppShredsALeftoverPlainVideo() {
        val app = RuntimeEnvironment.getApplication() as LenslyApp
        val leftover = VaultLayout.of(app).recording!!
        leftover.parentFile!!.mkdirs()
        leftover.writeBytes(ByteArray(64) { 7 })
        assertTrue(leftover.exists())

        app.onCreate()

        assertFalse(leftover.exists())
    }
}
