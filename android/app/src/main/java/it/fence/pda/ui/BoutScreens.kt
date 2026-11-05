package it.fence.pda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fence.pda.core.AppJson
import it.fence.pda.core.Athlete
import kotlinx.serialization.builtins.serializer
import it.fence.pda.core.UnitDto
import it.fence.pda.logic.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

// ---------------------------------------------------------------- dispatcher + conflitti + correzioni
@Composable
fun BoutScreen(vm: AppViewModel, unitId: String) {
    val units by vm.units.collectAsState()
    val outbox by vm.outbox.collectAsState()
    val u = units.firstOrNull { it.id == unitId }
    if (u == null) {
        Column { Header("Incontro", onBack = { vm.back() }); Text("Incontro non piu' assegnato a questo PDA.", Modifier.padding(16.dp)) }
        return
    }
    val problem = outbox.firstOrNull { it.unitId == unitId && (it.state == "conflict" || it.state == "rejected") }
    Column(Modifier.fillMaxSize()) {
        if (problem != null) {
            Column(Modifier.fillMaxWidth().background(Color(0xFFFFE3E3)).padding(12.dp)) {
                Text(if (problem.state == "conflict") "CONFLITTO: ${problem.lastError}" else "RIFIUTATO: ${problem.lastError}", color = Red, fontWeight = FontWeight.Bold)
                Text("Sul gateway c'e' un risultato diverso o non accettato. Il dato di Fence! non e' stato sovrascritto.", fontSize = 13.sp)
                TextButton(onClick = { vm.discardProblem(unitId) }) { Text("Scarta il mio invio e usa i dati di Fence!", color = Red) }
            }
        }
        when {
            u.isPool -> PoolScreen(vm, u)
            u.isTeam -> TeamScreen(vm, u)
            else -> EdScreen(vm, u)
        }
    }
}

/** Dialogo per correggere un risultato gia' trasmesso: PIN del responsabile + motivo, tracciato dal gateway. */
@Composable
fun CorrectionDialog(onDismiss: () -> Unit, onOk: (pin: String, reason: String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correzione autorizzata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Il risultato e' gia' stato contabilizzato. La modifica richiede il PIN del responsabile e viene registrata.", fontSize = 14.sp)
                OutlinedTextField(pin, { pin = it }, label = { Text("PIN responsabile") }, singleLine = true)
                OutlinedTextField(reason, { reason = it }, label = { Text("Motivo") })
            }
        },
        confirmButton = { TextButton(enabled = pin.isNotBlank() && reason.isNotBlank(), onClick = { onOk(pin, reason) }) { Text("Conferma") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun NotesField(notes: String, onChange: (String) -> Unit) =
    OutlinedTextField(notes, onChange, label = { Text("Note / sanzioni (opzionale, vanno al registro del tavolo)") }, modifier = Modifier.fillMaxWidth())

// ---------------------------------------------------------------- Girone
private val mapSer = MapSerializer(String.serializer(), PairResult.serializer())
private fun ks(p: Pair<Int, Int>) = "${p.first}-${p.second}"
private fun unks(s: String) = s.split("-").let { it[0].toInt() to it[1].toInt() }

@Composable
fun PoolScreen(vm: AppViewModel, u: UnitDto) {
    var results by remember(u.id, u.rev) { mutableStateOf(PoolLogic.fromUnit(u)) }
    var loaded by remember(u.id, u.rev) { mutableStateOf(false) }
    var grid by remember { mutableStateOf(vm.prefs.gridMode) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // chiavi (NumFis)
    var showStand by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    val done = u.status == "done"

    // bozza locale: sopravvive a chiusura app e perdita di rete
    LaunchedEffect(u.id, u.rev) {
        vm.loadDraft(u.id)?.let { d ->
            runCatching { AppJson.decodeFromString(mapSer, d).mapKeys { unks(it.key) } }.getOrNull()?.let { if (it.isNotEmpty()) results = PoolLogic.fromUnit(u) + it }
        }
        loaded = true
    }
    fun update(m: Map<Pair<Int, Int>, PairResult>) {
        results = m
        vm.viewModelScopeLaunch { vm.saveDraft(u.id, AppJson.encodeToString(mapSer, m.mapKeys { ks(it.key) })) }
    }

    val n = u.athletes.size
    val missing = PoolLogic.missing(u, results)
    val total = n * (n - 1) / 2

    Column(Modifier.fillMaxSize()) {
        Header(u.title, "${u.subtitle}${if (u.pedana > 0) " · Pedana ${u.pedana}" else ""}", onBack = { vm.back() }) {
            TextButton(onClick = { showStand = true }) { Text("Indici", color = Color.White) }
        }
        if (n > 7) Text("Girone con più di 7 atleti: non supportato dal tracciato PDA di Fence!. Compilare dal programma di gara.", Modifier.padding(16.dp), color = Red)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !grid, onClick = { grid = false; vm.prefs.gridMode = false }, label = { Text("Incontri") })
            FilterChip(selected = grid, onClick = { grid = true; vm.prefs.gridMode = true }, label = { Text("Griglia") })
            Spacer(Modifier.weight(1f))
            Text("${total - missing.size}/$total assalti", fontWeight = FontWeight.SemiBold)
            if (done) Pill("Completato", Green)
        }

        Box(Modifier.weight(1f)) {
            if (!grid) {
                val order = PoolLogic.order(u)
                val nextIdx = order.indexOfFirst { (i, j) -> results[PoolLogic.key(PoolLogic.sideKey(u, i - 1), PoolLogic.sideKey(u, j - 1))]?.isComplete != true }
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(order) { idx, (i, j) ->
                        val ka = PoolLogic.sideKey(u, i - 1); val kb = PoolLogic.sideKey(u, j - 1)
                        val p = results[PoolLogic.key(ka, kb)]
                        val a = u.athletes[i - 1]; val b = u.athletes[j - 1]
                        Card2(onClick = { editing = ka to kb }, modifier = if (idx == nextIdx) Modifier.border(2.dp, Red, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) else Modifier) {
                            Text("Assalto ${idx + 1} / ${order.size}", fontSize = 12.sp, color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$i ${a.cognome}", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (p?.isComplete == true) "${PoolLogic.cellText(p, ka, u.maxTouches)} – ${PoolLogic.cellText(p, kb, u.maxTouches)}" else "VS", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                    color = if (p?.isComplete == true) Green else Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                                Text("$j ${b.cognome}", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            } else {
                PoolGrid(u, results) { ka, kb -> editing = ka to kb }
            }
        }

        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vm.submitResult(u, PoolLogic.toCells(u, results), false); vm.back()
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = results.isNotEmpty() && !done && n <= 7) { Text("Salva parziale") }
            Button(onClick = { confirm = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = missing.isEmpty() && n in 2..7 && loaded) {
                Text(if (done) "Correggi…" else "CONFERMA GIRONE", fontWeight = FontWeight.Bold)
            }
        }
    }

    editing?.let { (ka, kb) ->
        val a = u.athletes.first { PoolLogic.sideKey(u, u.athletes.indexOf(it)) == ka }
        val b = u.athletes.first { PoolLogic.sideKey(u, u.athletes.indexOf(it)) == kb }
        AlertDialog(
            onDismissRequest = { editing = null }, confirmButton = {},
            title = { Text("${a.cognome} – ${b.cognome}") },
            text = {
                PairEditor(a.cognome, b.cognome, ka, kb, results[PoolLogic.key(ka, kb)]?.let { alignPair(it, ka, kb) }, u.maxTouches, true,
                    onSave = { p ->
                        var m = results + (PoolLogic.key(ka, kb) to p.copy(a = PoolLogic.key(ka, kb).first, b = PoolLogic.key(ka, kb).second,
                            ta = if (PoolLogic.key(ka, kb).first == ka) p.ta else p.tb, tb = if (PoolLogic.key(ka, kb).first == ka) p.tb else p.ta))
                        // A/R/E su un atleta vale per tutti i suoi assalti (come nella griglia di Fence!)
                        if (p.isSpecial) m = PoolLogic.applySpecial(u, m, p.specialWho!!, p.specialCode!!)
                        update(m); editing = null
                    },
                    onClear = { update(results - PoolLogic.key(ka, kb)); editing = null }, onCancel = { editing = null })
            }
        )
    }

    if (showStand) AlertDialog(
        onDismissRequest = { showStand = false }, confirmButton = { TextButton(onClick = { showStand = false }) { Text("Chiudi") } },
        title = { Text("Indici provvisori") },
        text = {
            Column {
                Row { Text("Atleta", Modifier.weight(2f), fontWeight = FontWeight.Bold); listOf("V", "V/M", "TS", "TR", "Ind").forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End) } }
                PoolLogic.standings(u, results).sortedWith(compareByDescending<PoolLogic.Standing> { it.ratio }.thenByDescending { it.ind }.thenByDescending { it.ts }).forEach { s ->
                    Row { Text(s.name, Modifier.weight(2f), maxLines = 1); Text("${s.v}", Modifier.weight(1f), textAlign = TextAlign.End); Text("%.2f".format(s.ratio), Modifier.weight(1f), textAlign = TextAlign.End)
                        Text("${s.ts}", Modifier.weight(1f), textAlign = TextAlign.End); Text("${s.tr}", Modifier.weight(1f), textAlign = TextAlign.End); Text("${s.ind}", Modifier.weight(1f), textAlign = TextAlign.End) }
                }
            }
        }
    )

    if (confirm && !done) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Confermare ${u.title}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Tutti i $total assalti sono compilati. Il girone verra' trasmesso a Fence!."); NotesField(notes) { notes = it } } },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                vm.submitResult(u, PoolLogic.toCells(u, results), true, notes.ifBlank { null })
                vm.back(); if (vm.prefs.requireSignature) vm.go(Screen.Sign(u.id))
            }) { Text("Conferma", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } }
    )
    if (confirm && done) CorrectionDialog({ confirm = false }) { pin, reason ->
        confirm = false
        vm.submitResult(u, PoolLogic.toCells(u, results), true, notes.ifBlank { null }, overwrite = true, reason = reason, pin = pin); vm.back()
    }
}

/** Porta il risultato salvato con chiavi ordinate (min,max) nel punto di vista di (ka, kb). */
private fun alignPair(p: PairResult, ka: Int, kb: Int): PairResult =
    if (p.a == ka) p else p.copy(a = ka, b = kb, ta = p.tb, tb = p.ta)

// ---------------------------------------------------------------- Griglia tradizionale a doppia entrata
@Composable
fun PoolGrid(u: UnitDto, results: Map<Pair<Int, Int>, PairResult>, onCell: (Int, Int) -> Unit) {
    val n = u.athletes.size
    val cell = 52.dp
    val nameW = 104.dp
    val hscroll = rememberScrollState()   // stesso stato per intestazione e righe: scorrono insieme
    val stands = PoolLogic.standings(u, results)
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row {
            Box(Modifier.width(nameW).height(cell).border(1.dp, Color.Gray).background(Soft), contentAlignment = Alignment.Center) { Text("Atleta", fontSize = 12.sp) }
            Row(Modifier.horizontalScroll(hscroll)) {
                for (j in 0 until n) HeaderCell("${j + 1}", cell)
                listOf("V", "M", "TS", "TR", "Ind").forEach { HeaderCell(it, cell) }
            }
        }
        for (i in 0 until n) {
            val ki = PoolLogic.sideKey(u, i)
            Row {
                Box(Modifier.width(nameW).height(cell).border(1.dp, Color.Gray).background(Soft).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    Text("${i + 1} ${u.athletes[i].cognome}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                Row(Modifier.horizontalScroll(hscroll)) {
                    for (j in 0 until n) {
                        val kj = PoolLogic.sideKey(u, j)
                        if (i == j) Box(Modifier.size(cell).border(1.dp, Color.Gray).background(Color.Black))   // diagonale nera, non modificabile
                        else {
                            val p = results[PoolLogic.key(ki, kj)]
                            val txt = PoolLogic.cellText(p, ki, u.maxTouches)
                            val bg = when { p == null || !p.isComplete -> Color.White; p.isSpecial -> Color(0xFFFFF0C2); p.winner == ki -> Color(0xFFD7F5E0); else -> Color(0xFFF4F4F4) }
                            Box(Modifier.size(cell).border(1.dp, Color.Gray).background(bg).clickable { onCell(ki, kj) }, contentAlignment = Alignment.Center) {
                                Text(txt, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (p?.winner == ki) Color(0xFF14672F) else Ink)
                            }
                        }
                    }
                    val s = stands[i]
                    listOf("${s.v}", "${s.m}", "${s.ts}", "${s.tr}", "${s.ind}").forEach { Box(Modifier.size(cell).border(1.dp, Color.Gray).background(Soft), contentAlignment = Alignment.Center) { Text(it, fontSize = 15.sp) } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Tocca una cella per inserire o correggere l'assalto: la cella speculare si aggiorna da sola. Scorri orizzontalmente per gli indici.", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun HeaderCell(t: String, size: androidx.compose.ui.unit.Dp) =
    Box(Modifier.size(size).border(1.dp, Color.Gray).background(Soft), contentAlignment = Alignment.Center) { Text(t, fontWeight = FontWeight.Bold) }

// ---------------------------------------------------------------- Eliminazione diretta
@Composable
fun EdScreen(vm: AppViewModel, u: UnitDto) {
    val a = u.athletes.getOrNull(0); val b = u.athletes.getOrNull(1)
    if (a == null || b == null) { Column { Header(u.title, onBack = { vm.back() }); Text("Dati incontro incompleti", Modifier.padding(16.dp)) }; return }
    val ka = PoolLogic.sideKey(u, 0); val kb = PoolLogic.sideKey(u, 1)
    val initial = remember(u.id, u.rev) { PoolLogic.fromUnit(u)[PoolLogic.key(ka, kb)]?.let { alignPair(it, ka, kb) } }
    var notes by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PairResult?>(null) }
    var correction by remember { mutableStateOf(false) }
    val done = u.status == "done"
    Column(Modifier.fillMaxSize()) {
        Header(u.title, "${u.subtitle}${if (u.pedana > 0) " · Pedana ${u.pedana}" else ""}", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("${a.sigla}", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
                Text("${b.sigla}", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.End)
            }
            if (done) Pill("Completato — modifica con PIN responsabile", Green)
            PairEditor(a.display, b.display, ka, kb, initial, u.maxTouches, true, onClear = null, onCancel = { vm.back() },
                onSave = { p -> pending = p })
            NotesField(notes) { notes = it }
        }
    }
    pending?.let { p ->
        if (!done) AlertDialog(
            onDismissRequest = { pending = null }, title = { Text("Confermare il risultato?") },
            text = { Text("${a.display}  ${PoolLogic.cellText(p, ka, u.maxTouches)}\n${b.display}  ${PoolLogic.cellText(p, kb, u.maxTouches)}", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            confirmButton = { TextButton(onClick = {
                pending = null
                vm.submitResult(u, listOf(Rules.toCell(p, u.maxTouches, u.fenceMax)), true, notes.ifBlank { null })
                if (vm.prefs.requireSignature) { vm.back(); vm.go(Screen.Sign(u.id)) } else vm.back()
            }) { Text("Conferma", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Correggi") } }
        ) else CorrectionDialog({ pending = null }) { pin, reason ->
            vm.submitResult(u, listOf(Rules.toCell(p, u.maxTouches, u.fenceMax)), true, notes.ifBlank { null }, overwrite = true, reason = reason, pin = pin)
            pending = null; vm.back()
        }
    }
}

// ---------------------------------------------------------------- Squadre: 9 frazioni (porting di frmIncontri)
@Composable
fun TeamScreen(vm: AppViewModel, u: UnitDto) {
    val a = u.athletes.getOrNull(0); val b = u.athletes.getOrNull(1)
    if (a == null || b == null) { Column { Header(u.title, onBack = { vm.back() }); Text("Dati incontro incompleti", Modifier.padding(16.dp)) }; return }
    var st by remember(u.id) { mutableStateOf(TeamState()) }
    var swapped by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val done = u.status == "done"
    LaunchedEffect(u.id) { vm.loadDraft(u.id)?.let { st = TeamLogic.decode(it) } }
    fun upd(s: TeamState) { st = s; vm.viewModelScopeLaunch { vm.saveDraft(u.id, TeamLogic.encode(s)) } }

    fun member(side: Athlete, idx: Int) = side.members.getOrNull(idx)?.nome?.ifBlank { null } ?: "Atleta ${idx + 1}"
    val (ta, tb) = TeamLogic.totals(st)
    val finished = TeamLogic.finished(st, u.maxTouches)
    val pair = TeamLogic.toPair(st)
    val err = if (!finished) "Compilare tutte le frazioni (o raggiungere ${u.maxTouches})" else Rules.validate(pair, u.maxTouches)

    Column(Modifier.fillMaxSize()) {
        Header(u.title, u.subtitle, onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.display.ifBlank { a.sigla }, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("$ta – $tb", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Red)
                    Text(b.display.ifBlank { b.sigla }, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.End)
                }
            }
            // formazioni (3 tiratori + eventuale riserva per posizione)
            listOf(a to true, b to false).forEach { (side, isA) ->
                val line = if (isA) st.lineA else st.lineB
                Card2 {
                    Text("Formazione ${side.sigla}", fontSize = 13.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (pos in 0 until 3) {
                            var open by remember { mutableStateOf(false) }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("${"ABC"[pos]}: ${member(side, line[pos])}", fontSize = 12.sp, maxLines = 1) }
                                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    (0 until maxOf(side.members.size, 3)).forEach { m ->
                                        DropdownMenuItem(text = { Text(member(side, m)) }, onClick = {
                                            val nl = line.toMutableList().also { it[pos] = m }
                                            upd(if (isA) st.copy(lineA = nl) else st.copy(lineB = nl)); open = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (leg in 0 until 9) {
                val ca = TeamLogic.cumulative(st.legsA, leg); val cb = TeamLogic.cumulative(st.legsB, leg)
                Card2 {
                    Text("Frazione ${leg + 1}  ·  fino a ${TeamLogic.cap(leg)}", fontSize = 12.sp, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(member(a, st.lineA[TeamLogic.ORDER_A[leg]]), fontSize = 13.sp, maxLines = 1)
                            Stepper(st.legsA[leg], TeamLogic.maxForLeg(st.legsA, leg), { upd(TeamLogic.withLeg(st, leg, it, st.legsB[leg] ?: 0)) }, big = false)
                        }
                        Text("$ca – $cb", Modifier.padding(horizontal = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(member(b, st.lineB[TeamLogic.ORDER_B[leg]]), fontSize = 13.sp, maxLines = 1)
                            Stepper(st.legsB[leg], TeamLogic.maxForLeg(st.legsB, leg), { upd(TeamLogic.withLeg(st, leg, st.legsA[leg] ?: 0, it)) }, big = false)
                        }
                    }
                }
            }
            if (finished && ta == tb) Card2 {
                Text("Parita': chi ha la priorita'?", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = st.priority == 1, onClick = { upd(st.copy(priority = 1)) }, label = { Text(a.sigla.ifBlank { "Squadra 1" }) })
                    FilterChip(selected = st.priority == 2, onClick = { upd(st.copy(priority = 2)) }, label = { Text(b.sigla.ifBlank { "Squadra 2" }) })
                }
            }
            NotesField(notes) { notes = it }
            if (err != null) Text(err, color = Red, fontSize = 13.sp)
        }
        BigButton(if (done) "Correggi…" else "CONFERMA INCONTRO", Modifier.padding(12.dp), enabled = err == null) { confirm = true }
    }

    fun send(over: Boolean, pin: String?, reason: String?) {
        val extra = AppJson.encodeToString(mapOf("legsA" to st.legsA.map { it ?: 0 }, "legsB" to st.legsB.map { it ?: 0 },
            "lineA" to st.lineA.map { a.members.getOrNull(it)?.numFis ?: 0 }, "lineB" to st.lineB.map { b.members.getOrNull(it)?.numFis ?: 0 }))
        vm.submitResult(u, listOf(Rules.toCell(pair, u.maxTouches, u.fenceMax)), true, notes.ifBlank { null }, extra, over, reason, pin)
        vm.back()
    }
    if (confirm && !done) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confermare $ta – $tb?") },
        confirmButton = { TextButton(onClick = { confirm = false; send(false, null, null) }) { Text("Conferma", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } })
    if (confirm && done) CorrectionDialog({ confirm = false }) { pin, reason -> confirm = false; send(true, pin, reason) }
}
