package com.example.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DatingDatabase
import com.example.data.model.ProfileEntity
import com.example.util.ReturningAccountProfilePersistence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class ReturningAccountProfilePersistenceRoomTest {
    private lateinit var database: DatingDatabase

    private val canonicalId = "a1b2c3d4e5f60708"
    private val legacyId = "9876543210"
    private val phone = "+919876543210"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DatingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun profile(id: String, name: String, bio: String, occupation: String) = ProfileEntity(
        id = id,
        name = name,
        age = 32,
        bio = bio,
        city = "Bengaluru",
        occupation = occupation,
        phoneNumber = phone
    )

    @Test
    fun recoveredLegacyProfileReplacesCanonicalStubAndLeavesExactlyOneSelfRow() = runBlocking {
        database.profileDao().insertProfile(
            profile(canonicalId, "Truecaller Member", "Hey there! Secure with VibeSync E2EE.", "Member")
        )
        database.profileDao().insertProfile(
            profile(legacyId, "Returning Member", "Long saved profile bio", "Engineer")
        )

        val recovered = ReturningAccountProfilePersistence.persistCanonicalProfile(
            database = database,
            recoveredProfile = profile(legacyId, "Returning Member", "Long saved profile bio", "Engineer"),
            canonicalProfileId = canonicalId,
            verifiedPhone = phone
        )

        val rows = database.profileDao().getAllProfilesSync()
        assertEquals(1, rows.count { it.id.equals(canonicalId, ignoreCase = true) })
        assertEquals(0, rows.count { it.id == legacyId })
        assertEquals(1, rows.size)
        assertEquals(canonicalId, recovered.id)
        assertEquals("Returning Member", rows.single().name)
        assertEquals("Long saved profile bio", rows.single().bio)
        assertEquals("Engineer", rows.single().occupation)
    }

    @Test
    fun repeatedRecoveryIsIdempotentAndDoesNotCreateDuplicateCanonicalRows() = runBlocking {
        val recovered = profile(canonicalId, "Returning Member", "Long saved profile bio", "Engineer")

        repeat(3) {
            ReturningAccountProfilePersistence.persistCanonicalProfile(
                database = database,
                recoveredProfile = recovered,
                canonicalProfileId = canonicalId,
                verifiedPhone = phone
            )
        }

        val rows = database.profileDao().getAllProfilesSync()
        assertEquals(1, rows.size)
        assertTrue(rows.single().id == canonicalId)
        assertEquals("Long saved profile bio", rows.single().bio)
    }
}
