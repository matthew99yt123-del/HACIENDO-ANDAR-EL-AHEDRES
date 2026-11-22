package com.matheo.ajedrez.chess

import kotlin.math.abs

/** Casillas 0..63: 0 = a8, 7 = h8, 56 = a1, 63 = h1. promo en minúscula ('q','r','b','n'). */
data class Move(val from: Int, val to: Int, val promo: Char? = null) {
    fun uci(): String = Chess.sqName(from) + Chess.sqName(to) + (promo?.toString() ?: "")
}

enum class GameStatus { ONGOING, CHECK, CHECKMATE, STALEMATE, DRAW_50, DRAW_MATERIAL }

object Chess {
    const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    fun sqName(i: Int): String = "${'a' + i % 8}${8 - i / 8}"
    fun parseSq(s: String): Int = (s[0] - 'a') + (8 - (s[1] - '0')) * 8
    fun isWhite(c: Char) = c.isUpperCase()
    fun parseUci(s: String): Move? =
        if (s.length < 4) null else Move(parseSq(s.substring(0, 2)), parseSq(s.substring(2, 4)), s.getOrNull(4))
}

private val KNIGHT = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
private val ROOK_D = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
private val BISHOP_D = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
private val KING_D = ROOK_D + BISHOP_D

class Position(
    val board: CharArray = Position.fromFen(Chess.START_FEN).board,
    val whiteToMove: Boolean = true,
    val castling: String = "KQkq",
    val ep: Int = -1,
    val halfmove: Int = 0,
    val fullmove: Int = 1
) {
    companion object {
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(" ")
            val b = CharArray(64) { ' ' }
            var i = 0
            for (ch in parts[0]) {
                when {
                    ch == '/' -> {}
                    ch.isDigit() -> i += ch - '0'
                    else -> { b[i] = ch; i++ }
                }
            }
            val epStr = parts.getOrElse(3) { "-" }
            return Position(
                b,
                parts.getOrElse(1) { "w" } == "w",
                parts.getOrElse(2) { "-" },
                if (epStr == "-") -1 else Chess.parseSq(epStr),
                parts.getOrElse(4) { "0" }.toInt(),
                parts.getOrElse(5) { "1" }.toInt()
            )
        }
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var e = 0
            for (c in 0..7) {
                val p = board[r * 8 + c]
                if (p == ' ') e++ else {
                    if (e > 0) { sb.append(e); e = 0 }
                    sb.append(p)
                }
            }
            if (e > 0) sb.append(e)
            if (r < 7) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ").append(castling).append(' ')
            .append(if (ep < 0) "-" else Chess.sqName(ep)).append(' ')
            .append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    private fun kingSquare(white: Boolean) = board.indexOf(if (white) 'K' else 'k')

    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val r = sq / 8
        val c = sq % 8
        val pawn = if (byWhite) 'P' else 'p'
        val pr = if (byWhite) r + 1 else r - 1
        if (pr in 0..7) for (dc in intArrayOf(-1, 1)) {
            val cc = c + dc
            if (cc in 0..7 && board[pr * 8 + cc] == pawn) return true
        }
        val kn = if (byWhite) 'N' else 'n'
        for ((dr, dc) in KNIGHT) {
            val rr = r + dr; val cc = c + dc
            if (rr in 0..7 && cc in 0..7 && board[rr * 8 + cc] == kn) return true
        }
        val kg = if (byWhite) 'K' else 'k'
        for ((dr, dc) in KING_D) {
            val rr = r + dr; val cc = c + dc
            if (rr in 0..7 && cc in 0..7 && board[rr * 8 + cc] == kg) return true
        }
        for ((dr, dc) in ROOK_D) if (ray(r, c, dr, dc, byWhite, 'R')) return true
        for ((dr, dc) in BISHOP_D) if (ray(r, c, dr, dc, byWhite, 'B')) return true
        return false
    }

    private fun ray(r: Int, c: Int, dr: Int, dc: Int, byWhite: Boolean, slider: Char): Boolean {
        var rr = r + dr
        var cc = c + dc
        while (rr in 0..7 && cc in 0..7) {
            val p = board[rr * 8 + cc]
            if (p != ' ') {
                val u = p.uppercaseChar()
                return Chess.isWhite(p) == byWhite && (u == slider || u == 'Q')
            }
            rr += dr; cc += dc
        }
        return false
    }

    fun inCheck(): Boolean = isAttacked(kingSquare(whiteToMove), !whiteToMove)

    private fun addPawn(out: MutableList<Move>, from: Int, to: Int, promo: Boolean) {
        if (promo) for (p in "qrbn") out.add(Move(from, to, p)) else out.add(Move(from, to))
    }

    private fun step(out: MutableList<Move>, from: Int, r: Int, c: Int) {
        if (r !in 0..7 || c !in 0..7) return
        val t = r * 8 + c
        if (board[t] == ' ' || Chess.isWhite(board[t]) != whiteToMove) out.add(Move(from, t))
    }

    private fun slide(out: MutableList<Move>, from: Int, dirs: List<Pair<Int, Int>>) {
        for ((dr, dc) in dirs) {
            var r = from / 8 + dr
            var c = from % 8 + dc
            while (r in 0..7 && c in 0..7) {
                val t = r * 8 + c
                if (board[t] == ' ') out.add(Move(from, t))
                else {
                    if (Chess.isWhite(board[t]) != whiteToMove) out.add(Move(from, t))
                    break
                }
                r += dr; c += dc
            }
        }
    }

    private fun pseudoMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        for (i in 0 until 64) {
            val p = board[i]
            if (p == ' ' || Chess.isWhite(p) != whiteToMove) continue
            val r = i / 8
            val c = i % 8
            when (p.uppercaseChar()) {
                'P' -> {
                    val dir = if (whiteToMove) -1 else 1
                    val startRow = if (whiteToMove) 6 else 1
                    val lastRow = if (whiteToMove) 0 else 7
                    val r1 = r + dir
                    if (r1 in 0..7) {
                        if (board[r1 * 8 + c] == ' ') {
                            addPawn(out, i, r1 * 8 + c, r1 == lastRow)
                            if (r == startRow && board[(r + 2 * dir) * 8 + c] == ' ') out.add(Move(i, (r + 2 * dir) * 8 + c))
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val cc = c + dc
                            if (cc !in 0..7) continue
                            val t = r1 * 8 + cc
                            if ((board[t] != ' ' && Chess.isWhite(board[t]) != whiteToMove) || t == ep) addPawn(out, i, t, r1 == lastRow)
                        }
                    }
                }
                'N' -> for ((dr, dc) in KNIGHT) step(out, i, r + dr, c + dc)
                'B' -> slide(out, i, BISHOP_D)
                'R' -> slide(out, i, ROOK_D)
                'Q' -> slide(out, i, KING_D)
                'K' -> {
                    for ((dr, dc) in KING_D) step(out, i, r + dr, c + dc)
                    val base = if (whiteToMove) 60 else 4
                    if (i == base) {
                        val ks = if (whiteToMove) 'K' else 'k'
                        val qs = if (whiteToMove) 'Q' else 'q'
                        val rook = if (whiteToMove) 'R' else 'r'
                        val en = !whiteToMove
                        if (castling.indexOf(ks) >= 0 && board[base + 1] == ' ' && board[base + 2] == ' ' && board[base + 3] == rook &&
                            !isAttacked(base, en) && !isAttacked(base + 1, en) && !isAttacked(base + 2, en)
                        ) out.add(Move(base, base + 2))
                        if (castling.indexOf(qs) >= 0 && board[base - 1] == ' ' && board[base - 2] == ' ' && board[base - 3] == ' ' && board[base - 4] == rook &&
                            !isAttacked(base, en) && !isAttacked(base - 1, en) && !isAttacked(base - 2, en)
                        ) out.add(Move(base, base - 2))
                    }
                }
            }
        }
        return out
    }

    fun legalMoves(): List<Move> = pseudoMoves().filter { m ->
        val n = apply(m)
        !n.isAttacked(n.kingSquare(whiteToMove), !whiteToMove)
    }

    fun apply(m: Move): Position {
        val b = board.copyOf()
        val p = b[m.from]
        val up = p.uppercaseChar()
        val captured = b[m.to]
        b[m.from] = ' '
        var newEp = -1
        if (up == 'P') {
            if (m.to == ep) b[(m.from / 8) * 8 + m.to % 8] = ' '
            if (abs(m.to - m.from) == 16) newEp = (m.from + m.to) / 2
        }
        b[m.to] = if (m.promo != null) (if (Chess.isWhite(p)) m.promo.uppercaseChar() else m.promo.lowercaseChar()) else p
        if (up == 'K' && abs(m.to % 8 - m.from % 8) == 2) {
            if (m.to % 8 == 6) { b[m.to - 1] = b[m.to + 1]; b[m.to + 1] = ' ' }
            else { b[m.to + 1] = b[m.to - 2]; b[m.to - 2] = ' ' }
        }
        var cr = castling
        fun drop(chars: String) { for (ch in chars) cr = cr.replace(ch.toString(), "") }
        if (up == 'K') drop(if (Chess.isWhite(p)) "KQ" else "kq")
        for (sq in intArrayOf(m.from, m.to)) when (sq) {
            63 -> drop("K"); 56 -> drop("Q"); 7 -> drop("k"); 0 -> drop("q")
            60 -> drop("KQ"); 4 -> drop("kq")
        }
        if (cr.isEmpty()) cr = "-"
        val hm = if (up == 'P' || captured != ' ') 0 else halfmove + 1
        return Position(b, !whiteToMove, cr, newEp, hm, if (whiteToMove) fullmove else fullmove + 1)
    }

    fun status(): GameStatus {
        val chk = inCheck()
        return when {
            legalMoves().isEmpty() -> if (chk) GameStatus.CHECKMATE else GameStatus.STALEMATE
            insufficientMaterial() -> GameStatus.DRAW_MATERIAL
            halfmove >= 100 -> GameStatus.DRAW_50
            chk -> GameStatus.CHECK
            else -> GameStatus.ONGOING
        }
    }

    private fun insufficientMaterial(): Boolean {
        val others = board.filter { it != ' ' && it.uppercaseChar() != 'K' }
        return others.isEmpty() || (others.size == 1 && others[0].uppercaseChar() in "BN")
    }

    /** Notación algebraica estándar (SAN) de una jugada legal en esta posición. */
    fun san(m: Move): String {
        val p = board[m.from]
        val up = p.uppercaseChar()
        val capture = board[m.to] != ' ' || (up == 'P' && m.to == ep)
        val sb = StringBuilder()
        if (up == 'K' && abs(m.to % 8 - m.from % 8) == 2) {
            sb.append(if (m.to % 8 == 6) "O-O" else "O-O-O")
        } else {
            val file = 'a' + m.from % 8
            val rank = '0' + (8 - m.from / 8)
            if (up != 'P') {
                sb.append(up)
                val others = legalMoves().filter { it != m && board[it.from] == p && it.to == m.to }
                if (others.isNotEmpty()) {
                    val sameFile = others.any { it.from % 8 == m.from % 8 }
                    val sameRank = others.any { it.from / 8 == m.from / 8 }
                    when {
                        !sameFile -> sb.append(file)
                        !sameRank -> sb.append(rank)
                        else -> { sb.append(file); sb.append(rank) }
                    }
                }
            } else if (capture) sb.append(file)
            if (capture) sb.append('x')
            sb.append(Chess.sqName(m.to))
            if (m.promo != null) sb.append('=').append(m.promo.uppercaseChar())
        }
        val next = apply(m)
        if (next.legalMoves().isEmpty()) { if (next.inCheck()) sb.append('#') }
        else if (next.inCheck()) sb.append('+')
        return sb.toString()
    }
}
