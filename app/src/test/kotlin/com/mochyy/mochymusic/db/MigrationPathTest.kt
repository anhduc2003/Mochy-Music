package com.mochyy.mochymusic.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shape of the migration graph, so a gap that would cost people their library cannot
 * appear (or grow) without a test going red.
 *
 * The app opens the database with `fallbackToDestructiveMigration`, which answers a version it
 * cannot reach by dropping every table. [SchemaRepair] now rebuilds such a file instead, but that is
 * a safety net, not a licence: every version that *has* a path must keep one.
 */
class MigrationPathTest {

    @Test
    fun `the current version needs no migration`() {
        assertTrue(hasMigrationPath(MusicDatabaseVersion, MusicDatabaseVersion))
    }

    @Test
    fun `the newest schemas still walk to the current version`() {
        // the hand-written migrations cover 35 onwards; anything inside that range must keep working
        for (version in 35..MusicDatabaseVersion) {
            assertTrue("schema $version lost its path", hasMigrationPath(version, MusicDatabaseVersion))
        }
    }

    @Test
    fun `the known gap is exactly where the repair expects it`() {
        // 25 -> 35 has no migration: schemas 26 to 34 come only from builds between the two, and the
        // versions they added are not recoverable from this history. SchemaRepair covers that range,
        // and this test is what says the range is still that one and has not quietly grown.
        for (version in 26..34) {
            assertFalse(
                "schema $version now has a path, so the repair no longer covers it",
                hasMigrationPath(version, MusicDatabaseVersion),
            )
        }
    }

    @Test
    fun `older schemas stop before the gap`() {
        // 1, 21, 22 and 24 have steps that lead to 25, and 25 leads nowhere
        for (version in listOf(1, 21, 22, 24, 25)) {
            assertFalse("schema $version reaches $MusicDatabaseVersion, which the list says it cannot", hasMigrationPath(version, MusicDatabaseVersion))
        }
    }

    @Test
    fun `a version this build does not know has no path`() {
        assertFalse(hasMigrationPath(MusicDatabaseVersion + 1, MusicDatabaseVersion))
        assertFalse(hasMigrationPath(0, MusicDatabaseVersion))
        assertFalse(hasMigrationPath(-3, MusicDatabaseVersion))
    }
}
