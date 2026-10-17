package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.TinkKeyPublicationEntity

/** Encrypted local ledger of server-confirmed Tink public-key publications. */
@Dao
interface TinkKeyPublicationDao {
    @Query("SELECT * FROM tink_key_publications WHERE profileId = :profileId LIMIT 1")
    suspend fun getPublication(profileId: String): TinkKeyPublicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPublication(publication: TinkKeyPublicationEntity)

    @Query("DELETE FROM tink_key_publications WHERE profileId = :profileId")
    suspend fun deletePublication(profileId: String)

    @Query("DELETE FROM tink_key_publications")
    suspend fun clearAll()
}
