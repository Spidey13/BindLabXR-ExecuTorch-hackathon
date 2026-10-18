package com.bindlab.shared.net

import com.bindlab.shared.bench.BenchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}

data class BindingRequest(
    val targetSeq: String,
    val binderSeq: String,
    val model: String,
    val backend: String,
    val score: Float,
    val targetResidues: List<Int>,
    val binderResidues: List<Int>,
    val latencyMs: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("target_seq", targetSeq).put("binder_seq", binderSeq)
        .put("model", model).put("backend", backend).put("score", score.toDouble())
        .put("target_residues", JSONArray(targetResidues)).put("binder_residues", JSONArray(binderResidues))
        .put("latency_ms", latencyMs)
}

data class BindingResponse(val runId: String, val state: String)

data class LabStepRequest(
    val runId: String,
    val stepId: String,
    val status: String, // "passed" | "anomaly"
    val detections: List<Detection>,
    val snapshotB64: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("run_id", runId).put("step_id", stepId).put("status", status)
        .put("detections", detections.toJson())
        .put("snapshot_b64", snapshotB64 ?: JSONObject.NULL)
}

class BackendException(message: String) : IOException(message)

/** REST side of the backend contract (see server/main.py). */
class BackendClient(private val baseUrl: () -> String, private val http: OkHttpClient = Http.client) {

    suspend fun postBench(r: BenchResult): JSONObject = post("/bench", r.toJson())

    suspend fun postBinding(r: BindingRequest): BindingResponse =
        post("/binding", r.toJson()).let { BindingResponse(it.optString("run_id"), it.optString("state")) }

    suspend fun postLabStep(r: LabStepRequest): JSONObject = post("/lab/step", r.toJson())

    suspend fun health(): String = get("/health")

    suspend fun protocol(): List<ProtocolStep> = ServerMessage.parseSteps(JSONObject(get("/protocol")).optJSONArray("steps"))

    /** Most recent run as (run_id, state, step_index), or null. */
    suspend fun latestRun(): Triple<String, String, Int>? {
        val arr = JSONArray(get("/runs"))
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return Triple(r.optString("id"), r.optString("state"), r.optInt("step_index", 0))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl().trimEnd('/') + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw BackendException("POST $path -> ${resp.code}: ${text.take(300)}")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl().trimEnd('/') + path).get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw BackendException("GET $path -> ${resp.code}: ${text.take(300)}")
            text
        }
    }
}
