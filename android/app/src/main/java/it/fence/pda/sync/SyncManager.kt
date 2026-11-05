package it.fence.pda.sync

import android.content.Context
import android.os.BatteryManager
import androidx.work.*
import it.fence.pda.BuildConfig
import it.fence.pda.core.*
import it.fence.pda.database.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

data class SyncReport(val online: Boolean, val error: String? = null, val pending: Int = 0)

/**
 * Sincronizzazione bidirezionale:
 *  1) invia la coda locale in ordine (idempotente per txId; si ferma al primo errore di rete per non perdere l'ordine);
 *  2) scarica le assegnazioni solo se la revisione sul gateway e' cambiata (sync incrementale).
 */
class SyncManager(private val ctx: Context, private val prefs: Prefs, private val db: AppDb, private val api: Api) {
    private val mutex = Mutex()
    private val dao get() = db.dao()

    suspend fun syncAll(): SyncReport = mutex.withLock {
        if (!prefs.paired || prefs.authFailed) return SyncReport(false, if (prefs.authFailed) "Dispositivo revocato o non autorizzato" else null)
        val flushErr = flush()
        val pull = pull()
        val pending = dao.sendable().size
        if (pull == null) { heartbeat(pending) }
        SyncReport(pull == null, pull ?: flushErr, pending)
    }

    /** Restituisce un messaggio di errore se la trasmissione e' interrotta, null se la coda e' stata svuotata. */
    private suspend fun flush(): String? {
        for (item in dao.sendable()) {
            when (item.kind) {
                "result" -> {
                    val req = AppJson.decodeFromString<ResultReq>(item.payload)
                    when (val r = api.postResult(req)) {
                        is ApiResult.Ok -> { applyServerUnit(r.value.unit); dao.mark(item.txId, "sent", null); dao.deleteDraft(item.unitId) }
                        is ApiResult.Err -> {
                            val resp = r.body?.let { runCatching { AppJson.decodeFromString<ResultResp>(it) }.getOrNull() }
                            when (r.code) {
                                409 -> { applyServerUnit(resp?.unit); dao.mark(item.txId, "conflict", r.message) }
                                401 -> return r.message
                                403, 422 -> dao.mark(item.txId, "rejected", r.message)
                                else -> { dao.mark(item.txId, "error", r.message); return r.message }
                            }
                        }
                    }
                }
                "signature" -> {
                    val req = AppJson.decodeFromString<SignatureReq>(item.payload)
                    when (val r = api.postSignature(req)) {
                        is ApiResult.Ok -> { applyServerUnit(r.value.unit); dao.mark(item.txId, "sent", null) }
                        is ApiResult.Err -> when (r.code) {
                            401 -> return r.message
                            403, 422 -> dao.mark(item.txId, "rejected", r.message)
                            else -> { dao.mark(item.txId, "error", r.message); return r.message }
                        }
                    }
                }
                "message" -> {
                    val req = AppJson.decodeFromString<MessageReq>(item.payload)
                    when (val r = api.sendMessage(req)) {
                        is ApiResult.Ok -> dao.mark(item.txId, "sent", null)
                        is ApiResult.Err -> if (r.code == 401) return r.message else { dao.mark(item.txId, "error", r.message); return r.message }
                    }
                }
            }
        }
        dao.purgeSent(System.currentTimeMillis() - 24 * 3600 * 1000L)
        return null
    }

    private suspend fun applyServerUnit(u: UnitDto?) {
        if (u == null) return
        dao.upsertUnits(listOf(u.toRow()))
    }

    /** null = ok; altrimenti messaggio di errore. */
    private suspend fun pull(): String? {
        return when (val r = api.sync(prefs.lastRev)) {
            is ApiResult.Ok -> {
                val s = r.value
                prefs.lastSyncAt = System.currentTimeMillis()
                if (s.changed) {
                    dao.upsertUnits(s.units.map { it.toRow() })
                    dao.deleteMissing(s.units.map { it.id })
                    prefs.lastRev = s.rev
                    prefs.arbiterJson = s.arbiter?.let { AppJson.encodeToString(it) } ?: ""
                    prefs.pendingMessages = s.pendingMessages
                }
                null
            }
            is ApiResult.Err -> r.message
        }
    }

    private suspend fun heartbeat(pending: Int) {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        api.heartbeat(HeartbeatReq(BuildConfig.VERSION_NAME, level, pending))
    }

    /** Annulla tutto lo stato locale (dopo lo scollegamento). */
    suspend fun wipeLocal() { dao.clearUnits(); dao.clearOutbox(); dao.clearDrafts() }
}

fun UnitDto.toRow() = UnitRow(id, AppJson.encodeToString(this), status, rev, "%03d.%d.%03d.%04d.%04d.%05d".format(flags, mf, cat, girone, ed, ordine))

class FlushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as it.fence.pda.FenceApp
        val rep = app.sync.syncAll()
        return if (rep.pending > 0 && !rep.online) Result.retry() else Result.success()
    }
}

object Scheduler {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Rete di sicurezza: sincronizza anche ad app chiusa. */
    fun periodic(ctx: Context) {
        val w = PeriodicWorkRequestBuilder<FlushWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("fence-periodic", ExistingPeriodicWorkPolicy.KEEP, w)
    }

    /** Alla conferma di un risultato: parte appena c'e' rete, anche se ora il Wi-Fi e' assente. */
    fun flushSoon(ctx: Context) {
        val w = OneTimeWorkRequestBuilder<FlushWorker>().setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("fence-flush", ExistingWorkPolicy.REPLACE, w)
    }
}
