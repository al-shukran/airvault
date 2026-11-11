package app.lensly.ui.navigation

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultHeader
import app.lensly.core.vault.VaultLayout
import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Leaving the hidden area pops its graph while the exit fade still shows the leaving screen. The draft screens resolve
 * the graph's back stack entry on every recomposition, so the fade must not ask for an entry that the pop removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class HiddenExitTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun leavingTheHiddenAreaKeepsTheDraftScreenAliveThroughItsFade() {
        val context = RuntimeEnvironment.getApplication()
        writeVaultOnDisk(context)
        val holder = VaultHolder(Vault(context))

        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            val controller = remember { nav }
            NavHost(
                navController = controller,
                startDestination = LenslyRoute.CAMERA,
                enterTransition = LenslyNavTransitions.enter,
                exitTransition = LenslyNavTransitions.exit,
                popEnterTransition = LenslyNavTransitions.popEnter,
                popExitTransition = LenslyNavTransitions.popExit,
            ) {
                composable(LenslyRoute.CAMERA) {}
                hiddenGraph(controller, holder)
            }
        }

        compose.runOnIdle { nav.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } }
        compose.waitForIdle()
        // The gate needs a real Keystore to unlock, so the test goes straight to a screen that resolves the draft.
        compose.runOnIdle { nav.navigate(HiddenRoute.WALLET_START) }
        compose.waitForIdle()
        assertEquals(HiddenRoute.WALLET_START, nav.currentBackStackEntry?.destination?.route)

        compose.runOnIdle { leaveHidden(nav, holder) }
        compose.waitForIdle()

        assertEquals(LenslyRoute.CAMERA, nav.currentBackStackEntry?.destination?.route)
    }

    /** A vault whose header and key file exist, so the gate reads the recorded security facts instead of the Keystore. */
    private fun writeVaultOnDisk(context: Context) {
        val layout = VaultLayout(File(context.noBackupFilesDir, "vault"))
        layout.root.mkdirs()
        layout.header.writeBytes(
            VaultHeader(
                kdf = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1),
                salt = ByteArray(VaultHeader.SALT_BYTES),
                createdAtWallMs = 0L,
                strongBox = false,
                hardwareBacked = true,
                keySecurityLevel = 0,
            ).encode(),
        )
        layout.vaultKey.writeBytes(ByteArray(64))
    }
}
