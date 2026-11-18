package com.mochyy.mochymusic.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ObjectInputStream

/**
 * A save has to survive being cut short, and — the bug this pins — it has to actually finish: syncing
 * the descriptor after the object stream had closed it threw on every write, so nothing was ever saved.
 */
class AtomicObjectFileTest {

    private fun tempTarget(): File =
        File.createTempFile("mochy-state", ".data").apply { deleteOnExit() }

    private fun read(file: File): Any =
        file.inputStream().use { input -> ObjectInputStream(input).use { it.readObject() } }

    @Test
    fun `a value written is a value readable back`() {
        val target = tempTarget()
        AtomicObjectFile.write(target, intArrayOf(1, 2, 3))
        val back = read(target) as IntArray
        assertEquals(3, back.size)
        assertEquals("[1, 2, 3]", back.joinToString(prefix = "[", postfix = "]"))
    }

    @Test
    fun `a later write replaces the earlier one`() {
        val target = tempTarget()
        AtomicObjectFile.write(target, "first")
        AtomicObjectFile.write(target, "second")
        assertEquals("second", read(target))
    }

    @Test
    fun `no scratch file is left behind`() {
        val target = tempTarget()
        AtomicObjectFile.write(target, "value")
        val leftovers = target.parentFile.listFiles().orEmpty().filter { it.name == "${target.name}.tmp" }
        assertTrue("scratch file left: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a target that does not exist yet is created`() {
        val target = File(tempTarget().parentFile, "created-${System.nanoTime()}.data")
        assertFalse(target.exists())
        AtomicObjectFile.write(target, 42)
        assertEquals(42, read(target))
        target.delete()
    }
}
