package it.fence.pda.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fence.pda.core.UnitDto
import it.fence.pda.logic.PairResult
import it.fence.pda.logic.Rules

val Red = Color(0xFFC1121F)
val Green = Color(0xFF2A9D55)
val Amber = Color(0xFFE9A100)
val Ink = Color(0xFF14202B)
val Soft = Color(0xFFF1F3F5)

@Composable
fun FenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Red, onPrimary = Color.White, secondary = Ink, background = Color.White, surface = Color.White, onSurface = Ink),
        content = content
    )
}

@Composable
fun Header(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Red).statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹", color = Color.White, fontSize = 30.sp) }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp)) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = Color(0xFFFFD6D9), fontSize = 13.sp, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = Red, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
}

fun statusColor(s: String) = when (s) { "done" -> Green; "progress" -> Amber; "pending" -> Color(0xFF3A6EA5); "problem" -> Red; else -> Color(0xFF6C757D) }
fun statusLabel(s: String) = when (s) { "done" -> "Completato"; "progress" -> "In corso"; "pending" -> "Da sincronizzare"; "problem" -> "Conflitto"; else -> "Da effettuare" }

@Composable
fun Card2(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Column(m.padding(14.dp), content = content)
}

@Composable
fun Stepper(value: Int?, max: Int, onChange: (Int) -> Unit, big: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(((value ?: 0) - 1).coerceAtLeast(0)) }, modifier = Modifier.size(if (big) 56.dp else 44.dp), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 26.sp) }
        Text(value?.toString() ?: "–", fontSize = if (big) 44.sp else 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 56.dp), textAlign = TextAlign.Center)
        Button(onClick = { onChange(((value ?: 0) + 1).coerceAtMost(max)) }, modifier = Modifier.size(if (big) 56.dp else 44.dp), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 26.sp) }
    }
}

/**
 * Editor di un singolo assalto (usato dal dialogo della griglia, dalla modalità incontri, dall'ED e dalle squadre).
 * Il vincitore e' scelto automaticamente quando raggiunge il limite, ma si puo' impostare a mano (vittoria a tempo/priorità).
 */
@Composable
fun PairEditor(
    nameA: String, nameB: String, keyA: Int, keyB: Int, initial: PairResult?, max: Int, allowSpecial: Boolean,
    onSave: (PairResult) -> Unit, onClear: (() -> Unit)?, onCancel: () -> Unit
) {
    var ta by remember(initial) { mutableStateOf(initial?.ta) }
    var tb by remember(initial) { mutableStateOf(initial?.tb) }
    var winner by remember(initial) { mutableStateOf(initial?.winner) }
    var special by remember(initial) { mutableStateOf(initial?.takeIf { it.isSpecial }?.let { it.specialWho to it.specialCode }) }
    var error by remember { mutableStateOf<String?>(null) }

    fun setA(v: Int) { ta = v; special = null; if (v >= max) winner = keyA else if (winner == keyA && v < (tb ?: 0)) winner = null }
    fun setB(v: Int) { tb = v; special = null; if (v >= max) winner = keyB else if (winner == keyB && v < (ta ?: 0)) winner = null }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple(nameA, ta, true), Triple(nameB, tb, false)).forEach { (n, t, isA) ->
                val key = if (isA) keyA else keyB
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(n, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, textAlign = TextAlign.Center)
                    Stepper(t, max, { if (isA) setA(it) else setB(it) })
                    Spacer(Modifier.height(6.dp))
                    FilterChip(selected = winner == key && special == null, onClick = { winner = key; special = null }, label = { Text("Vince") })
                }
            }
        }
        if (allowSpecial) {
            Text("Codici speciali", fontSize = 13.sp, color = Color.Gray)
            listOf(keyA to nameA, keyB to nameB).forEach { (key, n) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.take(12), modifier = Modifier.width(88.dp), fontSize = 13.sp, maxLines = 1)
                    Rules.SPECIALS.forEach { (code, label) ->
                        FilterChip(selected = special == (key to code), onClick = { special = if (special == (key to code)) null else (key to code) }, label = { Text(label.take(4) + ".") })
                    }
                }
            }
        }
        if (error != null) Text(error!!, color = Red, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Annulla") }
            if (onClear != null) OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Svuota") }
            Button(modifier = Modifier.weight(1f), onClick = {
                val p = if (special != null) PairResult(keyA, keyB, specialWho = special!!.first, specialCode = special!!.second)
                else PairResult(keyA, keyB, ta ?: 0, tb ?: 0, winner = winner)
                val e = Rules.validate(p, max)
                if (e != null) error = e else onSave(p)
            }) { Text("Salva") }
        }
    }
}

@Composable
fun UnitCard(u: UnitDto, state: String, onClick: () -> Unit) {
    Card2(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(u.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(statusLabel(state), statusColor(state))
        }
        Text(u.subtitle, fontSize = 14.sp, color = Color.DarkGray)
        val whereWhen = listOfNotNull(u.pedana.takeIf { it > 0 }?.let { "Pedana $it" }, u.data?.let { it.substring(11, 16) }).joinToString(" · ")
        if (whereWhen.isNotEmpty()) Text(whereWhen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (u.isPool) Text(u.athletes.joinToString(", ") { it.cognome }, fontSize = 13.sp, maxLines = 2)
        else u.athletes.forEach { Text("${it.display}  ·  ${it.sigla}", fontSize = 14.sp) }
    }
}
