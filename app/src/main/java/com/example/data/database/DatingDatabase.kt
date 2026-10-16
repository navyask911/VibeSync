package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.ChatMessageDao
import com.example.data.dao.ExpiredChatMessageDao
import com.example.data.dao.FriendshipRequestDao
import com.example.data.dao.MatchDao
import com.example.data.dao.ProfileDao
import com.example.data.dao.RegisteredAccountDao
import com.example.data.dao.StatusStoryDao
import com.example.data.dao.SwipeDao
import com.example.data.dao.UserPreferencesDao
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ExpiredChatMessageEntity
import com.example.data.model.FriendshipRequestEntity
import com.example.data.model.MatchEntity
import com.example.data.model.ProfileEntity
import com.example.data.model.PeerProfileKeyEntity
import com.example.data.model.TinkKeyPublicationEntity
import com.example.data.model.PeerKeyChangeEntity
import com.example.data.model.RegisteredAccountEntity
import com.example.data.model.StatusStoryEntity
import com.example.data.model.SwipeEntity
import com.example.data.model.UserPreferencesEntity

import com.example.data.dao.BusinessDao
import com.example.data.dao.ChannelDao
import com.example.data.dao.UserContactDao
import com.example.data.model.BusinessEntity
import com.example.data.model.BusinessPostEntity
import com.example.data.model.BusinessReviewEntity
import com.example.data.model.ChannelBroadcastEntity
import com.example.data.model.ChannelEntity
import com.example.data.model.UserContactEntity

import com.example.data.dao.BlockDao
import com.example.data.dao.MessageDao
import com.example.data.dao.MessageSyncWatermarkDao
import com.example.data.dao.ReportDao
import com.example.data.model.BlockEntity
import com.example.data.model.LocalMessage
import com.example.data.model.MessageSyncWatermarkEntity
import com.example.data.model.ReportEntity

import com.example.data.dao.MatchedContactDao
import com.example.data.dao.PeerProfileKeyDao
import com.example.data.dao.TinkKeyPublicationDao
import com.example.data.dao.PeerKeyChangeDao
import com.example.data.model.MatchedContactEntity
import com.example.data.dao.LocalBusinessAnalyticsDao
import com.example.data.dao.ApiCredentialRequestDao
import com.example.data.model.LocalBusinessAnalyticsEntity
import com.example.data.model.LocalBusinessAnalyticsEventEntity
import com.example.data.model.ApiCredentialRequestEntity
import com.example.data.model.ConversationNotificationPreferenceEntity
import com.example.data.dao.ConversationNotificationPreferencesDao

@Database(
    entities = [
        ProfileEntity::class,
        PeerProfileKeyEntity::class,
        TinkKeyPublicationEntity::class,
        PeerKeyChangeEntity::class,
        SwipeEntity::class,
        MatchEntity::class,
        ChatMessageEntity::class,
        ExpiredChatMessageEntity::class,
        LocalMessage::class,
        MessageSyncWatermarkEntity::class,
        UserPreferencesEntity::class,
        RegisteredAccountEntity::class,
        StatusStoryEntity::class,
        FriendshipRequestEntity::class,
        UserContactEntity::class,
        MatchedContactEntity::class,
        BusinessEntity::class,
        BusinessPostEntity::class,
        BusinessReviewEntity::class,
        ChannelEntity::class,
        ChannelBroadcastEntity::class,
        BlockEntity::class,
        ReportEntity::class,
        LocalBusinessAnalyticsEntity::class,
        LocalBusinessAnalyticsEventEntity::class,
        ApiCredentialRequestEntity::class,
        ConversationNotificationPreferenceEntity::class
    ],
    version = 62,
    exportSchema = false
)
abstract class DatingDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun peerProfileKeyDao(): PeerProfileKeyDao
    abstract fun tinkKeyPublicationDao(): TinkKeyPublicationDao
    abstract fun peerKeyChangeDao(): PeerKeyChangeDao
    abstract fun swipeDao(): SwipeDao
    abstract fun matchDao(): MatchDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun expiredChatMessageDao(): ExpiredChatMessageDao
    abstract fun messageDao(): MessageDao
    abstract fun messageSyncWatermarkDao(): MessageSyncWatermarkDao
    abstract fun userPreferencesDao(): UserPreferencesDao
    abstract fun registeredAccountDao(): RegisteredAccountDao
    abstract fun statusStoryDao(): StatusStoryDao
    abstract fun friendshipRequestDao(): FriendshipRequestDao
    abstract fun userContactDao(): UserContactDao
    abstract fun matchedContactDao(): MatchedContactDao
    abstract fun businessDao(): BusinessDao
    abstract fun channelDao(): ChannelDao
    abstract fun blockDao(): BlockDao
    abstract fun reportDao(): ReportDao
    abstract fun localBusinessAnalyticsDao(): LocalBusinessAnalyticsDao
    abstract fun apiCredentialRequestDao(): ApiCredentialRequestDao
    abstract fun conversationNotificationPreferencesDao(): ConversationNotificationPreferencesDao

    companion object {
        @Volatile
        private var INSTANCE: DatingDatabase? = null

        /**
         * Makes schema upgrades safe for installs whose recorded version and actual columns differ.
         * Room's destructive fallback does not recover from a migration that executes an invalid ALTER.
         */
        private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
            return db.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'"
            ).use { cursor -> cursor.moveToFirst() }
        }

        private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            if (!tableExists(db, table)) return false
            return db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                        found = true
                        break
                    }
                }
                found
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            if (tableExists(db, table) && !columnExists(db, table, column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "user_preferences", "soundAlertsEnabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "user_preferences", "vibrationAlertsEnabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "user_preferences", "messageSoundTone", "TEXT NOT NULL DEFAULT 'DEFAULT'")
                addColumnIfMissing(db, "user_preferences", "matchSoundTone", "TEXT NOT NULL DEFAULT 'CELEBRATION'")
                addColumnIfMissing(db, "user_preferences", "callRingtone", "TEXT NOT NULL DEFAULT 'STANDARD_RING'")
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 28 compatibility migration
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `local_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `senderUid` TEXT NOT NULL,
                            `textContent` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `isSentByMe` INTEGER NOT NULL,
                            `matchId` TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve message and profile history. The historical table wipe is disabled.
            }
        }

        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `matched_contacts` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `contactName` TEXT NOT NULL DEFAULT '',
                            `phoneNumber` TEXT NOT NULL DEFAULT '',
                            `phoneHash` TEXT NOT NULL DEFAULT '',
                            `vibeSyncUserId` TEXT NOT NULL DEFAULT '',
                            `isOnVibeSync` INTEGER NOT NULL DEFAULT 0,
                            `matchedAt` INTEGER NOT NULL DEFAULT 0,
                            `avatarEmoji` TEXT NOT NULL DEFAULT '✨',
                            `photoUrl` TEXT NOT NULL DEFAULT '',
                            `statusTagline` TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_messages_messageId` ON `chat_messages` (`messageId`)")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "businesses", "verificationTier", "TEXT NOT NULL DEFAULT 'STANDARD'")
                addColumnIfMissing(db, "businesses", "walletPoints", "INTEGER NOT NULL DEFAULT 500")
                addColumnIfMissing(db, "businesses", "isListingPaid", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "businesses", "listingPaymentTxnId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "businesses", "whatsappApiEnabled", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "businesses", "whatsappBusinessNumber", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "businesses", "whatsappWabaId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "businesses", "whatsappApiKey", "TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `business_reviews` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `businessId` TEXT NOT NULL DEFAULT '',
                        `userId` TEXT NOT NULL DEFAULT '',
                        `userName` TEXT NOT NULL DEFAULT '',
                        `userAvatarEmoji` TEXT NOT NULL DEFAULT '✨',
                        `rating` REAL NOT NULL DEFAULT 5.0,
                        `reviewText` TEXT NOT NULL DEFAULT '',
                        `isGpsVerifiedVisit` INTEGER NOT NULL DEFAULT 1,
                        `checkInDistanceMeters` REAL NOT NULL DEFAULT 35.0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "businesses", "photoGalleryJson", "TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db,
                    "user_preferences",
                    "lastFriendRequestResetTimestamp",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfMissing(
                    db,
                    "status_stories",
                    "backgroundMusicUrl",
                    "TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "profiles", "avatarCacheVersion", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "user_preferences", "avatarCacheVersion", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "profiles", "latitude", "REAL NOT NULL DEFAULT 0.0")
                addColumnIfMissing(db, "profiles", "longitude", "REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "deliveryState", "TEXT NOT NULL DEFAULT 'SENT_TO_SERVER'")
                addColumnIfMissing(db, "chat_messages", "syncAttempts", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "chat_messages", "lastSyncError", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "chat_messages", "lastSyncAttemptAt", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db,
                    "chat_messages",
                    "outboxOwnerProfileId",
                    "TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "senderDisplayName", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "chat_messages", "senderPhotoUri", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "matches", "localContactName", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "matches", "localPhotoUri", "TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "serverCreatedAt", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "chat_messages", "deliveryAckPending", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "profiles", "dateOfBirth", "TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "local_messages", "remoteMessageId", "TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_local_messages_remoteMessageId " +
                        "ON local_messages (remoteMessageId)"
                )
            }
        }

        private val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS message_sync_watermarks (" +
                        "localMatchId TEXT NOT NULL PRIMARY KEY, " +
                        "createdAtMs INTEGER NOT NULL, " +
                        "createdAtIso TEXT NOT NULL, " +
                        "messageId TEXT NOT NULL)"
                )
            }
        }

        private val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "recipient_id", "TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "reactions_json", "TEXT NOT NULL DEFAULT '{}'")
                addColumnIfMissing(db, "chat_messages", "is_deleted", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "matches", "isAnonymousPeer", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "expiresAt", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "matches", "disappearingTimerSeconds", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "user_preferences", "readReceiptsEnabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "user_preferences", "typingIndicatorsEnabled", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS expired_chat_messages (" +
                        "messageId TEXT NOT NULL PRIMARY KEY, " +
                        "expiredAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS peer_profile_keys (" +
                        "peer_id TEXT NOT NULL PRIMARY KEY, " +
                        "profile_key BLOB NOT NULL, " +
                        "profile_version INTEGER NOT NULL, " +
                        "cached_path TEXT)"
                )
            }
        }

        private val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "businesses", "isPublished", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "businesses", "isCuratedLandmark", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "businesses", "landmarkCategory", "TEXT NOT NULL DEFAULT 'LOCAL_FAVORITE'")
            }
        }

        private val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_notification_preferences (" +
                        "conversationId TEXT NOT NULL PRIMARY KEY, " +
                        "mutedUntilEpochMillis INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        private val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db,
                    "user_preferences",
                    "screenshotSecurityEnabled",
                    "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "forward_count", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tink_key_publications` (" +
                        "`profileId` TEXT NOT NULL PRIMARY KEY, " +
                        "`keyId` TEXT NOT NULL, " +
                        "`keyFingerprint` TEXT NOT NULL, " +
                        "`confirmedAtEpochMillis` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peer_key_changes` (" +
                        "`profileId` TEXT NOT NULL PRIMARY KEY, " +
                        "`previousKeyId` TEXT NOT NULL, " +
                        "`newKeyId` TEXT NOT NULL, " +
                        "`detectedAtEpochMillis` INTEGER NOT NULL, " +
                        "`acknowledged` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        private val MIGRATION_61_62 = object : Migration(61, 62) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `encrypted_payload` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `decrypt_state` TEXT NOT NULL DEFAULT 'OK'")
                db.execSQL("ALTER TABLE `local_messages` ADD COLUMN `encrypted_payload` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `local_messages` ADD COLUMN `decrypt_state` TEXT NOT NULL DEFAULT 'OK'")
                db.execSQL("UPDATE `chat_messages` SET `encrypted_payload` = `text`, `text` = '', `decrypt_state` = 'FAILED_RETRYABLE' WHERE `text` LIKE 'TINK_ENC:%'")
                db.execSQL("UPDATE `local_messages` SET `encrypted_payload` = `textContent`, `textContent` = '', `decrypt_state` = 'FAILED_RETRYABLE' WHERE `textContent` LIKE 'TINK_ENC:%'")
                db.execSQL("UPDATE `chat_messages` SET `text` = '', `decrypt_state` = CASE WHEN `text` = 'Cannot decrypt this message: the required recipient key changed and is no longer available on this device.' THEN 'FAILED_KEY_MISSING' ELSE 'FAILED_RETRYABLE' END WHERE `text` IN ('Message could not be decrypted - keys updated', 'Cannot decrypt this message: the required recipient key changed and is no longer available on this device.')")
                db.execSQL("UPDATE `local_messages` SET `textContent` = '', `decrypt_state` = CASE WHEN `textContent` = 'Cannot decrypt this message: the required recipient key changed and is no longer available on this device.' THEN 'FAILED_KEY_MISSING' ELSE 'FAILED_RETRYABLE' END WHERE `textContent` IN ('Message could not be decrypted - keys updated', 'Cannot decrypt this message: the required recipient key changed and is no longer available on this device.')")
            }
        }

        fun getDatabase(context: Context): DatingDatabase {
            val appContext = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val passphrase = DatabaseKeyManager.getOrCreatePassphrase(appContext)
                    try {
                        EncryptedDatabaseBootstrap.prepare(appContext, passphrase)
                        // The support factory retains its own copy so it can reopen the database.
                        val factoryPassphrase = passphrase.copyOf()
                        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                            factoryPassphrase,
                            DatabaseKeyManager.createSqlCipherHook(),
                            false
                        )
                        val instance = Room.databaseBuilder(
                            appContext,
                            DatingDatabase::class.java,
                            "dating_app.db"
                        )
                            .openHelperFactory(factory)
                            .addMigrations(
                                MIGRATION_26_27,
                                MIGRATION_27_28,
                                MIGRATION_28_29,
                                MIGRATION_29_30,
                                MIGRATION_30_31,
                                MIGRATION_31_32,
                                MIGRATION_32_33,
                                MIGRATION_33_34,
                                MIGRATION_34_35,
                                MIGRATION_35_36,
                                MIGRATION_36_37,
                                MIGRATION_37_38,
                                MIGRATION_38_39,
                                MIGRATION_39_40,
                                MIGRATION_40_41,
                                MIGRATION_41_42,
                                MIGRATION_42_43,
                                MIGRATION_43_44,
                                MIGRATION_44_45,
                                MIGRATION_45_46,
                                MIGRATION_46_47,
                                MIGRATION_47_48,
                                MIGRATION_48_49,
                                MIGRATION_49_50,
                                MIGRATION_50_51,
                                MIGRATION_51_52,
                                MIGRATION_52_53,
                                MIGRATION_53_54,
                                MIGRATION_54_55,
                                MIGRATION_55_56,
                                MIGRATION_56_57,
                                MIGRATION_57_58,
                                MIGRATION_58_59,
                                MIGRATION_59_60,
                                MIGRATION_60_61,
                                MIGRATION_61_62
                            )
                            // Fail closed instead of recreating a database that contains local history.
                            .build()
                        INSTANCE = instance
                        instance
                    } finally {
                        passphrase.fill(0)
                    }
                }
            }
        }
    }
}
