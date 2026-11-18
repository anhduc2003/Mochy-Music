// PreUpgradeBackup.kt

package com.mochyy.mochymusic.db

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Room answers a database it cannot walk to the current version by dropping every table
 * (`fallbackToDestructiveMigration`), which is what the missing 25 -> 35 step leaves behind: a
 * library written by a build at schema 25 has no path to [MusicDatabaseVersion] and is emptied on
 * the first start after the update, with no prompt and no way back.
 *
 * Until that step exists, the file is copied out of the way first, so the songs, playlists, likes
 * and listening history are still on the phone rather than gone. Everything here is best effort:
 * a backup that cannot be taken must never stop the app from opening its database.
 */
internal object PreUpgradeBackup {

    /** The four bytes at offset 60 of a SQLite file: its own `PRAGMA user_version`. */
    private const val UserVersionOffset = 60L

    /** Copies the database aside when opening it is about to wipe it. Returns the copy, if one was made. */
    fun keepBeforeDestructiveUpgrade(context: Context, name: String): File? = runCatching {
        val source = context.getDatabasePath(name)
        if (!source.exists()) return null
        val version = readUserVersion(source) ?: return null
        if (version == MusicDatabaseVersion) return null
        if (hasMigrationPath(version, MusicDatabaseVersion)) return null

        val backup = File(source.parentFile, "$name.from$version.${System.currentTimeMillis()}.bak")
        source.copyTo(backup, overwrite = true)
        // the write-ahead log still holds the newest writes, so it travels with the copy
        File("${source.path}-wal").takeIf { it.exists() }?.copyTo(File("${backup.path}-wal"), overwrite = true)

        Timber.tag("MusicDatabase").w(
            "Schema $version has no path to $MusicDatabaseVersion: the library is about to be rebuilt, kept a copy at ${backup.name}",
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
