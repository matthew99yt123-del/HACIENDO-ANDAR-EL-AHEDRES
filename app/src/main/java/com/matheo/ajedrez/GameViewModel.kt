package com.matheo.ajedrez

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.matheo.ajedrez.chess.GameStatus
import com.matheo.ajedrez.chess.Move
import com.matheo.ajedrez.chess.Position
import com.matheo.ajedrez.engine.EngineLine
import com.matheo.ajedrez.engine.UciEngine
import com.matheo.ajedrez.vision.VisionController
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

data class HistoryEntry(val move: Move, val san: String)

data class UiState(
    val position: Position = Position(),
    val history: List<HistoryEntry> = emptyList(),
    val selected: Int? = null,
    val promoChoices: List<Move>? = null,
    val lines: List<EngineLine> = emptyList(), // puntaje ya en perspectiva de las BLANCAS
    val bestUci: String? = null,
    val thinking: Boolean = false,
    val engineReady: Boolean? = null, // null = iniciando
    val engineError: String? = null,
    val depth: Int = 16,
    val timeSec: Float = 3f,
    val flipped: Boolean = false,
    val lastMove: Move? = null,
    val cameraOn: Boolean = false,
    val visionNote: String? = null,
    val editing: Boolean = false,
    val editBoard: String = "",
    val editSel: Int? = null,
    val editWhiteToMove: Boolean = true
)

/** La app juega SIEMPRE con blancas y mueve primero. El rival (negras) se detecta con la cámara o se carga tocando el tablero. */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = UciEngine(app)
    private val stack = mutableListOf<Position>()
    private var requestId = 0
    private var cvOk = false

    var ui by mutableStateOf(UiState())
        private set

    val vision = VisionController({ ui.position }) { moves ->
        viewModelScope.launch { onVisionMove(moves) }
    }

    init {
        cvOk = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false }
        viewModelScope.launch {
            val err = engine.start()
            ui = ui.copy(engineReady = err == null, engineError = err)
            if (err == null) requestAnalysis()
        }
    }

    // ---------- Cámara ----------
    fun startCamera() {
        if (!cvOk) { ui = ui.copy(visionNote = "No se pudo cargar OpenCV en este dispositivo."); return }
        ui = ui.copy(cameraOn = true, visionNote = null)
        vision.start()
    }

    fun stopCamera() {
        vision.stop()
        ui = ui.copy(cameraOn = false)
    }

    fun setNote(s: String?) { ui = ui.copy(visionNote = s) }

    private fun onVisionMove(moves: List<Move>) {
        val pos = ui.position
        if (isOver(pos)) return
        val legal = pos.legalMoves()
        val valid = moves.filter { it in legal }
        if (valid.isEmpty()) return
        if (valid.size > 1) {
            ui = ui.copy(promoChoices = valid, visionNote = "Detecté una coronación: elegí la pieza.")
            return
        }
        val m = valid[0]
        val san = pos.san(m)
        val sug = ui.bestUci
        val note = if (pos.whiteToMove) {
            when {
                sug == null -> "Detecté tu jugada: $san"
                sug == m.uci() -> "✔ Jugada correcta: $san"
                else -> "⚠ Detecté $san, pero la recomendada era ${legal.firstOrNull { it.uci() == sug }?.let { pos.san(it) } ?: sug}"
            }
        } else "Detecté la jugada del rival: $san"
        playMove(m, fromVision = true)
        ui = ui.copy(visionNote = note)
    }

    // ---------- Motor ----------
    private fun toWhite(l: EngineLine, pos: Position): EngineLine {
        if (pos.whiteToMove) return l
        return l.copy(cp = l.cp?.let { -it }, mate = l.mate?.let { -it })
    }

    private fun isOver(pos: Position): Boolean = when (pos.status()) {
        GameStatus.CHECKMATE, GameStatus.STALEMATE, GameStatus.DRAW_50, GameStatus.DRAW_MATERIAL -> true
        else -> false
    }

    fun requestAnalysis() {
        if (ui.engineReady != true) return
        val pos = ui.position
        if (isOver(pos)) return
        val id = ++requestId
        engine.stop()
        ui = ui.copy(thinking = true, lines = emptyList(), bestUci = null)
        viewModelScope.launch {
            val res = engine.analyze(pos.toFen(), ui.depth, (ui.timeSec * 1000).toInt()) { lines ->
                viewModelScope.launch {
                    if (id == requestId) ui = ui.copy(lines = lines.map { toWhite(it, pos) })
                }
            }
            if (id == requestId) {
                ui = ui.copy(
                    thinking = false,
                    bestUci = res.best?.takeIf { it != "(none)" },
                    lines = res.lines.map { toWhite(it, pos) }
                )
            }
        }
    }

    private fun playMove(m: Move, fromVision: Boolean = false) {
        val pos = ui.position
        val san = pos.san(m)
        stack.add(pos)
        val next = pos.apply(m)
        engine.stop()
        requestId++
        if (!fromVision) vision.rebaseline() // jugada cargada a mano: el escaneo toma el tablero actual como referencia
        ui = ui.copy(
            position = next,
            history = ui.history + HistoryEntry(m, san),
            selected = null, promoChoices = null,
            lines = emptyList(), bestUci = null, thinking = false, lastMove = m, visionNote = null
        )
        if (next.whiteToMove) requestAnalysis()
    }

    /** "Ya la jugué": aplica la jugada recomendada por Stockfish. */
    fun confirmSuggested() {
        val u = ui.bestUci ?: return
        ui.position.legalMoves().firstOrNull { it.uci() == u }?.let { playMove(it) }
    }

    fun onSquareTap(sq: Int) {
        val pos = ui.position
        if (isOver(pos)) return
        val sel = ui.selected
        if (sel != null) {
            val cands = pos.legalMoves().filter { it.from == sel && it.to == sq }
            if (cands.size == 1) { playMove(cands[0]); return }
            if (cands.size > 1) { ui = ui.copy(promoChoices = cands); return }
        }
        val p = pos.board[sq]
        ui = ui.copy(selected = if (p != ' ' && (p.isUpperCase() == pos.whiteToMove)) sq else null)
    }

    fun choosePromotion(m: Move) = playMove(m)
    fun dismissPromotion() { ui = ui.copy(promoChoices = null, selected = null) }

    fun undo() {
        if (stack.isEmpty()) return
        val pos = stack.removeAt(stack.lastIndex)
        engine.stop()
        requestId++
        vision.rebaseline()
        val h = ui.history.dropLast(1)
        ui = ui.copy(
            position = pos, history = h, selected = null, promoChoices = null,
            lines = emptyList(), bestUci = null, thinking = false, lastMove = h.lastOrNull()?.move, visionNote = null
        )
        if (pos.whiteToMove) requestAnalysis()
    }

    fun reset() {
        stack.clear()
        engine.stop()
        requestId++
        vision.backToSetup() // hay que rearmar las piezas y volver a confirmar el tablero
        ui = ui.copy(
            position = Position(), history = emptyList(), selected = null, promoChoices = null,
            lines = emptyList(), bestUci = null, thinking = false, lastMove = null, visionNote = null
        )
        requestAnalysis()
    }

    // ---------- Edición manual de piezas ----------
    fun startEdit() {
        ui = ui.copy(
            editing = true, editBoard = ui.position.board.concatToString(), editSel = null,
            editWhiteToMove = ui.position.whiteToMove, selected = null, promoChoices = null
        )
    }

    fun onEditTap(sq: Int) { ui = ui.copy(editSel = sq) }
    fun dismissEditPick() { ui = ui.copy(editSel = null) }

    fun setEditPiece(c: Char) {
        val sel = ui.editSel ?: return
        val b = ui.editBoard.toCharArray()
        b[sel] = c
        ui = ui.copy(editBoard = b.concatToString(), editSel = null)
    }

    fun toggleEditTurn() { ui = ui.copy(editWhiteToMove = !ui.editWhiteToMove) }
    fun cancelEdit() { ui = ui.copy(editing = false, editSel = null) }

    fun finishEdit() {
        val b = ui.editBoard.toCharArray()
        if (b.count { it == 'K' } != 1 || b.count { it == 'k' } != 1) {
            ui = ui.copy(visionNote = "Tiene que haber exactamente un rey blanco y uno negro."); return
        }
        if ((0..7).any { b[it].uppercaseChar() == 'P' } || (56..63).any { b[it].uppercaseChar() == 'P' }) {
            ui = ui.copy(visionNote = "No puede haber peones en la primera ni en la última fila."); return
        }
        var cr = ""
        if (b[60] == 'K' && b[63] == 'R') cr += "K"
        if (b[60] == 'K' && b[56] == 'R') cr += "Q"
        if (b[4] == 'k' && b[7] == 'r') cr += "k"
        if (b[4] == 'k' && b[0] == 'r') cr += "q"
        if (cr.isEmpty()) cr = "-"
        val wtm = ui.editWhiteToMove
        val pos = Position(b, wtm, cr, -1, 0, 1)
        val idle = b.indexOf(if (wtm) 'k' else 'K')
        if (pos.isAttacked(idle, wtm)) {
            ui = ui.copy(visionNote = "Posición ilegal: el rey del bando que NO mueve está en jaque."); return
        }
        engine.stop()
        requestId++
        stack.clear()
        vision.rebaseline()
        ui = ui.copy(
            position = pos, history = emptyList(), editing = false, editSel = null, selected = null,
            lines = emptyList(), bestUci = null, thinking = false, lastMove = null,
            visionNote = "Posición editada. Se reinició el historial."
        )
        requestAnalysis()
    }

    fun flip() { ui = ui.copy(flipped = !ui.flipped) }
    fun setDepth(d: Int) { ui = ui.copy(depth = d) }
    fun setTime(t: Float) { ui = ui.copy(timeSec = t) }

    override fun onCleared() {
        vision.stop()
        engine.destroy()
        super.onCleared()
    }
}
