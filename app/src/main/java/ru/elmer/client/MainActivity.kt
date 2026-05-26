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
    private lateinit var tvStatus: TextView
    private lateinit var etUrl: EditText

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var elmDevice: BluetoothDevice? = null

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
        tvStatus = findViewById(R.id.tv_status)
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

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
