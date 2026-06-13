package ru.elmer.client.elm

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * AndrOBD Protocol — ПОЛНАЯ стейт-машина (1:1 копия ElmProt.java).
 *
 * Состояния: UNDEFINED → INITIALIZING → READY
 *            BUSY (команда) → READY
 *            ERROR → RECOVERING → READY
 *            BUS ERROR → DISCONNECTED → READY
 *
 * Источник: github.com/fr3ts0n/AndrOBD (1993⭐, 10 лет продакшена)
 */
class ElmProtocol(
    private val input: InputStream,
    private val output: OutputStream
) {
    companion object {
        private const val TAG = "ElmProto"
        private const val POLL_DELAY = 1L

        // Таймауты
        private const val INIT_TIMEOUT = 10000L
        private const val DEF_TIMEOUT  = 500L
        private const val TIMEOUT_MIN  = 50L
        private const val TIMEOUT_MAX  = 2000L
        private const val TIMEOUT_STEP = 20L
        private const val TIMEOUT_RES  = 4
        private const val MAX_RETRIES  = 10
    }

    // ── Стейт-машина ──────────────────────────────────────

    private enum class State { UNDEFINED, INITIALIZING, READY, BUSY, ERROR, DISCONNECTED }

    private var state = State.UNDEFINED
    private var timeoutMs = DEF_TIMEOUT
    private var learnedMin = TIMEOUT_MIN

    // ── Инициализация ─────────────────────────────────────

    /** ATSP0→ATAT1(быстрый)→ATS0→ATL0→ATE0. Без ретраев — макс 20с. */
    fun init() {
        Log.i(TAG, "init start")
        state = State.INITIALIZING

        write("ATSP0"); tryRead(4000); drainInput()
        // ATAT1 — v1.5 клоны не поддерживают, не ждём
        write("ATAT1"); tryRead(2000); drainInput()
        updateAtst()
        write("ATS0"); tryRead(2000); drainInput()
        write("ATL0"); tryRead(2000); drainInput()
        write("ATE0"); tryRead(2000); drainInput()

        state = State.READY
        Log.i(TAG, "ready")
    }

    // ── OBD-команда ───────────────────────────────────────

    fun sendCommand(cmd: String): String {
        if (state == State.ERROR || state == State.DISCONNECTED) recover()
        state = State.BUSY
        val result = exec(cmd, timeoutMs)
        if (state == State.BUSY) state = State.READY
        return result
    }

    // ── Выполнение команды ────────────────────────────────

    private fun exec(cmd: String, timeout: Long): String {
        write(cmd)
        try {
            return handle(read(timeout))
        } catch (_: TimeoutException) {
            Log.w(TAG, "timeout for $cmd")
            increaseTimeout()
            state = State.ERROR
            drainInput()
            return ""
        }
    }

    // ── Обработка ответа ──────────────────────────────────

    private fun handle(raw: String): String {
        val u = raw.uppercase().trim()

        when {
            u.startsWith("SEARCHING") -> {}
            u.startsWith("OK") -> decreaseTimeout()

            u.startsWith("NODATA") || u.startsWith("NO DATA") -> {
                increaseTimeout(); updateAtst()
            }

            isBusError(u) -> {
                Log.w(TAG, "BUS ERROR: ${raw.take(60)}")
                state = State.DISCONNECTED
                resetTimeout(); updateAtst()
                write("ATPC"); tryRead(3000)
                write("ATSP0"); tryRead(3000)
            }

            u.startsWith("ERROR") && !u.startsWith("DATA ERROR") -> {
                Log.w(TAG, "ERROR — warm start")
                state = State.ERROR
                write("ATWS"); tryRead(3000)
            }

            isDataError(u) -> {
                Log.w(TAG, "data error — warm start")
                state = State.ERROR
                write("ATWS"); tryRead(3000)
            }

            else -> decreaseTimeout()
        }
        return raw
    }

    /** Быстрое восстановление — макс 6с */
    private fun recover() {
        Log.i(TAG, "recovering...")
        state = State.INITIALIZING
        write("ATWS"); tryRead(2000); drainInput()
        write("ATSP0"); tryRead(2000); drainInput()
        write("ATE0"); tryRead(2000); drainInput()
        state = State.READY
    }

    // ── Побайтовое чтение ─────────────────────────────────

    private fun write(cmd: String) {
        drainInput()
        output.write((cmd + "\r").toByteArray())
        output.flush()
        Log.d(TAG, "→ $cmd")
    }

    /** Очистить входной буфер от мусора */
    fun drainInput() {
        while (input.available() > 0) input.read()
    }

    @Throws(TimeoutException::class)
    private fun read(timeout: Long): String {
        val dl = System.currentTimeMillis() + timeout
        val sb = StringBuilder()
        val lines = mutableListOf<String>()
        var gotPrompt = false

        while (System.currentTimeMillis() < dl) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                when (b) {
                    62 -> { push(sb, lines); gotPrompt = true; break }     // '>'
                    13 -> push(sb, lines)                                   // CR
                    10, 32 -> {}                                            // LF, space
                    else -> sb.append(b.toChar())
                }
            } else {
                Thread.sleep(POLL_DELAY)
            }
        }
        push(sb, lines)
        if (!gotPrompt) throw TimeoutException("timeout ${timeout}ms")
        return lines.joinToString("\n")
    }

    private fun tryRead(timeout: Long) {
        try { read(timeout) } catch (_: TimeoutException) {}
    }

    private fun push(sb: StringBuilder, lines: MutableList<String>) {
        if (sb.isNotEmpty()) { lines.add(sb.toString()); sb.clear() }
    }

    // ── Таймаут ───────────────────────────────────────────

    private fun increaseTimeout() {
        if (timeoutMs + TIMEOUT_STEP < TIMEOUT_MAX) timeoutMs += TIMEOUT_STEP
    }

    private fun decreaseTimeout() {
        if (timeoutMs - TIMEOUT_STEP >= learnedMin) timeoutMs -= TIMEOUT_STEP
    }

    private fun resetTimeout() { timeoutMs = DEF_TIMEOUT }

    /** Сброс адаптивного тайминга — для speed-test */
    fun resetAdaptiveTiming() { timeoutMs = DEF_TIMEOUT }

    /** ATST с коротким таймаутом (v1.5 не поддерживает) */
    private fun updateAtst() {
        val v = (timeoutMs / TIMEOUT_RES).toInt().coerceAtLeast(1)
        write("ATST${v.toString(16).uppercase().padStart(2, '0')}")
        tryRead(2000)
        drainInput()
    }

    // ── Классификация ошибок ──────────────────────────────

    private fun isBusError(s: String): Boolean {
        return listOf("UNABLE", "BUS BUSY", "BUS ERROR", "CAN ERROR",
            "BUS INIT", "STOPPED").any { s.startsWith(it) }
    }

    private fun isDataError(s: String): Boolean {
        return listOf("DATA ERROR", "BUFFER FULL", "RX ERROR").any { s.startsWith(it) }
    }
}

class TimeoutException(message: String) : Exception(message)
