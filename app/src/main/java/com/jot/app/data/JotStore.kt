package com.jot.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

enum class ActionKind { ALARM, CALENDAR, REMINDER, MESSAGE }
enum class ActionStatus { PENDING, SCHEDULED, HANDED_OFF, CANCELLED }

@Entity(tableName = "notes")
data class NoteRecord(
    @PrimaryKey val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val draft: Boolean = false,
)

@Entity(
    tableName = "actions",
    foreignKeys = [ForeignKey(
        entity = NoteRecord::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class ActionRecord(
    @PrimaryKey val id: String,
    val noteId: String,
    val kind: ActionKind,
    val status: ActionStatus = ActionStatus.PENDING,
    val atMillis: Long? = null,
    val endMillis: Long? = null,
    val body: String? = null,
    val recipient: String? = null,
    val exact: Boolean = true,
)

data class NoteItem(
    @Embedded val note: NoteRecord,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val actions: List<ActionRecord>,
)

@Entity(
    tableName = "reminder_receipts",
    foreignKeys = [ForeignKey(
        entity = ActionRecord::class,
        parentColumns = ["id"],
        childColumns = ["actionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class ReminderReceipt(@PrimaryKey val actionId: String)

// SQLite's default trim only strips spaces. Match Kotlin's whitespace check for captured text.
private const val NONBLANK_NOTE_TEXT =
    "trim(text, char(9,10,11,12,13,28,29,30,31,32,160,5760," +
        "8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)) <> ''"

@Dao
internal interface JotDao {
    @Transaction
    @Query("SELECT * FROM notes WHERE draft = 0 OR $NONBLANK_NOTE_TEXT ORDER BY updatedAt DESC, id")
    fun observeNotes(): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteRecord?

    @Query("SELECT * FROM notes WHERE draft = 1 AND $NONBLANK_NOTE_TEXT ORDER BY updatedAt DESC, id LIMIT 1")
    suspend fun draft(): NoteRecord?

    @Query("SELECT * FROM actions WHERE noteId = :noteId ORDER BY rowid")
    suspend fun actions(noteId: String): List<ActionRecord>

    @Query("SELECT * FROM actions WHERE id = :id")
    suspend fun action(id: String): ActionRecord?

    @Upsert
    suspend fun saveNote(note: NoteRecord)

    @Upsert
    suspend fun saveAction(action: ActionRecord)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("SELECT * FROM actions WHERE kind = 'REMINDER' AND status = 'SCHEDULED' ORDER BY atMillis")
    suspend fun allScheduledReminders(): List<ActionRecord>

    @Query("""
        SELECT actions.* FROM actions
        LEFT JOIN reminder_receipts ON actions.id = reminder_receipts.actionId
        WHERE actions.kind = 'REMINDER' AND actions.status = 'SCHEDULED'
            AND reminder_receipts.actionId IS NULL
        ORDER BY actions.atMillis
    """)
    suspend fun undeliveredReminders(): List<ActionRecord>

    @Query("SELECT EXISTS(SELECT 1 FROM reminder_receipts WHERE actionId = :id)")
    suspend fun wasDelivered(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordDelivery(receipt: ReminderReceipt)

    @Query("DELETE FROM reminder_receipts WHERE actionId = :id")
    suspend fun clearDelivery(id: String)

    @Transaction
    suspend fun saveScheduledReminder(action: ActionRecord) {
        saveAction(action)
        clearDelivery(action.id)
    }
}

@Database(
    entities = [NoteRecord::class, ActionRecord::class, ReminderReceipt::class],
    version = 1,
    exportSchema = false,
)
internal abstract class JotDatabase : RoomDatabase() {
    abstract fun dao(): JotDao
}

class JotStore private constructor(private val dao: JotDao) {
    fun observeNotes(): Flow<List<NoteItem>> = dao.observeNotes()
    suspend fun note(id: String): NoteRecord? = dao.note(id)
    suspend fun draft(): NoteRecord? = dao.draft()
    suspend fun actions(noteId: String): List<ActionRecord> = dao.actions(noteId)
    suspend fun action(id: String): ActionRecord? = dao.action(id)
    suspend fun saveNote(note: NoteRecord) = dao.saveNote(note)
    suspend fun saveAction(action: ActionRecord) = dao.saveAction(action)
    suspend fun deleteNote(id: String) = dao.deleteNote(id)
    suspend fun allScheduledReminders(): List<ActionRecord> = dao.allScheduledReminders()
    internal suspend fun undeliveredReminders(): List<ActionRecord> = dao.undeliveredReminders()
    internal suspend fun wasDelivered(id: String): Boolean = dao.wasDelivered(id)
    internal suspend fun recordDelivery(id: String) = dao.recordDelivery(ReminderReceipt(id))
    internal suspend fun saveScheduledReminder(action: ActionRecord) = dao.saveScheduledReminder(action)

    companion object {
        @Volatile private var instance: JotStore? = null

        fun get(context: Context): JotStore = instance ?: synchronized(this) {
            instance ?: JotStore(
                Room.databaseBuilder(context.applicationContext, JotDatabase::class.java, "jot.db")
                    .build()
                    .dao(),
            ).also { instance = it }
        }
    }
}
