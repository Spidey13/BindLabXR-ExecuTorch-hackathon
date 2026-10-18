package com.bindlab.phone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun QrScanScreen(vm: PhoneViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var scanning by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Point the camera at the sample QR code") }
    val target by vm.target.collectAsState()
    val binder by vm.binder.collectAsState()

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Scan sample", style = MaterialTheme.typography.headlineSmall)
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            when {
                !granted -> Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                scanning -> QrCamera { raw ->
                    val parsed = SequenceParser.parse(raw)
                    if (parsed != null) {
                        vm.setSequences(parsed.target, parsed.binder)
                        status = "Loaded from QR: target ${parsed.target?.length ?: "-"} aa, binder ${parsed.binder?.length ?: "-"} aa"
                        scanning = false
                    } else status = "QR found but no sequence in it: ${raw.take(60)}"
                }
                else -> OutlinedButton(onClick = { scanning = true }) { Text("Scan again") }
            }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(target, { vm.setSequences(it, null) }, label = { Text("Target (${target.length} aa)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(binder, { vm.setSequences(null, it) }, label = { Text("Binder (${binder.length} aa)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.setSequences(SequenceParser.DEMO_TARGET, SequenceParser.DEMO_BINDER) }) { Text("Demo pair") }
        }
        if (target.length > EsmTokenizer.MAX_RESIDUES || binder.length > EsmTokenizer.MAX_RESIDUES) {
            Text("Sequences longer than ${EsmTokenizer.MAX_RESIDUES} aa are truncated (L=256).", color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCamera(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var done = false
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || done) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull { it.rawValue != null }?.rawValue?.let { if (!done) { done = true; onCode(it) } }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, Modifier.fillMaxWidth().height(300.dp))
}
