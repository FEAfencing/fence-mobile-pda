package it.fence.pda.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import it.fence.pda.core.AppJson
import it.fence.pda.core.QrPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Associazione
@Composable
fun PairScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    var url by remember { mutableStateOf("") }
    var fp by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { res ->
        val txt = res.contents ?: return@rememberLauncherForActivityResult
        val qr = runCatching { AppJson.decodeFromString<QrPayload>(txt) }.getOrNull()
        if (qr == null) error = "QR non riconosciuto: usare quello del pannello Fence! PDA Bridge"
        else { url = qr.u; fp = qr.fp; code = qr.c; error = null; vm.pair(qr.u, qr.fp, qr.c) { error = it } }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Fence! PDA", "Associazione al computer di gara")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card2 {
                Text("Identificativo di questo PDA", fontSize = 13.sp, color = Color.Gray)
                Text(vm.prefs.deviceId, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Comparira' tra i PDA disponibili in PrepArbPda dopo l'associazione.", fontSize = 13.sp)
            }
            BigButton("Scansiona il QR del Bridge", enabled = !busy) {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Inquadra il QR del pannello Bridge").setOrientationLocked(false).setBeepEnabled(false))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = Red, fontWeight = FontWeight.SemiBold) }
            Text("Oppure inserisci i dati a mano", fontSize = 14.sp, color = Color.Gray)
            OutlinedTextField(url, { url = it }, label = { Text("Indirizzo (https://192.168.1.10:8765)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(fp, { fp = it }, label = { Text("Impronta certificato (SHA-256)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Codice monouso") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            BigButton("Associa", color = Ink, enabled = !busy && url.isNotBlank() && fp.isNotBlank() && code.isNotBlank()) { vm.pair(url, fp, code) { error = it } }
        }
    }
}

// ---------------------------------------------------------------- Home
fun fmtTime(ms: Long): String = if (ms == 0L) "mai" else SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(ms))

@Composable
fun HomeScreen(vm: AppViewModel) {
    val st by vm.status.collectAsState()
    val units by vm.units.collectAsState()
    val outbox by vm.outbox.collectAsState()
    val todo = units.count { it.status != "done" }
    val done = units.count { it.status == "done" }
    val pending = outbox.count { it.state == "pending" || it.state == "error" }
    val problems = outbox.count { it.state == "conflict" || it.state == "rejected" }
    Column(Modifier.fillMaxSize()) {
        Header("FENCE! PDA", if (st.authFailed) "Dispositivo non autorizzato" else if (st.online) "Connesso" else "Offline — i risultati restano in coda")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card2 {
                Text("DISPOSITIVO", fontSize = 12.sp, color = Color.Gray)
                Text(vm.prefs.deviceId, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val arb = st.arbiter
                Text(if (arb != null) "Arbitro: ${arb.display} · FIS ${arb.numFis}" else "Nessun arbitro associato: chiedere al tavolo di associare questo PDA in PrepArbPda",
                    fontSize = 15.sp, color = if (arb != null) Ink else Red)
                units.firstOrNull { it.pedana > 0 }?.let { Text("Pedana ${it.pedana}", fontSize = 15.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card2(Modifier.weight(1f), onClick = { vm.go(Screen.List) }) { Text("Da effettuare", fontSize = 13.sp); Text("$todo", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Red) }
                Card2(Modifier.weight(1f), onClick = { vm.go(Screen.List) }) { Text("Completati", fontSize = 13.sp); Text("$done", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Green) }
            }
            Card2 {
                Text("In attesa di trasmissione: $pending", fontWeight = FontWeight.SemiBold)
                if (problems > 0) Text("Conflitti/rifiuti da risolvere: $problems", color = Red, fontWeight = FontWeight.Bold)
                st.error?.let { Text(it, color = Red, fontSize = 13.sp) }
                Text("Ultimo aggiornamento: ${fmtTime(st.lastSyncAt)}", fontSize = 13.sp, color = Color.Gray)
            }
            BigButton("INCONTRI ASSEGNATI") { vm.go(Screen.List) }
            BigButton(if (st.pendingMessages > 0) "MESSAGGI (${st.pendingMessages} nuovi)" else "MESSAGGI", color = Ink) { vm.go(Screen.Messages) }
            OutlinedButton(onClick = { vm.syncNow() }, modifier = Modifier.fillMaxWidth()) { Text("Sincronizza ora") }
            OutlinedButton(onClick = { vm.go(Screen.Settings) }, modifier = Modifier.fillMaxWidth()) { Text("Impostazioni") }
        }
    }
}

// ---------------------------------------------------------------- Elenco incontri
@Composable
fun ListScreen(vm: AppViewModel) {
    val units by vm.units.collectAsState()
    val outbox by vm.outbox.collectAsState()
    var filter by remember { mutableStateOf("all") }
    fun stateOf(id: String, base: String): String {
        val o = outbox.filter { it.unitId == id }
        return when {
            o.any { it.state == "conflict" || it.state == "rejected" } -> "problem"
            o.any { it.kind == "result" && (it.state == "pending" || it.state == "error") } -> "pending"
            else -> base
        }
    }
    val rows = units.map { it to stateOf(it.id, it.status) }.filter { filter == "all" || it.second == filter }
    Column(Modifier.fillMaxSize()) {
        Header("Incontri", "${rows.size} elementi", onBack = { vm.back() })
        Row(Modifier.padding(8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "Tutti", "todo" to "Da fare", "progress" to "In corso", "done" to "Completati", "pending" to "Da sincr.").forEach { (k, l) ->
                FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l, fontSize = 12.sp) })
            }
        }
        if (rows.isEmpty()) Text("Nessun incontro assegnato a questo PDA.", Modifier.padding(16.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.first.id }) { (u, s) -> UnitCard(u, s) { vm.go(Screen.Bout(u.id)) } }
        }
    }
}

// ---------------------------------------------------------------- Messaggi
@Composable
fun MessagesScreen(vm: AppViewModel) {
    val msgs by vm.messages.collectAsState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadMessages() }
    Column(Modifier.fillMaxSize()) {
        Header("Messaggi", "Tavolo ↔ arbitro", onBack = { vm.back() })
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), label = { Text("Scrivi al tavolo") })
            Button(onClick = { vm.sendMessage(text.trim()); text = "" }, enabled = text.isNotBlank()) { Text("Invia") }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs, key = { it.prog }) { m ->
                Card2 { Text(m.oraInvio.take(16).replace('T', ' '), fontSize = 12.sp, color = Color.Gray); Text(m.testo, fontSize = 17.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Impostazioni
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var sign by remember { mutableStateOf(vm.prefs.requireSignature) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("Impostazioni", onBack = { vm.back() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Chiedi la firma degli atleti", fontWeight = FontWeight.SemiBold); Text("Dopo la conferma dell'incontro", fontSize = 13.sp, color = Color.Gray) }
                    Switch(checked = sign, onCheckedChange = { sign = it; vm.prefs.requireSignature = it })
                }
            }
            Card2 {
                Text("Gateway", fontSize = 12.sp, color = Color.Gray); Text(vm.prefs.baseUrl)
                Text("Impronta certificato", fontSize = 12.sp, color = Color.Gray); Text(vm.prefs.fingerprint, fontSize = 11.sp)
                Text("PDA", fontSize = 12.sp, color = Color.Gray); Text(vm.prefs.deviceId, fontWeight = FontWeight.Bold)
            }
            BigButton("Scollega questo PDA", color = Ink) { confirm = true }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Scollegare il PDA?") },
        text = { Text("Il telefono verra' revocato sul gateway e i dati locali cancellati. I risultati non ancora trasmessi andranno persi.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.unpair() }) { Text("Scollega", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } }
    )
}
