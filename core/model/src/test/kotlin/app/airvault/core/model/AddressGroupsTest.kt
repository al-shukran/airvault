package app.airvault.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AddressGroupsTest {
    private val evm = "0x1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9a0B"

    @Test
    fun anEvmAddressKeepsItsPrefixAndReadsInGroupsOfFour() {
        assertEquals("0x1a2B 3c4D 5e6F 7a8B 9c0D 1e2F 3a4B 5c6D 7e8F 9a0B", AddressGroups.group(evm))
    }

    @Test
    fun anAddressIsCutToItsFirstTwoAndLastTwoGroups() {
        assertEquals("0x1a2B 3c4D … 7e8F 9a0B", AddressGroups.ends(evm))
        assertEquals("abcd efgh ij", AddressGroups.ends("abcdefghij"))
        assertEquals("abcd efgh ijkl mnop", AddressGroups.ends("abcdefghijklmnop"))
    }

    @Test
    fun aRowWritesAnEvmAddressAsItsEndsAndAnyOtherAddressAsItsFirstSixAndLastFour() {
        assertEquals("0x1a2B 3c4D … 7e8F 9a0B", AddressGroups.short(evm))
        assertEquals("0xabc", AddressGroups.short("0xabc"))
        assertEquals("bc1qar…5mdq", AddressGroups.short("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertEquals("1A1zP1eP5Q", AddressGroups.short("1A1zP1eP5Q"))
    }
}
