package io.github.thisisankit27.voicemail.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class RecordingStatus { RECORDING, RECORDED, EMPTY, FAILED }

/** One message left by a caller (see docs/DATABASE_DESIGN.md §2.1; this is the v1 subset). */
@Entity(
    tableName = "voicemail",
    indices = [Index(value = ["is_deleted", "created_at"]), Index("caller_number"), Index("is_read")],
)
data class Voicemail(
    @PrimaryKey val id: String,
    /** As delivered by Telecom. Null for withheld numbers. */
    @ColumnInfo(name = "caller_number") val callerNumber: String?,
    @ColumnInfo(name = "presentation") val presentation: Int,
    /** Contact name when the message was left (re-resolved live in the UI when possible). */
    @ColumnInfo(name = "caller_name") val callerName: String?,
    /** File name inside filesDir/voicemails, or null if no audio was kept. */
    @ColumnInfo(name = "audio_file") val audioFile: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "ring_ms") val ringMs: Long = 0,
    @ColumnInfo(name = "is_read") val isRead: Boolean = false,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "is_starred") val isStarred: Boolean = false,
    @ColumnInfo(name = "status") val status: RecordingStatus,
    /** Loudest 100 ms window, for "silent message" detection and diagnostics. */
    @ColumnInfo(name = "peak_db") val peakDb: Double = -96.0,
    @ColumnInfo(name = "sim_label") val simLabel: String? = null,
    @ColumnInfo(name = "transcript") val transcript: String? = null,
)

@Dao
interface VoicemailDao {
    @Query("SELECT * FROM voicemail WHERE is_deleted = 0 ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Voicemail>>

    @Query("SELECT * FROM voicemail WHERE id = :id")
    fun observe(id: String): Flow<Voicemail?>

    @Query("SELECT * FROM voicemail WHERE id = :id")
    suspend fun get(id: String): Voicemail?

    @Query("SELECT COUNT(*) FROM voicemail WHERE is_deleted = 0 AND is_read = 0 AND status = 'RECORDED'")
    fun observeUnreadCount(): Flow<Int>

    @Insert
    suspend fun insert(v: Voicemail)

    @Update
    suspend fun update(v: Voicemail)

    @Query("UPDATE voicemail SET is_read = :read WHERE id = :id")
    suspend fun setRead(id: String, read: Boolean)

    @Query("UPDATE voicemail SET is_read = 1 WHERE is_deleted = 0")
    suspend fun markAllRead()

    @Query("UPDATE voicemail SET is_starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("UPDATE voicemail SET is_deleted = :deleted WHERE id = :id")
    suspend fun setDeleted(id: String, deleted: Boolean)

    @Query("SELECT * FROM voicemail WHERE is_deleted = 1")
    suspend fun deleted(): List<Voicemail>

    @Query("DELETE FROM voicemail WHERE id = :id")
    suspend fun purge(id: String)

    /** Rows left in RECORDING by a killed process; the WAV prefix on disk is still valid. */
    @Query("SELECT * FROM voicemail WHERE status = 'RECORDING'")
    suspend fun interrupted(): List<Voicemail>
}

class Converters {
    @TypeConverter fun fromStatus(s: RecordingStatus): String = s.name
    @TypeConverter fun toStatus(s: String): RecordingStatus = RecordingStatus.valueOf(s)
}

@Database(entities = [Voicemail::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voicemails(): VoicemailDao
}
