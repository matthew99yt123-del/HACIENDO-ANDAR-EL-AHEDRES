package com.matheo.ajedrez.vision

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Convierte cada cuadro de CameraX (RGBA) en un Mat orientado y se lo pasa al VisionController (~8 cuadros/s). */
class FrameAnalyzer(private val vision: VisionController) : ImageAnalysis.Analyzer {
    private var last = 0L
    private var buf = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 120) return
            last = now
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val stride = plane.rowStride
            val bb = plane.buffer
            val mat = Mat(h, w, CvType.CV_8UC4)
            if (stride == w * 4) {
                if (buf.size != w * h * 4) buf = ByteArray(w * h * 4)
                bb.rewind()
                bb.get(buf, 0, w * h * 4)
                mat.put(0, 0, buf)
            } else {
                val row = ByteArray(w * 4)
                for (y in 0 until h) {
                    bb.position(y * stride)
                    bb.get(row, 0, w * 4)
                    mat.put(y, 0, row)
                }
            }
            val rot = Mat()
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(mat, rot, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(mat, rot, Core.ROTATE_180)
                270 -> Core.rotate(mat, rot, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> mat.copyTo(rot)
            }
            mat.release()
            vision.process(rot)
            rot.release()
        } catch (t: Throwable) {
            Log.e("FrameAnalyzer", "Error procesando cuadro", t)
        } finally {
            image.close()
        }
    }
}
