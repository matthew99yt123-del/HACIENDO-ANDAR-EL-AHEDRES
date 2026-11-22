package com.matheo.ajedrez.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.TreeMap

/** Puntaje desde el punto de vista del bando que mueve (como lo manda Stockfish). */
data class EngineLine(val rank: Int, val depth: Int, val cp: Int?, val mate: Int?, val pv: List<String>)
data class Analysis(val lines: List<EngineLine>, val best: String?)

/**
 * Ejecuta Stockfish como proceso local (libstockfish.so en jniLibs/arm64-v8a) y habla UCI.
 * Todo funciona sin internet.
 */
class UciEngine(private val context: Context) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val mutex = Mutex()

    /** Devuelve null si arrancó bien, o un mensaje de error. */
    suspend fun start(): String? = withContext(Dispatchers.IO) {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!bin.exists()) {
            return@withContext "Falta Stockfish: copiá el binario arm64 como app/src/main/jniLibs/arm64-v8a/libstockfish.so y recompilá."
        }
        try {
            val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            process = p
            reader = BufferedReader(InputStreamReader(p.inputStream))
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            send("uci"); waitFor("uciok")
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            send("setoption name Threads value $threads")
            send("setoption name Hash value 32")
            send("setoption name MultiPV value 3")
            send("isready"); waitFor("readyok")
            null
        } catch (e: Exception) {
            "No se pudo iniciar Stockfish: ${e.message}"
        }
    }

    private fun send(cmd: String) {
        writer?.let { it.write(cmd); it.newLine(); it.flush() }
    }

    private fun waitFor(token: String) {
        val r = reader ?: throw IOException("Sin lector")
        while (true) {
            val line = r.readLine() ?: throw IOException("Stockfish se cerró")
            if (line.trim() == token) return
        }
    }

    /** Pide al motor que corte el cálculo actual (el analyze en curso termina enseguida). */
    fun stop() {
        try { send("stop") } catch (_: Exception) {}
    }

    fun destroy() {
        try { send("quit") } catch (_: Exception) {}
        process?.destroy()
    }

    suspend fun analyze(
        fen: String,
        depth: Int,
        moveTimeMs: Int,
        onUpdate: (List<EngineLine>) -> Unit
    ): Analysis = mutex.withLock {
        withContext(Dispatchers.IO) {
            val r = reader ?: throw IOException("Motor no iniciado")
            send("position fen $fen")
            send("go depth $depth movetime $moveTimeMs")
            val lines = TreeMap<Int, EngineLine>()
            var best: String? = null
            while (true) {
                val l = r.readLine() ?: break
                if (l.startsWith("bestmove")) {
                    best = l.split(" ").getOrNull(1)
                    break
                }
                parseInfo(l)?.let {
                    lines[it.rank] = it
                    onUpdate(lines.values.toList())
                }
            }
            Analysis(lines.values.toList(), best)
        }
    }

    private fun parseInfo(line: String): EngineLine? {
        val t = line.split(" ")
        if (t.firstOrNull() != "info") return null
        val pvIdx = t.indexOf("pv")
        val sIdx = t.indexOf("score")
        val dIdx = t.indexOf("depth")
        if (pvIdx < 0 || sIdx < 0 || dIdx < 0) return null
        val depth = t.getOrNull(dIdx + 1)?.toIntOrNull() ?: return null
        val mIdx = t.indexOf("multipv")
        val rank = if (mIdx >= 0) t.getOrNull(mIdx + 1)?.toIntOrNull() ?: 1 else 1
        val kind = t.getOrNull(sIdx + 1) ?: return null
        val v = t.getOrNull(sIdx + 2)?.toIntOrNull() ?: return null
        return EngineLine(rank, depth, if (kind == "cp") v else null, if (kind == "mate") v else null, t.subList(pvIdx + 1, t.size))
    }
}
