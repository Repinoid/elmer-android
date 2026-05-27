package ru.elmer.client

import android.util.Log
import org.json.JSONObject

/**
 * Движок выполнения диагностического скрипта.
 *
 * Скрипт — JSON с шагами:
 *   {"id": "...", "cmd": "010C", "desc": "..."}           — OBD-команда
 *   {"id": "...", "prompt": "...", "wait_for_user": true} — запрос водителю
 *
 * Движок:
 *   1. Проходит шаги последовательно
 *   2. Для cmd-шагов: отправляет через [sendCommand], декодирует через [ObdDecoder]
 *   3. Для prompt-шагов: вызывает [onPrompt], ждёт [waitForResume]
 *   4. Каждый результат пишет через [onResult]
 */
class ScriptEngine(
    private val sendCommand: (String) -> String,
    private val onPrompt: (text: String) -> Unit,
    private val waitForResume: () -> Boolean,   // true = продолжаем, false = стоп
    private val onResult: (stepId: String, cmd: String, raw: String, decoded: String) -> Unit,
    private val onLog: (msg: String) -> Unit
) {
    companion object {
        private const val TAG = "ScriptEngine"
    }

    /** Выполняет скрипт. Возвращает true если дошли до конца, false если прервано. */
    fun run(scriptJson: String): Boolean {
        val script = try {
            JSONObject(scriptJson)
        } catch (e: Exception) {
            onLog("❌ Плохой JSON скрипта")
            return false
        }

        val title = script.optString("title", "Диагностика")
        val steps = script.optJSONArray("steps")
        if (steps == null || steps.length() == 0) {
            onLog("❌ Скрипт пуст")
            return false
        }

        onLog("📋 Скрипт: $title (${steps.length()} шагов)")

        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val stepId = step.optString("id", "step_$i")
            val cmd = step.optString("cmd", "")
            val prompt = step.optString("prompt", "")
            val desc = step.optString("desc", stepId)
            val waitUser = step.optBoolean("wait_for_user", false)

            // ── Prompt водителю ──
            if (prompt.isNotEmpty()) {
                onPrompt(prompt)
                if (waitUser) {
                    onLog("⏸ Жду водителя: $prompt")
                    if (!waitForResume()) return false
                    onPrompt("")  // скрыть
                }
                continue
            }

            if (cmd.isEmpty()) continue

            // ── OBD команда ──
            onLog("─── $desc ───")
            onLog("→ $cmd")

            val raw = sendCommand(cmd)
            val decoded = ObdDecoder.decode(cmd, raw)

            onLog("← $raw")
            if (decoded != raw.trim()) onLog("   → $decoded")

            onResult(stepId, cmd, raw, decoded)
        }

        return true
    }
}
