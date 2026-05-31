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
import ru.elmer.client.R
import ru.elmer.client.db.SessionDb
import ru.elmer.client.script.ScriptRunnerService

/**
 * Тонкий клиент Elmer — одна кнопка.
 * Подключается к ELM327 по Bluetooth, шлёт сырые OBD-ответы на сервер.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnScript: Button
    private lateinit var btnHistory: Button
    private lateinit var cbFullMode: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var etInput: EditText
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
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            runOnUiThread { appendStatus("\n$msg") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScript = findViewById(R.id.btn_script)
        cbFullMode = findViewById(R.id.cb_full_mode)
        btnHistory = findViewById(R.id.btn_history)

        btnHistory.setOnClickListener { showHistory() }
        tvStatus = findViewById(R.id.tv_status)
        tvPrompt = findViewById(R.id.tv_prompt)
        etInput = findViewById(R.id.et_input)
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

        registerReceiver(statusReceiver, IntentFilter(ScriptRunnerService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)


        // ТЕСТОВАЯ КНОПКА
        val btnTest = findViewById<Button>(R.id.btn_test)
        btnTest.setOnClickListener { startTest() }

        // СКРИПТ
        btnScript.setOnClickListener { startScript() }

        // Промпты от ScriptRunnerService
        registerScriptReceiver()

        // Восстановление вывода после поворота экрана
        savedInstanceState?.getString("status_text")?.let { tvStatus.text = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("status_text", tvStatus.text.toString())
    }

    private fun startTest() {
        thread(name = "ServerTest", isDaemon = true) {
            // Этап 1: связь с сервером (GET /ping)
            ui { appendStatus("📡 Сервер...") }
            var ok = false
            try {
                val t0 = System.currentTimeMillis()
                val req = java.net.URL("https://obdai.ru/api/v1/ping").openConnection() as java.net.HttpURLConnection
                req.connectTimeout = 5000; req.readTimeout = 5000
                ok = req.responseCode == 200
                req.disconnect()
                val ms = System.currentTimeMillis() - t0
                ui { appendStatus(if (ok) "✅ Сервер: ${ms}мс" else "❌ Сервер: HTTP ${req.responseCode}") }
            } catch (e: Exception) {
                ui { appendStatus("❌ Сервер: ${e.message}") }
            }
            if (!ok) return@thread

            // Этап 2: LLM (GET /ping-llm)
            ui { appendStatus("🧠 LLM...") }
            try {
                val t0 = System.currentTimeMillis()
                val req = java.net.URL("https://obdai.ru/api/v1/ping-llm").openConnection() as java.net.HttpURLConnection
                req.connectTimeout = 5000; req.readTimeout = 15000
                val code = req.responseCode
                val ms = System.currentTimeMillis() - t0
                val body = if (code == 200) req.inputStream.bufferedReader().readText() else ""
                req.disconnect()

                if (code == 200) {
                    val r = org.json.JSONObject(body)
                    if (r.optBoolean("ok"))
                        ui { appendStatus("✅ LLM: ${r.optInt("ms", ms.toInt())}мс") }
                    else
                        ui { appendStatus("⚠️ LLM: ${r.optString("error", "?")}") }
                } else {
                    ui { appendStatus("⚠️ LLM: HTTP $code") }
                }
            } catch (e: Exception) {
                ui { appendStatus("⚠️ LLM: ${e.message}") }
            }
        }
    }

    private fun ui(block: () -> Unit) { runOnUiThread(block) }

    private fun startScript() {
        val mode = if (cbFullMode.isChecked) "full" else "test"
        val intent = Intent(this, ScriptRunnerService::class.java).apply {
            action = ScriptRunnerService.ACTION_RUN
            putExtra(ScriptRunnerService.EXTRA_SCRIPT_URL, "https://obdai.ru/api/v1/script?mode=$mode")
        }
        ContextCompat.startForegroundService(this, intent)

        tvStatus.text = ""
        tvPrompt.visibility = android.view.View.GONE
        tvStatus.text = "⏳ Инициализация ELM327..."
        scriptStartTime = System.currentTimeMillis()
        registerScriptReceiver()
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

    private val scriptPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prompt = intent?.getStringExtra("prompt") ?: return
            runOnUiThread {
                if (prompt.isEmpty()) {
                    tvPrompt.visibility = android.view.View.GONE
                    tvPrompt.text = ""
                } else {
                    tvPrompt.text = "👆 $prompt"
                    tvPrompt.visibility = android.view.View.VISIBLE
                    // Кнопка «Далее» — отправляем ACTION_RESUME
                    tvPrompt.setOnClickListener {
                        val resume = Intent(this@MainActivity, ScriptRunnerService::class.java).apply {
                            action = ScriptRunnerService.ACTION_RESUME
                        }
                        startService(resume)
                        tvPrompt.visibility = android.view.View.GONE
                    }
                    appendStatus("\n⏸ $prompt (нажми на жёлтую строку)")
                }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptStatusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptPromptReceiver) } catch (_: Exception) {}
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
                btnClose.visibility = android.view.View.VISIBLE
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
