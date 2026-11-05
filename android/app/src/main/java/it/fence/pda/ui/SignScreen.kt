package it.fence.pda.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream

/** Firma touch: i tratti diventano un JPEG ridimensionato (max 600 px) salvato nel campo Firma di Gironi4Mobile. */
@Composable
fun SignScreen(vm: AppViewModel, unitId: String) {
    val units by vm.units.collectAsState()
    val u = units.firstOrNull { it.id == unitId }
    var idx by remember { mutableIntStateOf(0) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    if (u == null || idx >= u.athletes.size) { LaunchedEffect(Unit) { vm.back() }; return }
    val a = u.athletes[idx]

    Column(Modifier.fillMaxSize()) {
        Header("Firma", "${idx + 1} di ${u.athletes.size} · ${u.title}", onBack = { vm.back() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(a.display.ifBlank { a.sigla }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Firma nel riquadro con il dito", fontSize = 13.sp, color = Color.Gray)
            Canvas(
                Modifier.fillMaxWidth().height(240.dp).background(Color.White).border(2.dp, Ink)
                    .onSizeChanged { size = it }
                    .pointerInput(idx) {
                        var cur = mutableListOf<Offset>()
                        detectDragGestures(
                            onDragStart = { cur = mutableListOf(it); strokes.add(cur.toList()) },
                            onDrag = { change, _ -> cur.add(change.position); strokes[strokes.lastIndex] = cur.toList() }
                        )
                    }
            ) {
                strokes.forEach { s ->
                    if (s.size > 1) drawPath(Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }, Color.Black, style = Stroke(width = 5f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { strokes.clear() }, Modifier.weight(1f)) { Text("Cancella") }
                OutlinedButton(onClick = { strokes.clear(); idx++ }, Modifier.weight(1f)) { Text("Salta") }
                Button(onClick = {
                    vm.queueSignature(u.id, idx, toJpeg(strokes.toList(), size)); strokes.clear(); idx++
                }, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Salva") }
            }
        }
    }
}

fun toJpeg(strokes: List<List<Offset>>, size: IntSize): ByteArray {
    val w = 600
    val h = (w * size.height.toFloat() / size.width.coerceAtLeast(1)).toInt().coerceAtLeast(60)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val p = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 4f; isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    val sx = w.toFloat() / size.width.coerceAtLeast(1); val sy = h.toFloat() / size.height.coerceAtLeast(1)
    strokes.forEach { s ->
        for (i in 1 until s.size) c.drawLine(s[i - 1].x * sx, s[i - 1].y * sy, s[i].x * sx, s[i].y * sy, p)
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return out.toByteArray()
}
