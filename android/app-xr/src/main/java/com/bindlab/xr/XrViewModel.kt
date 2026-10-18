package com.bindlab.xr

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bindlab.shared.feedback.Haptics
import com.bindlab.shared.inference.ModelStore
import com.bindlab.shared.net.BackendClient
import com.bindlab.shared.net.ClientMessages
import com.bindlab.shared.net.ConfigStore
import com.bindlab.shared.net.Detection
import com.bindlab.shared.net.LabStepRequest
import com.bindlab.shared.net.ProtocolStep
import com.bindlab.shared.net.ServerMessage
import com.bindlab.shared.net.WsClient
import com.bindlab.xr.feedback.SpatialAudio
import com.bindlab.xr.protocol.StepGate
import com.bindlab.xr.yolo.YoloDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

enum class CardColor { NEUTRAL, GREEN, RED, DONE }

data class StepCard(val color: CardColor, val title: String, val detail: String)

class XrViewModel(app: Application) : AndroidViewModel(app) {
    val config = ConfigStore(app)
    private val backend = BackendClient({ config.server })
    val ws = WsClient({ config.server }, "xr", viewModelScope)
    private val haptics = Haptics(app)
    private val audio = SpatialAudio(app)
    val gate = StepGate()

    val engineMode = MutableStateFlow(config.getString("engine", YoloDetector.MODE_AAR))
    @Volatile private var detector: YoloDetector? = null
    @Volatile private var detectorMode: String? = null

    val detections = MutableStateFlow(emptyList<Detection>())
    val frameSize = MutableStateFlow(4 to 3)
    val inferMs = MutableStateFlow<Double?>(null)
    val modelStatus = MutableStateFlow("starting camera…")
    val steps = MutableStateFlow(emptyList<ProtocolStep>())
    val stepIndex = MutableStateFlow(0)
    val progress = MutableStateFlow(emptyMap<String, Int>())
    val runId = MutableStateFlow<String?>(null)
    val runState = MutableStateFlow<String?>(null)
    val labNames = MutableStateFlow(emptyMap<String, String>())
    val card = MutableStateFlow(StepCard(CardColor.NEUTRAL, "Waiting for protocol", "Steps are beamed from the backend"))
    val log = MutableStateFlow("")

    @Volatile private var lastFrame: Bitmap? = null
    private var lastWsSend = 0L
    private var seenRequired = emptySet<String>()

    val modelDir: String get() = ModelStore.primaryDir(getApplication()).absolutePath

    init {
        ws.start()
        viewModelScope.launch {
            ws.messages.collect { m ->
                when (m) {
                    is ServerMessage.Protocol -> {
                        runId.value = m.runId
                        labNames.value = m.labNames
                        applyProtocol(m.steps, 0)
                    }
                    is ServerMessage.State -> onState(m)
                    is ServerMessage.Haptic -> haptics.play(m.pattern)
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { sync() }
    }

    override fun onCleared() {
        ws.stop()
        audio.release()
        detector?.close()
    }

    // ------------------------------------------------------------------ protocol + state

    private fun applyProtocol(newSteps: List<ProtocolStep>, index: Int) {
        gate.setProtocol(newSteps, index)
        steps.value = newSteps
        stepIndex.value = index
        refreshCard()
    }

    private fun onState(m: ServerMessage.State) {
        runId.value = m.runId
        runState.value = m.state
        when {
            m.state.startsWith("STEP_") -> {
                val i = m.stepIndex ?: (m.state.removePrefix("STEP_").toIntOrNull()?.minus(1) ?: 0)
                if (i != stepIndex.value || gate.isPassed) {
                    gate.setIndex(i); stepIndex.value = i; refreshCard()
                }
            }
            m.state == "APPROVAL" || m.state == "BEAMED" -> {
                stepIndex.value = steps.value.size
                card.value = StepCard(CardColor.DONE, "Protocol complete", "Waiting for PI approval (${m.state})")
            }
        }
    }

    /** Pull protocol + latest run over REST (if the app started after the WS broadcast). */
    fun sync() {
        viewModelScope.launch {
            runCatching {
                val p = backend.protocol()
                val run = backend.latestRun()
                if (run != null) { runId.value = run.first; runState.value = run.second }
                val idx = if (run != null && run.second.startsWith("STEP_")) run.third else 0
                if (steps.value.isEmpty() || run?.second?.startsWith("STEP_") == true) applyProtocol(p, idx)
                log.value = "synced: ${p.size} steps, run ${run?.first ?: "-"} ${run?.second ?: ""}"
            }.onFailure { log.value = "sync failed: ${it.message}" }
        }
    }

    fun loadDemoProtocol() = applyProtocol(DEMO_STEPS, 0)

    private fun refreshCard() {
        val s = gate.current
        card.value = if (s == null) {
            if (steps.value.isEmpty()) StepCard(CardColor.NEUTRAL, "Waiting for protocol", "Steps are beamed from the backend")
            else StepCard(CardColor.DONE, "Protocol complete", "All ${steps.value.size} steps passed")
        } else StepCard(
            CardColor.NEUTRAL, "Step ${stepIndex.value + 1}/${steps.value.size}: ${s.title}",
            "Show: ${s.required.joinToString { lab(it) }}" +
                if (s.forbidden.isNotEmpty()) "  ·  Avoid: ${s.forbidden.joinToString { lab(it) }}" else "",
        )
    }

    fun lab(coco: String) = labNames.value[coco]?.let { "$it ($coco)" } ?: coco

    // ------------------------------------------------------------------ frames (analysis thread)

    fun onFrame(bmp: Bitmap) {
        val mode = engineMode.value
        if (detector == null || detectorMode != mode) {
            detector?.close()
            detector = YoloDetector(getApplication(), mode); detectorMode = mode
        }
        val det = detector!!
        lastFrame = bmp
        frameSize.value = bmp.width to bmp.height
        val frame = det.detect(bmp)
        modelStatus.value = det.status
        if (frame == null) { detections.value = emptyList(); inferMs.value = null; return }
        detections.value = frame.detections
        inferMs.value = frame.inferMs
        handleFrame(frame.detections)
    }

    private fun handleFrame(dets: List<Detection>) {
        val step = gate.current
        val event = gate.onFrame(dets)
        progress.value = gate.progress.toMap()
        step?.let { s ->
            // Cue toward a required object the first frame it comes into view.
            val now = dets.filter { it.conf > gate.conf && it.label in s.required }
            now.firstOrNull { it.label !in seenRequired }?.let { audio.play("tick", it.centerX) }
            seenRequired = now.map { it.label }.toSet()

            val t = SystemClock.elapsedRealtime()
            val rid = runId.value
            if (rid != null && t - lastWsSend > 250) {
                lastWsSend = t
                ws.send(ClientMessages.detections(rid, s.id, dets))
            }
        }
        when (event) {
            is StepGate.Event.Passed -> onPassed(event)
            is StepGate.Event.Anomaly -> onAnomaly(event)
            null -> if (step != null && !gate.isAnomaly && card.value.color == CardColor.RED) refreshCard()
        }
    }

    private fun onPassed(e: StepGate.Event.Passed) {
        val x = e.detections.filter { it.label in e.step.required }.map { it.centerX }.average().toFloat().takeIf { !it.isNaN() } ?: 0.5f
        card.value = StepCard(CardColor.GREEN, "✓ ${e.step.title}", "Unlocked: ${e.step.required.joinToString { lab(it) }}")
        audio.play("success", x)
        haptics.play("success")
        ws.send(ClientMessages.haptic("success"))
        val idx = stepIndex.value
        report(e.step, "passed", e.detections) { ok ->
            // The server's state message normally advances us; advance locally if it didn't (offline/demo).
            delay(1500)
            if (stepIndex.value == idx) {
                gate.setIndex(idx + 1); stepIndex.value = idx + 1; refreshCard()
            }
            if (!ok) log.value += " (advanced locally)"
        }
    }

    private fun onAnomaly(e: StepGate.Event.Anomaly) {
        val box = e.detections.firstOrNull { it.label == e.label }
        card.value = StepCard(CardColor.RED, "⚠ Anomaly: ${lab(e.label)}", "Not allowed in \"${e.step.title}\". Remove it to continue.")
        audio.play("anomaly", box?.centerX ?: 0.5f)
        haptics.play("anomaly")
        report(e.step, "anomaly", e.detections) { ok ->
            // On success the server already buzzes the phone for anomalies; only mirror it ourselves otherwise.
            if (!ok) ws.send(ClientMessages.haptic("anomaly"))
        }
    }

    private fun report(step: ProtocolStep, status: String, dets: List<Detection>, then: suspend (Boolean) -> Unit) {
        val rid = runId.value
        val snap = lastFrame
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (rid == null) {
                log.value = "no run_id (demo protocol): /lab/step not sent"; false
            } else runCatching {
                backend.postLabStep(LabStepRequest(rid, step.id, status, dets, snap?.let(::jpegB64)))
            }.fold({ log.value = "POST /lab/step ${step.id} $status → ${it.optString("state")}"; true },
                { log.value = "POST /lab/step failed: ${it.message}"; false })
            then(ok)
        }
    }

    // ------------------------------------------------------------------ debug / settings

    fun simulate(pass: Boolean) {
        val s = gate.current ?: return
        if (pass) {
            onPassed(StepGate.Event.Passed(s, s.required.map { Detection(it, 0.99f, floatArrayOf(0.4f, 0.4f, 0.2f, 0.2f)) }))
        } else {
            val label = s.forbidden.firstOrNull() ?: "scissors"
            onAnomaly(StepGate.Event.Anomaly(s, label, listOf(Detection(label, 0.99f, floatArrayOf(0.8f, 0.4f, 0.15f, 0.2f)))))
        }
    }

    fun setEngine(mode: String) {
        config.putString("engine", mode)
        engineMode.value = mode
    }

    fun onServerChanged() {
        ws.restart(); sync()
    }

    private fun jpegB64(bmp: Bitmap): String {
        val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        /** Same as server/protocol.json, for running with no backend. */
        val DEMO_STEPS = listOf(
            ProtocolStep("s1", "Operator at bench", listOf("person"), emptyList()),
            ProtocolStep("s2", "Pick up reagent", listOf("bottle"), listOf("scissors")),
            ProtocolStep("s3", "Prepare beaker", listOf("cup"), listOf("scissors")),
            ProtocolStep("s4", "Record in lab notebook", listOf("book"), emptyList()),
        )
    }
}
