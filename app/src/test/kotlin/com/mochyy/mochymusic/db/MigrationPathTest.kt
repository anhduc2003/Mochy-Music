package com.mochyy.mochymusic.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room drops every table when it cannot walk the file it finds up to the current version, so this has
 * to answer the same question room does, step for step, before anything opens a database.
 */
class MigrationPathTest {

    @Test
    fun `a database already at the current version needs no step`() {
        assertTrue(hasMigrationPath(MusicDatabaseVersion, MusicDatabaseVersion))
    }

    @Test
    fun `the newest step reaches the version it was written for`() {
        val last = AllMigrations.maxBy { it.endVersion }
        assertTrue(hasMigrationPath(last.startVersion, last.endVersion))
    }

    @Test
    fun `a descending range has no path, room never walks backwards`() {
        assertFalse(hasMigrationPath(2, 1))
    }

    @Test
    fun `a version below the first step has no path`() {
        assertFalse(hasMigrationPath(0, MusicDatabaseVersion))
    }

    /**
     * The hole reported in AUDIT.md as P0, pinned so it cannot grow: every step from 35 on chains,
     * and every version below it — 24 and 25 included, both of which the list migrates *to* — has no
     * way forward, so room empties the library of anyone upgrading from an older build. When that is
     * fixed (a 25 -> 35 step, or a catch-up migration), this expectation grows and the fix is done.
     */
    @Test
    fun `only the versions with a chain of their own reach the current one`() {
        val carried = (1 until MusicDatabaseVersion).filter { hasMigrationPath(it, MusicDatabaseVersion) }
        assertEquals((35 until MusicDatabaseVersion).toList(), carried)
    }
}
