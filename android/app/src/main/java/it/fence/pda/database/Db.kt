package it.fence.pda.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "units")
data class UnitRow(@PrimaryKey val id: String, val json: String, val status: String, val rev: String, val sortKey: String)

/** Coda di trasmissione persistente. state: pending | error | conflict | rejected | sent */
@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey val txId: String, val kind: String, val unitId: String, val payload: String,
    val createdAt: Long, val attempts: Int = 0, val state: String = "pending", val lastError: String? = null
)

@Entity(tableName = "drafts")
data class DraftRow(@PrimaryKey val unitId: String, val json: String, val updatedAt: Long)

@Dao
interface FenceDao {
    @Query("SELECT * FROM units ORDER BY sortKey") fun observeUnits(): Flow<List<UnitRow>>
    @Query("SELECT * FROM units WHERE id = :id") suspend fun unit(id: String): UnitRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertUnits(rows: List<UnitRow>)
    @Query("DELETE FROM units WHERE id NOT IN (:ids)") suspend fun deleteMissing(ids: List<String>)
    @Query("DELETE FROM units") suspend fun clearUnits()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutbox(o: OutboxRow)
    @Query("SELECT * FROM outbox WHERE state IN ('pending','error') ORDER BY createdAt") suspend fun sendable(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE state != 'sent' ORDER BY createdAt DESC") fun observeOpen(): Flow<List<OutboxRow>>
    @Query("UPDATE outbox SET state = :s, attempts = attempts + 1, lastError = :e WHERE txId = :id")
    suspend fun mark(id: String, s: String, e: String?)
    @Query("DELETE FROM outbox WHERE unitId = :unitId AND kind = 'result' AND state IN ('pending','error','conflict','rejected')")
    suspend fun dropOpenResults(unitId: String)
    @Query("DELETE FROM outbox WHERE unitId = :unitId AND state IN ('conflict','rejected')")
    suspend fun dropProblems(unitId: String)
    @Query("DELETE FROM outbox WHERE state = 'sent' AND createdAt < :before") suspend fun purgeSent(before: Long)
    @Query("DELETE FROM outbox") suspend fun clearOutbox()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(d: DraftRow)
    @Query("SELECT * FROM drafts WHERE unitId = :id") suspend fun draft(id: String): DraftRow?
    @Query("DELETE FROM drafts WHERE unitId = :id") suspend fun deleteDraft(id: String)
    @Query("DELETE FROM drafts") suspend fun clearDrafts()
}

@Database(entities = [UnitRow::class, OutboxRow::class, DraftRow::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): FenceDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "fence_pda.db").build().also { inst = it }
        }
    }
}
