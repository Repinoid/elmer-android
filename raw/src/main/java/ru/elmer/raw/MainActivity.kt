package ru.elmer.raw

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Минимальный UI для Raw Relay.
 *
 * Показывает: статус, последнюю команду, последний ответ, счётчики.
 * Запускает/останавливает RawRelayService.
 *
 * Выбор устройства из списка сопряжённых (bonded) Bluetooth-устройств.
 */
class MainActivity : AppCompatActivity() {

    // ── UI ──────────────────────────────────────────────

    private lateinit var deviceSpinner: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLastCmd: TextView
    private lateinit var tvLastResp: TextView
    private lateinit var tvCounters: TextView

    // ── BT ──────────────────────────────────────────────

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDevice: BluetoothDevice? = null

    // ── Сервис ──────────────────────────────────────────

    private var serviceBound = false
    private val statusReceiver = StatusReceiver()

    companion object {
        private const val REQUEST_BT = 1
    }

    // ── Жизненный цикл ──────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Запрос BT-разрешений на Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (missing.isNotEmpty())
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT)
        }

        // Привязка UI
        deviceSpinner = findViewById(R.id.device_spinner)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        tvStatus = findViewById(R.id.tv_status)
        tvLastCmd = findViewById(R.id.tv_last_cmd)
        tvLastResp = findViewById(R.id.tv_last_resp)
        tvCounters = findViewById(R.id.tv_counters)

        // Версия в заголовке
        title = "ELM Relay v${BuildConfig.VERSION_NAME}"

        // Обновить список устройств
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { loadDevices() }

        // Старт/стоп сервиса
        btnStart.setOnClickListener { startRelay() }
        btnStop.setOnClickListener { stopRelay() }

        // Приёмник статуса от сервиса
        val filter = IntentFilter(RawRelayService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        loadDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    // ── BT-устройства ───────────────────────────────────

    /** Загрузить список сопряжённых устройств в Spinner. */
    private fun loadDevices() {
        val devices = btAdapter?.bondedDevices?.toList() ?: emptyList()
        val names = devices.map { "${it.name} (${it.address})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        if (devices.isNotEmpty()) selectedDevice = devices[0]
    }

    // ── Управление сервисом ─────────────────────────────

    /** Запустить relay-сервис. Сервер URL берётся из Config (BuildConfig). */
    private fun startRelay() {
        val device = selectedDevice ?: return
        val intent = Intent(this, RawRelayService::class.java).apply {
            action = RawRelayService.ACTION_RUN
            putExtra(RawRelayService.EXTRA_DEVICE_MAC, device.address)
            // SERVER_URL НЕ передаём — сервис сам читает из Config.SERVER_URL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    /** Остановить relay-сервис. */
    private fun stopRelay() {
        val intent = Intent(this, RawRelayService::class.java).apply {
            action = RawRelayService.ACTION_STOP
        }
        startService(intent)
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    // ── Приём статуса ───────────────────────────────────

    /**
     * BroadcastReceiver для статуса от RawRelayService.
     * Получает: state, cmd, response, count, errors.
     */
    inner class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RawRelayService.BROADCAST_STATUS) return

            val state = intent.getStringExtra(RawRelayService.EXTRA_STATE) ?: ""
            val cmd = intent.getStringExtra(RawRelayService.EXTRA_CMD) ?: ""
            val response = intent.getStringExtra(RawRelayService.EXTRA_RESPONSE) ?: ""
            val count = intent.getIntExtra(RawRelayService.EXTRA_COUNT, 0)
            val errors = intent.getIntExtra(RawRelayService.EXTRA_ERRORS, 0)

            // Статус с эмодзи
            val stateText = when (state) {
                "connecting" -> "🔌 Подключение BT..."
                "bt_connecting" -> "🔌 BT..."
                "bt_error" -> "🔴 Ошибка BT"
                "elm_init" -> "⚙️ Инициализация ELM..."
                "elm_ready" -> "🟢 ELM готов"
                "elm_error" -> "🔴 Ошибка ELM"
                "ready" -> "🟢 Готов — жду команд"
                "server_error" -> "🔴 Сервер недоступен"
                "cmd_send" -> "📤 → $cmd"
                "cmd_done" -> "📥 ← ${response.take(60)}"
                else -> state
            }
            tvStatus.text = stateText

            if (cmd.isNotEmpty()) tvLastCmd.text = "→ $cmd"
            if (response.isNotEmpty()) tvLastResp.text = "← $response"
            tvCounters.text = "Команд: $count | Ошибок: $errors"

            // Авто-стоп при ошибках
            if (state == "bt_error" || state == "elm_error" || state == "server_error") {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
        }
    }
}
