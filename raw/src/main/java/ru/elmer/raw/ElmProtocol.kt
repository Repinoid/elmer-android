package ru.elmer.raw

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
 *
 * НЕ ИЗМЕНЯТЬ. Проверено годами.
 */
class ElmProtocol(
    private val input: InputStream,
    private val output: OutputStream
) {
    companion object {
        private const val TAG = "ElmProto"
        private const val POLL_DELAY = 1L

        private const val INIT_TIMEOUT = 10000L
        private const val DEF_TIMEOUT  = 500L
        private const val TIMEOUT_MIN  = 50L
        private const val TIMEOUT_MAX  = 2000L
        private const val TIMEOUT_STEP = 20L
        private const val TIMEOUT_RES  = 4
        private const val MAX_RETRIES  = 6     // потолок ретраев: 500+20×6 = 620мс
        private const val ATST_CLONE  = 0x96  // 150×4=600ms — фиксированный для клонов
    }

    private enum class State { UNDEFINED, INITIALIZING, READY, BUSY, ERROR, DISCONNECTED }

    @Volatile private var state = State.UNDEFINED
    private var timeoutMs = DEF_TIMEOUT
    private var learnedMin = TIMEOUT_MIN
    @Volatile var isClone = false
        private set

    /**
     * AndrOBD init: ATSP0→[ATAT1?]→ATS0→ATL0→ATE0.
     * ТОЧНЫЙ порядок из ElmProt.java (fr3ts0n/AndrOBD).
     * Клон v1.5: ATAT1 и ATST не шлём.
     */
    fun init() {
        Log.i(TAG, "init start")
        state = State.INITIALIZING

        // ATSP0 — протокол ПЕРВЫМ (как в AndrOBD)
        write("ATSP0"); tryRead(INIT_TIMEOUT); drainInput()

        // ATI для детекта клона (безопасно после ATSP0)
        val ati = try { sendCommand("ATI") } catch (_: Exception) { "" }
        isClone = ati.contains("v1.5") || ati.contains("V1.5")

        // ATAT1 — только для оригинала
        if (!isClone) {
            write("ATAT1"); tryRead(2000); drainInput()
            updateAtst()
        } else {
            Log.i(TAG, "clone v1.5 — ATAT1/ATST skipped")
        }

        // Остальные — точно как в AndrOBD
        write("ATS0"); tryRead(2000); drainInput()
        write("ATL0"); tryRead(2000); drainInput()
        write("ATE0"); tryRead(2000); drainInput()

        state = State.READY
        Log.i(TAG, "ready (clone=${isClone})")
    }

    fun sendCommand(cmd: String): String {
        if (state == State.ERROR || state == State.DISCONNECTED) recover()
        state = State.BUSY
        val result = exec(cmd, timeoutMs)
        // Полный дренаж с таймаутом — ловит хвосты после recovery
        tryRead(500)
        drainInput()
        if (state == State.BUSY) state = State.READY
        return result
    }

    private fun exec(cmd: String, timeout: Long): String {
        write(cmd)
        var t = timeout
        for (i in 0 until MAX_RETRIES) {
            try {
                return handle(read(t))
            } catch (_: TimeoutException) {
                if (state == State.INITIALIZING) t += 1000
                else { increaseTimeout(); t = timeoutMs }
            }
        }
        Log.e(TAG, "no response for $cmd")
        return ""
    }

    private fun handle(raw: String): String {
        val u = raw.uppercase().trim()
        when {
            u.startsWith("SEARCHING") -> { /* ждём */ }

            u.startsWith("OK") -> decreaseTimeout()

            u.startsWith("NODATA") || u.startsWith("NO DATA") -> {
                increaseTimeout()
            }

            u.startsWith("STOPPED") -> {
                Log.w(TAG, "STOPPED — restarting protocol")
                state = State.DISCONNECTED
                resetTimeout()
                // ATPC→ATWS→ATSP0 с ПОЛНЫМ чтением ответа
                write("ATPC"); tryRead(3000)
                write("ATWS"); tryRead(3000)
                if (isClone) tryRead(1000)
                write("ATSP0"); tryRead(3000)
            }

            u.startsWith("UNABLE") || u.startsWith("NABLETO") -> {
                Log.w(TAG, "UNABLE — restarting protocol")
                state = State.DISCONNECTED
                write("ATPC"); tryRead(3000)
                write("ATSP0"); tryRead(3000)
            }

            isBusError(u) -> {
                Log.w(TAG, "BUS ERROR: ${raw.take(60)}")
                state = State.DISCONNECTED
                resetTimeout()
                write("ATPC"); tryRead(3000)
                write("ATWS"); tryRead(3000)
                if (isClone) tryRead(1000)
                write("ATSP0"); tryRead(3000)
            }

            u.startsWith("ERROR") && !u.startsWith("DATA ERROR") -> {
                Log.w(TAG, "ERROR")
                state = State.ERROR
                write("ATWS"); tryRead(3000)
                if (isClone) tryRead(1000)
            }

            isDataError(u) -> {
                Log.w(TAG, "data error")
                state = State.ERROR
                write("ATWS"); tryRead(3000)
                if (isClone) tryRead(1000)
            }

            else -> decreaseTimeout()
        }
        return raw
    }

    private fun recover() {
        Log.i(TAG, "recovering...")
        state = State.READY
        resetTimeout()
    }

    private fun write(cmd: String) {
        drainInput()
        output.write((cmd + "\r").toByteArray())
        output.flush()
        Log.d(TAG, "→ $cmd")
    }

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
                    62 -> { push(sb, lines); gotPrompt = true; break }
                    13 -> push(sb, lines)
                    10, 32 -> {}
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

    private fun increaseTimeout() {
        if (timeoutMs + TIMEOUT_STEP < TIMEOUT_MAX) timeoutMs += TIMEOUT_STEP
    }

    private fun decreaseTimeout() {
        if (timeoutMs - TIMEOUT_STEP >= learnedMin) timeoutMs -= TIMEOUT_STEP
    }

    private fun resetTimeout() { timeoutMs = DEF_TIMEOUT }

    fun resetAdaptiveTiming() { timeoutMs = DEF_TIMEOUT }

    private fun updateAtst() {
        val v = (timeoutMs / TIMEOUT_RES).toInt().coerceAtLeast(1)
        write("ATST${v.toString(16).uppercase().padStart(2, '0')}")
        tryRead(2000)
        drainInput()
    }

    private fun isBusError(s: String): Boolean {
        return listOf("UNABLE", "BUS BUSY", "BUS ERROR", "CAN ERROR",
            "BUS INIT", "STOPPED").any { s.startsWith(it) }
    }

    private fun isDataError(s: String): Boolean {
        return listOf("DATA ERROR", "BUFFER FULL", "RX ERROR").any { s.startsWith(it) }
    }
}

class TimeoutException(message: String) : Exception(message)
