package app.lensly.app

import app.lensly.core.vault.VaultLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
        // Robolectric already started the app, so its startup shred must be over before the leftover is written.
        runBlocking { app.startupShred!!.join() }
        val leftover = VaultLayout.of(app).recording!!
        leftover.parentFile!!.mkdirs()
        leftover.writeBytes(ByteArray(64) { 7 })
        assertTrue(leftover.exists())

        app.onCreate()
        runBlocking { app.startupShred!!.join() }

        assertFalse(leftover.exists())
    }

    @Test
    fun startingTheAppDoesNotWaitForAShredOfALargeLeftoverVideo() {
        val app = RuntimeEnvironment.getApplication() as LenslyApp
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        // A shred that is still overwriting when onCreate returns stands for a leftover of hundreds of megabytes.
        app.startupShredAction = { running.countDown(); release.await(10, TimeUnit.SECONDS) }

        app.onCreate()

        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertTrue(app.startupShred!!.isActive)
        release.countDown()
        runBlocking { app.startupShred!!.join() }
    }

    @Test
    fun aFailedShredDoesNotCrashTheApp() {
        val job = shredRecordingInBackground { throw IllegalStateException("no disk") }
        runBlocking { job.join() }
        assertTrue(job.isCompleted)

        val outOfMemory = shredRecordingInBackground { throw OutOfMemoryError() }
        runBlocking { outOfMemory.join() }
        assertTrue(outOfMemory.isCompleted)
    }
}
