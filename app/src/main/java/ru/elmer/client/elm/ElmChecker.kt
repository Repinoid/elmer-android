package ru.elmer.client.elm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Проверка ELM327: определяется качество устройства.
 *
 * Гоняет AT-команды (ATZ, ATI, AT@1, ATDP, ATRV, ATAT1, 0100, 0902)
 * и возвращает вердикт.
 *
 * Использование:
 *   val result = ElmChecker(device, bluetoothAdapter).run()
 *   // result.good — true/false
 *   // result.vin — VIN или null
 *   // result.log — построчный лог
 */
class ElmChecker(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "ElmChecker"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    data class Result(
        val good: Boolean,          // устройство чёткое или клон
        val version: String,        // ELM327 v2.1 / v1.5 / "?"
        val deviceId: String,       // AT@2 — идентификатор
        val protocol: String,       // OBD-протокол
        val voltage: String,        // напряжение бортсети (или "—")
        val hasAdaptive: Boolean,   // поддерживает ATAT1
        val pidMask: String,        // битмаска PID'ов (или "—")
        val vin: String?,           // VIN или null
        val log: String             // полный лог команд
    )

    private val logLines = mutableListOf<String>()
    private var socket: BluetoothSocket? = null
    private var elm: ElmProtocol? = null

    fun run(): Result {
        log("🔌 Подключение к ${device.name} (${device.address})...")
        try {
            connect()
            log("✅ BT OK")
            log("─── Проверка ELM327 ───")
        } catch (e: Exception) {
            log("❌ Ошибка подключения: ${e.message}")
            return failResult("нет соединения", e.message ?: "?")
        }

        try {
            elm!!.init()
        } catch (e: Exception) {
            log("⚠️ Инициализация с ошибкой: ${e.message}")
        }

        // 1. Версия (ATZ уже был в init, пробуем ATI)
        val ati = send("ATI")
        val version = parseVersion(ati)

        // 2. AT@1 — описание устройства (клон не ответит)
        val at1 = send("AT@1")
        val hasDeviceId = at1.length > 3 && at1 != "OK" && !at1.startsWith("?")

        // 2a. AT@2 — идентификатор (клон = каша/пусто)
        val at2 = send("AT@2")
        val deviceId = if (at2.length > 3 && !at2.matches(Regex("[? \t\r\n]+")) && at2 != "OK") at2.trim().take(60) else "—"

        // 3. Протокол
        val dp = send("ATDP")
        val protocol = if (dp.length > 3 && dp != "OK") dp.take(60) else dp

        // 4. Напряжение
        val rv = send("ATRV")
        val voltage = if (rv.contains("V", ignoreCase = true) || rv.matches(Regex("[0-9.]+"))) rv else "—"

        // 5. Адаптивный тайминг
        val at1r = send("ATAT1")
        val hasAdaptive = at1r == "OK"

        // 6. PID'ы ЭБУ
        val pid0100 = send("0100")
        val pidMask = if (pid0100.startsWith("41")) pid0100.take(60) else "—"

        // 7. VIN
        val vinRaw = send("0902")
        val vin = parseVin(vinRaw)

        // Определяем качество
        val good = hasDeviceId && hasAdaptive && version.contains("v2")

        disconnect()

        return Result(
            good = good,
            version = version,
            deviceId = deviceId,
            protocol = protocol,
            voltage = voltage,
            hasAdaptive = hasAdaptive,
            pidMask = pidMask,
            vin = vin,
            log = logLines.joinToString("\n")
        )
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

    private fun connect() {
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

    private fun failResult(version: String, protocol: String) = Result(
        good = false, version = version, deviceId = "—", protocol = protocol,
        voltage = "—", hasAdaptive = false, pidMask = "—",
        vin = null, log = logLines.joinToString("\n")
    )
}
