package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.ChatMessageDao
import com.example.data.dao.FriendshipRequestDao
import com.example.data.dao.MatchDao
import com.example.data.dao.ProfileDao
import com.example.data.dao.RegisteredAccountDao
import com.example.data.dao.StatusStoryDao
import com.example.data.dao.SwipeDao
import com.example.data.dao.UserPreferencesDao
import com.example.data.model.ChatMessageEntity
import com.example.data.model.FriendshipRequestEntity
import com.example.data.model.MatchEntity
import com.example.data.model.ProfileEntity
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
import com.example.data.dao.ReportDao
import com.example.data.model.BlockEntity
import com.example.data.model.LocalMessage
import com.example.data.model.ReportEntity

import com.example.data.dao.MatchedContactDao
import com.example.data.model.MatchedContactEntity
import com.example.data.dao.LocalBusinessAnalyticsDao
import com.example.data.dao.ApiCredentialRequestDao
import com.example.data.model.LocalBusinessAnalyticsEntity
import com.example.data.model.LocalBusinessAnalyticsEventEntity
import com.example.data.model.ApiCredentialRequestEntity

@Database(
    entities = [
        ProfileEntity::class,
        SwipeEntity::class,
        MatchEntity::class,
        ChatMessageEntity::class,
        LocalMessage::class,
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
        ApiCredentialRequestEntity::class
    ],
    version = 40,
    exportSchema = false
)
abstract class DatingDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun swipeDao(): SwipeDao
    abstract fun matchDao(): MatchDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun messageDao(): MessageDao
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
                val tables = arrayOf(
                    "profiles", "swipes", "matches", "chat_messages", "local_messages",
                    "user_preferences", "registered_accounts", "status_stories",
                    "friendship_requests", "user_contacts", "matched_contacts", "businesses",
                    "business_posts", "channels", "channel_broadcasts", "blocks", "reports"
                )
                for (table in tables) {
                    try {
                        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'")
                        val exists = cursor.count > 0
                        cursor.close()
                        if (exists) {
                            db.execSQL("DELETE FROM $table")
                        }
                    } catch (_: Exception) {}
                }
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

        fun getDatabase(context: Context): DatingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DatingDatabase::class.java,
                    "dating_app.db"
                ).addMigrations(
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                    MIGRATION_28_29,
                    MIGRATION_29_30,
                    MIGRATION_30_31,
                    MIGRATION_31_32,
                    MIGRATION_32_33,
                    MIGRATION_33_34,
                    MIGRATION_36_37,
                    MIGRATION_37_38,
                    MIGRATION_38_39
                )
                 // Intentionally omit MIGRATION_39_40: upgrading an existing v39 store forces
                 // Room to rebuild tables from the current entity definitions if schemas drift.
                 .fallbackToDestructiveMigration()
                 .fallbackToDestructiveMigrationOnDowngrade()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
