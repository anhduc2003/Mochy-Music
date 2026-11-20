package com.mochyy.mochymusic.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * The version has to be read without opening the database: a write-ahead file cannot be opened
 * read-only without write access to its `-shm`, and reading it is the whole point of the check that
 * runs before room touches it.
 */
class PreUpgradeBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `reads the version out of the file header`() {
        val file = folder.newFile("mochy.db")
        writeVersion(file, 43)

        assertEquals(43, PreUpgradeBackup.readUserVersion(file))
    }

    @Test
    fun `reads a version written by an older build`() {
        val file = folder.newFile("old.db")
        writeVersion(file, 25)

        assertEquals(25, PreUpgradeBackup.readUserVersion(file))
    }

    @Test
    fun `a file too short to be a database has no version`() {
        val file = folder.newFile("empty.db")

        assertNull(PreUpgradeBackup.readUserVersion(file))
    }

    @Test
    fun `a file that is not there has no version`() {
        assertNull(PreUpgradeBackup.readUserVersion(folder.newFile("absent.db").also { it.delete() }))
    }

    /** The four bytes at offset 60, big-endian, which is where SQLite keeps `PRAGMA user_version`. */
    private fun writeVersion(file: java.io.File, version: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(ByteArray(100))
            raf.seek(60)
            raf.write(
                byteArrayOf(
                    (version shr 24).toByte(),
                    (version shr 16).toByte(),
                    (version shr 8).toByte(),
                    version.toByte(),
                ),
            )
        }
    }
}
