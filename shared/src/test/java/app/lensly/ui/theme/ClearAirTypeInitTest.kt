package app.lensly.ui.theme

import java.io.File
import java.net.URLClassLoader // CI_ALLOW_NETWORK_REFERENCE: class loader for an init-order test; no network access
import kotlin.test.assertNotNull
import org.junit.Test

/**
 * Touching [ClearAirType] before [ClearAirTypography] must not leave the Material scale holding null styles (#1762). The statics are set once
 * per JVM, so the test loads the theme classes in a fresh class loader, where the order is the one under test, whichever test ran first.
 */
class ClearAirTypeInitTest {
    @Test
    fun theScaleIsWholeWhenTheTypeObjectIsTouchedFirst() {
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        URLClassLoader(classpath.toTypedArray(), ClassLoader.getSystemClassLoader().parent).use { loader ->
            Class.forName("app.lensly.ui.theme.ClearAirType", true, loader)
            val scale = Class.forName("app.lensly.ui.theme.ClearAirTypeKt", true, loader).getMethod("getClearAirTypography").invoke(null)
            for (role in listOf("getDisplayLarge", "getDisplaySmall", "getTitleMedium", "getBodyMedium", "getLabelSmall")) {
                assertNotNull(scale.javaClass.getMethod(role).invoke(scale), "$role is null")
            }
        }
    }
}
