package com.matheo.ajedrez

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matheo.ajedrez.vision.FrameAnalyzer
import com.matheo.ajedrez.vision.VisionController
import com.matheo.ajedrez.vision.VisionPhase
import java.util.concurrent.Executors

@Composable
fun CameraSection(vm: GameViewModel) {
    val ui = vm.ui
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { denied = false; vm.startCamera() }
        else { denied = true; vm.setNote("La app necesita el permiso de cámara para escanear el tablero.") }
    }
    Button(
        onClick = {
            if (ui.cameraOn) vm.stopCamera()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                denied = false; vm.startCamera()
            } else launcher.launch(Manifest.permission.CAMERA)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (ui.cameraOn) "CERRAR CÁMARA" else "ESCANEAR TABLERO") }

    if (denied && !ui.cameraOn) {
        Text(
            "Si no te vuelve a aparecer el cartel de permiso, habilitá la cámara en Ajustes de la app y volvé a tocar ESCANEAR TABLERO.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ABRIR AJUSTES DE LA APP") }
    }

    if (ui.cameraOn) {
        CameraBinder(vm.vision)
        CameraPanel(vm.vision)
    }
}

@Composable
private fun CameraBinder(vision: VisionController) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, FrameAnalyzer(vision))
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}

@Composable
private fun CameraPanel(vision: VisionController) {
    val v by vision.state.collectAsState()
    var dragging by remember { mutableIntStateOf(-1) }
    val searching = v.phase == VisionPhase.SEARCHING

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(v.aspect).background(Color.Black)) {
                v.frame?.let {
                    Image(it.asImageBitmap(), contentDescription = "Cámara", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(searching) {
                        if (searching) detectDragGestures(
                            onDragStart = { o ->
                                val c = vision.cornersSnapshot()
                                var best = -1
                                var bd = 110f
                                for (i in 0..3) {
                                    val dx = c[i * 2] * size.width - o.x
                                    val dy = c[i * 2 + 1] * size.height - o.y
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (dist < bd) { bd = dist; best = i }
                                }
                                dragging = best
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 }
                        ) { change, _ ->
                            change.consume()
                            if (dragging >= 0) vision.setCorner(
                                dragging,
                                (change.position.x / size.width).coerceIn(0f, 1f),
                                (change.position.y / size.height).coerceIn(0f, 1f)
                            )
                        }
                    }
                ) {
                    val w = size.width
                    val h = size.height
                    val col = if (searching) Color(0xFFFFEB3B) else Color(0xFF4CAF50)
                    val g = v.grid
                    var i = 0
                    while (i + 3 < g.size) {
                        drawLine(col, Offset(g[i] * w, g[i + 1] * h), Offset(g[i + 2] * w, g[i + 3] * h), strokeWidth = 2f)
                        i += 4
                    }
                    if (searching && v.corners.size == 8) for (k in 0..3) {
                        drawCircle(Color(0xFFFF5722), radius = 18f, center = Offset(v.corners[k * 2] * w, v.corners[k * 2 + 1] * h))
                    }
                }
            }
            Text(v.message)
            if (searching) {
                Text("Arrastrá los círculos naranjas a las 4 esquinas del área de juego (las 64 casillas, sin el marco). Dejá el celular fijo, apuntando al tablero.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vision.confirm() }, Modifier.weight(1f)) { Text("CONFIRMAR TABLERO") }
                    OutlinedButton(onClick = { vision.autoDetect() }, Modifier.weight(1f)) { Text("AUTODETECTAR") }
                }
            } else if (v.phase == VisionPhase.TRACKING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vision.togglePause() }, Modifier.weight(1f)) { Text(if (v.paused) "REANUDAR" else "PAUSAR") }
                    OutlinedButton(onClick = { vision.rebaseline() }, Modifier.weight(1f)) { Text("ACTUALIZAR ESCANEO") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vision.rotateScan() }, Modifier.weight(1f)) { Text("GIRAR ESCANEO") }
                    OutlinedButton(onClick = { vision.backToSetup() }, Modifier.weight(1f)) { Text("REAJUSTAR ESQUINAS") }
                }
            }
        }
    }
}
