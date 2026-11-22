package com.matheo.ajedrez

import com.matheo.ajedrez.chess.Chess
import com.matheo.ajedrez.chess.Position
import com.matheo.ajedrez.vision.MoveMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveMatcherTest {
    private fun d(vararg v: Pair<String, Double>): DoubleArray {
        val a = DoubleArray(64) { 2.0 }
        for ((s, x) in v) a[Chess.parseSq(s)] = x
        return a
    }

    @Test fun peonDosPasos() {
        val r = MoveMatcher.best(Position(), d("e2" to 40.0, "e4" to 40.0))
        assertEquals("e2e4", r!!.moves.single().uci())
    }

    @Test fun sinCambiosNoHayJugada() = assertNull(MoveMatcher.best(Position(), d()))

    @Test fun manoSobreElTableroNoEsJugada() =
        assertNull(MoveMatcher.best(Position(), d("e2" to 40.0, "e4" to 40.0, "d5" to 35.0, "c3" to 30.0, "h5" to 30.0)))

    @Test fun enroqueCorto() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val r = MoveMatcher.best(p, d("e1" to 40.0, "g1" to 40.0, "h1" to 40.0, "f1" to 40.0))
        assertEquals("e1g1", r!!.moves.single().uci())
    }

    @Test fun capturaAlPaso() {
        val p = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        val r = MoveMatcher.best(p, d("e5" to 40.0, "d6" to 20.0, "d5" to 40.0))
        assertEquals("e5d6", r!!.moves.single().uci())
    }

    @Test fun coronacionDevuelveLasCuatroOpciones() {
        val p = Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val r = MoveMatcher.best(p, d("a7" to 40.0, "a8" to 40.0))
        assertEquals(4, r!!.moves.size)
    }
}
