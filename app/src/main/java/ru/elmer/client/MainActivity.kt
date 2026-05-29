package ru.elmer.client

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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

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

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var elmDevice: BluetoothDevice? = null
    private var scriptRegistered = false

    companion object {
        const val REQUEST_BT_PERMISSIONS = 1
        const val REQUEST_ENABLE_BT = 2
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            runOnUiThread { tvStatus.append("\n$msg") }
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

        // Версия из build.gradle (versionName)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "v$version"

        registerReceiver(statusReceiver, IntentFilter(ElmForwardService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)


        // ТЕСТОВАЯ КНОПКА
        val btnTest = findViewById<Button>(R.id.btn_test)
        btnTest.setOnClickListener { startTest() }

        // СКРИПТ
        btnScript.setOnClickListener { startScript() }

        // Промпты от ScriptRunnerService
        registerScriptReceiver()
    }

    private fun startTest() {
        // Тест сервера — без ELM, просто проверка доступности
        thread(name = "ServerTest", isDaemon = true) {
            runOnUiThread { tvStatus.text = "⏳ Проверка сервера..." }
            try {
                val url = java.net.URL("https://obdai.ru/api/v1/script?mode=test")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                runOnUiThread {
                    tvStatus.text = if (code == 200) "✅ Сервер доступен (HTTP $code)"
                    else "⚠️ Сервер ответил: HTTP $code"
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "❌ Сервер недоступен: ${e.message}" }
            }
        }

        // Очищаем экран
        tvStatus.text = ""

        registerReceiver(testReceiver, IntentFilter(TestService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        tvStatus.text = "⏳ Тест..."
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            runOnUiThread {
                tvStatus.append("\n$msg")
            }
        }
    }

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
                "ecu" -> tvStatus.text = "🔌 Соединение с ЭБУ...$elapsed"
                "upload" -> tvStatus.text = "📤 Отправка на сервер...$elapsed"
                "llm" -> tvStatus.text = "🧠 LLM анализ...$elapsed"
                "done" -> tvStatus.text = "✅ Завершено$elapsed"
                else -> tvStatus.text = "📡 $detail$elapsed"
            }
        }
    }

    private val scriptStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            runOnUiThread { tvStatus.append("\n$msg") }
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
                    tvStatus.append("\n⏸ $prompt (нажми на жёлтую строку)")
                }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(testReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptStatusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptPromptReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptStageReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── История ──────────────────────────────────────────

    private fun showHistory() {
        val db = SessionDb(this)
        val sessions = db.getSessions()
        if (sessions.isEmpty()) { tvStatus.text = "📋 История пуста"; return }
        val items = sessions.mapIndexed { i, s ->
            val dt = s["created_at"]?.let {
                java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it.toLong() * 1000))
            } ?: "?"
            val diag = (s["diagnosis"] ?: "ожидает...").take(80)
            "${i+1}. [$dt] ${s["title"] ?: "Диагностика"}\n$diag\n"
        }.joinToString("\n")
        tvStatus.text = items
    }
}
