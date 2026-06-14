package ru.elmer.raw

import android.Manifest
import android.app.Activity
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
 * Тонкий UI: статус, лог, кнопка Стоп.
 *
 * Не делает ничего кроме отображения состояния RawRelayService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLastCmd: TextView
    private lateinit var tvLastResponse: TextView
    private lateinit var tvCount: TextView
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var receiver: BroadcastReceiver? = null
    private var cmdCount = 0
    private var errCount = 0
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "ELM327 Raw Relay"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "Выбери ELM327:"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        })

        // Загружаем сопряжённые устройства
        devices = loadBondedDevices()
        val names = devices.map { "${it.name ?: "?"}  (${it.address})" }
        spinnerDevices = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                if (names.isNotEmpty()) names else listOf("Нет сопряжённых устройств"))
        }
        root.addView(spinnerDevices)

        root.addView(Button(this).apply {
            text = "🔄 Обновить список"
            setOnClickListener {
                devices = loadBondedDevices()
                val n = devices.map { "${it.name ?: "?"}  (${it.address})" }
                (spinnerDevices.adapter as ArrayAdapter<String>).let { a ->
                    a.clear()
                    a.addAll(if (n.isNotEmpty()) n else listOf("Нет сопряжённых устройств"))
                }
                Toast.makeText(this@MainActivity,
                    "Найдено: ${devices.size}", Toast.LENGTH_SHORT).show()
            }
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = Button(this).apply { text = "Старт" }
        btnStop = Button(this).apply { text = "Стоп"; isEnabled = false }
        btnRow.addView(btnStart)
        btnRow.addView(btnStop)
        root.addView(btnRow)

        tvStatus = TextView(this).apply { text = "Статус: ожидание" }
        root.addView(tvStatus)

        tvLastCmd = TextView(this).apply { text = "→ —" }
        root.addView(tvLastCmd)

        tvLastResponse = TextView(this).apply { text = "← —" }
        root.addView(tvLastResponse)

        tvCount = TextView(this).apply { text = "Команд: 0 | Ошибок: 0" }
        root.addView(tvCount)

        setContentView(root)

        // Кнопки
        btnStart.setOnClickListener { startRelay() }
        btnStop.setOnClickListener { stopRelay() }
    }

    override fun onResume() {
        super.onResume()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getStringExtra(RawRelayService.EXTRA_STATE) ?: return
                val cmd = intent.getStringExtra(RawRelayService.EXTRA_CMD) ?: ""
                val response = intent.getStringExtra(RawRelayService.EXTRA_RESPONSE) ?: ""
                cmdCount = intent.getIntExtra(RawRelayService.EXTRA_COUNT, cmdCount)
                errCount = intent.getIntExtra(RawRelayService.EXTRA_ERRORS, errCount)

                tvStatus.text = "Статус: $state"
                if (cmd.isNotEmpty()) tvLastCmd.text = "→ $cmd"
                if (response.isNotEmpty()) tvLastResponse.text = "← $response"
                tvCount.text = "Команд: $cmdCount | Ошибок: $errCount"

                when (state) {
                    "ready" -> { btnStart.isEnabled = false; btnStop.isEnabled = true }
                    "bt_error", "server_error" -> {
                        btnStart.isEnabled = true; btnStop.isEnabled = false
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(RawRelayService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        receiver?.let { unregisterReceiver(it) }
    }

    private fun startRelay() {
        val idx = spinnerDevices.selectedItemPosition
        if (idx < 0 || idx >= devices.size) {
            Toast.makeText(this, "Нет сопряжённых устройств. Сопряги ELM327 в настройках Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }
        val mac = devices[idx].address
        val name = devices[idx].name ?: mac
        if (mac.isEmpty()) { Toast.makeText(this, "Ошибка MAC", Toast.LENGTH_SHORT).show(); return }

        if (!checkBtPermissions()) return

        Toast.makeText(this, "Подключение к $name...", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, RawRelayService::class.java).apply {
            action = RawRelayService.ACTION_RUN
            putExtra(RawRelayService.EXTRA_DEVICE_MAC, mac)
            putExtra(RawRelayService.EXTRA_SERVER_URL, BuildConfig.SERVER_URL)
        }
        startService(intent)
        btnStart.isEnabled = false
        tvStatus.text = "Статус: запуск..."
    }

    private fun stopRelay() {
        val intent = Intent(this, RawRelayService::class.java).apply {
            action = RawRelayService.ACTION_STOP
        }
        startService(intent)
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "Статус: остановлен"
    }

    private fun checkBtPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
                return false
            }
        }
        return true
    }

    private fun loadBondedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return emptyList()
        }
        return adapter.bondedDevices.toList()
    }
}
