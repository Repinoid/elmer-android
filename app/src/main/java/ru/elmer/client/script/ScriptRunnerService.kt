package ru.elmer.client.script

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import ru.elmer.client.db.SessionDb
import ru.elmer.client.elm.ElmProtocol
import ru.elmer.client.server.ServerClient
import ru.elmer.client.ui.MainActivity

/**
 * Толстый клиент Elmer — сервис для офлайн-диагностики по скрипту.
 *
 * Жизненный цикл:
 *   1. ACTION_RUN → connectBt → ElmProtocol.init() → ScriptEngine.run()
 *   2. ACTION_STOP → остановить
 */
class ScriptRunnerService : Service() {

    private var btSocket: BluetoothSocket? = null
    private var elm: ElmProtocol? = null
    private var serverUrl: String = ""
    private var scriptUrl: String = ""
    private var running = false
    private lateinit var db: SessionDb
    private var sessionId: Long = -1
    private var startTime: Long = 0
    private var elmMac: String = ""
    private var elmBtName: String = ""
    private var obdProtocol: String = ""
    private var errorCount: Int = 0
    private var retryCount: Int = 0
    private var timeoutCount: Int = 0
    private var scriptMode: String = ""
    private var carInfo: String = ""
    private var dynamicSamplesJson: String? = null

    companion object {
        const val TAG = "ElmerScript"
        const val CHANNEL_ID = "elmer_script"
        const val NOTIFY_ID = 200
        const val ACTION_RUN = "ru.elmer.client.SCRIPT_RUN"
        const val ACTION_STOP = "ru.elmer.client.SCRIPT_STOP"
        const val EXTRA_SCRIPT_URL = "script_url"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEBUG_HOST = "debug_host"
        const val EXTRA_CAR_INFO = "car_info"
        const val EXTRA_DYNAMIC_SAMPLES = "dynamic_samples"
        const val BROADCAST_STATUS = "ru.elmer.client.SCRIPT_STATUS"
        const val BROADCAST_PROMPT = "ru.elmer.client.SCRIPT_PROMPT"
        const val BROADCAST_STAGE  = "ru.elmer.client.SCRIPT_STAGE"

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
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> { log("⏹ Стоп"); disconnect(); return START_NOT_STICKY }
            ACTION_RUN -> startRun(intent)
        }
        return START_NOT_STICKY
    }

    private fun startRun(intent: Intent) {
        if (running) { log("⚠️ Уже запущен"); return }
        running = true
        scriptUrl = intent.getStringExtra(EXTRA_SCRIPT_URL)
            ?: intent.getStringExtra(EXTRA_SERVER_URL)?.let { "$it/api/v1/script" }
            ?: "https://obdai.ru/api/v1/script"
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "https://obdai.ru"
        carInfo = intent.getStringExtra(EXTRA_CAR_INFO) ?: ""
        dynamicSamplesJson = intent.getStringExtra(EXTRA_DYNAMIC_SAMPLES)
        elmMac = intent.getStringExtra("elm_mac") ?: ""

        scriptMode = if (scriptUrl.contains("full")) "full" else "test"
        startTime = System.currentTimeMillis()

        startForeground(NOTIFY_ID, buildNotification())
        running = true

        thread(name = "ScriptRunner", isDaemon = true) { connectBt() }
    }

    // ── Connect ─────────────────────────────────────────

    private fun connectBt() {
        logHeader("Bluetooth")
        val a = BluetoothAdapter.getDefaultAdapter()
        if (a == null) {
            log("❌ Bluetooth не поддерживается на этом устройстве")
            errorDone("Bluetooth отсутствует")
            return
        }
        if (!a.isEnabled) {
            log("❌ Bluetooth выключен на телефоне")
            log("🔧 Включите Bluetooth в настройках и повторите.")
            errorDone("Bluetooth выключен")
            return
        }
        val bonded = a.bondedDevices?.toList() ?: emptyList()
        if (bonded.isEmpty()) {
            log("❌ Нет спаренных Bluetooth-устройств")
            log("🔧 Сопрягите ELM327 в Настройки → Bluetooth.")
            errorDone("Нет спаренных устройств")
            return
        }
        val targetMac = elmMac
        val dev = if (targetMac != null) bonded.find { it.address == targetMac } else bonded[0]
        if (dev == null) {
            log("❌ Устройство с MAC $targetMac не найдено")
            errorDone("ELM не найден")
            return
        }
        elmMac = dev.address; elmBtName = dev.name
        log("   Найден: ${dev.name} (${dev.address})"); log("⏳ Подключение...")
        try {
            btSocket = connectBtSocket(dev, a)
            Thread.sleep(500)
            elm = ElmProtocol(btSocket!!.inputStream, btSocket!!.outputStream)
            log("✅ BT OK")
        } catch (e: Exception) {
            log("❌ Ошибка подключения: ${e.message}")
            log("🔧 Попробуйте: перезагрузить телефон, вынуть/вставить ELM327, проверить зажигание.")
            errorDone("Ошибка подключения")
            return
        }
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
            } catch (e2: Exception) { throw IOException("не удалось подключиться к ELM327", e2) }
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
            onStage = { stage, detail ->
                sendBroadcast(Intent(BROADCAST_STAGE).apply { putExtra("stage", stage); putExtra("detail", detail); setPackage(packageName) })
            },
            onResult = { sid, cmd, raw, dec -> db.addResponse(sessionId, sid, cmd, raw, dec) },
            onLog = { log(it) }
        )

        val ok = engine.run(scriptJson)

        if (ok) {
            val responses = db.getResponses(sessionId)
            val count = responses.size

            val clientInfo = buildClientInfo()
            // Оценка размера данных: 200 байт на ответ + client_info
            val dataSizeKB = (count * 200 + 500) / 1024
            log("📤 Отправка $count ответов (~${dataSizeKB}KB) на сервер...")

            val progress = UploadProgress(this, packageName, count, dataSizeKB)
            progress.start()
            val resp = try {
                val dynSamples = parseDynamicSamples()
                client.uploadSession(sessionId, responses, clientInfo, carInfo, dynSamples)
            } finally {
                progress.stop()
            }
            if (resp != null) {
                val llmOk = resp.optBoolean("llm_success", false)
                val llmAvail = resp.optBoolean("llm_available", false)

                if (llmOk) {
                    sendBroadcast(Intent(BROADCAST_STAGE).apply { putExtra("stage", "llm"); putExtra("detail", "🧠 LLM анализ..."); setPackage(packageName) })
                }

                val d = resp.optString("diagnosis", "")
                if (d.isNotEmpty()) {
                    log("══════════════════")
                    if (llmOk) log("🩺 ДИАГНОЗ (LLM):")
                    else if (llmAvail) log("⚠️ LLM ответил с ошибкой:")
                    else log("📋 КОДЫ ОШИБОК (LLM недоступен):")
                    d.chunked(60).forEach { log(it.trim()) }
                    log("══════════════════")
                    db.saveDiagnosis(sessionId, d)
                }
                val status = if (llmOk) "✅ Готово (LLM)"
                             else if (llmAvail) "⚠️ LLM ошибка, коды готовы"
                             else "📋 Коды готовы (LLM выкл)"
                sendBroadcast(Intent(BROADCAST_STAGE).apply { putExtra("stage", "done"); putExtra("detail", status); setPackage(packageName) })
                db.markUploaded(sessionId); log("✅ Загружено")
            } else log("⚠️ Сервер недоступен. Данные сохранены.")
        }
        done(if (ok) "✅ Завершено" else "⏹ Прервано")
    }

    // ── Client info ──────────────────────────────────────

    private fun buildClientInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()

        // Телефон
        info["phone_model"] = Build.MODEL
        info["phone_maker"] = Build.MANUFACTURER
        info["android_version"] = Build.VERSION.RELEASE
        info["android_sdk"] = Build.VERSION.SDK_INT.toString()

        // Версия приложения
        try {
            info["app_version"] = packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {}

        // Android ID (уникальный ID устройства)
        try {
            info["android_id"] = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {}

        // UUID приложения (не сбрасывается при обновлении)
        try {
            val prefs = getSharedPreferences("elmer", 0)
            var uuid = prefs.getString("device_uuid", null)
            if (uuid == null) {
                uuid = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("device_uuid", uuid).apply()
            }
            info["device_uuid"] = uuid
        } catch (_: Exception) {}

        // Регион, язык, дисплей (безопасно, без разрешений)
        info["phone_lang"] = java.util.Locale.getDefault().toString()
        info["phone_tz"] = java.util.TimeZone.getDefault().id
        info["phone_display"] = resources.displayMetrics.run { "${widthPixels}x${heightPixels} @${densityDpi}dpi" }

        // ELM327
        info["elm_mac"] = elmMac
        info["elm_bt_name"] = elmBtName
        info["obd_protocol"] = obdProtocol

        // Сессия
        info["duration_ms"] = (System.currentTimeMillis() - startTime).toString()
        info["error_count"] = errorCount.toString()
        info["retry_count"] = retryCount.toString()
        info["timeout_count"] = timeoutCount.toString()
        info["script_mode"] = scriptMode
        info["transport"] = "bt"

        return info
    }

    private fun parseDynamicSamples(): List<List<Map<String, String?>>>? {
        val json = dynamicSamplesJson ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val batch = arr.getJSONArray(i)
                (0 until batch.length()).map { j ->
                    val obj = batch.getJSONObject(j)
                    mapOf(
                        "step_id" to obj.optString("step_id"),
                        "cmd" to obj.optString("cmd"),
                        "raw" to obj.optString("raw"),
                        "decoded" to obj.optString("decoded"),
                        "timestamp" to obj.optString("timestamp"),
                    )
                }
            }
        } catch (e: Exception) { null }
    }

    // ── Helpers ─────────────────────────────────────────

    private fun logHeader(msg: String) {
        log("══════════════════"); log("🔧 ELMER SCRIPT"); log("   $msg"); log("══════════════════")
    }
    private fun done(msg: String) {
        if (msg.isNotEmpty()) { log("══════════════════"); log(msg); log("══════════════════") }
        disconnect(); stopSelf()
    }
    private fun errorDone(reason: String) {
        // Посылаем "done" чтобы показать кнопку ✕ Закрыть и не стирать вывод
        sendBroadcast(Intent(BROADCAST_STAGE).apply {
            putExtra("stage", "done"); putExtra("detail", "❌ $reason"); setPackage(packageName)
        })
        disconnect(); stopSelf()
    }
    private fun log(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply { putExtra("message", msg); setPackage(packageName) })
        Log.i(TAG, msg)
    }
    private fun disconnect() {
        running = false
        try { btSocket?.close() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "elmAI Script", NotificationManager.IMPORTANCE_LOW))
        }
    }
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("elmAI").setContentText("Скрипт...")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
    }
}
