package ru.elmer.client.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import ru.elmer.client.BuildConfig
import ru.elmer.client.R
import ru.elmer.client.db.SessionDb
import ru.elmer.client.script.ScriptRunnerService

/**
 * Тонкий клиент Elmer — одна кнопка.
 * Подключается к ELM327 по Bluetooth, шлёт сырые OBD-ответы на сервер.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnScript: Button
    private lateinit var btnDtc: Button
    private lateinit var tvDtcStatus: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnCheckElm: Button
    private lateinit var btnCheckEcu: Button
    private var elmChecker: ru.elmer.client.elm.ElmChecker? = null
    private lateinit var cbFullMode: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var etInput: EditText
    private lateinit var etCarInfo: EditText
    private lateinit var btnSend: Button
    private lateinit var btnClose: Button
    private lateinit var scrollOutput: ScrollView

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var elmDevice: BluetoothDevice? = null
    private var scriptRegistered = false
    private val chatHistory = mutableListOf<Pair<String, String>>()  // (role, text)

    companion object {
        const val REQUEST_BT_PERMISSIONS = 1
        const val REQUEST_ENABLE_BT = 2
        private const val PING_THROTTLE_MS = 7_000L       // 7с при ошибках
        private const val PING_OK_THROTTLE_MS = 60_000L   // 60с при успехе
    }

    private var lastPingTime = 0L
    private var lastPingLlmTime = 0L
    private var lastPingOk = false

    private fun addApiKey(conn: java.net.HttpURLConnection) {
        val key = BuildConfig.API_KEY
        if (key.isNotEmpty()) conn.setRequestProperty("X-Api-Key", key)
    }

    // ⚠️ statusReceiver удалён — scriptStatusReceiver уже слушает BROADCAST_STATUS (дубликат)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Восстановление chatHistory после поворота
        savedInstanceState?.getString("chat_json")?.let { json ->
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    chatHistory.add(obj.getString("role") to obj.getString("content"))
                }
            } catch (_: Exception) {}
        }

        btnScript = findViewById(R.id.btn_script)
        btnDtc = findViewById(R.id.btn_dtc)
        tvDtcStatus = findViewById(R.id.tv_dtc_status)
        cbFullMode = findViewById(R.id.cb_full_mode)
        btnHistory = findViewById(R.id.btn_history)
        btnCheckElm = findViewById(R.id.btn_check_elm)
        btnCheckEcu = findViewById(R.id.btn_check_ecu)

        btnDtc.setOnClickListener { scanDtc() }
        btnHistory.setOnClickListener { showHistory() }
        btnCheckElm.setOnClickListener { checkElm() }
        btnCheckEcu.setOnClickListener { checkEcu() }
        tvStatus = findViewById(R.id.tv_status)
        tvPrompt = findViewById(R.id.tv_prompt)
        etInput = findViewById(R.id.et_input)
        etCarInfo = findViewById(R.id.et_car_info)
        btnSend = findViewById(R.id.btn_send)
        btnClose = findViewById(R.id.btn_close)
        scrollOutput = findViewById(R.id.scroll_output)

        btnSend.setOnClickListener { sendToLlm() }
        btnClose.setOnClickListener {
            tvStatus.text = "Готов"
            btnClose.visibility = android.view.View.GONE
        }

        // Версия из build.gradle (versionName)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "v$version"

        // ТЕСТОВАЯ КНОПКА
        val btnTest = findViewById<Button>(R.id.btn_test)
        btnTest.setOnClickListener { startTest() }

        // СКРИПТ
        btnScript.setOnClickListener { startScript() }

        // Промпты от ScriptRunnerService
        registerScriptReceiver()

        // Восстановление вывода после поворота экрана
        savedInstanceState?.getString("status_text")?.let {
            tvStatus.text = it
        } ?: run {
            tvStatus.text = "⏳ Загрузка...\n\nНажмите 📡 Сервер для проверки связи.\nНажмите 🔍 ДИАГНОСТИКА для запуска."
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("status_text", tvStatus.text.toString())
    }

    private fun startTest() {
        thread(name = "ServerTest", isDaemon = true) {
            val client = ru.elmer.client.server.ServerClient(
                "https://obdai.ru",
                "https://obdai.ru/api/v1/script",
                ""
            )

            // Этап 1: сервер (с троттлингом)
            val now = System.currentTimeMillis()
            val throttle = if (lastPingOk) PING_OK_THROTTLE_MS else PING_THROTTLE_MS
            if (now - lastPingTime < throttle) {
                ui { appendStatus("⏱ Сервер: проверка не чаще ${throttle/1000}с") }
                return@thread
            }
            lastPingTime = now
            ui { appendStatus("📡 Сервер...") }
            val ping = client.ping()
            if (ping.ok) {
                lastPingOk = true
                ui { appendStatus("✅ Сервер: ${ping.ms}мс") }
            } else {
                lastPingOk = false
                ui { appendStatus("❌ Сервер: ${ping.error}") }
                return@thread
            }

            // Этап 2: LLM
            ui { appendStatus("🧠 LLM...") }
            val llm = client.pingLlm()
            if (llm.ok) {
                ui { appendStatus("✅ LLM: ${llm.ms}мс") }
            } else {
                ui { appendStatus("⚠️ LLM: ${llm.error}") }
            }
        }
    }

    private fun ui(block: () -> Unit) { runOnUiThread(block) }

    private fun startScript() {
        val mode = if (cbFullMode.isChecked) "full" else "test"
        val carInfo = etCarInfo.text.toString().trim()
        val intent = Intent(this, ScriptRunnerService::class.java).apply {
            action = ScriptRunnerService.ACTION_RUN
            putExtra(ScriptRunnerService.EXTRA_SCRIPT_URL, "https://obdai.ru/api/v1/script?mode=$mode")
            if (carInfo.isNotEmpty()) putExtra(ScriptRunnerService.EXTRA_CAR_INFO, carInfo)
        }
        ContextCompat.startForegroundService(this, intent)

        tvStatus.text = ""
        tvPrompt.visibility = android.view.View.GONE
        tvStatus.text = "⏳ Инициализация ELM327..."
        scriptStartTime = System.currentTimeMillis()
        registerScriptReceiver()
    }

    // ── Сканирование ошибок ────────────────────────────────

    private var dtcCodes = listOf<String>()
    private var dtcChecked = false

    private fun scanDtc() {
        val dev = findElmDevice() ?: return
        tvStatus.text = "⏳ Сканирование ошибок..."
        tvDtcStatus.visibility = android.view.View.GONE
        startTimer("DtcTimer", "⏳ Сканирование ошибок")
        thread(name = "DtcScan", isDaemon = true) {
            val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
            val r = checker.scanDtc()
            runOnUiThread {
                if (r == null) {
                    val log = checker.getLog()
                    tvStatus.text = "❌ ELM не отвечает\n\nЛог:\n$log"
                } else {
                    dtcCodes = r
                    dtcChecked = true
                    if (r.isEmpty()) {
                        tvStatus.text = "✅ Ошибок нет"
                    } else {
                        tvStatus.text = "⚠️ Обнаружены ошибки (${r.size}):\n${
                            r.joinToString(", ")
                        }"
                    }
                    btnScript.isEnabled = true
                    tvDtcStatus.text = "✅ Ошибки считаны — можно диагностировать"
                    tvDtcStatus.setTextColor(0xFF4CAF50.toInt())
                    tvDtcStatus.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun checkElm() {
        val dev = findElmDevice() ?: return
        tvStatus.text = "⏳ Опрос ELM..."
        startTimer("ElmTimer", "⏳ Опрос ELM")
        thread(name = "ElmCheck", isDaemon = true) {
            val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
            val r = checker.checkDevice()
            runOnUiThread {
                if (r == null) {
                    val log = checker.getLog()
                    tvStatus.text = "❌ ELM не отвечает\n\nЛог:\n$log"
                } else {
                    elmChecker = checker  // сохраняем для ЭБУ
                    val deviceLine = if (r.deviceId != "—") "🔹 Устройство: ${r.deviceId}\n" else ""
                    val good = r.hasAdaptive && r.version.contains("v2")
                    tvStatus.text = if (good) {
                        "✅ Чёткое устройство!\n\n${deviceLine}" +
                        "🔹 Версия: ${r.version}\n🔹 Протокол: ${r.protocol}\n" +
                        "🔹 Напряжение: ${r.voltage}\n🔹 Адаптивный тайминг: ✅\n"
                    } else {
                        "⚠️ Клон или слабый ELM327\n\n${deviceLine}" +
                        "🔹 Версия: ${r.version}\n🔹 Протокол: ${r.protocol}\n🔹 Адаптивный тайминг: ❌\n"
                    }
                }
            }
        }
    }

    private fun checkEcu() {
        val checker = elmChecker
        if (checker == null) {
            tvStatus.text = "⚠️ Сначала нажми \"🔌 ELM\""
            return
        }
        tvStatus.text = "⏳ Опрос ЭБУ..."
        startTimer("EcuTimer", "⏳ Опрос ЭБУ")
        thread(name = "EcuCheck", isDaemon = true) {
            val r = checker.checkEcu()
            runOnUiThread {
                val obd = if (r.supportsObd) "✅" else "❌"
                tvStatus.text = "🚗 ЭБУ: OBD $obd, PID: ${r.pidMask}" +
                    if (r.vin != null) "\n🔹 VIN: ${r.vin}" else "\n🔹 VIN: не определился"
            }
        }
    }

    private fun findElmDevice(): BluetoothDevice? {
        if (btAdapter == null) { tvStatus.text = "❌ BT не поддерживается"; return null }
        if (!btAdapter!!.isEnabled) { tvStatus.text = "❌ Включите Bluetooth"; return null }
        val paired = btAdapter!!.bondedDevices
        val names = paired.map { it.name }.joinToString(", ")
        val dev = paired.find { it.name.uppercase().let { n -> n.contains("OBD") || n.contains("ELM") || n.contains("CBT") || n.contains("V-LINK") } }
        if (dev == null) tvStatus.text = "❌ ELM не найден\nСопряжено: ${paired.size} шт.\nИмена: $names"
        return dev
    }

    private fun startTimer(name: String, label: String) {
        thread(name = name, isDaemon = true) {
            var sec = 0
            while (true) {
                Thread.sleep(1000)
                sec++
                runOnUiThread {
                    if (tvStatus.text?.startsWith("⏳") == true)
                        tvStatus.text = "$label... [${sec}с]"
                }
            }
        }
    }

    private fun registerScriptReceiver() {
        if (scriptRegistered) return
        scriptRegistered = true
        registerReceiver(scriptStatusReceiver,
            IntentFilter(ScriptRunnerService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registerReceiver(scriptStageReceiver,
            IntentFilter(ScriptRunnerService.BROADCAST_STAGE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private var scriptStartTime: Long = 0

    private val scriptStageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stage = intent?.getStringExtra("stage") ?: return
            val detail = intent.getStringExtra("detail") ?: ""
            val elapsed = if (scriptStartTime > 0)
                " [${(System.currentTimeMillis() - scriptStartTime) / 1000}с]"
            else ""
            when (stage) {
                "ecu" -> appendStatus("\n🔌 Соединение с ЭБУ...$elapsed")
                "upload" -> appendStatus("\n📤 $detail$elapsed")
                "llm" -> appendStatus("\n🧠 $detail$elapsed")
                "done" -> {
                    appendStatus("\n$detail$elapsed")
                    btnClose.visibility = android.view.View.VISIBLE
                }
                else -> appendStatus("\n📡 $detail$elapsed")
            }
        }
    }

    private val scriptStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            runOnUiThread { appendStatus("\n$msg") }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(scriptStatusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptStageReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Помощники ───────────────────────────────────────

    private fun appendStatus(text: String) {
        tvStatus.append(text)
        scrollOutput.post { scrollOutput.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ── Чат с LLM ────────────────────────────────────────

    private fun sendToLlm() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.text.clear()
        chatHistory.add("user" to text)
        appendStatus("\n👤 $text")
        thread(name = "LlmChat", isDaemon = true) {
            try {
                val json = org.json.JSONObject().apply {
                    put("question", text)
                    put("history", org.json.JSONArray().apply {
                        for ((role, msg) in chatHistory) {
                            put(org.json.JSONObject().apply {
                                put("role", role); put("content", msg)
                            })
                        }
                    })
                }
                val req = java.net.URL("https://obdai.ru/api/v1/chat").openConnection() as java.net.HttpURLConnection
                req.connectTimeout = 10000; req.readTimeout = 60000
                req.doOutput = true; req.setRequestProperty("Content-Type", "application/json")
                addApiKey(req)
                req.outputStream.write(json.toString().toByteArray())
                val body = if (req.responseCode == 200)
                    req.inputStream.bufferedReader().readText() else ""
                req.disconnect()
                val answer = try {
                    org.json.JSONObject(body).optString("answer", "(пусто)")
                } catch (_: Exception) { body.take(200) }
                runOnUiThread {
                    chatHistory.add("assistant" to answer)
                    appendStatus("\n🤖 $answer")
                }
            } catch (e: Exception) {
                runOnUiThread { appendStatus("\n❌ ${e.message}") }
            }
        }
    }

    private fun showHistory() {
        val db = SessionDb(this)
        val sessions = db.getSessions()
        if (sessions.isEmpty()) {
            tvStatus.text = "📋 История пуста"
            return
        }

        // Берём последние 20, формируем список
        val recent = sessions.take(20)
        val items = recent.map { s ->
            val dt = s["created_at"]?.let {
                java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it.toLong() * 1000))
            } ?: "?"
            val title = s["title"] ?: "Диагностика"
            val uploaded = s["uploaded"] == "1"
            val prefix = if (uploaded) "✅" else "⏳"
            "$prefix [$dt] $title"
        }.toTypedArray()

        val diagnoses = recent.map { s -> s["diagnosis"] ?: "(нет данных)" }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 История (${recent.size})")
            .setItems(items) { _, which ->
                // Показать полный диагноз
                tvStatus.text = "📋 ${items[which]}\n\n${diagnoses[which]}"
                btnClose.setOnClickListener {
                    btnClose.visibility = android.view.View.GONE
                    showHistory()
                }
                btnClose.visibility = android.view.View.VISIBLE
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
