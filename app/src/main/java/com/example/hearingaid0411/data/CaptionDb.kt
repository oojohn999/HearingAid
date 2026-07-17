package com.example.hearingaid0411.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 一個對話場次：同一天內按開始時間編號（第幾個對話） */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,      // "2026-07-17"
    val seqInDay: Int,     // 當日第幾個對話（1 起算）
    val startedAt: Long,
    val endedAt: Long,
)

/** 一句字幕，屬於某個場次；isSelf = 聲紋判定為「自己說的」 */
@Entity(tableName = "captions")
data class CaptionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val timeMillis: Long,
    val isSelf: Boolean = false,
)

data class DateSummary(val date: String, val sessionCount: Int)

data class SessionSummary(
    val id: Long,
    val seqInDay: Int,
    val startedAt: Long,
    val endedAt: Long,
    val sentenceCount: Int,
    val preview: String?,
)

@Dao
interface CaptionDao {

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun updateSessionEnd(id: Long, endedAt: Long)

    @Insert
    suspend fun insertCaption(caption: CaptionRow)

    @Query("SELECT COUNT(*) FROM sessions WHERE date = :date")
    suspend fun sessionCountOn(date: String): Int

    @Query("SELECT date, COUNT(*) AS sessionCount FROM sessions GROUP BY date ORDER BY date DESC")
    fun dates(): Flow<List<DateSummary>>

    @Query(
        "SELECT s.id AS id, s.seqInDay AS seqInDay, s.startedAt AS startedAt, s.endedAt AS endedAt, " +
            "(SELECT COUNT(*) FROM captions WHERE sessionId = s.id) AS sentenceCount, " +
            "(SELECT text FROM captions WHERE sessionId = s.id ORDER BY timeMillis LIMIT 1) AS preview " +
            "FROM sessions s WHERE s.date = :date ORDER BY s.seqInDay"
    )
    fun sessionsOn(date: String): Flow<List<SessionSummary>>

    @Query("SELECT * FROM captions WHERE sessionId = :sessionId ORDER BY timeMillis")
    fun captionsOf(sessionId: Long): Flow<List<CaptionRow>>

    @Query("DELETE FROM captions WHERE sessionId IN (SELECT id FROM sessions WHERE date < :minDate)")
    suspend fun deleteCaptionsBefore(minDate: String)

    @Query("DELETE FROM sessions WHERE date < :minDate")
    suspend fun deleteSessionsBefore(minDate: String)
}

@Database(entities = [SessionEntity::class, CaptionRow::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CaptionDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captions ADD COLUMN isSelf INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "captions.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
