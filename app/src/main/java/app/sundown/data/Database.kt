package app.sundown.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import app.sundown.model.Rule
import app.sundown.model.RuleKind
import app.sundown.model.Target
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What happened to one target on one run.
 *
 * Every outcome is recorded, including the ones where nothing was done: a
 * "Closed" that was really a fallback is the failure this log exists to catch.
 */
@Entity(tableName = "log", indices = [Index("at")])
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** Groups the entries of one run, so the log can show them together. */
    val runId: Long,
    val ruleName: String,
    val packageName: String,
    val label: String,
    val activity: String? = null,
    val outcome: Outcome,
    val detail: String = "",
)

enum class Outcome {
    /** Force stop was pressed and the button went grey afterwards. */
    ForceStopped,
    /** The Force stop button was already grey: nothing of the app was running. */
    NotRunning,
    /** A chosen screen was on top and Back took it away. */
    ScreenClosed,
    /** A chosen screen was not on top, so there was nothing to close. */
    ScreenNotOpen,
    /** Only background processes were killed — the app may still be alive. */
    BackgroundKilled,
    /** The phone was locked; the full close waits for the next unlock. */
    Deferred,
    Failed,
    ;

    val succeeded: Boolean get() = this == ForceStopped || this == NotRunning || this == ScreenClosed || this == ScreenNotOpen
}

/** A screen seen on top while the service was on, offered in the screen picker. */
@Entity(tableName = "seen_screens", primaryKeys = ["packageName", "activity"])
data class SeenScreen(
    val packageName: String,
    val activity: String,
    val lastSeen: Long,
)

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun targetsToJson(v: List<Target>): String = json.encodeToString(v)
    @TypeConverter fun targetsFromJson(v: String): List<Target> = json.decodeFromString(v)
    @TypeConverter fun kindToString(v: RuleKind): String = v.name
    @TypeConverter fun kindFromString(v: String): RuleKind = RuleKind.valueOf(v)
    @TypeConverter fun outcomeToString(v: Outcome): String = v.name
    @TypeConverter fun outcomeFromString(v: String): Outcome = Outcome.valueOf(v)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY kind DESC, minuteOfDay, id")
    fun observeAll(): Flow<List<Rule>>

    @Query("SELECT * FROM rules")
    suspend fun all(): List<Rule>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): Rule?

    @Upsert
    suspend fun upsert(rule: Rule): Long

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM log ORDER BY at DESC, id DESC LIMIT 500")
    fun observeRecent(): Flow<List<LogEntry>>

    @Insert
    suspend fun insert(entries: List<LogEntry>)

    @Query("DELETE FROM log WHERE at < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM log")
    suspend fun clear()
}

@Dao
interface SeenDao {
    @Query("SELECT * FROM seen_screens ORDER BY lastSeen DESC LIMIT 300")
    fun observe(): Flow<List<SeenScreen>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: SeenScreen)
}

@Database(entities = [Rule::class, LogEntry::class, SeenScreen::class], version = 1)
@TypeConverters(Converters::class)
abstract class SundownDb : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun log(): LogDao
    abstract fun seen(): SeenDao

    companion object {
        fun build(context: Context): SundownDb =
            Room.databaseBuilder(context, SundownDb::class.java, "sundown.db").build()
    }
}
