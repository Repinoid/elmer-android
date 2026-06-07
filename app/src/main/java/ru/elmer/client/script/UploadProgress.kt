package ru.elmer.client.script

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Таймер прогресса загрузки на сервер.
 *
 * Раз в секунду шлёт BROADCAST_STAGE с обновлённым временем и объёмом данных.
 * Используется ScriptRunnerService во время upload'а.
 *
 * Использование:
 *   val progress = UploadProgress(context, "ru.elmer.client", 15, 4)
 *   progress.start()
 *   // ... upload ...
 *   progress.stop()
 */
class UploadProgress(
    private val context: Context,
    private val packageName: String,
    private val responseCount: Int,
    private val dataSizeKB: Int
) {
    private val running = AtomicBoolean(false)

    fun start() {
        running.set(true)
        val startTime = System.currentTimeMillis()
        // Первое сообщение сразу
        val intent = Intent(ScriptRunnerService.BROADCAST_STAGE).apply {
            putExtra("stage", "upload")
            putExtra("detail", "Отправка ${responseCount} отв. (${dataSizeKB}KB)...")
            setPackage(packageName)
        }
        context.sendBroadcast(intent)
        // Дальше — каждые 3 секунды (не спамим)
        thread(name = "UploadTimer", isDaemon = true) {
            while (running.get()) {
                Thread.sleep(3000)
                if (!running.get()) break
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val intent2 = Intent(ScriptRunnerService.BROADCAST_STAGE).apply {
                    putExtra("stage", "upload")
                    putExtra("detail", "Отправка... [${elapsed}с]")
                    setPackage(packageName)
                }
                context.sendBroadcast(intent2)
            }
        }
    }

    fun stop() {
        running.set(false)
    }
}
