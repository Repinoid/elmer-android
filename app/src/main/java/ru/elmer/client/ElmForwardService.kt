package ru.elmer.client

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.fr3ts0n.ecu.prot.obd.ElmProt
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * Фоновый сервис: Bluetooth SPP → ELM327 → HTTP-forward.
 * Забираем сырые OBD-ответы из ElmProt и шлём на сервер.
 */
class ElmForwardService : Service() {

    private var btSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val elm = ElmProt()
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var serverUrl = "https://obdai.ru/api/v1/raw-obd"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var readThread: Thread? = null

    companion object {
        const val TAG = "ElmForward"
        const val ACTION_CONNECT = "ru.elmer.client.CONNECT"
        const val ACTION_DISCONNECT = "ru.elmer.client.DISCONNECT"
        const val EXTRA_DEVICE_MAC = "device_mac"
        const val EXTRA_SERVER_URL = "server_url"
        const val BROADCAST_STATUS = "ru.elmer.client.STATUS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            it.getStringExtra(EXTRA_SERVER_URL)?.let { url -> serverUrl = url }
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_DEVICE_MAC) ?: return START_NOT_STICKY
                connect(mac)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    // ── Bluetooth connection ──────────────────────────

    private fun connect(mac: String) {
        broadcast("Подключение к $mac...")

        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)

            // UUID для SPP (Serial Port Profile)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            btSocket = device.createRfcommSocketToServiceRecord(uuid)
            adapter.cancelDiscovery()
            btSocket?.connect()

            inputStream = btSocket?.inputStream
            outputStream = btSocket?.outputStream

            // Настраиваем ELM протокол
            elm.addTelegramWriter { buffer ->
                outputStream?.write(String(buffer).toByteArray())
            }

            broadcast("Подключено к ELM327")
            startReading()
            initElm()

        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth error", e)
            broadcast("Ошибка: ${e.message}")
        }
    }

    private fun initElm() {
        // Отправляем AT-команды инициализации
        sendRaw("ATZ\r")     // сброс
        Thread.sleep(1000)
        sendRaw("ATE0\r")    // эхо off
        sendRaw("ATL0\r")    // linefeed off
        sendRaw("ATSP0\r")   // авто-протокол
        sendRaw("ATH1\r")    // заголовки on
    }

    // ── Reading loop ──────────────────────────────────

    private fun startReading() {
        running = true
        readThread = Thread {
            val buffer = ByteArray(256)
            val sb = StringBuilder()

            while (running) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) break

                    for (i in 0 until bytesRead) {
                        val c = buffer[i].toInt().toChar()
                        if (c == '>' || c == '\r') {
                            if (sb.isNotEmpty()) {
                                val raw = sb.toString().trim()
                                if (raw.isNotEmpty()) {
                                    elm.handleTelegram(raw.toCharArray())
                                    forwardToServer(raw)
                                }
                                sb.clear()
                            }
                        } else if (c != '\n') {
                            sb.append(c)
                        }
                    }
                } catch (e: IOException) {
                    if (running) {
                        Log.e(TAG, "Read error", e)
                        broadcast("Соединение потеряно")
                    }
                    break
                }
            }
            disconnect()
        }.apply {
            name = "ELM-Reader"
            isDaemon = true
            start()
        }

        broadcast("Чтение данных...")
    }

    // ── HTTP forward ──────────────────────────────────

    private fun forwardToServer(rawLine: String) {
        try {
            val json = JSONObject().apply {
                put("raw", rawLine)
                put("timestamp", System.currentTimeMillis())
            }

            val body = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(serverUrl)
                .post(body)
                .build()

            okHttp.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Молча, сервер недоступен — не критично
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Forward error", e)
        }
    }

    // ── Utils ─────────────────────────────────────────

    fun sendRaw(cmd: String) {
        try {
            outputStream?.write(cmd.toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    private fun broadcast(msg: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra("message", msg)
        }
        sendBroadcast(intent)
    }

    private fun disconnect() {
        running = false
        try {
            btSocket?.close()
        } catch (_: Exception) {}
        broadcast("Отключено")
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
