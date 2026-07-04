package ru.elmer.raw

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.UUID

/**
 * Foreground-сервис: Bluetooth → ELM327 → поллинг команд с сервера → ответ.
 *
 * ТУПОЙ ретранслятор. Никакой логики диагностики.
 * Только: получил команду → отправил в ELM → вернул сырой ответ.
 */
class RawRelayService : Service() {

    companion object {
        private const val TAG = "RawRelay"
        private const val CHANNEL_ID = "elmer_raw_relay"
        private const val NOTIFY_ID = 300
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val ACTION_RUN = "ru.elmer.raw.RUN"
        const val ACTION_STOP = "ru.elmer.raw.STOP"
        const val EXTRA_DEVICE_MAC = "device_mac"
        const val EXTRA_SERVER_URL = "server_url"

        const val BROADCAST_STATUS = "ru.elmer.raw.STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_RESPONSE = "response"
        const val EXTRA_COUNT = "count"
        const val EXTRA_ERRORS = "errors"
    }

    private var btSocket: BluetoothSocket? = null
    private var actor: ElmActor? = null
    private var client: RelayClient? = null
    private var worker: Thread? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_RUN) {
            val mac = intent.getStringExtra(EXTRA_DEVICE_MAC) ?: return START_NOT_STICKY
            val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                ?: BuildConfig.SERVER_URL

            startForeground(NOTIFY_ID, buildNotification("Подключение..."))
            broadcast("connecting", "", "", 0, 0)

            worker = Thread { relayLoop(mac, serverUrl) }
            worker!!.start()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        closeBt()
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    // ── Главный цикл ───────────────────────────────────

    private fun relayLoop(mac: String, serverUrl: String) {
        running = true

        // 1. Bluetooth
        broadcast("bt_connecting", "", "", 0, 0)
        if (!connectBt(mac)) {
            broadcast("bt_error", "", "", 0, 0)
            updateNotification("Ошибка Bluetooth")
            stopSelf()
            return
        }

        // 2. ElmProtocol init (через actor)
        broadcast("elm_init", "", "", 0, 0)
        updateNotification("Инициализация ELM327...")
        try {
            actor!!.init()
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
            broadcast("elm_error", "", "", 0, 0)
            updateNotification("Ошибка инициализации ELM")
            stopSelf()
            return
        }
        broadcast("elm_ready", "", "", 0, 0)

        // 3. Инфо о клоне уже есть из init() (detectClone)
        val clone = if (actor!!.isClone()) " (clone v1.5)" else ""
        Log.i(TAG, "ELM: clone=${actor!!.isClone()}")
        // hello с минимальной инфой — без ATDPN/ATRV чтобы не засорять буфер
        if (!client!!.hello(if (actor!!.isClone()) "ELM327v1.5" else "ELM327", "A0", "12V")) {
            broadcast("server_error", "", "", 0, 0)
            updateNotification("Сервер недоступен")
            stopSelf()
            return
        }
        broadcast("ready", "", "", 0, 0)
        updateNotification("Готов — жду команд")

        // 5. Поллинг-цикл
        var cmdCount = 0
        var errCount = 0
        var commandsSinceCheck = 0
        val FREEZE_CHECK_INTERVAL = 10  // проверка залипания каждые 10 команд

        while (running) {
            val cmdJson = client!!.pollCommand()
            if (cmdJson == null) {
                Thread.sleep(500)
                continue
            }

            val seq = cmdJson.optInt("seq", 0)
            val cmd = cmdJson.optString("cmd", "")
            val drainFirst = cmdJson.optBoolean("drain_first", false)

            if (cmd.isEmpty()) continue

            Log.i(TAG, "#$seq ← $cmd")
            broadcast("cmd_send", cmd, "", cmdCount, errCount)
            updateNotification("#$seq: $cmd")

            // Drain если нужно
            if (drainFirst) {
                actor!!.drain()
            }

            // Отправить команду (через actor)
            val t0 = System.currentTimeMillis()
            var raw = ""
            var prompt = false
            var error: String? = null

            try {
                raw = actor!!.sendBlocking(cmd, 5000)
                prompt = raw.isNotEmpty()
            } catch (e: Exception) {
                error = e.message ?: "unknown"
                Log.w(TAG, "sendCommand error: $error")
                errCount++
            }

            val elapsed = System.currentTimeMillis() - t0
            val byteCount = raw.length

            // Отправить ответ на сервер
            val ok = client!!.postResponse(seq, cmd, raw, prompt, elapsed, byteCount, error)
            if (!ok) errCount++

            cmdCount++
            commandsSinceCheck++
            broadcast("cmd_done", cmd, raw.take(120), cmdCount, errCount)
            Log.i(TAG, "#$seq → ${raw.take(80)} (${elapsed}ms)")

            // Детект залипания: каждые N команд проверяем ATRV
            if (commandsSinceCheck >= FREEZE_CHECK_INTERVAL && actor!!.isClone()) {
                commandsSinceCheck = 0
                val atrv = actor!!.sendBlocking("ATRV", 2000)
                if (!atrv.contains("V") && !atrv.matches(Regex("[0-9.]+"))) {
                    Log.w(TAG, "ELM FROZEN — ATRV=$atrv, trying ATWS...")
                    broadcast("elm_frozen", "ATRV=$atrv", "", cmdCount, errCount)
                    // Попытка восстановления
                    actor!!.sendBlocking("ATWS", 3000)
                    Thread.sleep(2000)
                    val atrv2 = actor!!.sendBlocking("ATRV", 2000)
                    if (atrv2.contains("V") || atrv2.matches(Regex("[0-9.]+"))) {
                        Log.i(TAG, "ELM recovered: ATRV=$atrv2")
                        broadcast("elm_recovered", "ATRV=$atrv2", "", cmdCount, errCount)
                    } else {
                        Log.e(TAG, "ELM still frozen — requires power cycle")
                        broadcast("elm_dead", "Требуется переподключение ELM", "", cmdCount, errCount)
                    }
                }
            }
        }
    }

    // ── Bluetooth ──────────────────────────────────────

    private fun connectBt(mac: String): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return false
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)
            btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            btSocket!!.connect()
            actor = ElmActor(btSocket!!.inputStream, btSocket!!.outputStream)
            client = RelayClient(BuildConfig.SERVER_URL)
            true
        } catch (e: IOException) {
            Log.e(TAG, "BT connect failed: ${e.message}")
            closeBt()
            false
        }
    }

    private fun closeBt() {
        actor?.shutdown()
        actor = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
    }

    // ── Хелперы ────────────────────────────────────────

    private fun broadcast(state: String, cmd: String, response: String,
                          count: Int, errors: Int) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_CMD, cmd)
            putExtra(EXTRA_RESPONSE, response)
            putExtra(EXTRA_COUNT, count)
            putExtra(EXTRA_ERRORS, errors)
        }
        sendBroadcast(intent)
    }

    // ── Нотификация ────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ELM Raw Relay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ELM Raw Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(text))
    }
}
