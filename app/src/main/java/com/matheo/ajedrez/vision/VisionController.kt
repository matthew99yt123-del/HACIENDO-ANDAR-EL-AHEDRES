package com.matheo.ajedrez.vision

import android.graphics.Bitmap
import com.matheo.ajedrez.chess.Chess
import com.matheo.ajedrez.chess.Move
import com.matheo.ajedrez.chess.Position
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class VisionPhase { OFF, SEARCHING, TRACKING }

data class VisionUi(
    val phase: VisionPhase = VisionPhase.OFF,
    val frame: Bitmap? = null,
    val aspect: Float = 0.75f,
    val corners: List<Float> = emptyList(), // x,y normalizados de TL,TR,BR,BL
    val grid: List<Float> = emptyList(),    // 18 líneas: x1,y1,x2,y2 normalizados
    val message: String = "Iniciando cámara…",
    val paused: Boolean = false
)

/**
 * Visión por computadora: detecta el tablero, lo "endereza" (homografía) y compara cada casilla
 * contra la última posición confirmada. Las piezas y sus colores se siguen por las reglas
 * (la partida siempre empieza desde cero), no se clasifican a ciegas.
 */
class VisionController(
    private val position: () -> Position,
    private val onMove: (List<Move>) -> Unit
) {
    val state = MutableStateFlow(VisionUi())

    private class Pt(var x: Float, var y: Float)

    private companion object {
        const val S = 512
        const val CELL = 64
        const val PAD = 10
    }

    private val cornerLock = Any()
    private val corners = arrayOf(Pt(0.15f, 0.2f), Pt(0.85f, 0.2f), Pt(0.85f, 0.8f), Pt(0.15f, 0.8f))

    @Volatile private var phase = VisionPhase.OFF
    @Volatile private var paused = false
    @Volatile private var autoCorners = true
    @Volatile private var calibrateRequested = false
    @Volatile private var rebaseRequested = false
    @Volatile private var rotation = 0
    @Volatile private var grid: List<Float> = emptyList()
    private var orientationKnown = false

    private var ref: Mat? = null
    private var prevCur: Mat? = null
    private var message = "Apuntá la cámara al tablero"
    private var frameCount = 0
    private var calm = 0
    private var stable = 0
    private var lastKey: String? = null
    private var mismatch = 0

    // ---------- API pública (hilo de UI) ----------

    @Synchronized fun start() {
        phase = VisionPhase.SEARCHING
        autoCorners = true; paused = false; orientationKnown = false; rotation = 0
        calibrateRequested = false; rebaseRequested = false
        releaseMats()
        message = "Ajustá las 4 esquinas sobre el área de las 64 casillas y tocá CONFIRMAR."
        recomputeGrid()
    }

    @Synchronized fun stop() {
        phase = VisionPhase.OFF
        releaseMats()
        state.value = VisionUi()
    }

    private fun releaseMats() {
        ref?.release(); ref = null
        prevCur?.release(); prevCur = null
        stable = 0; lastKey = null; mismatch = 0; calm = 0
    }

    fun setCorner(i: Int, x: Float, y: Float) {
        synchronized(cornerLock) { corners[i].x = x; corners[i].y = y }
        autoCorners = false
        recomputeGrid()
    }

    fun cornersSnapshot(): List<Float> = synchronized(cornerLock) { corners.flatMap { listOf(it.x, it.y) } }
    fun autoDetect() { autoCorners = true }
    fun confirm() { calibrateRequested = true }
    fun togglePause() { paused = !paused }
    fun rebaseline() { if (phase == VisionPhase.TRACKING) rebaseRequested = true }
    fun rotateScan() { rotation = (rotation + 1) % 4; if (phase == VisionPhase.TRACKING) rebaseRequested = true }

    @Synchronized fun backToSetup() {
        if (phase == VisionPhase.OFF) return
        phase = VisionPhase.SEARCHING
        releaseMats()
        autoCorners = false
        message = "Armá las piezas en la posición inicial, ajustá las esquinas y tocá CONFIRMAR."
    }

    // ---------- Procesamiento (hilo de análisis) ----------

    @Synchronized fun process(rgba: Mat) {
        if (phase == VisionPhase.OFF) return
        val w = rgba.cols()
        val h = rgba.rows()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        frameCount++
        try {
            when (phase) {
                VisionPhase.SEARCHING -> searching(gray, w, h)
                VisionPhase.TRACKING -> tracking(gray, w, h)
                else -> {}
            }
        } finally {
            gray.release()
        }
        publish(rgba)
    }

    private fun searching(gray: Mat, w: Int, h: Int) {
        if (autoCorners && frameCount % 6 == 0) {
            detectQuad(gray)?.let { q ->
                synchronized(cornerLock) {
                    val far = (0..3).any { abs(corners[it].x - q[it].x) + abs(corners[it].y - q[it].y) > 0.1f }
                    for (i in 0..3) {
                        corners[i].x = if (far) q[i].x else 0.5f * corners[i].x + 0.5f * q[i].x
                        corners[i].y = if (far) q[i].y else 0.5f * corners[i].y + 0.5f * q[i].y
                    }
                }
                recomputeGrid()
            }
        }
        if (calibrateRequested) {
            calibrateRequested = false
            calibrate(gray, w, h)
        }
    }

    private fun calibrate(gray: Mat, w: Int, h: Int) {
        val atStart = position().toFen() == Chess.START_FEN
        if (atStart) {
            val h0 = buildH(w, h, 0)
            val warp0 = Mat()
            Imgproc.warpPerspective(gray, warp0, h0, Size(S.toDouble(), S.toDouble()))
            h0.release()
            val r = detectOrientation(warp0)
            warp0.release()
            if (r < 0) {
                message = "No distingo el lado de las blancas ni la posición inicial. Mejorá la luz, ajustá las esquinas y probá de nuevo."
                return
            }
            rotation = r
            orientationKnown = true
        } else if (!orientationKnown) {
            rotation = 0
        }
        val hh = buildH(w, h, rotation)
        val wr = Mat()
        Imgproc.warpPerspective(gray, wr, hh, Size(S.toDouble(), S.toDouble()))
        hh.release()
        Imgproc.GaussianBlur(wr, wr, Size(5.0, 5.0), 0.0)
        ref?.release()
        ref = wr
        prevCur?.release(); prevCur = null
        stable = 0; lastKey = null; mismatch = 0; calm = 0
        phase = VisionPhase.TRACKING
        message = if (atStart) "Tablero listo. Hacé la jugada de blancas que recomienda la app."
        else "Escaneo recalibrado con la posición actual."
    }

    private fun tracking(gray: Mat, w: Int, h: Int) {
        val refM = ref
        if (refM == null) { phase = VisionPhase.SEARCHING; return }
        val hh = buildH(w, h, rotation)
        val warped = Mat()
        Imgproc.warpPerspective(gray, warped, hh, Size(S.toDouble(), S.toDouble()))
        hh.release()
        Imgproc.GaussianBlur(warped, warped, Size(5.0, 5.0), 0.0)
        val mr = Core.mean(refM).`val`[0]
        val mc = Core.mean(warped).`val`[0]
        val cur = Mat()
        if (mc > 1.0) warped.convertTo(cur, -1, mr / mc, 0.0) else warped.copyTo(cur)
        warped.release()

        val prev = prevCur
        if (prev != null) {
            val dd = Mat()
            Core.absdiff(prev, cur, dd)
            val mv = Core.mean(dd).`val`[0]
            dd.release()
            calm = if (mv < 2.5) calm + 1 else 0
            prev.release()
        }
        prevCur = cur.clone()

        if (paused) { message = "Escaneo en pausa."; cur.release(); return }

        if (rebaseRequested) {
            if (calm >= 3) {
                refM.release(); ref = cur.clone()
                rebaseRequested = false; stable = 0; lastKey = null; mismatch = 0
                message = "Escaneo actualizado con el tablero actual."
            } else message = "Esperando que el tablero quede quieto para actualizar…"
            cur.release()
            return
        }

        val diff = Mat()
        Core.absdiff(refM, cur, diff)
        val d = DoubleArray(64)
        for (s in 0 until 64) {
            val roi = diff.submat(Rect((s % 8) * CELL + PAD, (s / 8) * CELL + PAD, CELL - 2 * PAD, CELL - 2 * PAD))
            d[s] = Core.mean(roi).`val`[0]
            roi.release()
        }
        diff.release()

        val pos = position()
        val res = MoveMatcher.best(pos, d)
        val changed = (0 until 64).filter { d[it] > 12.0 }.joinToString(" ") { Chess.sqName(it) }
        if (res != null) {
            val key = res.moves.joinToString(",") { it.uci() }
            stable = if (key == lastKey) stable + 1 else 1
            lastKey = key
            message = "Detectando jugada… ($changed)"
            if (stable >= VisionConfig.STABLE_FRAMES) {
                refM.release(); ref = cur.clone()
                stable = 0; lastKey = null; mismatch = 0
                message = "Jugada detectada."
                onMove(res.moves)
            }
        } else {
            stable = 0; lastKey = null
            val ex = MoveMatcher.excess(d)
            when {
                ex < 20.0 -> {
                    mismatch = 0
                    message = "Tablero igual a la partida. Esperando jugada de ${if (pos.whiteToMove) "blancas" else "negras"}…"
                }
                calm >= 3 -> {
                    mismatch++
                    message = if (mismatch >= 10)
                        "⚠ El tablero no coincide con ninguna jugada legal (cambios en: $changed). Revisá las piezas, o corregí a mano y tocá ACTUALIZAR ESCANEO."
                    else "Analizando cambios…"
                }
                else -> { mismatch = 0; message = "Movimiento en el tablero…" }
            }
        }
        cur.release()
    }

    // ---------- Geometría ----------

    private fun buildH(w: Int, h: Int, rot: Int): Mat {
        val c = synchronized(cornerLock) { corners.map { Point((it.x * w).toDouble(), (it.y * h).toDouble()) } }
        val src = MatOfPoint2f(*Array(4) { c[(it + rot) % 4] })
        val s = S.toDouble()
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(s, 0.0), Point(s, s), Point(0.0, s))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        src.release(); dst.release()
        return m
    }

    private fun recomputeGrid() {
        try {
            val c = synchronized(cornerLock) { corners.map { Point(it.x.toDouble(), it.y.toDouble()) } }
            val unit = MatOfPoint2f(Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 8.0), Point(0.0, 8.0))
            val dst = MatOfPoint2f(*c.toTypedArray())
            val m = Imgproc.getPerspectiveTransform(unit, dst)
            val pts = ArrayList<Point>()
            for (i in 0..8) { pts.add(Point(0.0, i.toDouble())); pts.add(Point(8.0, i.toDouble())) }
            for (i in 0..8) { pts.add(Point(i.toDouble(), 0.0)); pts.add(Point(i.toDouble(), 8.0)) }
            val inM = MatOfPoint2f(*pts.toTypedArray())
            val outM = MatOfPoint2f()
            Core.perspectiveTransform(inM, outM, m)
            grid = outM.toArray().flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }
            unit.release(); dst.release(); m.release(); inM.release(); outM.release()
        } catch (_: Throwable) {}
    }

    /** Propone las 4 esquinas buscando el contorno cuadrilátero más grande. El usuario puede corregirlas. */
    private fun detectQuad(gray: Mat): Array<Pt>? {
        val scale = 480.0 / max(gray.cols(), gray.rows())
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(small, edges, 40.0, 120.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val imgArea = small.cols().toDouble() * small.rows()
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < imgArea * 0.10 || area > imgArea * 0.95 || area <= bestArea) continue
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
            val peri = Imgproc.arcLength(hull, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull, approx, eps * peri, true)
                val n = approx.total().toInt()
                if (n == 4) { best = approx.toArray(); bestArea = area; break }
                if (n < 4) break
            }
        }
        val sw = small.cols().toDouble()
        val sh = small.rows().toDouble()
        small.release(); edges.release()
        val q = best ?: return null
        // orden TL, TR, BR, BL
        val tl = q.minByOrNull { it.x + it.y }!!
        val br = q.maxByOrNull { it.x + it.y }!!
        val tr = q.minByOrNull { it.y - it.x }!!
        val bl = q.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl).map { Pt((it.x / sw).toFloat(), (it.y / sh).toFloat()) }.toTypedArray()
    }

    /**
     * Con la posición inicial en el tablero: las filas con piezas tienen más bordes que las vacías,
     * y el lado de las blancas es el más claro. Devuelve cuánto rotar las esquinas (0..3) para que
     * las blancas queden abajo, o -1 si no se puede decidir.
     */
    private fun detectOrientation(w0: Mat): Int {
        val b = Mat()
        Imgproc.GaussianBlur(w0, b, Size(5.0, 5.0), 0.0)
        val e = Mat()
        Imgproc.Canny(b, e, 60.0, 160.0)
        fun dens(r0: Int, r1: Int, c0: Int, c1: Int): Double {
            val m = e.submat(r0, r1, c0, c1)
            val v = Core.countNonZero(m).toDouble() / ((r1 - r0) * (c1 - c0))
            m.release(); return v
        }
        fun avg(r0: Int, r1: Int, c0: Int, c1: Int): Double {
            val m = b.submat(r0, r1, c0, c1)
            val v = Core.mean(m).`val`[0]
            m.release(); return v
        }
        val band = 2 * CELL
        val tb = dens(0, band, 0, S) + dens(S - band, S, 0, S)
        val lr = dens(0, S, 0, band) + dens(0, S, S - band, S)
        val result: Int
        if (max(tb, lr) < 1.08 * min(tb, lr)) result = -1
        else if (tb > lr) {
            val top = avg(0, band, 0, S)
            val bottom = avg(S - band, S, 0, S)
            result = if (abs(top - bottom) < 3.0) -1 else if (bottom > top) 0 else 2
        } else {
            val left = avg(0, S, 0, band)
            val right = avg(0, S, S - band, S)
            result = if (abs(left - right) < 3.0) -1 else if (right > left) 3 else 1
        }
        b.release(); e.release()
        return result
    }

    private fun publish(rgba: Mat) {
        val w = rgba.cols()
        val h = rgba.rows()
        val sc = min(1.0, 960.0 / max(w, h))
        val small: Mat
        if (sc < 1.0) { small = Mat(); Imgproc.resize(rgba, small, Size(), sc, sc, Imgproc.INTER_AREA) } else small = rgba
        val bmp = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(small, bmp)
        if (small !== rgba) small.release()
        state.value = VisionUi(phase, bmp, w.toFloat() / h, cornersSnapshot(), grid, message, paused)
    }
}
