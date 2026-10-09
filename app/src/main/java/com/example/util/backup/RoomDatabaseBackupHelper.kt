package com.example.util.backup

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.database.DatingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Encrypted full Room SQLite snapshot and merge-style restore support. */
object RoomDatabaseBackupHelper {
    private const val TAG = "RoomDatabaseBackup"

    suspend fun createEncryptedSnapshot(context: Context, passphrase: String): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val room = DatingDatabase.getDatabase(appContext)
        val writableDb = room.openHelper.writableDatabase

        // Checkpoint the WAL before copying the database file so committed writes are included.
        writableDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                throw IllegalStateException("Database is busy; please retry the backup.")
            }
        }

        val databaseFile = appContext.getDatabasePath("dating_app.db")
        check(databaseFile.isFile && databaseFile.length() > 0L) { "Local Room database file was not found." }
        val suffix = System.currentTimeMillis()
        val plaintext = File(appContext.cacheDir, "vibesync_room_$suffix.sqlite")
        val encrypted = File(appContext.cacheDir, "vibesync_room_$suffix.enc")
        try {
            databaseFile.copyTo(plaintext, overwrite = true)
            check(ChatBackupCryptoHelper.encryptFileToFile(plaintext, encrypted, passphrase)) {
                "Could not encrypt the Room database snapshot."
            }
            encrypted
        } finally {
            plaintext.delete()
        }
    }

    suspend fun restoreEncryptedSnapshot(context: Context, encryptedFile: File, passphrase: String): Int = withContext(Dispatchers.IO) {
        val plaintext = File(context.applicationContext.cacheDir, "vibesync_restore_${System.currentTimeMillis()}.sqlite")
        try {
            check(ChatBackupCryptoHelper.decryptFileToFile(encryptedFile, plaintext, passphrase)) {
                "Unable to decrypt this database backup. Verify the passphrase and file."
            }
            restoreTables(DatingDatabase.getDatabase(context.applicationContext).openHelper.writableDatabase, plaintext)
        } finally {
            plaintext.delete()
        }
    }

    private fun restoreTables(destination: SupportSQLiteDatabase, sourceFile: File): Int {
        var attached = false
        var restoredTables = 0
        destination.execSQL("PRAGMA foreign_keys = OFF")
        try {
            destination.execSQL("ATTACH DATABASE ? AS restore_db", arrayOf(sourceFile.absolutePath))
            attached = true
            val sourceTables = readTableNames(destination, "restore_db")
            val destinationTables = readTableNames(destination, "main")
            val compatibleTables = sourceTables.intersect(destinationTables).filterNot {
                it.startsWith("room_", ignoreCase = true) ||
                    it.startsWith("android_", ignoreCase = true) ||
                    it.startsWith("sqlite_", ignoreCase = true)
            }

            destination.beginTransaction()
            try {
                compatibleTables.forEach { tableName ->
                    val sourceColumns = readColumns(destination, "restore_db", tableName)
                    val destinationColumns = readColumns(destination, "main", tableName)
                    val columns = destinationColumns.filter { it in sourceColumns }
                    if (columns.isEmpty()) return@forEach
                    val table = quote(tableName)
                    val columnList = columns.joinToString(",") { quote(it) }
                    destination.execSQL("DELETE FROM main.$table")
                    destination.execSQL(
                        "INSERT OR REPLACE INTO main.$table ($columnList) SELECT $columnList FROM restore_db.$table"
                    )
                    restoredTables += 1
                }
                destination.setTransactionSuccessful()
            } finally {
                destination.endTransaction()
            }
        } finally {
            if (attached) {
                try {
                    destination.execSQL("DETACH DATABASE restore_db")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not detach temporary database: ${e.message}")
                }
            }
            destination.execSQL("PRAGMA foreign_keys = ON")
        }
        check(restoredTables > 0) { "No compatible VibeSync Room tables were found in the backup." }
        Log.i(TAG, "Restored $restoredTables Room tables from encrypted database snapshot.")
        return restoredTables
    }

    private fun readTableNames(db: SupportSQLiteDatabase, schema: String): Set<String> {
        val tables = linkedSetOf<String>()
        db.query("SELECT name FROM $schema.sqlite_master WHERE type='table'").use { cursor ->
            val index = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (index >= 0) tables += cursor.getString(index)
        }
        return tables
    }

    private fun readColumns(db: SupportSQLiteDatabase, schema: String, table: String): List<String> {
        val columns = mutableListOf<String>()
        db.query("PRAGMA $schema.table_info(${quote(table)})").use { cursor ->
            val index = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (index >= 0) columns += cursor.getString(index)
        }
        return columns
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
