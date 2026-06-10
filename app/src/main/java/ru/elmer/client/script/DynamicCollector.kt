package ru.elmer.client.script

import ru.elmer.client.elm.ElmProtocol
import ru.elmer.client.elm.ObdDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Сборщик данных для динамического теста.
 *
 * Опрашивает заданный список PID'ов каждые N мс, копит в памяти.
 * Остановка — по флагу.
 *
 * Использование:
 *   val c = DynamicCollector(elm, steps, 250, onSample, onLog)
 *   c.start()
 *   // ... ждём ...
 *   val samples = c.stop()
 */
class DynamicCollector(
    private val elm: ElmProtocol,
    private val steps: List<ElmStep>,
    private val intervalMs: Long,
    private val onSample: (sampleIndex: Int) -> Unit,
    private val onLog: (msg: String) -> Unit
) {
    data class ElmStep(val id: String, val cmd: String, val desc: String)

    private val running = AtomicBoolean(false)
    private val samples = mutableListOf<List<SampleResponse>>()
    private var threadRef: Thread? = null

    data class SampleResponse(
        val stepId: String,
        val cmd: String,
        val raw: String,
        val decoded: String,
        val ts: Long = 0  // ms от начала сбора
    )

    fun start() {
        running.set(true)
        val startTs = System.currentTimeMillis()
        threadRef = thread(name = "DynamicCollector", isDaemon = true) {
            var idx = 0
            while (running.get()) {
                val t0 = System.currentTimeMillis()
                val batch = mutableListOf<SampleResponse>()
                for (step in steps) {
                    if (!running.get()) break
                    try {
                        val raw = elm.sendCommand(step.cmd)
                        val dec = ObdDecoder.decode(step.cmd, raw)
                        batch.add(SampleResponse(step.id, step.cmd, raw, dec, System.currentTimeMillis() - startTs))
                    } catch (e: Exception) {
                        batch.add(SampleResponse(step.id, step.cmd, "(err)", e.message ?: "error", System.currentTimeMillis() - startTs))
                    }
                    Thread.sleep(350)  // ELM327 v1.5: пауза между командами — иначе retry забивают буфер
                }
                if (batch.isNotEmpty()) {
                    synchronized(samples) { samples.add(batch) }
                    onSample(idx)
                    idx++
                }
                // Выдерживаем интервал
                val elapsed = System.currentTimeMillis() - t0
                val sleep = intervalMs - elapsed
                if (sleep > 0 && running.get()) Thread.sleep(sleep)
            }
        }
    }

    fun stop(): List<List<SampleResponse>> {
        running.set(false)
        try { threadRef?.join(3000) } catch (_: Exception) {}
        return synchronized(samples) { samples.toList() }
    }

    fun isRunning(): Boolean = running.get()
}
