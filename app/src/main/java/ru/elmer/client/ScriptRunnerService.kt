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
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Толстый клиент Elmer — сервис для офлайн-диагностики по скрипту.
 *
 * Жизненный цикл:
 *   1. ACTION_RUN → connectBt/connectTcp → ElmProtocol.init() → ScriptEngine.run()
 *   2. ACTION_RESUME → продолжить после паузы (водитель ответил)
 *   3. ACTION_STOP → остановить
 */
class ScriptRunnerService : Service() {

    private var btSocket: BluetoothSocket? = null
    private var tcpSocket: Socket? = null
    private var elm: ElmProtocol? = null
    private var serverUrl: String = ""
    private var scriptUrl: String = ""
    private var running = false
    @Volatile private var paused = false
    private lateinit var db: SessionDb
    private var sessionId: Long = -1

    companion object {
        const val TAG = "ElmerScript"
        const val CHANNEL_ID = "elmer_script"
        const val NOTIFY_ID = 200
        const val ACTION_RUN = "ru.elmer.client.SCRIPT_RUN"
        const val ACTION_RESUME = "ru.elmer.client.SCRIPT_RESUME"
        const val ACTION_STOP = "ru.elmer.client.SCRIPT_STOP"
        const val EXTRA_SCRIPT_URL = "script_url"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEBUG_HOST = "debug_host"
        const val BROADCAST_STATUS = "ru.elmer.client.SCRIPT_STATUS"
        const val BROADCAST_PROMPT = "ru.elmer.client.SCRIPT_PROMPT"

        val DEFAULT_SCRIPT = """
{
  "version": 1,
  "title": "Базовая диагностика (офлайн)",
  "steps": [
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

    // ── Service lifecycle ───────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        db = SessionDb(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME -> { paused = false; log("▶ Водитель нажал «Далее»") }
            ACTION_STOP -> { log("⏹ Стоп"); disconnect(); return START_NOT_STICKY }
            ACTION_RUN -> startRun(intent)
        }
        return START_STICKY
    }

    private fun startRun(intent: Intent) {
        scriptUrl = intent.getStringExtra(EXTRA_SCRIPT_URL)
            ?: intent.getStringExtra(EXTRA_SERVER_URL)?.let { "$it/api/v1/script" }
            ?: "http://10.47.183.102:5005/api/v1/script"
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://10.47.183.102:5005"
        val debugHost = intent.getStringExtra(EXTRA_DEBUG_HOST)

        startForeground(NOTIFY_ID, buildNotification())
        running = true

        thread(name = "ScriptRunner", isDaemon = true) {
            if (debugHost != null) {
                val p = debugHost.split(":")
                connectTcp(p[0], p.getOrNull(1)?.toIntOrNull() ?: 35000)
            } else connectBt()
        }
    }

    // ── Connect ─────────────────────────────────────────

    private fun connectTcp(host: String, port: Int) {
        logHeader("TCP $host:$port")
        try {
            tcpSocket = Socket(host, port).also { it.soTimeout = 3000 }
            elm = ElmProtocol(tcpSocket!!.inputStream, tcpSocket!!.outputStream)
            log("✅ TCP OK")
        } catch (e: Exception) { log("❌ TCP: ${e.message}"); done("Ошибка"); return }
        executeScript()
    }

    private fun connectBt() {
        logHeader("Bluetooth")
        val a = BluetoothAdapter.getDefaultAdapter()
        if (a == null) { log("❌ Нет BT"); done(""); return }
        val dev = a.bondedDevices.find {
            it.name.uppercase().let { n -> n.contains("OBD") || n.contains("ELM") || n.contains("CBT") }
        }
        if (dev == null) { log("❌ ELM не найден"); done(""); return }
        log("   Найден: ${dev.name}"); log("⏳ Подключение...")
        try {
            btSocket = connectBtSocket(dev, a)
            Thread.sleep(500)
            elm = ElmProtocol(btSocket!!.inputStream, btSocket!!.outputStream)
            log("✅ BT OK")
        } catch (e: Exception) { log("❌ BT: ${e.message}"); done(""); return }
        executeScript()
    }

    private fun connectBtSocket(dev: BluetoothDevice, a: BluetoothAdapter): BluetoothSocket {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        try {
            val s = dev.createRfcommSocketToServiceRecord(uuid); a.cancelDiscovery()
            s.connect(); return s
        } catch (e: IOException) {
            try {
                val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.java)
                val s = m.invoke(dev, 1) as BluetoothSocket; s.connect(); return s
            } catch (e2: Exception) { throw IOException("BT fallback: ${e2.message}", e2) }
        }
    }

    // ── Script execution ────────────────────────────────

    private fun executeScript() {
        val client = ServerClient(serverUrl, scriptUrl, DEFAULT_SCRIPT)
        val scriptJson = client.downloadScript()

        log("─── Инициализация ELM327 ───")
        elm?.init()

        val title = try {
            org.json.JSONObject(scriptJson).optString("title", "Диагностика")
        } catch (_: Exception) { "Диагностика" }
        sessionId = db.createSession(scriptJson, title, serverUrl)

        val engine = ScriptEngine(
            sendCommand = { try { elm?.sendCommand(it) ?: "(no elm)" } catch (e: Exception) { "(err)" } },
            onPrompt = { text ->
                sendBroadcast(Intent(BROADCAST_PROMPT).apply { putExtra("prompt", text); setPackage(packageName) })
            },
            waitForResume = { paused = true; while (paused && running) Thread.sleep(500); running },
            onResult = { sid, cmd, raw, dec -> db.addResponse(sessionId, sid, cmd, raw, dec) },
            onLog = { log(it) }
        )

        val ok = engine.run(scriptJson)

        if (ok) {
            log("📤 Загрузка на сервер...")
            val resp = client.uploadSession(sessionId, db.getResponses(sessionId))
            if (resp != null) {
                val d = resp.optString("diagnosis", "")
                if (d.isNotEmpty()) {
                    log("══════════════════"); log("🩺 ДИАГНОЗ:")
                    d.chunked(60).forEach { log(it.trim()) }
                    log("══════════════════")
                }
                db.markUploaded(sessionId); log("✅ Загружено")
            } else log("⚠️ Сервер недоступен. Данные сохранены.")
        }
        done(if (ok) "✅ Завершено" else "⏹ Прервано")
    }

    // ── Helpers ─────────────────────────────────────────

    private fun logHeader(msg: String) {
        log("══════════════════"); log("🔧 ELMER SCRIPT"); log("   $msg"); log("══════════════════")
    }
    private fun done(msg: String) {
        if (msg.isNotEmpty()) { log("══════════════════"); log(msg); log("══════════════════") }
        disconnect(); stopSelf()
    }
    private fun log(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply { putExtra("message", msg); setPackage(packageName) })
        Log.i(TAG, msg)
    }
    private fun disconnect() {
        running = false
        try { btSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Elmer Script", NotificationManager.IMPORTANCE_LOW))
        }
    }
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Elmer").setContentText("Скрипт...")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
    }
}
