package ru.elmer.client.elm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Проверка ELM327: два этапа.
 *
 * ## Этап 1 — прибор (DeviceInfo)
 * Только AT-команды, зажигание не нужно. Bluetooth + ATI, AT@1, AT@2, ATRV, ATDP.
 *   val device = ElmChecker(device, bt).checkDevice()
 *   // device.version, device.deviceId, device.voltage, device.protocol
 *
 * ## Этап 2 — ЭБУ (EcuData)
 * Требует зажигания. VIN, PID'ы.
 *   val ecu = ElmChecker(device, bt).checkEcu()
 *   // ecu.vin, ecu.pidMask
 *
 * ## Всё вместе
 *   val result = ElmChecker(device, bt).run()
 *   // result.good, result.vin, result.log
 */
class ElmChecker(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "ElmChecker"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    /** Данные прибора (без зажигания) */
    data class DeviceInfo(
        val version: String,        // ELM327 v2.1 / v1.5 / "?"
        val deviceId: String,       // AT@2 или "—"
        val protocol: String,       // OBD-протокол
        val voltage: String,        // напряжение бортсети (или "—")
        val hasAdaptive: Boolean,   // поддерживает ATAT1
    )

    /** Данные ЭБУ (требует зажигания) */
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

    private val logLines = mutableListOf<String>()

    fun getLog(): String = logLines.joinToString("\n")
    private var socket: BluetoothSocket? = null
    private var elm: ElmProtocol? = null

    // ── Публичные методы ────────────────────────────────

    /** Этап 1: только AT-команды, зажигание НЕ нужно */
    fun checkDevice(): DeviceInfo? {
        if (!connectAndInit()) return null
        val ati = send("ATI")
        val version = parseVersion(ati)
        val at1 = send("AT@1")
        val hasDeviceId = at1.length > 3 && at1 != "OK" && !at1.startsWith("?")
        val at2 = send("AT@2")
        val deviceId = cleanAt2(at2)
        val dp = send("ATDP")
        val protocol = if (dp.length > 3 && dp != "OK") dp.take(60) else dp
        val rv = send("ATRV")
        val voltage = if (rv.contains("V", ignoreCase = true) || rv.matches(Regex("[0-9.]+"))) rv else "—"
        val at1r = send("ATAT1")
        val hasAdaptive = at1r == "OK"
        return DeviceInfo(version, deviceId, protocol, voltage, hasAdaptive)
    }

    /** Этап 2: данные ЭБУ (требует зажигание, ELM уже инициализирован) */
    fun checkEcu(): EcuData {
        val pid0100 = send("0100")
        val supportsObd = pid0100.startsWith("41")
        val pidMask = if (supportsObd) pid0100.take(60) else "—"
        val vinRaw = send("0902")
        val vin = parseVin(vinRaw)
        return EcuData(supportsObd, pidMask, vin)
    }

    /** Сканирование DTC (03 + 07). Возвращает список кодов. */
    fun scanDtc(): List<String>? {
        if (!connectAndInit()) {
            disconnect()
            return null
        }
        val codes = mutableListOf<String>()
        val raw03 = send("03")
        val dtc03 = parseDtcCodes(raw03)
        codes.addAll(dtc03)
        val raw07 = send("07")
        val dtc07 = parseDtcCodes(raw07)
        codes.addAll(dtc07)
        disconnect()
        return codes.distinct()
    }

    private fun parseDtcCodes(raw: String): List<String> {
        val clean = raw.replace(Regex("\\s+"), "").uppercase()
        if (!clean.startsWith("43") && !clean.startsWith("47")) return emptyList()
        val hex = clean.substring(2)
        val codes = mutableListOf<String>()
        var i = 2  // skip byte count
        while (i + 3 < hex.length) {
            try {
                val a = Integer.parseInt(hex.substring(i, i + 2), 16)
                val b = Integer.parseInt(hex.substring(i + 2, i + 4), 16)
                val p = when (a shr 6) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                val code = "$p${(a shr 4) and 3}${a and 15}${b.toString(16).uppercase().padStart(2, '0')}"
                if (code != "P0000") codes.add(code)
            } catch (_: Exception) { break }
            i += 4
        }
        return codes
    }

    /** Полный цикл: подключение → прибор → ЭБУ (checkDevice сам подключается) */
    fun run(): Result {
        log("🔌 Подключение к ${device.name} (${device.address})...")

        // checkDevice() сам вызывает connectAndInit() — двойной вызов не нужен
        val deviceInfo = checkDevice() ?: return failResult("нет данных")

        val ecuData = checkEcu()

        val good = deviceInfo.hasAdaptive && deviceInfo.version.contains("v2")

        disconnect()

        return Result(
            good = good,
            device = deviceInfo,
            ecu = ecuData,
            log = logLines.joinToString("\n")
        )
    }

    private fun connectAndInit(): Boolean {
        try {
            connect()
            log("✅ BT OK")
            log("─── Проверка ELM327 ───")
            try { elm!!.init() } catch (e: Exception) { log("⚠️ init: ${e.message}") }
            return true
        } catch (e: Exception) {
            log("❌ ${e.message}")
            return false
        }
    }

    private fun cleanAt2(raw: String): String {
        val clean = raw.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        return if (clean.length > 3 && raw != "OK") clean.take(60) else "—"
    }

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

    /**
     * Создать BT-сокет и подключиться.
     * Если сокет уже открыт — повторно не подключается (идемпотентность).
     */
    private fun connect() {
        // Уже подключены — не дёргаем повторно
        if (socket?.isConnected == true) return

        // На всякий случай закроем старый сокет, если он есть, но не connected
        try { socket?.close() } catch (_: Exception) {}

        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            s.connect()
            socket = s
            Thread.sleep(500)
            elm = ElmProtocol(s.inputStream, s.outputStream)
        } catch (e: IOException) {
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                val s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
                socket = s
                Thread.sleep(500)
                elm = ElmProtocol(s.inputStream, s.outputStream)
            } catch (e2: Exception) {
                throw IOException("BT fallback: ${e2.message}", e2)
            }
        }
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logLines.add(msg)
    }

    private fun parseVersion(raw: String): String {
        if (raw.contains("ELM", ignoreCase = true)) return raw.take(40)
        if (raw == "OK" || raw.startsWith("?")) return "?"
        return raw.take(40)
    }

    private fun parseVin(raw: String): String? {
        // Ищем 17 символов VIN в ответе: 490201 + HEX
        val clean = raw.replace(Regex("\\s+"), "").uppercase()
        if (!clean.contains("490201")) return null
        val hex = clean.substringAfter("490201").take(34)
        if (hex.length < 34) return null
        return try {
            hex.chunked(2).take(17).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) { null }
    }

    private fun failResult(reason: String) = Result(
        good = false,
        device = DeviceInfo(version = "?", deviceId = "—", protocol = reason, voltage = "—", hasAdaptive = false),
        ecu = EcuData(supportsObd = false, pidMask = "—", vin = null),
        log = logLines.joinToString("\n")
    )
}
