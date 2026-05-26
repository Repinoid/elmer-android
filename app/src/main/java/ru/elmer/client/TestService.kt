package ru.elmer.client

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.net.Socket
import java.util.*
import kotlin.concurrent.thread

/**
 * ТЕСТОВЫЙ сервис. Без HTTP, без сервера.
 * TCP (mock) или Bluetooth (реальный ELM327) — автоопределение.
 * Гонит протокол сам: AT-init → VIN → DTC → PID.
 */
class TestService : Service() {

    private var socket: Socket? = null
    private var btSocket: BluetoothSocket? = null
    private var inp: java.io.InputStream? = null
    private var out: java.io.OutputStream? = null

    companion object {
        const val TAG = "ElmerTest"
        const val ACTION_RUN = "ru.elmer.client.TEST_RUN"
        const val BROADCAST_STATUS = "ru.elmer.client.TEST_STATUS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RUN) return START_NOT_STICKY

        val host = intent.getStringExtra("host")
        val port = intent.getIntExtra("port", 35000)

        thread(name = "TestRunner", isDaemon = true) {
            if (host != null) {
                runTestTcp(host, port)
            } else {
                runTestBt()
            }
        }
        return START_NOT_STICKY
    }

    // ── TCP (mock ELM327) ─────────────────────────

    private fun runTestTcp(host: String, port: Int) {
        header("TCP $host:$port")
        try {
            socket = Socket(host, port).also { it.soTimeout = 3000 }
            inp = socket!!.inputStream
            out = socket!!.outputStream
            say("✅ TCP OK")
        } catch (e: Exception) {
            say("❌ TCP: ${e.message}")
            stopSelf(); return
        }
        runProtocol()
    }

    // ── Bluetooth (реальный ELM327) ───────────────

    private fun runTestBt() {
        header("Bluetooth")

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            say("❌ Нет Bluetooth")
            stopSelf(); return
        }

        // Ищем спаренный ELM327
        val paired = adapter.bondedDevices
        val dev = paired.find { d ->
            d.name.uppercase().let { it.contains("OBD") || it.contains("ELM") || it.contains("CBT") }
        }

        if (dev == null) {
            say("❌ ELM не найден в сопряжённых")
            say("   Сопряги в Настройки → Bluetooth: PIN 1234")
            stopSelf(); return
        }

        say("   Найден: ${dev.name} (${dev.address})")
        say("⏳ Подключение...")

        try {
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            btSocket = dev.createRfcommSocketToServiceRecord(uuid)
            adapter.cancelDiscovery()
            btSocket?.connect()
            inp = btSocket?.inputStream
            out = btSocket?.outputStream
            say("✅ BT OK")
        } catch (e: Exception) {
            say("❌ BT: ${e.message}")
            disconnectBt(); stopSelf(); return
        }

        runProtocol()
    }

    // ── Общий протокол ────────────────────────────

    private fun runProtocol() {
        say("─── ШАГ 1: Связь ───")
        say("→ ATZ")
        if (!sendAndRead("ATZ")) { done("❌ ELM не отвечает"); return }

        say("✅ СВЯЗЬ ЕСТЬ!")
        say("─── ШАГ 2: Инициализация ───")
        for (cmd in listOf("ATE0", "ATL0", "ATSP0", "ATH1")) {
            if (!sendAndRead(cmd)) { done("❌ Сбой на $cmd"); return }
        }

        say("─── ШАГ 3: VIN ───")
        if (!sendAndRead("0902")) { done("⚠️ VIN не прочитан"); }

        say("─── ШАГ 4: Ошибки ───")
        sendAndRead("03")  // stored
        sendAndRead("07")  // pending

        say("─── ШАГ 5: Параметры ───")
        val pids = listOf("0105", "010C", "010D", "0111", "010B", "010F", "011F", "0104", "0106", "0107")
        for (pid in pids) {
            sendAndRead(pid)
            Thread.sleep(100)
        }

        done("✅ ТЕСТ ПРОЙДЕН!")
    }

    // ── Декодер сырых ответов ─────────────────────────

    private fun decode(cmd: String, raw: String): String {
        val clean = raw.replace(" ", "").uppercase()

        // VIN: 0902 → 490201575657...
        if (cmd == "0902" && clean.contains("490201")) {
            val hex = clean.substringAfter("490201")
            val vin = buildString {
                for (i in hex.indices step 2) {
                    if (i + 1 >= hex.length) break
                    try { append(Integer.parseInt(hex.substring(i, i+2), 16).toChar()) }
                    catch (_: Exception) {}
                }
            }
            if (vin.length == 17) return "VIN: $vin"
            if (vin.isNotEmpty()) return "VIN(${vin.length}): $vin"
        }

        // DTC: 43/47 → коды ошибок
        if (clean.startsWith("43") || clean.startsWith("47")) {
            val mode = if (clean.startsWith("43")) "stored" else "pending"
            val hex = clean.substring(2)
            val codes = mutableListOf<String>()
            var i = 2 // skip byte count
            while (i + 3 < hex.length) {
                try {
                    val a = Integer.parseInt(hex.substring(i, i+2), 16)
                    val b = Integer.parseInt(hex.substring(i+2, i+4), 16)
                    val prefix = when (a shr 6) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                    val code = "$prefix${(a shr 4) and 3}${a and 15}${b.toString(16).uppercase().padStart(2,'0')}"
                    if (code != "P0000") codes.add(code)
                } catch (_: Exception) {}
                i += 4
            }
            if (codes.isEmpty()) return "DTC $mode: none"
            return "DTC $mode: ${codes.joinToString(" ")}"
        }

        // PID: 41XX → значение
        if (clean.startsWith("41") && clean.length >= 6) {
            val pid = clean.substring(2, 4)
            val hex = clean.substring(4)
            try {
                val b0 = Integer.parseInt(hex.substring(0, 2), 16)
                val b1 = if (hex.length >= 4) Integer.parseInt(hex.substring(2, 4), 16) else 0
                val result = when (pid) {
                    "05" -> "${b0 - 40} °C"
                    "0C" -> "${(b0 * 256 + b1) / 4.0} об/мин"
                    "0D" -> "$b0 км/ч"
                    "11" -> "${(b0 * 100.0 / 255.0).let { "%.1f".format(it) }} %"
                    "0B" -> "$b0 кПа"
                    "0F" -> "${b0 - 40} °C"
                    "1F" -> "${b0 * 256 + b1} с"
                    "04" -> "${(b0 * 100.0 / 255.0).let { "%.1f".format(it) }} %"
                    "06", "07" -> "${((b0 - 128) * 100.0 / 128.0).let { "%.1f".format(it) }} %"
                    else -> null
                }
                if (result != null) {
                    val names = mapOf(
                        "05" to "ОЖ", "0C" to "RPM", "0D" to "Скорость",
                        "11" to "Дроссель", "0B" to "MAP", "0F" to "IAT",
                        "1F" to "Время", "04" to "Нагрузка", "06" to "STFT", "07" to "LTFT"
                    )
                    return "${names[pid] ?: pid}: $result"
                }
            } catch (_: Exception) {}
        }

        // Сырой — как есть, но компактно
        return raw.trim()
    }

    // ── Низкоуровневые ────────────────────────────

    private fun sendAndRead(cmd: String): Boolean {
        try {
            out?.write((cmd + "\r\n").toByteArray())
            out?.flush()
        } catch (e: Exception) {
            say("❌ WRITE: ${e.message}")
            return false
        }
        Thread.sleep(250)
        val resp = readResponse()
        say("← ${decode(cmd, resp)}")
        return resp.isNotEmpty() && resp != "(timeout)"
    }

    private fun readResponse(): String {
        val sb = StringBuilder()
        try {
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                val b = inp?.read() ?: -1
                if (b == -1) break
                val c = b.toChar()
                if (c == '>') break
                if (c != '\r' && c != '\n') sb.append(c)
            }
        } catch (e: IOException) {
            if (sb.isEmpty()) sb.append("(timeout)")
        }
        return sb.toString().trim().take(120)
    }

    private fun header(mode: String) {
        say("══════════════════")
        say("🔧 ELMER TEST v0.6")
        say("   Режим: $mode")
        say("══════════════════")
    }

    private fun done(msg: String) {
        say("══════════════════")
        say(msg)
        say("══════════════════")
        disconnect()
        disconnectBt()
        stopSelf()
    }

    private fun say(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra("message", msg)
            setPackage(packageName)
        })
        Log.i(TAG, msg)
    }

    private fun disconnect() { try { socket?.close() } catch (_: Exception) {} }
    private fun disconnectBt() { try { btSocket?.close() } catch (_: Exception) {} }
    override fun onDestroy() { disconnect(); disconnectBt(); super.onDestroy() }
}
