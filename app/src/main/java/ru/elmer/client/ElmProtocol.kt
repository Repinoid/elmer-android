package ru.elmer.client

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * ELM327 Protocol — точная копия паттернов AndrOBD (1993⭐, 10 лет продакшена).
 *
 * Источник: github.com/fr3ts0n/AndrOBD
 *   - StreamHandler.java → побайтовое чтение, 1мс пауза, '>' = разделитель
 *   - ElmProt.java → адаптивный таймаут, обработка ошибок
 *   - BtCommService.java → 500мс пауза после BT connect
 *
 * Паттерны:
 *   1. Побайтовое чтение с паузой 1мс (НЕ readLine!)
 *   2. '>' — обычный разделитель строк (как CR/LF)
 *   3. Адаптивный таймаут: 200мс ± 4мс, диапазон 12-1000мс
 *   4. ATST меняется на лету
 *   5. Инициализация: ATSP0→ATAT1→ATS0→ATL0→ATE0
 *   6. flush() после каждой команды
 *   7. SEARCHING → ждать. NODATA → +таймаут. BUS ERROR → reset.
 */
class ElmProtocol(
    private val input: InputStream,
    private val output: OutputStream
) {
    companion object {
        private const val TAG = "ElmProto"

        // AdaptiveTiming (AndrOBD)
        private const val TIMEOUT_DEFAULT = 200L  // ms
        private const val TIMEOUT_MIN     = 12L
        private const val TIMEOUT_MAX     = 1000L
        private const val TIMEOUT_STEP    = 4L
        private const val TIMEOUT_RES     = 4     // ATST = timeout / RES

        // Чтение
        private const val POLL_DELAY = 1L  // 1ms (AndrOBD StreamHandler)
    }

    private var timeoutMs = TIMEOUT_DEFAULT
    private var learnedMin = TIMEOUT_MIN
    private var lastCmd: String? = null

    // ── Инициализация (AndrOBD ElmProt.initialize) ─────────

    /** ATSP0→ATAT1→ATS0→ATL0→ATE0. Каждая команда с чтением ответа. */
    fun init() {
        Log.i(TAG, "init start")

        sendCommand("ATSP0")  // авто-протокол (может быть SEARCHING...)
        sendCommand("ATAT1")  // adaptive timing
        updateTimeout()
        sendCommand("ATS0")   // пробелы выкл
        sendCommand("ATL0")   // line feeds выкл
        sendCommand("ATE0")   // эхо выкл

        Log.i(TAG, "init done")
    }

    // ── Отправка команды + чтение (AndrOBD StreamHandler) ─

    /**
     * Отправляет OBD/AT команду, читает ответ побайтово.
     * Разделители: CR(13), LF(10), '>'(62) — все равноправны.
     * Возвращает строки ответа через \n, БЕЗ разделителей.
     */
    fun sendCommand(cmd: String): String {
        lastCmd = cmd
        write(cmd)

        return try {
            val result = read(timeoutMs)
            handleResponse(result)
            result
        } catch (_: TimeoutException) {
            // NODATA → увеличить таймаут и попробовать ещё раз
            increaseTimeout()
            updateTimeout()
            try {
                val result = read(timeoutMs)
                handleResponse(result)
                result
            } catch (_: TimeoutException) {
                ""
            }
        }
    }

    // ── Внутренние (AndrOBD StreamHandler) ────────────────

    /** cmd + CR + flush */
    private fun write(cmd: String) {
        output.write((cmd + "\r").toByteArray())
        output.flush()
        Log.d(TAG, "→ $cmd")
    }

    /** Побайтовое чтение, пауза 1мс. Возвращает строки через \n. */
    @Throws(TimeoutException::class)
    private fun read(timeout: Long): String {
        val deadline = System.currentTimeMillis() + timeout
        val lines = mutableListOf<String>()
        val cur = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break

                when (b) {
                    62 -> {         // '>' — разделитель как CR/LF
                        addLine(cur, lines)
                        break
                    }
                    13 -> {         // CR
                        addLine(cur, lines)
                    }
                    10, 32 -> {     // LF и пробел — игнорируем
                        // skip
                    }
                    else -> {
                        cur.append(b.toChar())
                    }
                }
            } else {
                Thread.sleep(POLL_DELAY)  // 1ms
            }
        }

        addLine(cur, lines)
        if (lines.isEmpty()) throw TimeoutException("no response in ${timeout}ms")
        return lines.joinToString("\n")
    }

    private fun addLine(cur: StringBuilder, lines: MutableList<String>) {
        if (cur.isNotEmpty()) {
            lines.add(cur.toString())
            cur.clear()
        }
    }

    /** Слить входной буфер */
    private fun drain() {
        try {
            while (input.available() > 0) {
                input.skip(input.available().toLong())
                Thread.sleep(50)
            }
        } catch (_: Exception) {}
    }

    // ── Обработка ответа (AndrOBD ElmProt.handleTelegram) ──

    private fun handleResponse(raw: String): String {
        val u = raw.uppercase()

        when {
            "SEARCHING" in u -> {
                Log.d(TAG, "SEARCHING — wait")
            }
            "NODATA" in u || "NO DATA" in u -> {
                increaseTimeout()
                updateTimeout()
            }
            anyBusError(u) -> {
                Log.w(TAG, "bus error — reset: ${raw.take(60)}")
                resetTimeout()
                updateTimeout()
                write("ATPC")
                write("ATSP0")
            }
            "ERROR" in u && "DATA" !in u -> {
                Log.w(TAG, "ERROR — warm start")
                write("ATWS")
            }
            "DATA ERROR" in u || "BUFFER FULL" in u || "RX ERROR" in u -> {
                Log.w(TAG, "data error — warm start")
                write("ATWS")
            }
            else -> {
                decreaseTimeout()  // успех — уменьшаем
            }
        }

        return raw
    }

    private fun anyBusError(s: String): Boolean {
        return listOf("UNABLE", "BUS BUSY", "BUS ERROR",
            "CAN ERROR", "BUS INIT", "STOPPED").any { it in s }
    }

    // ── Адаптивный таймаут (AndrOBD AdaptiveTiming) ────────

    private fun increaseTimeout() {
        if (timeoutMs + TIMEOUT_STEP < TIMEOUT_MAX)
            timeoutMs += TIMEOUT_STEP
    }

    private fun decreaseTimeout() {
        if (timeoutMs - TIMEOUT_STEP >= learnedMin)
            timeoutMs -= TIMEOUT_STEP
    }

    private fun resetTimeout() {
        timeoutMs = TIMEOUT_DEFAULT
    }

    /** Отправить ATST с текущим таймаутом */
    private fun updateTimeout() {
        val val_ = (timeoutMs / TIMEOUT_RES).toInt().coerceAtLeast(1)
        write("ATST${val_.toString(16).uppercase().padStart(2, '0')}")
    }

    // ── Raw send (для init-команд) ────────────────────────

    private fun sendRaw(cmd: String) {
        write(cmd)
    }
}

class TimeoutException(message: String) : Exception(message)
