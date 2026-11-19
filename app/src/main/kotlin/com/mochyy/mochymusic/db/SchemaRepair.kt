// SchemaRepair.kt

package com.mochyy.mochymusic.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import timber.log.Timber
import java.io.File

/**
 * Rebuilds a library that room cannot migrate, instead of letting `fallbackToDestructiveMigration`
 * drop every table and leave the user with an empty app.
 *
 * A gap in [AllMigrations] — the one at 25 → 35 that no migration covers — makes every schema from
 * the far side of the gap unreachable, and the price of that was the whole library. Writing the
 * missing steps by hand needs the schema of each version in between, which nobody kept. So this
 * does not try: it asks room what the current schema is, by letting room create an empty database
 * of its own, and then rewrites the user's file to match — keeping every row of every column that
 * both the old file and the new schema still have.
 *
 * The description of the current schema therefore comes from room itself on every run, so it
 * cannot drift from the entities the way a second copy of the `CREATE TABLE` statements would.
 *
 * Only ever called for a file room has no path for; a file it can migrate is left alone.
 */
internal object SchemaRepair {

    /** A scratch database room creates and throws away, to read the current schema out of. */
    private const val ProbeName = "schema-probe.db"

    /**
     * Rebuilds [name] to [MusicDatabaseVersion], returning true when the file is ready to open.
     *
     * [backupOnFailure], when given, replaces the file again if the rebuild threw partway through,
     * so a failure leaves room exactly the file it would have had without any of this.
     */
    fun repair(context: Context, name: String, backupOnFailure: File?): Boolean {
        val target = context.getDatabasePath(name)
        val schema = runCatching { readCurrentSchema(context) }.getOrElse { error ->
            Timber.tag("MusicDatabase").e(error, "Could not read the schema to rebuild $name against")
            return false
        }
        return try {
            rebuild(target, schema)
            true
        } catch (error: Exception) {
            Timber.tag("MusicDatabase").e(error, "Could not rebuild $name for schema $MusicDatabaseVersion")
            restore(target, backupOnFailure)
            false
        }
    }

    /** Room's own description of schema [MusicDatabaseVersion], read from a database it just built. */
    private fun readCurrentSchema(context: Context): Schema {
        context.deleteDatabase(ProbeName)
        // allowed on the calling thread: this whole check runs before the app has a database to
        // work with, on whatever thread started the app, and it only creates 23 empty tables
        val probe = Room.databaseBuilder(context, InternalDatabase::class.java, ProbeName)
            .allowMainThreadQueries()
            .build()
        try {
            val database = probe.openHelper.writableDatabase
            // room writes the identity of the schema it just created; a rebuilt file has to carry it
            // or the next open refuses the library as "changed schema without a version bump"
            val identity = database
                .query("SELECT identity_hash FROM room_master_table WHERE id = 42")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: error("room did not record the identity of the schema it created")

            val tables = mutableListOf<Table>()
            val indexes = mutableListOf<String>()
            val views = mutableListOf<View>()
            database.query("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL").use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val sql = cursor.getString(2) ?: continue
                    when {
                        type == "table" && name == "room_master_table" -> Unit
                        type == "table" && !name.startsWith("sqlite_") -> tables += Table(name, sql)
                        type == "index" -> indexes += sql
                        type == "view" -> views += View(name, sql)
                    }
                }
            }
            return Schema(tables = tables, indexes = indexes, views = views, identity = identity)
        } finally {
            probe.close()
            context.deleteDatabase(ProbeName)
        }
    }

    private fun rebuild(file: File, schema: Schema) {
        val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            // off for the rewrite: dropping a table whose rows other tables point at would otherwise
            // cascade and take the rest of the library with it
            database.execSQL("PRAGMA foreign_keys = OFF")
            database.beginTransaction()
            try {
                val before = columnsOf(database)
                // every view goes, current ones included: they read the tables that are about to be
                // renamed, and re-creating them at the end puts back exactly the ones this version has
                namesOf(database, "view").forEach { database.execSQL("DROP VIEW IF EXISTS `$it`") }

                schema.tables.forEach { table ->
                    copyInto(database, table, before[table.name].orEmpty())
                }
                schema.indexes.forEach { database.execSQL(it) }
                schema.views.forEach { database.execSQL(it.sql) }

                database.execSQL("DROP TABLE IF EXISTS room_master_table")
                database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                database.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.identity}')")
                database.execSQL("PRAGMA user_version = $MusicDatabaseVersion")
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } finally {
            database.close()
        }
    }

    /**
     * Leaves [table] looking exactly like room's definition of it, carrying over the rows of every
     * column both versions still have. A table the old file never had is simply created.
     */
    private fun copyInto(database: SQLiteDatabase, table: Table, before: List<Column>) {
        if (before.isEmpty()) {
            database.execSQL(table.sql)
            return
        }

        // built under another name first: the old rows have to be read while the old table is still there
        val shadow = "${table.name}_rebuilt"
        database.execSQL("DROP TABLE IF EXISTS `$shadow`")
        database.execSQL(renamed(table, shadow))

        val after = columnsOf(database)[shadow].orEmpty()
        val beforeByName = before.associateBy { it.name }
        val carried = after.filter { it.name in beforeByName }
        // a column the old file did not have and that refuses null needs a value of its own type;
        // anything else can stay absent and take its default
        val filled = after.filter { it.notNull && it.name !in beforeByName }
        val inserted = carried + filled

        if (inserted.isNotEmpty()) {
            val columnList = inserted.joinToString(", ") { "`${it.name}`" }
            val values = inserted.joinToString(", ") { column ->
                if (column.name in beforeByName) "`${column.name}`" else emptyValueOf(column.type)
            }
            database.execSQL("INSERT INTO `$shadow` ($columnList) SELECT $values FROM `${table.name}`")
        }

        database.execSQL("DROP TABLE `${table.name}`")
        database.execSQL("ALTER TABLE `$shadow` RENAME TO `${table.name}`")
    }

    /** Room's `CREATE TABLE` for [table] with the table itself called [name]. */
    private fun renamed(table: Table, name: String): String {
        val at = table.sql.indexOf("`${table.name}`")
        require(at >= 0) { "the definition of ${table.name} does not name it" }
        return table.sql.substring(0, at) + "`$name`" + table.sql.substring(at + table.name.length + 2)
    }

    /** What to put in a column that has to hold something and has no old value to take. */
    private fun emptyValueOf(type: String): String = when {
        "TEXT" in type.uppercase() || "CHAR" in type.uppercase() || "CLOB" in type.uppercase() -> "''"
        "BLOB" in type.uppercase() -> "x''"
        else -> "0"
    }

    private fun namesOf(database: SQLiteDatabase, type: String): List<String> {
        val names = mutableListOf<String>()
        database.rawQuery("SELECT name FROM sqlite_master WHERE type = '$type'", null).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { names += it }
        }
        return names
    }

    private fun columnsOf(database: SQLiteDatabase): Map<String, List<Column>> =
        namesOf(database, "table").associateWith { table ->
            val columns = mutableListOf<Column>()
            database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                val nameAt = cursor.getColumnIndex("name")
                val typeAt = cursor.getColumnIndex("type")
                val notNullAt = cursor.getColumnIndex("notnull")
                while (cursor.moveToNext()) {
                    columns += Column(
                        name = cursor.getString(nameAt),
                        type = cursor.getString(typeAt).orEmpty(),
                        notNull = cursor.getInt(notNullAt) != 0,
                    )
                }
            }
            columns
        }

    /** Puts the copy of the file back when a rebuild threw partway through. */
    private fun restore(target: File, backup: File?) {
        if (backup == null || !backup.exists()) return
        runCatching {
            target.delete()
            backup.copyTo(target, overwrite = true)
            // the shared-memory file of the old, half-rewritten database must not outlive it
            File("${target.path}-shm").delete()
            File("${target.path}-wal").delete()
            File("${backup.path}-wal").takeIf { it.exists() }
                ?.copyTo(File("${target.path}-wal"), overwrite = true)
        }.onFailure { Timber.tag("MusicDatabase").e(it, "Could not put the copy of the database back") }
    }

    private class Schema(
        val tables: List<Table>,
        val indexes: List<String>,
        val views: List<View>,
        val identity: String,
    )

    private class Table(val name: String, val sql: String)

    private class View(val name: String, val sql: String)

    private class Column(val name: String, val type: String, val notNull: Boolean)
}
