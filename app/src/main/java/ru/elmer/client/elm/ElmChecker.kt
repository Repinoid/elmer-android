package ru.elmer.client.elm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log

/**
 * ELM327 — проверка устройства и ЭБУ.
 *
 * Делегирует BT-подключение в BtConnector.
 * Отвечает за: AT-команды, OBD-команды, сканирование DTC, замер скорости.
 *
 * Использование:
 *   val checker = ElmChecker(device, adapter)
 *   val info = checker.checkDevice()      // AT-команды (без зажигания)
 *   val ecu = checker.checkEcu()          // OBD-команды (требует зажигания)
 *   val dtc = checker.scanDtc()           // Коды ошибок
 *   val speed = checker.measureResponseTime { msg -> ... }  // Замер скорости
 *   checker.close()                       // Освободить BT
 */
class ElmChecker(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "ElmChecker"
    }

    // ── Данные ──────────────────────────────────────────

    /** Результат проверки прибора (AT-команды, зажигание не нужно) */
    data class DeviceInfo(
        val version: String,        // "ELM327 v2.1" / "v1.5" / "?"
        val deviceId: String,       // AT@2 или "—"
        val protocol: String,       // OBD-протокол
        val voltage: String,        // напряжение бортсети (или "—")
        val hasAdaptive: Boolean,   // поддерживает ATAT1
    )

    /** Результат проверки ЭБУ (OBD-команды, нужно зажигание) */
    data class EcuData(
        val supportsObd: Boolean,   // 0100 ответил
        val pidMask: String,        // битмаска PID'ов (или "—")
        val vin: String?,           // VIN или null
    )

    /** Полный результат (прибор + ЭБУ) */
    data class Result(
        val good: Boolean,          // устройство чёткое или клон
        val device: DeviceInfo,     // параметры ELM327
        val ecu: EcuData,           // данные ЭБУ
        val log: String             // полный лог команд
    )

    /** Результат замера скорости */
    data class SpeedTestResult(
        val perPidAvg: List<Int>,   // среднее время (ms) на каждый PID
        val batchTime: Int,         // сумма perPidAvg — время полного батча
        val reliable: Boolean,      // true если замер стабильный
        val message: String         // что показать пользователю
    )

    // ── Состояние ───────────────────────────────────────

    private val logLines = mutableListOf<String>()
    private val btConnector = BtConnector()
    private var elm: ElmProtocol? = null

    fun getLog(): String = logLines.joinToString("\n")

    // ── Публичные методы ────────────────────────────────

    /** Подключиться к ELM327. Вызвать перед DynamicCollector. */
    fun ensureConnected(): Boolean = connectAndInit()

    /** Проверить, подключен ли ELM. */
    fun isConnected(): Boolean = btConnector.isConnected && elm != null

    /** Отправить команду через ElmProtocol. */
    fun sendRaw(cmd: String): String = elm?.sendCommand(cmd) ?: "(no elm)"

    /** Быстрая проверка: один PID (RPM), один замер. Сверка с профилем. */
    fun quickCheck(): Int? {
        val e = elm ?: return null
        return try {
            val t0 = System.currentTimeMillis()
            val raw = e.sendCommand("010C")
            val dt = System.currentTimeMillis() - t0
            if (raw.isBlank() || raw == "(err)") null else dt.toInt()
        } catch (_: Exception) { null }
    }

    /** Закрыть соединение с ELM327. Освобождает BT-сокет. */
    fun close() {
        btConnector.disconnect()
        elm = null
    }

    // ── Этап 1: AT-команды (без зажигания) ──────────────

    /**
     * Проверка прибора ELM327.
     * ATI, AT@1 (только v2+), ATDP, ATRV, ATAT1 (только v2+).
     * v1.5 клоны: только базовые ATI, ATDP, ATRV.
     */
    fun checkDevice(): DeviceInfo? {
        if (!connectAndInit()) return null

        // ATI — версия (работает на всех)
        val ati = send("ATI")
        val version = parseVersion(ati)
        val isV2 = version.contains("v2", ignoreCase = true)

        // AT@1, AT@2 — только для v2+ (v1.5 не понимает)
        val deviceId: String
        if (isV2) {
            val at2 = send("AT@2")
            deviceId = cleanAt2(at2)
        } else {
            deviceId = "—"
        }

        val dp = send("ATDP")
        val protocol = if (dp.length > 3 && dp != "OK") dp.take(60) else dp

        val rv = send("ATRV")
        val voltage = if (rv.contains("V", ignoreCase = true) || rv.matches(Regex("[0-9.]+"))) rv else "—"

        // ATAT1 — только для v2+ (v1.5 не поддерживает)
        val hasAdaptive = if (isV2) send("ATAT1") == "OK" else false

        return DeviceInfo(version, deviceId, protocol, voltage, hasAdaptive)
    }

    /** Этап 2: данные ЭБУ (требует зажигание, ELM уже инициализирован) */
    fun checkEcu(): EcuData {
        val pid0100 = send("0100")
        val supportsObd = pid0100.startsWith("41") && pid0100.length > 5
        val pidMask = if (supportsObd) pid0100.take(100) else "—"

        val vin: String?
        if (supportsObd) {
            val raw = send("0902")
            vin = parseVin(raw)
        } else {
            vin = null
        }

        return EcuData(supportsObd, pidMask, vin)
    }

    /** Сканирование DTC (mode 03). */
    fun scanDtc(): List<String>? {
        if (!connectAndInit()) {
            log("❌ Не удалось подключиться к ELM для сканирования DTC")
            return null
        }
        return parseDtcCodes(send("03"))
    }

    /**
     * Замер скорости ответа ELM по каждому PID.
     * 3 PID (RPM, MAF, STFT) × 3-4 замера.
     * Первый замер RPM отбрасывается (ELM «просыпается» после паузы).
     * Стабильность: разброс >50% → unreliable.
     */
    fun measureResponseTime(onProgress: (String) -> Unit): SpeedTestResult {
        val testPids = listOf("010C" to "RPM", "0110" to "MAF", "0106" to "STFT")
        val perPidAvg = mutableListOf<Int>()
        var hadErrors = false
        var reliable = true
        val reasons = mutableListOf<String>()
        val e = elm ?: return SpeedTestResult(listOf(250, 250, 250), 750, false, "❌ ELM не инициализирован")

        // Разогрев: один пустой запрос чтобы ELM не спал
        try { e.sendCommand("010C") } catch (_: Exception) {}

        onProgress("\n⏱ Тест скорости ELM...")
        for ((pi, pair) in testPids.withIndex()) {
            val (cmd, name) = pair
            val allTimes = mutableListOf<Long>()
            val count = if (pi == 0) 4 else 3  // RPM: 4 замера (первый отбросим)
            for (i in 0 until count) {
                val t0 = System.currentTimeMillis()
                val raw = try { e.sendCommand(cmd) } catch (_: Exception) { "(err)" }
                val dt = System.currentTimeMillis() - t0
                allTimes.add(dt)
                if (raw == "(err)" || raw.isBlank()) hadErrors = true
            }
            // Для RPM — только 2 последних из 4 (первый — разогрев, второй — адаптация)
            val times = if (pi == 0) allTimes.takeLast(2).toMutableList() else allTimes
            val avg = times.average().toInt()
            perPidAvg.add(avg)
            val display = times.joinToString("ms, ")
            onProgress("\n   $name: ${display}ms  (среднее ${avg}ms)")

            // Проверка стабильности: разброс >50% от среднего
            for (t in times) {
                if (t > 0 && avg > 0 && kotlin.math.abs(t - avg).toFloat() / avg > 0.5f) {
                    if (reliable) reliable = false
                    reasons.add("${name} нестабилен: ${t}ms vs среднее ${avg}ms")
                }
            }
        }

        val batchTime = perPidAvg.sum()
        val message = if (reliable) {
            "✅ Скорость стабильна: ${perPidAvg.joinToString("+")}=${batchTime}ms"
        } else {
            "⚠️ ${reasons.joinToString("; ")}. Проверьте контакт ELM в OBD-разъёме."
        }
        onProgress("\n$message")

        return SpeedTestResult(perPidAvg, batchTime, reliable, message)
    }

    /** Полный цикл: прибор + ЭБУ. */
    fun run(): Result {
        val deviceInfo = checkDevice()
        if (deviceInfo == null) return failResult("❌ ELM327 не отвечает")
        val ecuInfo = checkEcu()
        return Result(
            good = deviceInfo.hasAdaptive && ecuInfo.supportsObd,
            device = deviceInfo,
            ecu = ecuInfo,
            log = getLog()
        )
    }

    // ── Приватные методы ────────────────────────────────

    /** Подключить BT и инициализировать ELM. С одной повторной попыткой. */
    private fun connectAndInit(): Boolean {
        try {
            elm = btConnector.connect(device, adapter)
            Thread.sleep(400)  // даём ELM проснуться
            log("✅ BT OK")
            log("─── Проверка ELM327 ───")
            try { elm!!.init() } catch (e: Exception) { log("⚠️ init: ${e.message}") }
            return true
        } catch (e: Exception) {
            log("❌ ${e.message}")
            // Retry один раз — иногда ELM не отвечает с первого раза
            try {
                Thread.sleep(600)
                elm = btConnector.connect(device, adapter)
                Thread.sleep(400)
                log("✅ BT OK (retry)")
                try { elm!!.init() } catch (e2: Exception) { log("⚠️ init: ${e2.message}") }
                return true
            } catch (e2: Exception) {
                log("❌ retry: ${e2.message}")
                return false
            }
        }
    }

    /** Отправить команду в ELM, залогировать. */
    private fun send(cmd: String): String {
        log("→ $cmd")
        return try {
            val r = elm?.sendCommand(cmd) ?: "(no elm)"
            log("← $r")
            r
        } catch (e: Exception) {
            log("← ❌ ${e.message}")
            "?"
        }
    }

    // ── Парсинг ─────────────────────────────────────────

    /** Распарсить версию из ATI. */
    private fun parseVersion(raw: String): String {
        val v = raw.trim().replace(Regex("[\r\n>]"), "")
        return when {
            v.length in 3..80 -> v
            raw.contains("v1.5", ignoreCase = true) || raw.contains("V1.5") -> "v1.5 (клон)"
            else -> "?"
        }
    }

    /** Очистить AT@2 — ID устройства. */
    private fun cleanAt2(raw: String): String {
        val clean = raw.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        return if (clean.length > 3 && raw != "OK") clean.take(60) else "—"
    }

    /**
     * Декодировать коды DTC из mode 03.
     * Формат: байт длины + пары байт (byte1, byte2) на каждый код.
     * byte1 биты 7-6: 0=P, 1=C, 2=B, 3=U
     */
    private fun parseDtcCodes(raw: String): List<String> {
        val clean = raw.trim().replace(Regex("[\r\n>]"), "").replace(" ", "")
        if (clean.length < 4) return emptyList()
        val codes = mutableListOf<String>()
        var i = 2  // skip byte count
        while (i + 3 < clean.length) {
            val byte1 = clean.substring(i, i + 2).toIntOrNull(16) ?: break
            val byte2 = clean.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            val prefix = when ((byte1 shr 6) and 0x03) {
                0 -> "P"; 1 -> "C"; 2 -> "B"; 3 -> "U"; else -> "?"
            }
            val digit1 = byte1.toString(16).last().uppercase()
            val digits = byte2.toString(16).padStart(2, '0').uppercase()
            codes.add("$prefix$digit1$digits")
            i += 4
        }
        return codes
    }

    /** Распарсить VIN из mode 09 PID 02. HEX → ASCII. */
    private fun parseVin(raw: String): String? {
        val clean = raw.trim().replace(Regex("[\r\n>]"), "").replace(" ", "")
        val hexOnly = clean.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hexOnly.length < 30) return null
        return try {
            hexOnly.chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")
                .take(17)
        } catch (_: Exception) { null }
    }

    private fun failResult(reason: String) = Result(
        good = false,
        device = DeviceInfo("?", "—", "—", "—", false),
        ecu = EcuData(false, "—", null),
        log = getLog() + "\n$reason"
    )

    private fun log(msg: String) {
        logLines.add(msg)
        Log.i(TAG, msg)
    }
}
