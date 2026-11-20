// PreUpgradeBackup.kt

package com.mochyy.mochymusic.db

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Room answers a database it cannot walk to the current version by dropping every table
 * (`fallbackToDestructiveMigration`), which is what a gap in the migration list leaves behind: a
 * library written by a build from the middle of that gap has no path to [MusicDatabaseVersion] and
 * is emptied on the first start after the update, with no prompt and no way back.
 *
 * [SchemaRepair] tries first to rebuild such a file instead, which keeps the songs, playlists,
 * likes and listening history. This copies the file aside as well, so when that rebuild cannot be
 * done — or does not produce something room accepts — the library is still on the phone rather
 * than gone. Everything here is best effort: a backup that cannot be taken must never stop the app
 * from opening its database.
 */
internal object PreUpgradeBackup {

    /** The four bytes at offset 60 of a SQLite file: its own `PRAGMA user_version`. */
    private const val UserVersionOffset = 60L

    /**
     * Called once per open, before room touches the file: rebuilds a library room would otherwise
     * wipe, and keeps a copy of anything that cannot be rebuilt. Returns the copy, if one was made.
     */
    fun prepareForOpen(context: Context, name: String): File? = runCatching {
        val source = context.getDatabasePath(name)
        if (!source.exists()) return null
        val version = readUserVersion(source) ?: return null
        if (version == MusicDatabaseVersion) return null
        if (hasMigrationPath(version, MusicDatabaseVersion)) return null

        val backup = keep(source, version)
        if (SchemaRepair.repair(context, name, backupOnFailure = backup)) {
            Timber.tag("MusicDatabase").i("Rebuilt the $version library as $MusicDatabaseVersion without losing it")
            // the rebuilt file is the library now; the copy was insurance for a rebuild that failed
            backup?.delete()
            return null
        }
        backup
    }.getOrElse { error ->
        Timber.tag("MusicDatabase").e(error, "Could not keep a copy of the database before the upgrade")
        null
    }

    /** Copies [source] aside, replacing any copy left by an earlier attempt so only one is ever kept. */
    private fun keep(source: File, version: Int): File? = runCatching {
        val parent = source.parentFile ?: return null
        // older copies of this database: one stale copy per past attempt would sit on the phone forever
        parent.listFiles()
            ?.filter { it.name.startsWith("${source.name}.from") && it.name.endsWith(".bak") }
            ?.forEach { it.delete() }

        val backup = File(parent, "${source.name}.from$version.bak")
        source.copyTo(backup, overwrite = true)
        // the write-ahead log still holds the newest writes, so it travels with the copy
        File("${source.path}-wal").takeIf { it.exists() }?.copyTo(File("${backup.path}-wal"), overwrite = true)

        Timber.tag("MusicDatabase").w(
            "Schema $version has no path to $MusicDatabaseVersion: rebuilt it if possible, kept a copy at ${backup.name}",
        )
        backup
    }.getOrElse { error ->
        Timber.tag("MusicDatabase").e(error, "Could not keep a copy of the database before the upgrade")
        null
    }

    /**
     * Reads the version straight out of the file header rather than opening it: a write-ahead
     * database cannot be opened read-only without write access to its `-shm`, and it is that path
     * this has to survive.
     */
    internal fun readUserVersion(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < UserVersionOffset + 4) return null
            raf.seek(UserVersionOffset)
            val bytes = ByteArray(4)
            raf.readFully(bytes)
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }
    }.getOrNull()
}
