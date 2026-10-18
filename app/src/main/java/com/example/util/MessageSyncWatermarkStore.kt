package com.example.util

import android.content.Context
import androidx.room.withTransaction
import com.example.data.database.DatingDatabase
import com.example.data.model.MessageSyncWatermarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime

data class MessageSyncCursor(
    val createdAtMs: Long = 0L,
    val createdAtIso: String = "",
    val messageId: String = ""
)

/**
 * Message delta cursors are persisted in the SQLCipher Room database, not plaintext Preferences.
 * The old Preferences DataStore file is deleted when the encrypted store is first initialized.
 */
object MessageSyncWatermarkStore {
    private const val GLOBAL_KEY = "__global__"
    private const val LEGACY_DATASTORE_NAME = "vibesync_message_sync_watermarks.preferences_pb"

    private fun timestampKey(localMatchId: String): String = stableKey(localMatchId)

    /** Stable Room key exposed to the explicit Clear Chat DAO, not to remote payloads. */
    internal fun databaseKey(localMatchId: String): String = timestampKey(localMatchId)

    suspend fun read(context: Context, localMatchId: String): Long = withContext(Dispatchers.IO) {
        removeLegacyPlaintextStore(context)
        DatingDatabase.getDatabase(context.applicationContext)
            .messageSyncWatermarkDao().get(timestampKey(localMatchId))?.createdAtMs ?: 0L
    }

    suspend fun readCursor(context: Context, localMatchId: String): MessageSyncCursor =
        withContext(Dispatchers.IO) {
            removeLegacyPlaintextStore(context)
            val entity = DatingDatabase.getDatabase(context.applicationContext)
                .messageSyncWatermarkDao().get(timestampKey(localMatchId))
            MessageSyncCursor(
                createdAtMs = entity?.createdAtMs ?: 0L,
                createdAtIso = entity?.createdAtIso.orEmpty(),
                messageId = entity?.messageId.orEmpty()
            )
        }

    /** Compatibility path for timestamp-only callers, preserving the monotonic cursor contract. */
    suspend fun advance(context: Context, localMatchId: String, serverCreatedAtMs: Long) =
        withContext(Dispatchers.IO) {
            if (localMatchId.isBlank() || serverCreatedAtMs <= 0L) return@withContext
            removeLegacyPlaintextStore(context)
            advanceInEncryptedRoom(
                context,
                MessageSyncWatermarkEntity(
                    localMatchId = timestampKey(localMatchId),
                    createdAtMs = serverCreatedAtMs,
                    createdAtIso = Instant.ofEpochMilli(serverCreatedAtMs).toString(),
                    messageId = ""
                )
            )
        }

    suspend fun advance(
        context: Context,
        localMatchId: String,
        serverCreatedAtIso: String,
        serverMessageId: String,
        serverCreatedAtMs: Long
    ) = withContext(Dispatchers.IO) {
        if (localMatchId.isBlank() || serverCreatedAtMs <= 0L ||
            serverCreatedAtIso.isBlank() || serverMessageId.isBlank()
        ) return@withContext
        removeLegacyPlaintextStore(context)
        advanceInEncryptedRoom(
            context,
            MessageSyncWatermarkEntity(
                localMatchId = timestampKey(localMatchId),
                createdAtMs = serverCreatedAtMs,
                createdAtIso = serverCreatedAtIso,
                messageId = serverMessageId
            )
        )
    }

    suspend fun readGlobal(context: Context): Long = withContext(Dispatchers.IO) {
        removeLegacyPlaintextStore(context)
        DatingDatabase.getDatabase(context.applicationContext)
            .messageSyncWatermarkDao().get(GLOBAL_KEY)?.createdAtMs ?: 0L
    }

    private suspend fun advanceInEncryptedRoom(
        context: Context,
        candidate: MessageSyncWatermarkEntity
    ) {
        val database = DatingDatabase.getDatabase(context.applicationContext)
        database.withTransaction {
            val dao = database.messageSyncWatermarkDao()
            val current = dao.get(candidate.localMatchId)
            if (current == null || compareCursor(candidate, current) > 0) {
                dao.upsert(candidate.copy(
                    createdAtMs = maxOf(current?.createdAtMs ?: 0L, candidate.createdAtMs)
                ))
            }

            val global = dao.get(GLOBAL_KEY)
            if (global == null || candidate.createdAtMs > global.createdAtMs) {
                dao.upsert(
                    MessageSyncWatermarkEntity(
                        localMatchId = GLOBAL_KEY,
                        createdAtMs = candidate.createdAtMs,
                        createdAtIso = candidate.createdAtIso,
                        messageId = candidate.messageId
                    )
                )
            }
        }
    }

    private fun compareCursor(
        left: MessageSyncWatermarkEntity,
        right: MessageSyncWatermarkEntity
    ): Int {
        val leftInstant = parseInstant(left.createdAtIso)
        val rightInstant = parseInstant(right.createdAtIso)
        val timeComparison = if (left.createdAtIso.isNotBlank() && right.createdAtIso.isNotBlank() &&
            leftInstant != null && rightInstant != null
        ) {
            leftInstant.compareTo(rightInstant)
        } else {
            left.createdAtMs.compareTo(right.createdAtMs)
        }
        return if (timeComparison != 0) timeComparison else left.messageId.compareTo(right.messageId)
    }

    private fun parseInstant(raw: String): Instant? =
        if (raw.isBlank()) null else runCatching { Instant.parse(raw) }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
            .getOrNull()

    private fun removeLegacyPlaintextStore(context: Context) {
        val legacyFile = File(
            File(context.applicationContext.filesDir, "datastore"),
            LEGACY_DATASTORE_NAME
        )
        if (legacyFile.exists()) {
            check(legacyFile.delete()) {
                "Could not remove the legacy unencrypted message-sync metadata file."
            }
        }
    }

    private fun stableKey(localMatchId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(localMatchId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
