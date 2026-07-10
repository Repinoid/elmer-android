package ru.elmer.client.ui

import android.content.Context
import android.os.Build
import org.json.JSONObject
import ru.elmer.client.Config
import ru.elmer.client.db.SessionDb
import ru.elmer.client.elm.ElmChecker
import ru.elmer.client.elm.ObdDecoder
import ru.elmer.client.script.DynamicCollector

class DiagnosisRunner(
    private val context: Context,
    private val checker: ElmChecker,
    private val db: SessionDb,
    private val carInfo: String,
    private var dynamicSamples: MutableList<List<DynamicCollector.SampleResponse>>?
) {
    var onStage: ((String, String) -> Unit)? = null
    var onDone: ((Boolean) -> Unit)? = null

    fun run() {
        Thread {
            try {
                val client = Config.client(context)
                val scriptJson = client.downloadScript()
                val script = org.json.JSONObject(scriptJson as String)
                val stepsArr = script.getJSONArray("steps")
                val total = stepsArr.length()
                onStage?.invoke("info", "$total steps")

                val results = mutableListOf<Map<String, String?>>()
                for (i in 0 until total) {
                    val s = stepsArr.getJSONObject(i)
                    val cmd = s.optString("cmd", "")
                    if (cmd.isEmpty()) continue
                    val desc = s.optString("desc", "")
                    onStage?.invoke("step:${i + 1}/$total", "$desc ($cmd)")

                    val raw = try { checker.sendRaw(cmd) } catch (e: Exception) { "(err)" }
                    val decoded = ObdDecoder.decode(cmd, raw)
                    results.add(mapOf("step_id" to s.optString("id", ""), "cmd" to cmd, "raw" to raw, "decoded" to decoded))
                }

                val count = results.size
                val sid = db.createSession(scriptJson, "Diagnostics", Config.HOST)
                results.forEach { r -> db.addResponse(sid, r["step_id"] ?: "", r["cmd"] ?: "", r["raw"] ?: "", r["decoded"] ?: "") }

                val dynForUpload = dynamicSamples?.map { batch ->
                    batch.map { r -> mapOf("step_id" to r.stepId, "cmd" to r.cmd, "raw" to r.raw, "decoded" to r.decoded, "ts" to r.ts.toString()) }
                }
                dynamicSamples = null

                onStage?.invoke("upload", "Upload $count responses...")
                val clientInfo = mapOf("phone_model" to Build.MODEL, "app_version" to "1.18.0-dev")
                val resp = client.uploadSession(sid, results, clientInfo, carInfo, dynForUpload)

                if (resp != null) {
                    db.markUploaded(sid)
                    val d = resp.optString("diagnosis", "")
                    if (d.isNotEmpty()) {
                        db.saveDiagnosis(sid, d)
                        onStage?.invoke("done", "DIAGNOSIS\n$d")
                    } else {
                        onStage?.invoke("done", "Empty LLM response")
                    }
                } else {
                    onStage?.invoke("done", "Server unavailable. Data saved.")
                }
                onDone?.invoke(true)
            } catch (e: Exception) {
                onStage?.invoke("error", "Error: ${e.message}")
                onDone?.invoke(false)
            }
        }.apply { name = "DiagnosisRunner"; isDaemon = true; start() }
    }
}
