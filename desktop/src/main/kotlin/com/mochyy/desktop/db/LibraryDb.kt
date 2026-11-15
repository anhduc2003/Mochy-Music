package com.mochyy.desktop.db

import com.mochyy.desktop.model.Track
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * The library on disk.
 *
 * The Android build uses Room over a `Song` entity with thirty columns; Room's code generation is
 * an Android thing and none of it runs here. This is plain SQLite through JDBC with only the
 * columns the PC build shows, so the schema is small enough to read in one screen and there is no
 * migration machinery to keep in step with the phone.
 *
 * Every call opens and closes its own connection: the PC build writes the library rarely (adding a
 * folder) and reads it at startup, so a pool would be more moving parts than the job needs.
 */
class LibraryDb(private val file: Path = defaultPath()) {

    init {
        Files.createDirectories(file.parent)
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS track (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL DEFAULT '',
                        album TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        uri TEXT NOT NULL,
                        added_at INTEGER NOT NULL,
                        play_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_track_added ON track(added_at)")
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")

    /** adds what is new and leaves what is already there, so re-adding a folder is harmless */
    fun addAll(tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        var added = 0
        connect().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                """
                INSERT OR IGNORE INTO track (id, title, artist, album, duration_ms, uri, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                val now = System.currentTimeMillis()
                for (t in tracks) {
                    ps.setString(1, t.id)
                    ps.setString(2, t.title)
                    ps.setString(3, t.artist)
                    ps.setString(4, t.album)
                    ps.setLong(5, t.durationMs)
                    ps.setString(6, t.uri)
                    ps.setLong(7, now)
                    added += ps.executeUpdate()
                }
            }
            c.commit()
        }
        return added
    }

    fun all(): List<Track> {
        val out = ArrayList<Track>()
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT id, title, artist, album, duration_ms, uri FROM track ORDER BY added_at, title",
                ).use { rs ->
                    while (rs.next()) {
                        out += Track(
                            id = rs.getString(1),
                            title = rs.getString(2),
                            artist = rs.getString(3).orEmpty(),
                            album = rs.getString(4),
                            durationMs = rs.getLong(5),
                            uri = rs.getString(6),
                        )
                    }
                }
            }
        }
        return out
    }

    fun remove(id: String) {
        connect().use { c ->
            c.prepareStatement("DELETE FROM track WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun clear() {
        connect().use { c -> c.createStatement().use { it.executeUpdate("DELETE FROM track") } }
    }

    /** drops entries whose file has gone, so a moved or deleted folder doesn't leave dead rows */
    fun pruneMissing(): Int {
        val gone = all().filter { !java.io.File(it.uri).isFile }
        gone.forEach { remove(it.id) }
        return gone.size
    }

    companion object {
        /** `~/.local/share/Mochy` on Linux, `%APPDATA%\Mochy` on Windows, `~/Library/Application Support` on macOS */
        fun defaultPath(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = Paths.get(System.getProperty("user.home"))
            val base = when {
                os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString())
                os.contains("mac") -> home.resolve("Library/Application Support")
                else -> Paths.get(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString())
            }
            return base.resolve("Mochy").resolve("library.db")
        }
    }
}
