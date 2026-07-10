package ru.elmer.client.ui

import android.os.Build
import org.json.JSONObject
import ru.elmer.client.Config
import ru.elmer.client.db.SessionDb
import ru.elmer.client.elm.ElmChecker
import ru.elmer.client.elm.ObdDecoder
import ru.elmer.client.script.DynamicCollector

/**
 * Исполнитель диагностики.
 *
 * Выполняет полный цикл: скачать скрипт → опросить ЭБУ → загрузить на сервер → диагноз.
 * Работает в фоновом потоке. Пишет прогресс через callback.
 *
 * НЕ зависит от Activity — можно тестировать отдельно.
 *
 * Использование:
 *   val runner = DiagnosisRunner(checker, db, carInfo, dynamicSamples)
 *   runner.run(
 *     onStage = { stage, detail -> ... },
 *     onDone = { success -> ... }
 *   )
 */
class DiagnosisRunner(
    private val checker: ElmChecker,
    private val db: SessionDb,
    private val carInfo: String,
    private var dynamicSamples: MutableList<List<DynamicCollector.SampleResponse>>?
) {
    /** Callback для обновления UI: (этап, детали) */
    var onStage: ((String, String) -> Unit)? = null

    /** Callback по завершении: (успех) */
    var onDone: ((Boolean) -> Unit)? = null

    /**
     * Запустить диагностику.
     * Скачивает скрипт с сервера, выполняет шаги, отправляет батч на сервер.
     * Вызывает onStage для прогресса, onDone по завершении.
     */
    fun run() {
        Thread {
            try {
                // ── Скачать скрипт ──
                val client = Config.client(null) ?: run {
                    onStage?.invoke("error", "Нет конфигурации сервера")
                    onDone?.invoke(false); return@Thread
                }
                val scriptJson = client.downloadScript()
                val script = JSONObject(scriptJson)
                val stepsArr = script.getJSONArray("steps")
                val total = stepsArr.length()
                onStage?.invoke("info", "📋 $total шагов")

                // ── Выполнить шаги ──
                val results = mutableListOf<Map<String, String?>>()
                for (i in 0 until total) {
                    val s = stepsArr.getJSONObject(i)
                    val cmd = s.optString("cmd", "")
                    if (cmd.isEmpty()) continue
                    val desc = s.optString("desc", "")
                    onStage?.invoke("step:${i + 1}/$total", "📡 $desc ($cmd)")

                    val raw = try {
                        checker.sendRaw(cmd)
                    } catch (e: Exception) {
                        "(err)"
                    }
                    val decoded = ObdDecoder.decode(cmd, raw)
                    results.add(mapOf(
                        "step_id" to s.optString("id", ""),
                        "cmd" to cmd,
                        "raw" to raw,
                        "decoded" to decoded
                    ))
                }

                // ── Сохранить в БД ──
                val count = results.size
                val sid = db.createSession(scriptJson, "Диагностика", Config.HOST)
                results.forEach { r ->
                    db.addResponse(sid, r["step_id"] ?: "", r["cmd"] ?: "", r["raw"] ?: "", r["decoded"] ?: "")
                }

                // ── Динамические сэмплы (если есть) ──
                val dynForUpload = dynamicSamples?.map { batch ->
                    batch.map { r ->
                        mapOf(
                            "step_id" to r.stepId, "cmd" to r.cmd,
                            "raw" to r.raw, "decoded" to r.decoded,
                            "ts" to r.ts.toString()
                        )
                    }
                }
                dynamicSamples = null

                // ── Отправить на сервер ──
                onStage?.invoke("upload", "📤 Отправка $count ответов...")
                val clientInfo = mapOf(
                    "phone_model" to Build.MODEL,
                    "app_version" to "1.18.0-dev"
                )
                val resp = client.uploadSession(sid, results, clientInfo, carInfo, dynForUpload)

                // ── Результат ──
                if (resp != null) {
                    db.markUploaded(sid)
                    val d = resp.optString("diagnosis", "")
                    if (d.isNotEmpty()) {
                        db.saveDiagnosis(sid, d)
                        onStage?.invoke("done", "🩺 ДИАГНОЗ\n$d")
                    } else {
                        onStage?.invoke("done", "⚠️ Пустой ответ от LLM")
                    }
                } else {
                    onStage?.invoke("done", "⚠️ Сервер недоступен. Данные сохранены.")
                }
                onDone?.invoke(true)
            } catch (e: Exception) {
                onStage?.invoke("error", "❌ ${e.message}")
                onDone?.invoke(false)
            }
        }.apply {
            name = "DiagnosisRunner"
            isDaemon = true
            start()
        }
    }
}
