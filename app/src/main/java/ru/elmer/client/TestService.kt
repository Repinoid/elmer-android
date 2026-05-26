package ru.elmer.client

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ТЕСТОВЫЙ сервис. Без HTTP, без сервера.
 * Подключается к ELM327/mock по TCP, гонит протокол сам.
 * Каждый шаг — в Broadcast на экран.
 */
class TestService : Service() {

    private var socket: Socket? = null
    private var inp: java.io.InputStream? = null
    private var out: java.io.OutputStream? = null

    companion object {
        const val TAG = "ElmerTest"
        const val ACTION_RUN = "ru.elmer.client.TEST_RUN"
        const val BROADCAST_STATUS = "ru.elmer.client.TEST_STATUS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RUN) return START_NOT_STICKY
        val host = intent.getStringExtra("host") ?: "10.47.183.102"
        val port = intent.getIntExtra("port", 35000)
        thread(name = "TestRunner", isDaemon = true) { runTest(host, port) }
        return START_NOT_STICKY
    }

    private fun runTest(host: String, port: Int) {
        say("══════════════════")
        say("🔧 ELMER TEST v0.1")
        say("══════════════════")

        // ── TCP ──
        say("⏳ TCP: $host:$port")
        try {
            socket = Socket(host, port).also { it.soTimeout = 3000 }
            inp = socket!!.inputStream
            out = socket!!.outputStream
        } catch (e: Exception) {
            say("❌ TCP FAIL: ${e.message}")
            stopSelf(); return
        }

        // ── Читаем приветствие ──
        val greeting = readResponse()
        say("← GREETING: $greeting")

        // ── INIT ──
        val initCmds = listOf("ATZ", "ATE0", "ATL0", "ATSP0", "ATH1")
        for (cmd in initCmds) {
            if (!sendAndRead(cmd)) { disconnect(); stopSelf(); return }
        }

        // ── VIN ──
        say("─── VIN ───")
        if (!sendAndRead("0902")) { disconnect(); stopSelf(); return }

        // ── DTC ──
        say("─── DTC STORED ───")
        if (!sendAndRead("03")) { disconnect(); stopSelf(); return }

        say("─── DTC PENDING ───")
        if (!sendAndRead("07")) { disconnect(); stopSelf(); return }

        // ── PIDs ──
        val pids = listOf("0105", "010C", "010D", "0111", "010B", "010F", "011F", "0104", "0106", "0107")
        say("─── PIDs (${pids.size}) ───")
        for (pid in pids) {
            sendAndRead(pid)
            Thread.sleep(100)  // даём ELM передохнуть
        }

        say("══════════════════")
        say("✅ TEST COMPLETE")
        say("══════════════════")
        disconnect()
        stopSelf()
    }

    private fun sendAndRead(cmd: String): Boolean {
        try {
            out?.write((cmd + "\r\n").toByteArray())
            out?.flush()
        } catch (e: Exception) {
            say("❌ WRITE FAIL: ${e.message}")
            return false
        }
        Thread.sleep(200)
        val resp = readResponse()
        say("→ $cmd")
        say("← $resp")
        return true
    }

    private fun readResponse(): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        try {
            // Читаем всё что есть, пока не увидим '>' или таймаут
            val start = System.currentTimeMillis()
            var seenPrompt = false
            while (System.currentTimeMillis() - start < 2000) {
                val avail = inp?.available() ?: 0
                if (avail > 0) {
                    val n = inp?.read(buf) ?: -1
                    if (n == -1) break
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '>') { seenPrompt = true; break }
                        if (c != '\r' && c != '\n') sb.append(c)
                    }
                    if (seenPrompt) break
                } else if (sb.isNotEmpty()) {
                    // Данные пришли и прекратились — выходим
                    Thread.sleep(100)
                    if ((inp?.available() ?: 0) == 0) break
                } else {
                    Thread.sleep(50)
                }
            }
        } catch (e: IOException) {
            // таймаут или разрыв
        }
        return sb.toString().trim().take(100)
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
        try { socket?.close() } catch (_: Exception) {}
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
}
