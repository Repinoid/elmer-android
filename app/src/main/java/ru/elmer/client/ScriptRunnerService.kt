package ru.elmer.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.IBinder
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.util.*
import kotlin.concurrent.thread

/**
 * Толстый клиент Elmer — офлайн-скрипты.
 *
 * Протокол:
 *   1. Скачивает скрипт с сервера (или кэш)
 *   2. Показывает промпты водителю
 *   3. Гоняет ELM327 сам (как TestService)
 *   4. Пишет всё в SQLite
 *   5. Заливает батч на сервер
 *   6. Показывает диагноз
 */
class ScriptRunnerService : Service() {

    // ── Transport ──────────────────────────────
    private var btSocket: BluetoothSocket? = null
    private var tcpSocket: Socket? = null
    private var inp: java.io.InputStream? = null
    private var out: java.io.OutputStream? = null

    // ── State ──────────────────────────────────
    private var sessionId: Long = -1
    private var scriptUrl: String = ""
    private var serverUrl: String = ""
    private var running = false
    @Volatile private var paused = false  // ждём действия водителя

    private lateinit var db: SessionDb
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val TAG = "ElmerScript"
        const val CHANNEL_ID = "elmer_script"
        const val NOTIFY_ID = 200
        const val ACTION_RUN = "ru.elmer.client.SCRIPT_RUN"
        const val ACTION_RESUME = "ru.elmer.client.SCRIPT_RESUME"  // водитель нажал «Далее»
        const val ACTION_STOP = "ru.elmer.client.SCRIPT_STOP"
        const val EXTRA_SCRIPT_URL = "script_url"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEBUG_HOST = "debug_host"
        const val BROADCAST_STATUS = "ru.elmer.client.SCRIPT_STATUS"
        const val BROADCAST_PROMPT = "ru.elmer.client.SCRIPT_PROMPT"  // показать водителю

        // ── Встроенный скрипт (fallback без сервера) ─────
        val DEFAULT_SCRIPT = """
{
  "version": 1,
  "title": "Базовая диагностика (офлайн)",
  "steps": [
    {"id": "init_atz",    "cmd": "ATZ",  "desc": "Сброс ELM327"},
    {"id": "init_ate0",   "cmd": "ATE0", "desc": "Эхо выкл"},
    {"id": "init_atl0",   "cmd": "ATL0", "desc": "Перевод строки выкл"},
    {"id": "init_atsp0",  "cmd": "ATSP0","desc": "Авто-протокол"},
    {"id": "init_ath1",   "cmd": "ATH1", "desc": "Заголовки вкл"},
    {"id": "vin",         "cmd": "0902", "desc": "VIN"},
    {"id": "dtc_stored",  "cmd": "03",   "desc": "Ошибки (сохранённые)"},
    {"id": "dtc_pending", "cmd": "07",   "desc": "Ошибки (ожидающие)"},
    {"id": "pid_05", "cmd": "0105", "desc": "Температура ОЖ"},
    {"id": "pid_0C", "cmd": "010C", "desc": "Обороты"},
    {"id": "pid_0D", "cmd": "010D", "desc": "Скорость"},
    {"id": "pid_11", "cmd": "0111", "desc": "Дроссель"},
    {"id": "pid_0B", "cmd": "010B", "desc": "MAP"},
    {"id": "pid_0F", "cmd": "010F", "desc": "IAT"},
    {"id": "pid_1F", "cmd": "011F", "desc": "Время работы"},
    {"id": "pid_04", "cmd": "0104", "desc": "Нагрузка"},
    {"id": "pid_06", "cmd": "0106", "desc": "STFT"},
    {"id": "pid_07", "cmd": "0107", "desc": "LTFT"}
  ]
}
        """.trimIndent()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        db = SessionDb(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME -> {
                paused = false
                say("▶ Водитель нажал «Далее»")
            }
            ACTION_STOP -> {
                say("⏹ Стоп")
                disconnect(); return START_NOT_STICKY
            }
            ACTION_RUN -> {
                scriptUrl = intent.getStringExtra(EXTRA_SCRIPT_URL)
                    ?: intent.getStringExtra(EXTRA_SERVER_URL)?.let { "$it/api/v1/script" }
                    ?: "http://10.47.183.102:5005/api/v1/script"
                serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                    ?: "http://10.47.183.102:5005"
                val debugHost = intent.getStringExtra(EXTRA_DEBUG_HOST)

                startForeground(NOTIFY_ID, buildNotification())
                running = true

                thread(name = "ScriptRunner", isDaemon = true) {
                    if (debugHost != null) {
                        val parts = debugHost.split(":")
                        connectTcp(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 35000)
                    } else {
                        connectBt()
                    }
                }
            }
        }
        return START_STICKY
    }

    // ── Connect ─────────────────────────────────────

    private fun connectTcp(host: String, port: Int) {
        header("TCP $host:$port")
        try {
            tcpSocket = Socket(host, port).also { it.soTimeout = 3000 }
            inp = tcpSocket!!.inputStream
            out = tcpSocket!!.outputStream
            say("✅ TCP OK")
        } catch (e: Exception) {
            say("❌ TCP: ${e.message}"); done("Ошибка подключения"); return
        }
        runScript()
    }

    private fun connectBt() {
        header("Bluetooth")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { say("❌ Нет Bluetooth"); done(""); return }

        val paired = adapter.bondedDevices
        val dev = paired.find { d ->
            d.name.uppercase().let { it.contains("OBD") || it.contains("ELM") || it.contains("CBT") }
        }
        if (dev == null) {
            say("❌ ELM не найден"); done(""); return
        }
        say("   Найден: ${dev.name}")
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
            say("❌ BT: ${e.message}"); done(""); return
        }
        runScript()
    }

    // ── Script Engine ────────────────────────────────

    private fun runScript() {
        // 1. Скачиваем скрипт
        val scriptJson = downloadScript()
        if (scriptJson == null) {
            done("❌ Не удалось загрузить скрипт")
            return
        }

        // 2. Парсим
        val script = try { JSONObject(scriptJson) } catch (e: Exception) {
            done("❌ Плохой JSON скрипта"); return
        }
        val title = script.optString("title", "Диагностика")
        val steps = script.optJSONArray("steps")
        if (steps == null || steps.length() == 0) {
            done("❌ Скрипт пуст"); return
        }

        // 3. Создаём сессию в SQLite
        sessionId = db.createSession(scriptJson, title, serverUrl)
        say("📋 Скрипт: $title (${steps.length()} шагов)")

        // 4. Выполняем шаги
        for (i in 0 until steps.length()) {
            if (!running) break

            val step = steps.getJSONObject(i)
            val stepId = step.optString("id", "step_$i")
            val cmd = step.optString("cmd", "")
            val prompt = step.optString("prompt", "")
            val desc = step.optString("desc", stepId)
            val waitUser = step.optBoolean("wait_for_user", false)

            // Промпт водителю
            if (prompt.isNotEmpty()) {
                showPrompt(prompt)
                if (waitUser) {
                    say("⏸ Жду водителя: $prompt")
                    paused = true
                    while (paused && running) Thread.sleep(500)
                    if (!running) break
                    showPrompt("")  // скрыть
                }
                continue  // prompt-шаги не шлют команды
            }

            if (cmd.isEmpty()) continue

            // Выполняем команду
            say("─── $desc ───")
            say("→ $cmd")
            val raw = sendAndRead(cmd)

            val decoded = decodeResponse(cmd, raw)
            say("← $raw")
            if (decoded != raw.trim()) say("   → $decoded")

            // Пишем в SQLite
            db.addResponse(sessionId, stepId, cmd, raw, decoded)
        }

        // 5. Загружаем батч на сервер
        uploadSession()
    }

    // ── Upload ───────────────────────────────────────

    private fun uploadSession() {
        if (sessionId < 0) return

        say("📤 Загрузка на сервер...")
        val responses = db.getResponses(sessionId)
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("responses", JSONArray().apply {
                for (r in responses) {
                    put(JSONObject().apply {
                        put("step_id", r["step_id"])
                        put("cmd", r["cmd"])
                        put("raw", r["raw"])
                        put("decoded", r["decoded"])
                        put("timestamp", r["timestamp"])
                    })
                }
            })
        }

        try {
            val req = Request.Builder()
                .url("$serverUrl/api/v1/session/upload")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()

            try {
                val respJson = JSONObject(body)
                val diagnosis = respJson.optString("diagnosis", "")
                if (diagnosis.isNotEmpty()) {
                    say("══════════════════")
                    say("🩺 ДИАГНОЗ:")
                    diagnosis.chunked(60).forEach { say(it.trim()) }
                    say("══════════════════")
                }
                val error = respJson.optString("error", "")
                if (error.isNotEmpty()) say("❌ Сервер: $error")
            } catch (_: Exception) {
                say("⚠️ Ответ: ${body.take(100)}")
            }

            db.markUploaded(sessionId)
            say("✅ Загружено")
        } catch (e: IOException) {
            say("⚠️ Сервер недоступен: ${e.message?.take(40)}")
            say("   Данные сохранены, попробуем позже")
        }

        done("✅ Завершено")
    }

    // ── HTTP ─────────────────────────────────────────

    private fun downloadScript(): String? {
        say("📥 Загрузка скрипта...")
        try {
            val req = Request.Builder().url(scriptUrl).build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                say("❌ HTTP ${resp.code}")
                return null
            }
            return body
        } catch (e: IOException) {
            say("⚠️ Офлайн — использую встроенный скрипт")
            return DEFAULT_SCRIPT
        }
    }

    // ── ELM327 Decoder ───────────────────────────────

    private fun decodeResponse(cmd: String, raw: String): String {
        if (raw.startsWith("SEARCHING") || raw.startsWith("STOPPED") ||
            raw == "OK" || raw == "NO DATA" || raw == "?") return raw

        val clean = raw.replace(":", "").replace(" ", "").uppercase()

        if (cmd == "0902" && "490201" in clean) {
            val hex = clean.substringAfter("490201").take(34)
            val vin = buildString {
                for (i in hex.indices step 2) {
                    if (i + 1 >= hex.length) break
                    try { append(Integer.parseInt(hex.substring(i, i+2), 16).toChar()) }
                    catch (_: Exception) { break }
                }
            }
            if (vin.length == 17) return "VIN: $vin"
            if (vin.isNotEmpty()) return "VIN(${vin.length}): $vin"
        }

        if (clean.startsWith("43") || clean.startsWith("47")) {
            val mode = if (clean.startsWith("43")) "stored" else "pending"
            val hex = clean.substring(2)
            val codes = mutableListOf<String>()
            var i = 2
            while (i + 3 < hex.length) {
                try {
                    val a = Integer.parseInt(hex.substring(i, i+2), 16)
                    val b = Integer.parseInt(hex.substring(i+2, i+4), 16)
                    val p = when (a shr 6) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                    val code = "$p${(a shr 4) and 3}${a and 15}${b.toString(16).uppercase().padStart(2,'0')}"
                    if (code != "P0000") codes.add(code)
                } catch (_: Exception) { break }
                i += 4
            }
            if (codes.isEmpty()) return "DTC $mode: none"
            return "DTC $mode: ${codes.joinToString(" ")}"
        }

        if (clean.startsWith("41") && clean.length >= 6) {
            val pid = clean.substring(2, 4)
            val hex = clean.substring(4)
            try {
                val b0 = Integer.parseInt(hex.substring(0, 2), 16)
                val b1 = if (hex.length >= 4) Integer.parseInt(hex.substring(2, 4), 16) else 0
                val (name, res) = when (pid) {
                    "05" -> "ОЖ" to "${b0 - 40} °C"
                    "0C" -> "RPM" to "${(b0 * 256 + b1) / 4.0} RPM"
                    "0D" -> "Скорость" to "$b0 км/ч"
                    "11" -> "Дроссель" to "${"%.1f".format(b0 * 100.0 / 255.0)} %"
                    "0B" -> "MAP" to "$b0 кПа"
                    "0F" -> "IAT" to "${b0 - 40} °C"
                    "1F" -> "Время" to "${b0 * 256 + b1} с"
                    "04" -> "Нагрузка" to "${"%.1f".format(b0 * 100.0 / 255.0)} %"
                    "06" -> "STFT" to "${"%.1f".format((b0 - 128) * 100.0 / 128.0)} %"
                    "07" -> "LTFT" to "${"%.1f".format((b0 - 128) * 100.0 / 128.0)} %"
                    else -> pid to null
                }
                if (res != null) return "$name: $res"
            } catch (_: Exception) {}
        }

        return raw.trim()
    }

    // ── Low-level ELM327 ─────────────────────────────

    private fun sendAndRead(cmd: String): String {
        try {
            out?.write((cmd + "\r\n").toByteArray())
            out?.flush()
        } catch (e: Exception) {
            say("❌ WRITE: ${e.message}")
            return "(write error)"
        }
        Thread.sleep(250)
        return readResponse()
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

    // ── UI helpers ───────────────────────────────────

    private fun header(mode: String) {
        say("══════════════════")
        say("🔧 ELMER SCRIPT")
        say("   Режим: $mode")
        say("══════════════════")
    }

    private fun done(msg: String) {
        if (msg.isNotEmpty()) {
            say("══════════════════")
            say(msg)
            say("══════════════════")
        }
        disconnect()
        stopSelf()
    }

    private fun say(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra("message", msg)
            setPackage(packageName)
        })
        Log.i(TAG, msg)
    }

    /** Показать/скрыть промпт водителю. Пустая строка = скрыть. */
    private fun showPrompt(text: String) {
        sendBroadcast(Intent(BROADCAST_PROMPT).apply {
            putExtra("prompt", text)
            setPackage(packageName)
        })
    }

    private fun disconnect() {
        running = false
        try { btSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Elmer Script",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Elmer").setContentText("Скрипт...")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Elmer").setContentText("Скрипт...")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        }
    }

}
