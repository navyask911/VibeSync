package com.example.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherSQLiteDatabase
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * One-time converter from the historical plaintext SQLite file to SQLCipher.
 *
 * The legacy source remains untouched until SQLCipher's encrypted export passes integrity and
 * row-count checks. No plaintext staging copy is created by the conversion.
 */
internal object EncryptedDatabaseBootstrap {
    private const val DATABASE_NAME = "dating_app.db"
    private const val ENCRYPTED_TEMP_SUFFIX = ".sqlcipher.tmp"

    fun prepare(context: Context, passphrase: ByteArray) {
        System.loadLibrary("sqlcipher")
        // Drop the old cursor store: its file contains message IDs/timestamps outside SQLCipher.
        val legacyWatermarks = File(
            File(context.applicationContext.filesDir, "datastore"),
            "vibesync_message_sync_watermarks.preferences_pb"
        )
        if (legacyWatermarks.exists()) {
            check(legacyWatermarks.delete()) {
                "Could not remove legacy unencrypted message-sync metadata."
            }
        }
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val encryptedTemporaryFile = File(
            databaseFile.parentFile,
            DATABASE_NAME + ENCRYPTED_TEMP_SUFFIX
        )

        if (!databaseFile.exists()) {
            if (encryptedTemporaryFile.exists()) {
                validateEncryptedDatabase(encryptedTemporaryFile, passphrase, null, null)
                removeSidecars(databaseFile)
                check(encryptedTemporaryFile.renameTo(databaseFile)) {
                    "Could not complete the interrupted encrypted database installation."
                }
                removeSidecars(encryptedTemporaryFile)
            }
            return
        }

        if (isReadablePlaintextDatabase(databaseFile)) {
            migratePlaintextDatabase(databaseFile, encryptedTemporaryFile, passphrase)
        } else {
            // An encrypted/corrupt file which cannot be authenticated must not be replaced with
            // a fresh Room database: that would silently discard locally received messages.
            validateEncryptedDatabase(databaseFile, passphrase, null, null)
            if (encryptedTemporaryFile.exists()) {
                check(encryptedTemporaryFile.delete()) {
                    "Could not remove a stale SQLCipher conversion file."
                }
                removeSidecars(encryptedTemporaryFile)
            }
        }
    }

    private fun migratePlaintextDatabase(
        sourceFile: File,
        encryptedFile: File,
        passphrase: ByteArray
    ) {
        if (encryptedFile.exists()) {
            check(encryptedFile.delete()) {
                "Could not reset an interrupted SQLCipher conversion file."
            }
        }
        removeSidecars(encryptedFile)

        var source: SqlCipherSQLiteDatabase? = null
        var attached = false
        var originalVersion = 0
        var expectedRowCounts: Map<String, Long> = emptyMap()
        try {
            source = SqlCipherSQLiteDatabase.openDatabase(
                sourceFile.absolutePath,
                null,
                SqlCipherSQLiteDatabase.OPEN_READWRITE
            )
            originalVersion = source.version
            expectedRowCounts = tableRowCounts(source)

            val passphraseText = String(passphrase, StandardCharsets.UTF_8)
            source.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf<Any>(encryptedFile.absolutePath, passphraseText)
            )
            attached = true
            source.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            source.execSQL("PRAGMA encrypted.user_version = " + originalVersion)
            source.execSQL("DETACH DATABASE encrypted")
            attached = false
        } catch (failure: Throwable) {
            runCatching { if (attached) source?.execSQL("DETACH DATABASE encrypted") }
            runCatching { source?.close() }
            runCatching { encryptedFile.delete() }
            removeSidecars(encryptedFile)
            throw IllegalStateException(
                "Could not encrypt the existing VibeSync database. The original file was preserved.",
                failure
            )
        } finally {
            runCatching { source?.close() }
        }

        try {
            validateEncryptedDatabase(
                encryptedFile,
                passphrase,
                expectedRowCounts,
                originalVersion
            )

            // SQLCipher's export is now verified. Drop plaintext SQLite journal files first,
            // then replace the source file. If rename is interrupted, the encrypted temp remains
            // and is recovered on the next startup.
            removeSidecars(sourceFile)
            check(sourceFile.delete()) {
                "The encrypted copy was verified, but the legacy plaintext file could not be removed."
            }
            check(encryptedFile.renameTo(sourceFile)) {
                "Could not move the verified encrypted database into the Room database path."
            }
            removeSidecars(encryptedFile)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "SQLCipher migration could not finish. The database was not intentionally discarded.",
                failure
            )
        }
    }

    private fun isReadablePlaintextDatabase(file: File): Boolean {
        var database: FrameworkSQLiteDatabase? = null
        return try {
            database = FrameworkSQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                FrameworkSQLiteDatabase.OPEN_READONLY
            )
            database.rawQuery("SELECT count(*) FROM sqlite_master", null)
                .use { cursor -> cursor.moveToFirst() }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { database?.close() }
        }
    }

    private fun validateEncryptedDatabase(
        file: File,
        passphrase: ByteArray,
        expectedCounts: Map<String, Long>?,
        expectedVersion: Int?
    ) {
        check(file.isFile && file.length() > 0L) {
            "The SQLCipher database file is missing or empty."
        }
        val database = SqlCipherSQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SqlCipherSQLiteDatabase.OPEN_READWRITE,
            null
        )
        try {
            val integrityOk = database.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            check(integrityOk) { "SQLCipher integrity check failed." }
            if (expectedVersion != null) {
                check(database.version == expectedVersion) {
                    "SQLCipher export changed the Room database version."
                }
            }
            if (expectedCounts != null) {
                check(tableRowCounts(database) == expectedCounts) {
                    "SQLCipher export row counts do not match the source database."
                }
            }
        } finally {
            database.close()
        }
    }

    private fun tableRowCounts(database: SqlCipherSQLiteDatabase): Map<String, Long> {
        val names = mutableListOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }

        val counts = linkedMapOf<String, Long>()
        for (name in names) {
            val quotedName = "\"" + name.replace("\"", "\"\"") + "\""
            val count = database.rawQuery(
                "SELECT COUNT(*) FROM " + quotedName,
                emptyArray()
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            counts[name] = count
        }
        return counts
    }

    private fun removeSidecars(databaseFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(databaseFile.parentFile, databaseFile.name + suffix)
            if (sidecar.exists()) {
                check(sidecar.delete()) { "Could not remove an old SQLite " + suffix + " file." }
            }
        }
    }
}
