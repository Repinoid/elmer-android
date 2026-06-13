package ru.elmer.client.script

import ru.elmer.client.elm.ObdDecoder

import org.json.JSONObject

/**
 * Движок скрипта. Шлёт stage-события:
 *   "ecu"     → запрос к ЭБУ
 *   "step:N/M"→ шаг N из M
 */
class ScriptEngine(
    private val sendCommand: (String) -> String,
    private val onStage: (stage: String, detail: String) -> Unit,
    private val onResult: (stepId: String, cmd: String, raw: String, decoded: String) -> Unit,
    private val onLog: (msg: String) -> Unit
) {
    fun run(scriptJson: String): Boolean {
        val script = try { JSONObject(scriptJson) } catch (e: Exception) { onLog("❌ JSON"); return false }
        val steps = script.optJSONArray("steps") ?: return false
        val title = script.optString("title", "Диагностика")
        val total = steps.length()
        onLog("📋 $title ($total шагов)")

        onStage("ecu", "Запрос к ЭБУ...")
        for (i in 0 until total) {
            val s = steps.getJSONObject(i)
            val cmd = s.optString("cmd", ""); if (cmd.isEmpty()) continue
            val desc = s.optString("desc", s.optString("id", "step"))
            val waitMs = s.optInt("wait", 0)
            if (waitMs > 0) Thread.sleep(waitMs.toLong())
            onStage("step:${i+1}/$total", desc)
            onLog("→ $cmd")
            val raw = sendCommand(cmd)
            val decoded = ObdDecoder.decode(cmd, raw)
            onLog("← $decoded")
            onResult(s.optString("id",""), cmd, raw, decoded)
        }
        return true
    }
}
