package app.lensly.ui.gate

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PasswordBufferTest {

    @Test
    fun startsEmpty() {
        val buffer = PasswordBuffer()
        assertEquals(0, buffer.length)
        assertTrue(buffer.isEmpty())
        assertEquals("", String(buffer.toCharArray()))
    }

    @Test
    fun appendKeepsOrder() {
        val buffer = PasswordBuffer()
        "Hello1!".forEach { buffer.append(it) }
        assertEquals(7, buffer.length)
        assertEquals("Hello1!", String(buffer.toCharArray()))
    }

    @Test
    fun backspaceRemovesLastCharacter() {
        val buffer = PasswordBuffer()
        "abc".forEach { buffer.append(it) }
        buffer.backspace()
        assertEquals("ab", String(buffer.toCharArray()))
        buffer.backspace()
        buffer.backspace()
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun backspaceOnEmptyIsNoOp() {
        val buffer = PasswordBuffer()
        buffer.backspace()
        assertEquals(0, buffer.length)
        buffer.append('x')
        assertEquals("x", String(buffer.toCharArray()))
    }

    @Test
    fun clearResetsLengthAndZeroFillsBackingArray() {
        val buffer = PasswordBuffer(initialCapacity = 8)
        "secret12".forEach { buffer.append(it) }
        val backing = buffer.backingArrayForTest()

        buffer.clear()

        assertEquals(0, buffer.length)
        assertTrue(buffer.isEmpty())
        assertTrue(backing.all { it == '\u0000' }, "every slot must be zero after clear()")
    }

    @Test
    fun backspaceZeroFillsVacatedSlot() {
        val buffer = PasswordBuffer(initialCapacity = 4)
        "wxyz".forEach { buffer.append(it) }
        val backing = buffer.backingArrayForTest()

        buffer.backspace()

        assertEquals('\u0000', backing[3])
        assertEquals("wxy", String(buffer.toCharArray()))
    }

    @Test
    fun toCharArrayReturnsIndependentCopy() {
        val buffer = PasswordBuffer()
        "pass".forEach { buffer.append(it) }
        val copy = buffer.toCharArray()
        copy.fill('#')

        assertEquals("pass", String(buffer.toCharArray()))
        buffer.append('!')
        assertEquals(4, copy.size)
        assertEquals("####", String(copy))
    }

    @Test
    fun growthPreservesContents() {
        val buffer = PasswordBuffer(initialCapacity = 1)
        val text = "the quick brown fox jumps over the lazy dog 0123456789"
        text.forEach { buffer.append(it) }
        assertEquals(text, String(buffer.toCharArray()))
    }

    @Test
    fun appendStopsAtMaxLength() {
        val buffer = PasswordBuffer()
        repeat(PasswordBuffer.MAX_LENGTH) { assertTrue(buffer.append('a')) }
        assertFalse(buffer.append('b'))
        assertEquals(PasswordBuffer.MAX_LENGTH, buffer.length)
        assertTrue(buffer.toCharArray().all { it == 'a' })
    }

    @Test
    fun clearAllowsReuseAfterGrowth() {
        val buffer = PasswordBuffer(initialCapacity = 2)
        "abcdefgh".forEach { buffer.append(it) }
        buffer.clear()
        "xy".forEach { buffer.append(it) }
        assertEquals("xy", String(buffer.toCharArray()))
    }
}
