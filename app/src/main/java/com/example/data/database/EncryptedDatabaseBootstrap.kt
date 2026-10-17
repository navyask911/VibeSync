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

    private var preparedDatabasePath: String? = null

    /**
     * Serialize bootstrap across the foreground preflight and persisted WorkManager retries.
     * Cache only a successful preparation; failures remain retryable in the recovery screen.
     */
    @Synchronized
    fun prepare(context: Context, passphrase: ByteArray) {
        val path = context.getDatabasePath(DATABASE_NAME).absolutePath
        if (preparedDatabasePath == path) return
        prepareInternal(context, passphrase)
        preparedDatabasePath = path
    }

    private fun prepareInternal(context: Context, passphrase: ByteArray) {
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
        ensureDirectory(databaseFile.parentFile)
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
            // A first ATTACH can fail transiently if Android has just recreated app-private
            // database storage. Retry once only while the original plaintext file is still
            // readable and intact. Never fall back to opening it without SQLCipher.
            var firstFailure: Throwable? = null
            repeat(2) { attempt ->
                try {
                    migratePlaintextDatabase(databaseFile, encryptedTemporaryFile, passphrase)
                    return
                } catch (failure: Throwable) {
                    val sourceStillIntact = databaseFile.isFile &&
                        isReadablePlaintextDatabase(databaseFile)
                    if (attempt == 0 && sourceStillIntact) {
                        firstFailure = failure
                    } else {
                        firstFailure?.let { previous ->
                            if (previous !== failure) failure.addSuppressed(previous)
                        }
                        throw IllegalStateException(
                            "Could not securely encrypt the existing VibeSync database. " +
                                "The original database was preserved; no plaintext fallback was opened.",
                            failure
                        )
                    }
                }
            }
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
        ensureDirectory(encryptedFile.parentFile)
        if (encryptedFile.exists()) {
            check(encryptedFile.delete()) {
                "Could not reset an interrupted SQLCipher conversion file."
            }
        }
        removeSidecars(encryptedFile)
        // ATTACH should not have to create a missing file under a missing/unwritable parent.
        // createNewFile() keeps this temporary database in the app-private databases directory.
        check(encryptedFile.createNewFile()) {
            "Could not create the temporary SQLCipher database file."
        }
        check(encryptedFile.setReadable(true, true) && encryptedFile.setWritable(true, true) &&
            encryptedFile.canWrite()) {
            "Could not set app-private read/write permissions on the temporary SQLCipher database."
        }

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
            // The source is a legacy plaintext SQLite file. Minimize exposure while it is
            // read for conversion; the verified SQLCipher export replaces this file below.
            source.rawExecSQL("PRAGMA cipher_memory_security = ON")
            source.rawExecSQL("PRAGMA secure_delete = ON")
            originalVersion = source.version
            expectedRowCounts = tableRowCounts(source)

            val passphraseText = String(passphrase, StandardCharsets.US_ASCII)
            check(passphraseText.length == 64 && passphraseText.all {
                it in '0'..'9' || it in 'a'..'f'
            }) {
                "The SQLCipher passphrase has an unexpected encoding."
            }
            // ATTACH accepts SQL string literals; escape the filesystem path and keep the
            // validated hex passphrase literal out of logs. The destination file already exists.
            val escapedDestinationPath = encryptedFile.absolutePath.replace("'", "''")
            source.execSQL(
                "ATTACH DATABASE '$escapedDestinationPath' AS encrypted KEY '$passphraseText'"
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
            // If the source was deleted but the validated encrypted temp could not be renamed,
            // immediately attempt recovery rather than forcing an avoidable second-launch crash.
            if (!sourceFile.exists() && encryptedFile.isFile) {
                try {
                    validateEncryptedDatabase(
                        encryptedFile,
                        passphrase,
                        expectedRowCounts,
                        originalVersion
                    )
                    check(encryptedFile.renameTo(sourceFile)) {
                        "Could not recover the verified encrypted database into its final path."
                    }
                    removeSidecars(encryptedFile)
                    return
                } catch (recoveryFailure: Throwable) {
                    if (recoveryFailure !== failure) failure.addSuppressed(recoveryFailure)
                }
            }
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

    private fun ensureDirectory(directory: File?) {
        check(directory != null) {
            "The SQLCipher database directory path is unavailable."
        }
        if (!directory.exists()) {
            check(directory.mkdirs() || directory.isDirectory) {
                "Could not create the app-private SQLCipher database directory."
            }
        }
        check(directory.isDirectory && directory.canWrite()) {
            "The app-private SQLCipher database directory is not writable."
        }
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
