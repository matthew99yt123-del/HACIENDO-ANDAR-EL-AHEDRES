package com.matheo.ajedrez

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matheo.ajedrez.chess.Chess
import com.matheo.ajedrez.chess.GameStatus
import com.matheo.ajedrez.chess.Move
import com.matheo.ajedrez.chess.Position
import com.matheo.ajedrez.engine.EngineLine
import java.util.Locale
import kotlin.math.abs

private fun glyph(c: Char): String {
    val g = when (c.uppercaseChar()) {
        'K' -> "\u265A"; 'Q' -> "\u265B"; 'R' -> "\u265C"
        'B' -> "\u265D"; 'N' -> "\u265E"; else -> "\u265F"
    }
    return g + "\uFE0E" // fuerza glifo de texto (no emoji)
}

private fun pieceName(c: Char) = when (c.uppercaseChar()) {
    'K' -> "rey"; 'Q' -> "dama"; 'R' -> "torre"; 'B' -> "alfil"; 'N' -> "caballo"; else -> "peón"
}

private fun promoName(c: Char?) = when (c) { 'q' -> "Dama"; 'r' -> "Torre"; 'b' -> "Alfil"; else -> "Caballo" }

/** Explicación breve de la jugada. */
private fun describe(pos: Position, m: Move, san: String): String {
    val sb = StringBuilder()
    if (san.startsWith("O-O")) sb.append(if (san.startsWith("O-O-O")) "Enroque largo." else "Enroque corto.")
    else {
        sb.append("Mové el ${pieceName(pos.board[m.from])} de ${Chess.sqName(m.from)} a ${Chess.sqName(m.to)}.")
        if ('x' in san) sb.append(" Captura una pieza.")
        if (m.promo != null) sb.append(" Corona en ${promoName(m.promo).lowercase()}.")
    }
    if (san.endsWith("#")) sb.append(" ¡Jaque mate!") else if (san.endsWith("+")) sb.append(" Da jaque.")
    return sb.toString()
}

private fun evalText(l: EngineLine?): String {
    if (l == null) return "—"
    l.mate?.let { return if (it > 0) "Mate en $it (blancas)" else "Mate en ${abs(it)} (negras)" }
    return String.format(Locale.US, "%+.2f", (l.cp ?: 0) / 100.0)
}

private fun verdict(l: EngineLine?): String {
    if (l == null) return ""
    val m = l.mate
    val cp = l.cp ?: 0
    return when {
        (m != null && m > 0) || cp >= 150 -> "Vas GANANDO (blancas)"
        (m != null && m < 0) || cp <= -150 -> "Vas PERDIENDO (blancas)"
        else -> "Posición IGUALADA"
    }
}

private fun pvSan(start: Position, pv: List<String>, max: Int = 8): String {
    var p = start
    val sb = StringBuilder()
    var n = 0
    for (u in pv.take(max)) {
        val m = p.legalMoves().firstOrNull { it.uci() == u } ?: break
        if (p.whiteToMove) sb.append("${p.fullmove}. ") else if (n == 0) sb.append("${p.fullmove}... ")
        sb.append(p.san(m)).append(' ')
        p = p.apply(m); n++
    }
    return sb.toString().trim()
}

@Composable
fun BoardView(
    pos: Position, flipped: Boolean, selected: Int?, targets: Set<Int>,
    best: Move?, last: Move?, onTap: (Int) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val cell = maxWidth / 8
        Column(Modifier.fillMaxSize()) {
            for (r in 0..7) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0..7) {
                    val sq = if (!flipped) r * 8 + c else (7 - r) * 8 + (7 - c)
                    val light = (sq / 8 + sq % 8) % 2 == 0
                    var bg = if (light) Color(0xFFF0D9B5) else Color(0xFFB58863)
                    if (last != null && (sq == last.from || sq == last.to)) bg = if (light) Color(0xFFDDE29A) else Color(0xFFAAB05A)
                    if (best != null && (sq == best.from || sq == best.to)) bg = Color(0xFF7FC97F)
                    if (sq == selected) bg = Color(0xFFF6F669)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().background(bg).clickable { onTap(sq) },
                        contentAlignment = Alignment.Center
                    ) {
                        val ch = pos.board[sq]
                        if (ch != ' ') {
                            val white = Chess.isWhite(ch)
                            Text(
                                glyph(ch),
                                fontSize = (cell.value * 0.78f).sp,
                                color = if (white) Color.White else Color.Black,
                                style = TextStyle(shadow = Shadow(if (white) Color.Black else Color.White, blurRadius = 6f))
                            )
                        }
                        if (sq in targets) Box(Modifier.size(cell * 0.28f).background(Color(0x66000000), CircleShape))
                    }
                }
            }
        }
        if (best != null) {
            Canvas(Modifier.fillMaxSize()) {
                val cs = size.width / 8
                fun center(sq: Int): Offset {
                    val c = if (!flipped) sq % 8 else 7 - sq % 8
                    val r = if (!flipped) sq / 8 else 7 - sq / 8
                    return Offset((c + 0.5f) * cs, (r + 0.5f) * cs)
                }
                val a = center(best.from)
                val b = center(best.to)
                val d = b - a
                val len = d.getDistance()
                if (len > 0f) {
                    val u = d / len
                    val color = Color(0xCC1E88E5)
                    val headLen = cs * 0.5f
                    val headW = cs * 0.3f
                    val lineEnd = b - u * headLen
                    drawLine(color, a, lineEnd, strokeWidth = cs * 0.14f, cap = StrokeCap.Round)
                    val perp = Offset(-u.y, u.x)
                    val path = Path().apply {
                        moveTo(b.x, b.y)
                        lineTo((lineEnd + perp * headW).x, (lineEnd + perp * headW).y)
                        lineTo((lineEnd - perp * headW).x, (lineEnd - perp * headW).y)
                        close()
                    }
                    drawPath(path, color)
                }
            }
        }
    }
}

@Composable
fun AjedrezScreen(vm: GameViewModel) {
    val ui = vm.ui
    val pos = ui.position
    val status = remember(pos) { pos.status() }
    val legal = remember(pos) { pos.legalMoves() }
    val targets = remember(pos, ui.selected) {
        ui.selected?.let { s -> legal.filter { it.from == s }.map { it.to }.toSet() } ?: emptySet()
    }
    val shownUci = ui.bestUci ?: ui.lines.firstOrNull()?.pv?.firstOrNull()
    val bestMove = legal.firstOrNull { it.uci() == shownUci }
    val top = ui.lines.firstOrNull()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Asistente de ajedrez", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("La app juega con BLANCAS y mueve primero.", style = MaterialTheme.typography.bodySmall)

        when (ui.engineReady) {
            null -> Text("Iniciando Stockfish…")
            false -> Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0E0))) {
                Text(ui.engineError ?: "Error con el motor", Modifier.padding(12.dp))
            }
            true -> {}
        }

        CameraSection(vm)

        val turnText = when (status) {
            GameStatus.CHECKMATE -> "Jaque mate. Ganan ${if (pos.whiteToMove) "las negras" else "las blancas"}."
            GameStatus.STALEMATE -> "Tablas por ahogado."
            GameStatus.DRAW_50 -> "Tablas por regla de 50 movimientos."
            GameStatus.DRAW_MATERIAL -> "Tablas por material insuficiente."
            else -> (if (pos.whiteToMove) "Turno de BLANCAS (jugás vos con lo que recomienda la app)"
            else "Turno de NEGRAS (rival): tocá su jugada en el tablero") + if (status == GameStatus.CHECK) " — ¡JAQUE!" else ""
        }
        Text(turnText, fontWeight = FontWeight.SemiBold)
        ui.visionNote?.let { Text(it, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0)) }

        if (ui.editing) {
            BoardView(Position(ui.editBoard.toCharArray()), ui.flipped, ui.editSel, emptySet(), null, null) { vm.onEditTap(it) }
            Text("Modo edición: tocá una casilla para poner o quitar una pieza.", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.toggleEditTurn() }, Modifier.weight(1f)) {
                    Text(if (ui.editWhiteToMove) "Mueven: BLANCAS" else "Mueven: NEGRAS")
                }
                Button(onClick = { vm.finishEdit() }, Modifier.weight(1f)) { Text("LISTO") }
                OutlinedButton(onClick = { vm.cancelEdit() }, Modifier.weight(1f)) { Text("CANCELAR") }
            }
        } else {
            BoardView(pos, ui.flipped, ui.selected, targets, bestMove, ui.lastMove) { vm.onSquareTap(it) }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (bestMove != null) {
                    val san = pos.san(bestMove)
                    Text(
                        (if (ui.thinking) "Pensando… " else "Mejor jugada: ") + san,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                    )
                    Text(describe(pos, bestMove, san))
                    Text("Evaluación: ${evalText(top)}   |   Profundidad: ${top?.depth ?: 0}")
                    Text(verdict(top))
                    top?.let { Text("Variante: " + pvSan(pos, it.pv)) }
                } else {
                    Text(if (ui.thinking) "Pensando…" else "Sin análisis todavía. Tocá ANALIZAR.")
                }
                ui.lines.filter { it.rank > 1 }.forEach {
                    Text("Alternativa ${it.rank}: ${pvSan(pos, it.pv, 4)}  (${evalText(it)})", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.confirmSuggested() },
                enabled = ui.bestUci != null && !ui.thinking,
                modifier = Modifier.weight(1f)
            ) { Text("YA LA JUGUÉ") }
            Button(
                onClick = { vm.requestAnalysis() },
                enabled = ui.engineReady == true,
                modifier = Modifier.weight(1f)
            ) { Text("ANALIZAR") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.undo() }, Modifier.weight(1f)) { Text("Deshacer") }
            OutlinedButton(onClick = { vm.flip() }, Modifier.weight(1f)) { Text("Girar") }
            OutlinedButton(onClick = { vm.reset() }, Modifier.weight(1f)) { Text("Reiniciar") }
        }
        OutlinedButton(onClick = { vm.startEdit() }, Modifier.fillMaxWidth(), enabled = !ui.editing) { Text("EDITAR PIEZAS") }

        Text("Profundidad máxima: ${ui.depth}")
        Slider(value = ui.depth.toFloat(), onValueChange = { vm.setDepth(it.toInt()) }, valueRange = 8f..24f)
        Text("Tiempo de análisis: ${String.format(Locale.US, "%.0f", ui.timeSec)} s")
        Slider(value = ui.timeSec, onValueChange = { vm.setTime(it) }, valueRange = 1f..20f)

        Text("Historial", fontWeight = FontWeight.SemiBold)
        Text(
            if (ui.history.isEmpty()) "Todavía no hay jugadas."
            else buildString { ui.history.forEachIndexed { i, h -> if (i % 2 == 0) append("${i / 2 + 1}. "); append(h.san).append(' ') } }
        )
    }

    if (ui.editing && ui.editSel != null) {
        val names = listOf('K' to "Rey", 'Q' to "Dama", 'R' to "Torre", 'B' to "Alfil", 'N' to "Caballo", 'P' to "Peón")
        AlertDialog(
            onDismissRequest = { vm.dismissEditPick() },
            title = { Text("Casilla ${Chess.sqName(ui.editSel!!)}") },
            text = {
                Column {
                    Text("Blancas")
                    Row { names.take(3).forEach { (c, n) -> TextButton(onClick = { vm.setEditPiece(c) }) { Text(n) } } }
                    Row { names.drop(3).forEach { (c, n) -> TextButton(onClick = { vm.setEditPiece(c) }) { Text(n) } } }
                    Text("Negras")
                    Row { names.take(3).forEach { (c, n) -> TextButton(onClick = { vm.setEditPiece(c.lowercaseChar()) }) { Text(n) } } }
                    Row { names.drop(3).forEach { (c, n) -> TextButton(onClick = { vm.setEditPiece(c.lowercaseChar()) }) { Text(n) } } }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setEditPiece(' ') }) { Text("Dejar vacía") } },
            dismissButton = { TextButton(onClick = { vm.dismissEditPick() }) { Text("Cancelar") } }
        )
    }

    ui.promoChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = { vm.dismissPromotion() },
            title = { Text("Coronación") },
            text = { Text("¿En qué pieza?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { m -> TextButton(onClick = { vm.choosePromotion(m) }) { Text(promoName(m.promo)) } }
                }
            }
        )
    }
}
