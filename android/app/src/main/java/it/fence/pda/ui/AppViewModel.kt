package it.fence.pda.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.fence.pda.FenceApp
import it.fence.pda.core.*
import it.fence.pda.database.*
import it.fence.pda.sync.Scheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID

sealed class Screen {
    object Pair : Screen()
    object Home : Screen()
    object List : Screen()
    data class Bout(val unitId: String) : Screen()
    data class Sign(val unitId: String) : Screen()
    object Messages : Screen()
    object Settings : Screen()
}

data class Status(
    val online: Boolean = false, val error: String? = null, val lastSyncAt: Long = 0, val arbiter: Arbiter? = null,
    val pendingMessages: Int = 0, val authFailed: Boolean = false, val paired: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val fa = app as FenceApp
    val prefs = fa.prefs
    private val dao = fa.db.dao()

    private val stack = ArrayDeque<Screen>()
    val screen = MutableStateFlow<Screen>(if (prefs.paired) Screen.Home else Screen.Pair)
    val status = MutableStateFlow(snapshot())
    val busy = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)

    val units: StateFlow<List<UnitDto>> = dao.observeUnits()
        .map { rows -> rows.mapNotNull { runCatching { AppJson.decodeFromString<UnitDto>(it.json) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val outbox: StateFlow<List<OutboxRow>> = dao.observeOpen().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                if (prefs.paired) refresh()
                delay(5000)
            }
        }
    }

    private fun snapshot(online: Boolean = false, error: String? = null) = Status(
        online, error, prefs.lastSyncAt,
        prefs.arbiterJson.takeIf { it.isNotEmpty() }?.let { runCatching { AppJson.decodeFromString<Arbiter>(it) }.getOrNull() },
        prefs.pendingMessages, prefs.authFailed, prefs.paired
    )

    suspend fun refresh() {
        val r = fa.sync.syncAll()
        status.value = snapshot(r.online, r.error)
    }

    fun syncNow() = viewModelScope.launch { refresh() }

    fun viewModelScopeLaunch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    // ---- navigazione ----
    fun go(s: Screen) { stack.addLast(screen.value); screen.value = s }
    fun root(s: Screen) { stack.clear(); screen.value = s }
    fun back(): Boolean {
        val prev = stack.removeLastOrNull() ?: return false
        screen.value = prev
        return true
    }

    // ---- associazione ----
    fun pair(url: String, fp: String, code: String, onError: (String) -> Unit) = viewModelScope.launch {
        busy.value = true
        when (val r = fa.api.pair(url.trim(), fp.trim(), code.trim().uppercase())) {
            is ApiResult.Ok -> {
                if (r.value.ok) { prefs.savePairing(url.trim(), fp.trim(), r.value.token); status.value = snapshot(); refresh(); root(Screen.Home) }
                else onError(r.value.error ?: "Associazione rifiutata")
            }
            is ApiResult.Err -> onError(r.message)
        }
        busy.value = false
    }

    fun unpair() = viewModelScope.launch {
        fa.api.unpair()
        prefs.clearPairing()
        fa.sync.wipeLocal()
        status.value = snapshot()
        root(Screen.Pair)
    }

    // ---- risultati ----
    fun unitById(id: String) = units.value.firstOrNull { it.id == id }

    fun hasOpenProblem(unitId: String) = outbox.value.firstOrNull { it.unitId == unitId && (it.state == "conflict" || it.state == "rejected") }

    suspend fun saveDraft(unitId: String, json: String) = dao.putDraft(DraftRow(unitId, json, System.currentTimeMillis()))
    suspend fun loadDraft(unitId: String): String? = dao.draft(unitId)?.json

    /** Salva in coda locale e prova subito l'invio. Sostituisce eventuali invii non ancora trasmessi dello stesso incontro. */
    fun submitResult(
        u: UnitDto, cells: List<PairCell>, final: Boolean, notes: String? = null, extra: String? = null,
        overwrite: Boolean = false, reason: String? = null, pin: String? = null, expectedRev: String? = u.rev
    ) = viewModelScope.launch {
        dao.dropOpenResults(u.id)
        val req = ResultReq(UUID.randomUUID().toString(), u.id, final, expectedRev, overwrite, reason, pin, notes, extra, cells)
        dao.putOutbox(OutboxRow(req.txId, "result", u.id, AppJson.encodeToString(req), System.currentTimeMillis()))
        Scheduler.flushSoon(getApplication())
        refresh()
    }

    fun queueSignature(unitId: String, athleteIndex: Int, jpeg: ByteArray) = viewModelScope.launch {
        val req = SignatureReq(UUID.randomUUID().toString(), unitId, athleteIndex, android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
        dao.putOutbox(OutboxRow(req.txId, "signature", unitId, AppJson.encodeToString(req), System.currentTimeMillis()))
        Scheduler.flushSoon(getApplication())
        refresh()
    }

    fun discardProblem(unitId: String) = viewModelScope.launch { dao.dropProblems(unitId); dao.deleteDraft(unitId); fa.prefs.lastRev = ""; refresh() }

    // ---- messaggi ----
    val messages = MutableStateFlow<List<MsgDto>>(emptyList())
    fun loadMessages() = viewModelScope.launch {
        (fa.api.messages() as? ApiResult.Ok)?.let { r ->
            messages.value = r.value
            r.value.filter { !it.letto }.forEach { fa.api.ackMessage(it.prog) }
            prefs.pendingMessages = 0
            status.value = snapshot(status.value.online, status.value.error)
        }
    }
    fun sendMessage(text: String) = viewModelScope.launch {
        val req = MessageReq(UUID.randomUUID().toString(), text)
        dao.putOutbox(OutboxRow(req.txId, "message", "", AppJson.encodeToString(req), System.currentTimeMillis()))
        Scheduler.flushSoon(getApplication())
        refresh()
        toast.value = "Messaggio in invio al tavolo"
    }
}
