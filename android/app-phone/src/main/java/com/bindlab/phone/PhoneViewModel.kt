package com.bindlab.phone

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bindlab.shared.bench.BenchRecorder
import com.bindlab.shared.bench.BenchResult
import com.bindlab.shared.feedback.Haptics
import com.bindlab.shared.inference.ExecuTorchModule
import com.bindlab.shared.inference.ModelStore
import com.bindlab.shared.inference.looksLikeOom
import com.bindlab.shared.net.BackendClient
import com.bindlab.shared.net.BindingRequest
import com.bindlab.shared.net.ConfigStore
import com.bindlab.shared.net.ServerMessage
import com.bindlab.shared.net.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LadderRow(
    val size: String,
    val backend: String,
    val available: Boolean,
    val phase: String, // missing | ready | running | ok | oom | load_failed
    val detail: String = "",
    val result: BenchResult? = null,
)

data class BindingUi(
    val running: Boolean = false,
    val message: String = "",
    val score: BindingHead.Score? = null,
    val targetResidues: List<Int> = emptyList(),
    val binderResidues: List<Int> = emptyList(),
    val latencyMs: Double? = null,
)

class PhoneViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    val config = ConfigStore(app)
    val backend = BackendClient({ config.server })
    val ws = WsClient({ config.server }, "phone", viewModelScope)
    private val haptics = Haptics(app)
    private val prefs = app.getSharedPreferences("ladder", Context.MODE_PRIVATE)

    val target = MutableStateFlow(config.getString("target", SequenceParser.DEMO_TARGET))
    val binder = MutableStateFlow(config.getString("binder", SequenceParser.DEMO_BINDER))
    val runId = MutableStateFlow<String?>(null)
    val runState = MutableStateFlow<String?>(null)
    val lastHaptic = MutableStateFlow<String?>(null)

    private val _ladder = MutableStateFlow(emptyList<LadderRow>())
    val ladder: StateFlow<List<LadderRow>> = _ladder
    val ladderRunning = MutableStateFlow(false)
    val sustained = MutableStateFlow(true)
    private var ladderJob: Job? = null

    val binding = MutableStateFlow(BindingUi())

    val modelDir: String get() = ModelStore.primaryDir(ctx).absolutePath

    init {
        refreshLadder()
        reportCrashedRun()
        ws.start()
        viewModelScope.launch {
            ws.messages.collect { m ->
                when (m) {
                    is ServerMessage.Haptic -> { haptics.play(m.pattern); lastHaptic.value = m.pattern }
                    is ServerMessage.State -> { runId.value = m.runId; runState.value = m.state }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        ws.stop()
    }

    fun setSequences(t: String?, b: String?) {
        t?.let { target.value = it; config.putString("target", it) }
        b?.let { binder.value = it; config.putString("binder", it) }
    }

    // ------------------------------------------------------------------ ladder

    fun refreshLadder() {
        if (ladderRunning.value) return
        _ladder.value = EsmModels.SIZES.flatMap { size ->
            EsmModels.BACKENDS.map { be ->
                val found = ModelStore.find(ctx, EsmModels.base(size, be))
                val prev = _ladder.value.firstOrNull { it.size == size && it.backend == be }
                when {
                    found == null -> LadderRow(size, be, false, "missing", "model missing: ${EsmModels.base(size, be)}.pte")
                    prev?.result != null -> prev.copy(available = true)
                    else -> LadderRow(size, be, true, "ready", found.joinToString { it.name })
                }
            }
        }
    }

    private fun updateRow(size: String, be: String, f: (LadderRow) -> LadderRow) =
        _ladder.update { rows -> rows.map { if (it.size == size && it.backend == be) f(it) else it } }

    fun runLadder() {
        if (ladderRunning.value) return
        refreshLadder()
        ladderRunning.value = true
        val seq = target.value
        val recorder = BenchRecorder(ctx)
        ladderJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                for (row in _ladder.value.filter { it.available }) {
                    val files = ModelStore.find(ctx, EsmModels.base(row.size, row.backend)) ?: continue
                    updateRow(row.size, row.backend) { it.copy(phase = "running", detail = "starting") }
                    // If the process dies here (native OOM / LMK), the next launch reports it as "oom".
                    prefs.edit().putString(KEY_INFLIGHT, "${row.size}|${row.backend}").commit()
                    val (engine, path) = ModelStore.engineFor(files) { ExecuTorchModule(ctx) }
                    val result = try {
                        recorder.measure(
                            "s25", EsmModels.modelName(row.size), row.backend, TokenDtypeAdapter(engine), path,
                            tokenInputs(seq), sustained.value,
                        ) { p -> updateRow(row.size, row.backend) { it.copy(detail = p) } }
                    } catch (e: OutOfMemoryError) {
                        engine.close()
                        BenchResult("s25", EsmModels.modelName(row.size), row.backend, status = "oom", error = "Java OOM")
                    }
                    prefs.edit().remove(KEY_INFLIGHT).commit()
                    updateRow(row.size, row.backend) { it.copy(phase = result.status, detail = summary(result), result = result) }
                    post(result, row)
                    System.gc()
                }
            } finally {
                prefs.edit().remove(KEY_INFLIGHT).apply()
                ladderRunning.value = false
            }
        }
    }

    fun cancelLadder() {
        ladderJob?.cancel()
    }

    private suspend fun post(r: BenchResult, row: LadderRow) {
        runCatching { backend.postBench(r) }.onFailure { e ->
            updateRow(row.size, row.backend) { it.copy(detail = it.detail + "\nPOST /bench failed: ${e.message}") }
        }
    }

    private fun reportCrashedRun() {
        val inflight = prefs.getString(KEY_INFLIGHT, null) ?: return
        prefs.edit().remove(KEY_INFLIGHT).apply()
        val (size, be) = inflight.split("|").let { it[0] to it.getOrElse(1) { "qnn" } }
        val r = BenchResult(
            "s25", EsmModels.modelName(size), be, status = "oom",
            error = "app process was killed during this model (native OOM / low-memory killer)",
        )
        updateRow(size, be) { it.copy(phase = "oom", detail = r.error!!, result = r) }
        viewModelScope.launch { post(r, LadderRow(size, be, true, "oom")) }
    }

    private fun summary(r: BenchResult): String = if (r.status == "ok") {
        "load ${fmt(r.loadMs)} ms · p50 ${fmt(r.p50Ms)} · p95 ${fmt(r.p95Ms)} ms · peak ${fmt(r.peakRssMb)} MB\n" +
            "thermal ${fmt(r.thermalHeadroom, 2)} · battery ${fmt(r.batteryDelta, 2)}% · sustained ${fmt(r.sustainedS)} s" +
            (r.error?.let { "\n$it" } ?: "")
    } else "${r.status}: ${r.error}"

    // ------------------------------------------------------------------ binding

    fun runBinding(size: String, be: String) {
        if (binding.value.running) return
        val t = EsmTokenizer.clean(target.value)
        val b = EsmTokenizer.clean(binder.value)
        if (t.isEmpty() || b.isEmpty()) {
            binding.value = BindingUi(message = "scan or enter both sequences first"); return
        }
        binding.value = BindingUi(running = true, message = "loading ${EsmModels.base(size, be)}…")
        viewModelScope.launch(Dispatchers.Default) {
            val ui = try {
                EsmEmbedder(ctx, size, be).open().use { emb ->
                    binding.update { it.copy(message = "loaded in ${emb.loadMs} ms, embedding…") }
                    val et = emb.embed(t)
                    val eb = emb.embed(b)
                    val score = BindingHead.load(ctx, size)?.score(et.embedding, eb.embedding)
                        ?: BindingHead.cosine(et.embedding, eb.embedding)
                    var tr = emptyList<Int>()
                    var br = emptyList<Int>()
                    if (et.contacts != null && InterfaceResidues.fits(t, b)) {
                        val pair = emb.embed(InterfaceResidues.linked(t, b))
                        pair.contacts?.let { c ->
                            val res = InterfaceResidues.fromContacts(c.data, c.shape[2].toInt(), t.length, b.length)
                            tr = res.first; br = res.second
                        }
                    }
                    val latency = et.latencyMs + eb.latencyMs
                    val req = BindingRequest(t, b, EsmModels.modelName(size), be, score.value, tr, br, latency)
                    val posted = runCatching { backend.postBinding(req) }
                    posted.getOrNull()?.let { runId.value = it.runId; runState.value = it.state }
                    BindingUi(
                        message = posted.fold({ "run ${it.runId}: ${it.state}" }, { "POST /binding failed: ${it.message}" }),
                        score = score, targetResidues = tr, binderResidues = br, latencyMs = latency,
                    )
                }
            } catch (e: OutOfMemoryError) {
                BindingUi(message = "oom: ${EsmModels.base(size, be)} does not fit")
            } catch (e: Exception) {
                BindingUi(message = (if (looksLikeOom(e)) "oom: " else "") + (e.message ?: e.javaClass.simpleName))
            }
            binding.value = ui
        }
    }

    fun onServerChanged() = ws.restart()

    companion object {
        private const val KEY_INFLIGHT = "inflight"
        fun fmt(v: Double?, digits: Int = 1) = v?.let { "%.${digits}f".format(it) } ?: "–"
    }
}
