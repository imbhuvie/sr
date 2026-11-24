package com.example.screenreader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CaptureDao {
    @Insert suspend fun insert(record: CaptureRecord): Long
    @Query("SELECT * FROM capture_records ORDER BY id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<CaptureRecord>
    @Query("SELECT COUNT(*) FROM capture_records")
    suspend fun count(): Int
}
