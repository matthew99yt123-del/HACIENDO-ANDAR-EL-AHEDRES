package com.matheo.ajedrez

import com.matheo.ajedrez.chess.Position
import org.junit.Assert.assertEquals
import org.junit.Test

class PerftTest {
    private fun perft(p: Position, d: Int): Long {
        if (d == 0) return 1
        var n = 0L
        for (m in p.legalMoves()) n += perft(p.apply(m), d - 1)
        return n
    }

    @Test fun inicial() = assertEquals(197281L, perft(Position(), 4))

    @Test fun kiwipete() =
        assertEquals(97862L, perft(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3))

    @Test fun finales() =
        assertEquals(43238L, perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4))

    @Test fun promociones() =
        assertEquals(9467L, perft(Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"), 3))

    @Test fun san() {
        var p = Position()
        val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5")
        val sans = mutableListOf<String>()
        for (u in moves) {
            val m = p.legalMoves().first { it.uci() == u }
            sans.add(p.san(m)); p = p.apply(m)
        }
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5"), sans)
    }
}
