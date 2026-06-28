package ru.elmer.raw

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.*

/**
 * Single-thread actor вокруг ElmProtocol.
 *
 * ГАРАНТИРУЕТ: только ОДИН поток когда-либо касается InputStream/OutputStream.
 * Все вызовы (init, sendCommand) сериализуются через однопоточный executor.
 *
 * Использование:
 *   val actor = ElmActor(input, output)
 *   actor.init()                           // блокирующий
 *   val result = actor.send("0105").get()  // Future<String>, блокирующий .get()
 *   actor.shutdown()
 */
class ElmActor(
    input: InputStream,
    output: OutputStream
) {
    private val elm = ElmProtocol(input, output)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ElmActor").apply { isDaemon = true }
    }

    /** Инициализация ELM327. Блокирует до завершения. */
    fun init() {
        try {
            executor.submit { elm.init() }.get(30, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException("ELM init timed out after 30s", e)
        } catch (e: ExecutionException) {
            throw IllegalStateException("ELM init failed", e.cause ?: e)
        }
    }

    /** Отправить команду. Возвращает Future с сырым ответом. */
    fun send(cmd: String): Future<String> {
        return executor.submit(Callable {
            elm.sendCommand(cmd)
        })
    }

    /** Отправить команду и дождаться ответа (удобная обёртка). */
    fun sendBlocking(cmd: String, timeoutMs: Long = 5000): String {
        return try {
            send(cmd).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            ""
        } catch (e: ExecutionException) {
            ""
        }
    }

    /** Очистить входной буфер. */
    fun drain() {
        executor.submit { elm.drainInput() }
    }

    /** Проверить, является ли устройство клоном v1.5. */
    fun isClone(): Boolean = elm.isClone

    /** Остановить executor. */
