package com.matheo.ajedrez.vision

import com.matheo.ajedrez.chess.Move
import com.matheo.ajedrez.chess.Position
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Constantes de ajuste (Etapa 8): si en tu tablero real detecta de más o de menos, tocá estos valores. */
object VisionConfig {
    const val CAP = 30.0          // aporte máximo de una casilla que debería cambiar
    const val PEN_T = 8.0         // cambio tolerado en casillas que NO deberían cambiar (ruido, sombras)
    const val FROM_MIN = 10.0     // cambio mínimo en la casilla de origen
    const val SQ_MIN = 6.0        // cambio mínimo en las demás casillas involucradas
    const val MARGIN = 12.0       // ventaja mínima sobre la segunda mejor hipótesis
    const val MIN_REWARD = 25.0
    const val STABLE_FRAMES = 5   // cuadros seguidos con la misma jugada antes de aceptarla
}

data class MatchResult(val moves: List<Move>, val score: Double, val margin: Double)

/**
 * Dado cuánto cambió cada casilla (d[0..63], 0 = a8) respecto de la última posición confirmada,
 * decide cuál jugada LEGAL explica mejor lo que se ve. Lógica pura: no depende de OpenCV.
 */
object MoveMatcher {
    fun expectedSquares(pos: Position, m: Move): IntArray {
        val list = mutableListOf(m.from, m.to)
        val p = pos.board[m.from].uppercaseChar()
        if (p == 'P' && m.to == pos.ep) list.add((m.from / 8) * 8 + m.to % 8) // captura al paso
        if (p == 'K' && abs(m.to % 8 - m.from % 8) == 2) {                       // enroque
            if (m.to % 8 == 6) { list.add(m.to + 1); list.add(m.to - 1) }
            else { list.add(m.to - 2); list.add(m.to + 1) }
        }
        return list.toIntArray()
    }

    fun excess(d: DoubleArray): Double {
        var t = 0.0
        for (v in d) t += max(0.0, v - VisionConfig.PEN_T)
        return t
    }

    private class G(val moves: MutableList<Move>, val score: Double, val ok: Boolean)

    fun best(pos: Position, d: DoubleArray): MatchResult? {
        val total = excess(d)
        val groups = LinkedHashMap<String, G>()
        for (m in pos.legalMoves()) {
            val e = expectedSquares(pos, m)
            val key = e.sorted().joinToString(",")
            val g = groups[key]
            if (g != null) { g.moves.add(m); continue } // variantes de coronación: mismas casillas
            var reward = 0.0
            var inside = 0.0
            for (s in e) {
                reward += min(d[s], VisionConfig.CAP)
                inside += max(0.0, d[s] - VisionConfig.PEN_T)
            }
            val penalty = total - inside
            var ok = d[m.from] >= VisionConfig.FROM_MIN
            for (s in e) if (s != m.from && d[s] < VisionConfig.SQ_MIN) ok = false
            ok = ok && reward >= VisionConfig.MIN_REWARD && penalty <= 0.5 * reward
            groups[key] = G(mutableListOf(m), reward - penalty, ok)
        }
        val sorted = groups.values.sortedByDescending { it.score }
        val top = sorted.firstOrNull() ?: return null
        if (!top.ok) return null
        val margin = top.score - (sorted.getOrNull(1)?.score ?: -1e9)
        if (margin < VisionConfig.MARGIN) return null
        return MatchResult(top.moves, top.score, margin)
    }
}
