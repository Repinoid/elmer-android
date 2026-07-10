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
 * Foreground-сервис Raw Relay.
 *
 * Поток: Bluetooth → ELM327 → HTTP-поллинг команд с сервера → ответ.
 * ТУПОЙ ретранслятор — никакой логики диагностики.
 * Только: получил команду → отправил в ELM → вернул сырой ответ на сервер.
 */
class RawRelayService : Service() {

    companion object {
        private const val TAG = "RawRelay"
        private const val CHANNEL_ID = "elmer_raw_relay"
        private const val NOTIFY_ID = 300

        /** UUID для SPP (Serial Port Profile) — стандартный для ELM327 */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Intent actions
        const val ACTION_RUN = "ru.elmer.raw.RUN"
        const val ACTION_STOP = "ru.elmer.raw.STOP"

        // Intent extras (входящие)
        const val EXTRA_DEVICE_MAC = "device_mac"

        // Broadcast: статус реле → UI
        const val BROADCAST_STATUS = "ru.elmer.raw.STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_RESPONSE = "response"
        const val EXTRA_COUNT = "count"
        const val EXTRA_ERRORS = "errors"
    }

    // ── Состояние ───────────────────────────────────────

    /** Bluetooth RFCOMM сокет */
    private var btSocket: BluetoothSocket? = null
    /** Однопоточный актор для ElmProtocol */
    private var actor: ElmActor? = null
    /** HTTP-клиент для поллинга команд */
    private var client: RelayClient? = null
    /** Рабочий поток relayLoop */
    private var worker: Thread? = null
    /** Флаг работы цикла */
    private var running = false

    // ── Жизненный цикл Service ──────────────────────────

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
            // MAC устройства — из Intent (обязателен)
            val mac = intent.getStringExtra(EXTRA_DEVICE_MAC)
                ?: return START_NOT_STICKY

            // Запуск foreground с уведомлением
            startForeground(NOTIFY_ID, buildNotification("Подключение..."))
            broadcast("connecting", "", "", 0, 0)

            // Рабочий поток
            worker = Thread { relayLoop(mac) }
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

    // ── Главный цикл ────────────────────────────────────

    /**
     * Главный цикл реле.
     *
     * 1. Подключить Bluetooth к ELM327
     * 2. Инициализировать ElmProtocol (AndrOBD: ATSP0→ATAT1→ATST→ATS0→ATL0→ATE0)
     * 3. Сообщить серверу «я готов» (hello)
     * 4. Бесконечный поллинг: забрать команду → отправить в ELM → вернуть ответ
     */
    private fun relayLoop(mac: String) {
        running = true

        // ── 1. Bluetooth ──
        broadcast("bt_connecting", "", "", 0, 0)
        if (!connectBt(mac)) {
            broadcast("bt_error", "", "", 0, 0)
            updateNotification("Ошибка Bluetooth")
            stopSelf()
            return
        }

        // ── 2. ElmProtocol init (AndrOBD) ──
        broadcast("elm_init", "", "", 0, 0)
        updateNotification("Инициализация ELM327...")
        try {
            actor!!.init()
        } catch (e: Exception) {
            Log.e(TAG, "ELM init failed: ${e.message}")
            broadcast("elm_error", "", "", 0, 0)
            updateNotification("Ошибка инициализации ELM")
            stopSelf()
            return
        }
        broadcast("elm_ready", "", "", 0, 0)

        // ── 3. Hello серверу ──
        if (!client!!.hello("ELM327", "A0", "12V")) {
            broadcast("server_error", "", "", 0, 0)
            updateNotification("Сервер недоступен")
            stopSelf()
            return
        }
        broadcast("ready", "", "", 0, 0)
        updateNotification("Готов — жду команд")

        // ── 4. Поллинг-цикл ──
        var cmdCount = 0
        var errCount = 0

        while (running) {
            // Забрать команду с сервера (GET /api/v1/elm/raw/cmd)
            val cmdJson = client!!.pollCommand()
            if (cmdJson == null) {
                Thread.sleep(500)  // Нет команд — ждём 500мс
                continue
            }

            val seq = cmdJson.optInt("seq", 0)
            val cmd = cmdJson.optString("cmd", "")
            val drainFirst = cmdJson.optBoolean("drain_first", false)

            if (cmd.isEmpty()) continue

            Log.i(TAG, "#$seq ← $cmd")
            broadcast("cmd_send", cmd, "", cmdCount, errCount)
            updateNotification("#$seq: $cmd")

            // Опциональный дренаж буфера (сервер просит)
            if (drainFirst) {
                actor!!.drain()
            }

            // Отправить команду в ELM (через actor, блокирующий вызов)
            val t0 = System.currentTimeMillis()
            var raw = ""
            var prompt = false
            var error: String? = null

            try {
                raw = actor!!.sendBlocking(cmd, 5000)  // 5с таймаут
                prompt = raw.isNotEmpty()
            } catch (e: Exception) {
                error = e.message ?: "unknown"
                Log.w(TAG, "sendCommand error: $error")
                errCount++
            }

            val elapsed = System.currentTimeMillis() - t0
            val byteCount = raw.length

            // Отправить ответ на сервер (POST /api/v1/elm/raw/response)
            val ok = client!!.postResponse(seq, cmd, raw, prompt, elapsed, byteCount, error)
            if (!ok) errCount++

            cmdCount++
            broadcast("cmd_done", cmd, raw.take(120), cmdCount, errCount)
            Log.i(TAG, "#$seq → ${raw.take(80)} (${elapsed}ms)")
        }
    }

    // ── Bluetooth ────────────────────────────────────────

    /**
     * Подключиться к ELM327 по Bluetooth.
     * Создаёт RFCOMM-сокет, ElmActor и RelayClient.
     *
     * @param mac MAC-адрес ELM327 (из bonded-устройств)
     * @return true если подключение успешно
     */
    private fun connectBt(mac: String): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return false
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)

            btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            btSocket!!.connect()

            // Создать actor и клиент ПОСЛЕ успешного коннекта
            actor = ElmActor(btSocket!!.inputStream, btSocket!!.outputStream)
            client = RelayClient(this)  // this = Service (Context)
            true
        } catch (e: IOException) {
            Log.e(TAG, "BT connect failed: ${e.message}")
            closeBt()
            false
        }
    }

    /** Закрыть BT-сокет и остановить actor. */
    private fun closeBt() {
        actor?.shutdown()
        actor = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
    }

    // ── Broadcast ────────────────────────────────────────

    /**
     * Отправить статус в UI (MainActivity).
     *
     * @param state    состояние: connecting, ready, cmd_send, cmd_done, error...
     * @param cmd      последняя команда
     * @param response последний ответ (первые 120 символов)
     * @param count    счётчик выполненных команд
     * @param errors   счётчик ошибок
     */
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

    // ── Нотификация ──────────────────────────────────────

    /** Создать канал уведомлений для Android 8+. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ELM Raw Relay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /** Построить уведомление foreground-сервиса. */
    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ELM Raw Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)  // Нельзя смахнуть
            .build()
    }

    /** Обновить текст уведомления (например, показать текущую команду). */
    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(text))
    }
}
