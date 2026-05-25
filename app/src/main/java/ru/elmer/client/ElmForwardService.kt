package ru.elmer.client

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
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
    private var running = false

    companion object {
        const val TAG = "ElmRelay"
        const val ACTION_CONNECT = "ru.elmer.client.CONNECT"
        const val ACTION_DISCONNECT = "ru.elmer.client.DISCONNECT"
        const val EXTRA_DEVICE_MAC = "device_mac"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEBUG_HOST = "debug_host"
        const val BROADCAST_STATUS = "ru.elmer.client.STATUS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SERVER_URL)?.let { serverUrl = it }
        if (intent?.action == ACTION_DISCONNECT) { disconnect(); return START_NOT_STICKY }
        if (intent?.action != ACTION_CONNECT) return START_STICKY

        val debug = intent.getStringExtra(EXTRA_DEBUG_HOST)
        if (debug != null) {
            val p = debug.split(":")
            tcpConnect(p[0], p.getOrNull(1)?.toIntOrNull() ?: 35000)
        } else {
            intent.getStringExtra(EXTRA_DEVICE_MAC)?.let { btConnect(it) }
        }
        return START_STICKY
    }

    private fun btConnect(mac: String) {
        say("BT: $mac...")
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
                        } else if (c != '\r') sb.append(c)
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
        try {
            val json = JSONObject().apply {
                put("raw", raw)
                put("ts", System.currentTimeMillis())
            }
            val req = Request.Builder().url(serverUrl)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            try {
                val cmd = JSONObject(body).optString("cmd", "")
                if (cmd.isNotEmpty()) write(cmd)
            } catch (_: Exception) {}
        } catch (_: IOException) {}
    }

    private fun write(data: String) {
        try {
            out?.write((data + "\r").toByteArray())
            out?.flush()
        } catch (_: Exception) {}
    }

    private fun say(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).putExtra("message", msg))
        Log.i(TAG, msg)
    }

    private fun disconnect() {
        running = false
        try { btSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        say("Disconnected")
        stopSelf()
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
}
