package com.mochyy.mochymusic.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The version decides whether the library is about to be dropped, so it is read straight out of the
 * file header. Getting the byte order wrong would either miss the wipe or take a copy on every start.
 */
class PreUpgradeBackupTest {

    private fun databaseFile(version: Int, size: Int = 100): File =
        File.createTempFile("mochy", ".db").apply {
            deleteOnExit()
            val bytes = ByteArray(size)
            "SQLite format 3\u0000".toByteArray().copyInto(bytes)
            if (size >= 64) {
                bytes[60] = (version ushr 24).toByte()
                bytes[61] = (version ushr 16).toByte()
                bytes[62] = (version ushr 8).toByte()
                bytes[63] = version.toByte()
            }
            writeBytes(bytes)
        }

    @Test
    fun `the version comes from the four header bytes, big-endian`() {
        assertEquals(25, PreUpgradeBackup.readUserVersion(databaseFile(25)))
        assertEquals(MusicDatabaseVersion, PreUpgradeBackup.readUserVersion(databaseFile(MusicDatabaseVersion)))
        assertEquals(1, PreUpgradeBackup.readUserVersion(databaseFile(1)))
    }

    @Test
    fun `a file with no version written says zero, which has no path`() {
        assertEquals(0, PreUpgradeBackup.readUserVersion(databaseFile(0)))
        assertEquals(false, hasMigrationPath(0, MusicDatabaseVersion))
    }

    @Test
    fun `a file too short to hold a header has no version`() {
        assertNull(PreUpgradeBackup.readUserVersion(databaseFile(25, size = 40)))
    }
}
