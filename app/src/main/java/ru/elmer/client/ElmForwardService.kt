package ru.elmer.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.util.*
import kotlin.concurrent.thread

/**
 * Универсальный Bluetooth SPP <-> HTTP реле.
 * НЕ знает протокол. Только транспорт.
 *
 * Android <-- Bluetooth/TCP --> Устройство (ELM327, Arduino, etc.)
 * Android <-- HTTP JSON ------> Сервер
 */
class ElmForwardService : Service() {

    private var btSocket: BluetoothSocket? = null
    private var tcpSocket: Socket? = null
    private var inp: java.io.InputStream? = null
    private var out: java.io.OutputStream? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var serverUrl = "https://obdai.ru/api/v1/raw-obd"
    private var sessionId: String? = null  // сохраняем ID сессии от сервера
    private var running = false

    companion object {
        const val TAG = "ElmRelay"
        const val CHANNEL_ID = "elm_relay"
        const val NOTIFY_ID = 100
        const val ACTION_CONNECT = "ru.elmer.client.CONNECT"
        const val ACTION_DISCONNECT = "ru.elmer.client.DISCONNECT"
        const val EXTRA_DEVICE_MAC = "device_mac"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEBUG_HOST = "debug_host"
        const val BROADCAST_STATUS = "ru.elmer.client.STATUS"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SERVER_URL)?.let { serverUrl = it }
        if (intent?.action == ACTION_DISCONNECT) { disconnect(); return START_NOT_STICKY }
        if (intent?.action != ACTION_CONNECT) return START_STICKY

        // Обязательно для Android 8+ — без этого сервис умрёт через 5 сек
        startForeground(NOTIFY_ID, buildNotification())

        sessionId = null  // новая сессия

        val debug = intent.getStringExtra(EXTRA_DEBUG_HOST)
        // Сетевые операции НЕЛЬЗЯ делать на main thread — обязателен background thread
        if (debug != null) {
            val p = debug.split(":")
            thread(name = "ElmConnect", isDaemon = true) {
                tcpConnect(p[0], p.getOrNull(1)?.toIntOrNull() ?: 35000)
            }
        } else {
            intent.getStringExtra(EXTRA_DEVICE_MAC)?.let { mac ->
                thread(name = "ElmConnect", isDaemon = true) { btConnect(mac) }
            }
        }
        return START_STICKY
    }

    private fun btConnect(mac: String) {
        say("BT: $mac...")
        say("🌐 Server: $serverUrl")
        try {
            val dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
            btSocket = dev.createRfcommSocketToServiceRecord(
                UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
            btSocket?.connect()
            inp = btSocket?.inputStream
            out = btSocket?.outputStream
            say("BT: OK")
            loop()
        } catch (e: Exception) { say("BT err: ${e.message}") }
    }

    private fun tcpConnect(host: String, port: Int) {
        say("TCP: $host:$port...")
        say("🌐 Server: $serverUrl")
        try {
            tcpSocket = Socket(host, port).also { it.soTimeout = 0 }
            inp = tcpSocket?.inputStream
            out = tcpSocket?.outputStream
            say("TCP: OK")
            loop()
        } catch (e: Exception) { say("TCP err: ${e.message}") }
    }

    private fun loop() {
        running = true
        thread(name = "ElmReader", isDaemon = true) {
            val buf = ByteArray(256)
            val sb = StringBuilder()
            while (running) {
                try {
                    val n = inp?.read(buf) ?: -1
                    if (n == -1) break
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '>') {
                            if (sb.isNotEmpty()) { fwd(sb.toString().trim()); sb.clear() }
                        } else if (c != '\r' && c != '\n') sb.append(c)
                    }
                } catch (e: IOException) {
                    if (running) say("Lost connection")
                    break
                }
            }
            disconnect()
        }
    }

    private fun fwd(raw: String) {
        val preview = if (raw.length > 50) raw.take(50) + "…" else raw
        say("← $preview")
        try {
            val json = JSONObject().apply {
                put("raw", raw)
                put("ts", System.currentTimeMillis())
                sessionId?.let { put("session", it) }
            }
            val req = Request.Builder().url(serverUrl)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            try {
                val respJson = JSONObject(body)
                respJson.optString("session", "").takeIf { it.isNotEmpty() }?.let {
                    sessionId = it
                }
                val cmd = respJson.optString("cmd", "")
                if (cmd.isNotEmpty()) {
                    say("→ $cmd")
                    write(cmd)
                }
                val msg = respJson.optString("msg", "")
                if (msg.isNotEmpty()) say("🖥 $msg")
            } catch (_: Exception) {
                say("⚠️ Bad JSON: ${body.take(60)}")
            }
        } catch (e: IOException) {
            say("⚠️ Server down: ${e.message?.take(40)}")
        }
    }

    private fun write(data: String) {
        try {
            out?.write((data + "\r").toByteArray())
            out?.flush()
        } catch (_: Exception) {}
    }

    private fun say(msg: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra("message", msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.i(TAG, msg)
    }

    private fun disconnect() {
        running = false
        try { btSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        say("Disconnected")
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Elmer Relay",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Elmer").setContentText("Диагностика...")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Elmer").setContentText("Диагностика...")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        }
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
}
