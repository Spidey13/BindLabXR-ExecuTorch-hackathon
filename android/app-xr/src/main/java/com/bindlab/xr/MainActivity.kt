package com.bindlab.xr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bindlab.shared.ui.SettingsScreen
import com.bindlab.xr.camera.FrameAnalyzer
import com.bindlab.xr.yolo.YoloDetector
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val vm: XrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var settings by remember { mutableStateOf(false) }
                if (settings) {
                    Column(Modifier.fillMaxSize().background(Color.White)) {
                        OutlinedButton(onClick = { settings = false }, modifier = Modifier.padding(16.dp)) { Text("← Back") }
                        SettingsScreen(vm.config, vm.modelDir, vm::onServerChanged, Modifier.fillMaxSize()) { EngineChooser(vm) }
                    }
                } else LabScreen(vm, onSettings = { settings = true })
            }
        }
    }
}

@Composable
private fun EngineChooser(vm: XrViewModel) {
    val mode by vm.engineMode.collectAsState()
    Text("Inference engine", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == YoloDetector.MODE_AAR, { vm.setEngine(YoloDetector.MODE_AAR) }, label = { Text("ExecuTorch AAR") })
        FilterChip(mode == YoloDetector.MODE_RUNNER, { vm.setEngine(YoloDetector.MODE_RUNNER) }, label = { Text("qnn_executor_runner") })
    }
    Text(
        "AAR tries yolo11n_qnn.pte then yolo11n_xnnpack.pte. Runner execs jniLibs/libqnn_executor_runner.so per frame (slow fallback).",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LabScreen(vm: XrViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Row(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (granted) {
                CameraWithOverlay(vm)
            } else Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        Panel(vm, onSettings, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun CameraWithOverlay(vm: XrViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val res = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
            ).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(res)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(executor, FrameAnalyzer(vm::onFrame)) }
            // Galaxy XR may expose its world-facing camera without a "back" lens facing.
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> provider.availableCameraInfos.firstOrNull()?.cameraSelector
            }
            if (selector == null) {
                vm.modelStatus.value = "no camera available"
            } else {
                provider.unbindAll()
                runCatching { provider.bindToLifecycle(owner, selector, preview, analysis) }
                    .onFailure { vm.modelStatus.value = "camera bind failed: ${it.message}" }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    val dets by vm.detections.collectAsState()
    val frame by vm.frameSize.collectAsState()
    val step = vm.steps.collectAsState().value.getOrNull(vm.stepIndex.collectAsState().value)
    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            // Same 4:3 stream + FIT_CENTER: map normalised boxes into the fitted rectangle.
            val (fw, fh) = frame
            val s = minOf(size.width / fw, size.height / fh)
            val dw = fw * s; val dh = fh * s
            val ox = (size.width - dw) / 2; val oy = (size.height - dh) / 2
            val text = Paint().apply { textSize = 28f; isAntiAlias = true }
            for (d in dets) {
                val color = when {
                    step != null && d.label in step.forbidden -> Color(0xFFFF4D4D)
                    step != null && d.label in step.required -> Color(0xFF3DDC84)
                    else -> Color(0xFFFFC107)
                }
                val tl = Offset(ox + d.box[0] * dw, oy + d.box[1] * dh)
                drawRect(color, tl, GSize(d.box[2] * dw, d.box[3] * dh), style = Stroke(4f))
                text.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText("${d.label} ${"%.2f".format(d.conf)}", tl.x + 6, tl.y + 30, text)
            }
        }
    }
}

@Composable
private fun Panel(vm: XrViewModel, onSettings: () -> Unit, modifier: Modifier) {
    val card by vm.card.collectAsState()
    val steps by vm.steps.collectAsState()
    val index by vm.stepIndex.collectAsState()
    val progress by vm.progress.collectAsState()
    val dets by vm.detections.collectAsState()
    val ms by vm.inferMs.collectAsState()
    val status by vm.modelStatus.collectAsState()
    val connected by vm.ws.connected.collectAsState()
    val runId by vm.runId.collectAsState()
    val runState by vm.runState.collectAsState()
    val log by vm.log.collectAsState()
    val light = Color(0xFFE8EAED)

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val bg = when (card.color) {
            CardColor.GREEN -> Color(0xFF1E7D3A)
            CardColor.RED -> Color(0xFFB3261E)
            CardColor.DONE -> Color(0xFF3949AB)
            CardColor.NEUTRAL -> Color(0xFF2B3138)
        }
        Card(colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(card.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(card.detail, color = Color.White)
                steps.getOrNull(index)?.let { s ->
                    if (s.required.isNotEmpty()) {
                        Text(
                            s.required.joinToString("   ") { "${vm.lab(it)} ${minOf(progress[it] ?: 0, vm.gate.passFrames)}/${vm.gate.passFrames}" },
                            color = Color.White, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        steps.forEachIndexed { i, s ->
            val mark = when { i < index -> "✓"; i == index -> "▶"; else -> "·" }
            Text("$mark ${s.title}", color = if (i == index) Color.White else Color.Gray)
        }
        Text("Detections" + (ms?.let { " · %.0f ms".format(it) } ?: ""), color = light, fontWeight = FontWeight.Bold)
        Text(if (dets.isEmpty()) "none" else dets.take(6).joinToString("\n") { "${it.label} %.2f".format(it.conf) }, color = light)
        Text(status, color = if (status.startsWith("model missing") || status.contains("failed")) Color(0xFFFFB4AB) else Color.Gray,
            style = MaterialTheme.typography.bodySmall)
        Text(
            (if (connected) "● server connected" else "○ server offline (${vm.config.server})") +
                "\nrun ${runId ?: "-"} ${runState ?: ""}" + (if (log.isNotEmpty()) "\n$log" else ""),
            color = Color.Gray, style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::sync) { Text("Sync", color = light) }
            OutlinedButton(onClick = vm::loadDemoProtocol) { Text("Demo steps", color = light) }
            OutlinedButton(onClick = onSettings) { Text("Settings", color = light) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.simulate(true) }) { Text("Sim pass", color = light) }
            OutlinedButton(onClick = { vm.simulate(false) }) { Text("Sim anomaly", color = light) }
        }
    }
}
