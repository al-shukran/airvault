package app.lensly.core.registry

import app.airvault.abi.AbiValue
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AbiRowsTest {
    /** A string argument whose bytes are not text is an invariant the decoder keeps, so the rows refuse it rather than show an empty string. */
    @Test
    fun aStringThatIsNotTextFailsRatherThanShowingAnEmptyString() {
        val decoded = DecodedParam(WhitelistParam("memo", "string"), AbiValue.Dynamic("ff"))

        assertFailsWith<IllegalStateException> { AbiRows.of(decoded) }
    }
}
