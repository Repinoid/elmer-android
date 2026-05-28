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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Тонкий клиент Elmer — одна кнопка.
 * Подключается к ELM327 по Bluetooth, шлёт сырые OBD-ответы на сервер.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect: Button
    private lateinit var btnScript: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var etUrl: EditText

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

        btnConnect = findViewById(R.id.btn_connect)
        btnScript = findViewById(R.id.btn_script)
        tvStatus = findViewById(R.id.tv_status)
        tvPrompt = findViewById(R.id.tv_prompt)
        etUrl = findViewById(R.id.et_server_url)

        // Версия из build.gradle (versionName)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "v$version"

        // Дефолтный URL
        etUrl.setText("10.47.183.102:35000")

        registerReceiver(statusReceiver, IntentFilter(ElmForwardService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        btnConnect.setOnClickListener { startDiagnostics() }

        // ТЕСТОВАЯ КНОПКА
        val btnTest = findViewById<Button>(R.id.btn_test)
        btnTest.setOnClickListener { startTest() }

        // СКРИПТ
        btnScript.setOnClickListener { startScript() }

        // Промпты от ScriptRunnerService
        registerScriptReceiver()
    }

    private fun startTest() {
        val url = etUrl.text.toString().trim()

        val intent = Intent(this, TestService::class.java).apply {
            action = TestService.ACTION_RUN
            if (url.contains(":")) {
                // TCP-режим (mock): IP:port
                val parts = url.split(":")
                putExtra("host", parts[0])
                putExtra("port", parts.getOrNull(1)?.toIntOrNull() ?: 35000)
            }
            // если поле пустое → BT-режим (host = null)
        }
        startService(intent)

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

    private fun startDiagnostics() {
        val url = etUrl.text.toString().trim()
        val debugHost = url.ifBlank { null }

        // Если введён IP:port — TCP-режим (тест без Bluetooth)
        // Сервер на том же IP, порт 5005
        if (debugHost != null && debugHost.contains(":") && !debugHost.startsWith("http")) {
            val deviceHost = debugHost.split(":")[0]
            val localServerUrl = "http://$deviceHost:5005/api/v1/raw-obd"
            val intent = Intent(this, ElmForwardService::class.java).apply {
                action = ElmForwardService.ACTION_CONNECT
                putExtra(ElmForwardService.EXTRA_SERVER_URL, localServerUrl)
                putExtra(ElmForwardService.EXTRA_DEBUG_HOST, debugHost)
            }
            ContextCompat.startForegroundService(this, intent)
            tvStatus.text = "⏳ TCP подключение к $debugHost..."
            return
        }

        // Bluetooth-режим
        if (btAdapter == null) {
            tvStatus.text = "❌ Bluetooth не поддерживается"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    REQUEST_BT_PERMISSIONS)
                return
            }
        }

        if (!btAdapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
            return
        }

        val paired = btAdapter.bondedDevices
        elmDevice = paired.find { d ->
            d.name.uppercase().let { it.contains("OBD") || it.contains("ELM") }
        }

        if (elmDevice == null) {
            tvStatus.text = "❌ ELM327 не найден. Сопряги в настройках Bluetooth."
        } else {
            val intent = Intent(this, ElmForwardService::class.java).apply {
                action = ElmForwardService.ACTION_CONNECT
                putExtra(ElmForwardService.EXTRA_DEVICE_MAC, elmDevice!!.address)
                // Сервер на ноутбуке (IP смотри в ipconfig WiFi)
                putExtra(ElmForwardService.EXTRA_SERVER_URL,
                    "http://10.47.183.102:5005/api/v1/raw-obd")
            }
            ContextCompat.startForegroundService(this, intent)
            tvStatus.text = "⏳ Подключение..."
        }
    }

    private fun startScript() {
        val url = etUrl.text.toString().trim()

        val intent = Intent(this, ScriptRunnerService::class.java).apply {
            action = ScriptRunnerService.ACTION_RUN
            if (url.contains(":") && !url.startsWith("http")) {
                // TCP-режим (mock): IP:port
                val parts = url.split(":")
                putExtra(ScriptRunnerService.EXTRA_DEBUG_HOST, "${parts[0]}:${parts.getOrNull(1) ?: "35000"}")
                putExtra(ScriptRunnerService.EXTRA_SERVER_URL, "http://${parts[0]}:5005")
                putExtra(ScriptRunnerService.EXTRA_SCRIPT_URL, "http://${parts[0]}:5005/api/v1/script")
            }
            // BT-режим — host = null
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
        super.onDestroy()
    }
}
