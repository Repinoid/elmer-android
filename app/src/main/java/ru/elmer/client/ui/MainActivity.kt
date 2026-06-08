package ru.elmer.client.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import ru.elmer.client.R
import ru.elmer.client.db.SessionDb
import ru.elmer.client.script.ScriptRunnerService

class MainActivity : AppCompatActivity() {

    private lateinit var btnAction: Button
    private lateinit var btnHistory: Button
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var etUserInput: EditText
    private lateinit var scrollOutput: ScrollView

    private lateinit var indServer: TextView
    private lateinit var indElm: TextView
    private lateinit var indEcu: TextView
    private lateinit var indLlm: TextView

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var elmDevice: BluetoothDevice? = null
    private var elmChecker: ru.elmer.client.elm.ElmChecker? = null
    private var scriptRegistered = false
    private val chatHistory = mutableListOf<Pair<String, String>>()

    private var dynamicCollector: ru.elmer.client.script.DynamicCollector? = null
    private var dynamicSamples: MutableList<List<ru.elmer.client.script.DynamicCollector.SampleResponse>>? = null

    enum class State { INIT, DTC, DIAG, START, STOP }
    private var state = State.INIT

    companion object {
        const val REQUEST_BT_PERMISSIONS = 1
    }

    // ── Жизненный цикл ──────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (missing.isNotEmpty())
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT_PERMISSIONS)
        }

        btnAction = findViewById(R.id.btn_action)
        btnHistory = findViewById(R.id.btn_history)
        btnSend = findViewById(R.id.btn_send)
        tvStatus = findViewById(R.id.tv_status)
        etUserInput = findViewById(R.id.et_user_input)
        scrollOutput = findViewById(R.id.scroll_output)

        indServer = findViewById(R.id.ind_server)
        indElm = findViewById(R.id.ind_elm)
        indEcu = findViewById(R.id.ind_ecu)
        indLlm = findViewById(R.id.ind_llm)

        btnAction.setOnClickListener { onAction() }
        btnSend.setOnClickListener { onSend() }
        btnHistory.setOnClickListener { showHistory() }

        indServer.setOnClickListener { checkServer() }
        indElm.setOnClickListener { checkElm() }
        indEcu.setOnClickListener { checkEcu() }
        indLlm.setOnClickListener { checkLlm() }

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.tv_version).text = "v$version"

        registerScriptReceiver()
        startChecks()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("status_text", tvStatus.text.toString())
    }

    // ── Светофоры ────────────────────────────────────────

    private fun setIndicator(tv: TextView, label: String, color: String) {
        val c = when (color) { "🟢" -> 0xFF4CAF50.toInt(); "🟡" -> 0xFFFFC107.toInt(); else -> 0xFFF44336.toInt() }
        runOnUiThread {
            tv.text = "$label $color"
            tv.setTextColor(c)
            updateUiState()
        }
    }

    private fun updateUiState() {
        val elmOk = indElm.text.contains("🟢")
        val llmOk = indLlm.text.contains("🟢")
        btnAction.isEnabled = elmOk
        btnAction.alpha = if (elmOk) 1.0f else 0.4f
        etUserInput.isEnabled = llmOk
        etUserInput.alpha = if (llmOk) 1.0f else 0.4f
        btnSend.isEnabled = llmOk
        btnSend.alpha = if (llmOk) 1.0f else 0.4f
        if (!elmOk && state == State.INIT) {
            btnAction.text = "⚠️ Нет ELM"
        } else if (state == State.INIT) {
            btnAction.text = "⚠️ ОШИБКИ"
        }
    }

    private var debugMode = true  // временно — логировать ответы в вывод
    private var runningDiag = false  // защита от повторного запуска

    private fun debugLog(msg: String) {
        if (debugMode) runOnUiThread { appendStatus("\n🔹 $msg") }
    }

    private fun startChecks() {
        thread(name = "StartChecks", isDaemon = true) {
            checkServer()  // сама вызовет checkLlm если 🟢
            checkElm()     // сама вызовет checkEcu если 🟢
        }
    }

    private fun checkServer() {
        setIndicator(indServer, "📡", "🟡")
        debugLog("Пинг сервера...")
        try {
            val url = java.net.URL("https://obdai.ru/api/v1/ping")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            val code = conn.responseCode
            conn.disconnect()
            debugLog("Сервер: $code")
            setIndicator(indServer, "📡", if (code == 200) "🟢" else "🔴")
            if (code == 200) {
                checkLlm()
                syncPendingSessions()
            }
        } catch (e: Exception) {
            debugLog("Сервер: ${e.message}")
            setIndicator(indServer, "📡", "🔴")
        }
    }

    private fun checkElm() {
        setIndicator(indElm, "ELM", "🟡")
        debugLog("Поиск ELM...")
        val dev = findElmDevice()
        if (dev == null) { debugLog("ELM не найден"); setIndicator(indElm, "ELM", "🔴"); return }
        elmDevice = dev
        debugLog("Найден: ${dev.name} (${dev.address})")
        try {
            val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
            val r = checker.checkDevice()
            if (r != null) {
                elmChecker = checker
                debugLog("ELM: версия=${r.version}, протокол=${r.protocol}, напряжение=${r.voltage}")
                setIndicator(indElm, "ELM", "🟢")
                checkEcu()
            } else {
                debugLog("ELM: нет ответа")
                setIndicator(indElm, "ELM", "🔴")
            }
        } catch (e: Exception) {
            debugLog("ELM: ${e.message}")
            setIndicator(indElm, "ELM", "🔴")
        }
    }

    private fun checkEcu() {
        val checker = elmChecker ?: return
        setIndicator(indEcu, "ECU", "🟡")
        debugLog("Запрос ЭБУ (03)...")
        try {
            val raw = checker.getElm()?.sendCommand("03") ?: ""
            debugLog("ЭБУ ответ: ${raw.take(40)}")
            val ok = raw.startsWith("43")
            setIndicator(indEcu, "ECU", if (ok) "🟢" else "🔴")
        } catch (e: Exception) {
            debugLog("ЭБУ: ${e.message}")
            setIndicator(indEcu, "ECU", "🔴")
        }
    }

    private fun checkLlm() {
        setIndicator(indLlm, "LLM", "🟡")
        debugLog("Пинг LLM...")
        try {
            val url = java.net.URL("https://obdai.ru/api/v1/ping-llm")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            debugLog("LLM: $code")
            setIndicator(indLlm, "LLM", if (code == 200) "🟢" else "🔴")
        } catch (e: Exception) {
            debugLog("LLM: ${e.message}")
            setIndicator(indLlm, "LLM", "🔴")
        }
    }

    // ── Кнопка-трансформер ──────────────────────────────

    private fun onAction() {
        when (state) {
            State.INIT -> scanDtc()
            State.DTC  -> runDiagnostics()
            State.DIAG -> startDynamicRecording()
            State.START -> stopDynamicRecording()
            State.STOP  -> { /* уже остановлен */ }
        }
    }

    private fun setActionState(newState: State) {
        state = newState
        when (newState) {
            State.INIT -> { btnAction.text = "⚠️ ОШИБКИ"; btnAction.setBackgroundColor(0xFFFF5722.toInt()) }
            State.DTC  -> { btnAction.text = "🔍 ДИАГНОСТИКА"; btnAction.setBackgroundColor(0xFF2196F3.toInt()) }
            State.DIAG -> { btnAction.text = "▶ СТАРТ"; btnAction.setBackgroundColor(0xFF4CAF50.toInt()) }
            State.START -> { btnAction.text = "⏹ СТОП"; btnAction.setBackgroundColor(0xFFd32f2f.toInt()) }
            State.STOP  -> { btnAction.text = "▶ СТАРТ"; btnAction.setBackgroundColor(0xFF4CAF50.toInt()) }
        }
    }

    private fun scanDtc() {
        val dev = elmDevice ?: run { appendStatus("\n❌ ELM не найден"); return }
        tvStatus.text = ""  // очищаем вывод (логи проверок)
        appendStatus("\n─── Сканирование ошибок ───")
        var timer: java.util.concurrent.atomic.AtomicBoolean? = null
        thread(name = "DtcScan", isDaemon = true) {
            val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
            val r = checker.scanDtc()
            timer?.set(false)
            runOnUiThread {
                if (r == null) {
                    appendStatus("\n❌ ELM не отвечает")
                } else if (r.isEmpty()) {
                    appendStatus("\n✅ Ошибок нет")
                    setIndicator(indEcu, "ECU", "🟢")
                    elmChecker = checker
                } else {
                    appendStatus("\n⚠️ Ошибки: ${r.joinToString(", ")}")
                    setIndicator(indEcu, "ECU", "🟢")
                    elmChecker = checker
                }
                setActionState(State.DTC)
            }
        }
    }

    private fun runDiagnostics() {
        if (runningDiag) return
        runningDiag = true
        val serverOk = indServer.text.contains("🟢")
        if (!serverOk) {
            appendStatus("\n⚠️ Сервер недоступен")
            appendStatus("\nСделайте тест на месте: газ до 3000 об/мин, 3-4 сек, сброс.")
            setActionState(State.DIAG)
            runningDiag = false
            return
        }
        val checker = elmChecker
        if (checker == null) { appendStatus("\n❌ Нет связи с ELM"); runningDiag = false; return }
        val elmProto = checker.getElm()
        if (elmProto == null) { appendStatus("\n❌ ELM не инициализирован"); runningDiag = false; return }

        val carInfo = etUserInput.text.toString().trim()
        val timer = startTimer()

        thread(name = "Diagnostics", isDaemon = true) {
            try {
                val client = ru.elmer.client.server.ServerClient("https://obdai.ru", "https://obdai.ru/api/v1/script", "")
                val scriptJson = client.downloadScript()
                val script = org.json.JSONObject(scriptJson)
                val stepsArr = script.getJSONArray("steps")
                val total = stepsArr.length()
                ui { appendStatus("\n📋 $total шагов") }

                val results = mutableListOf<Map<String, String?>>()
                for (i in 0 until total) {
                    val s = stepsArr.getJSONObject(i)
                    val cmd = s.optString("cmd", ""); if (cmd.isEmpty()) continue
                    val desc = s.optString("desc", "")
                    ui { updateLastLine("📡 $desc ($cmd)") }

                    val raw = try { elmProto.sendCommand(cmd) } catch (e: Exception) { "(err)" }
                    val decoded = ru.elmer.client.elm.ObdDecoder.decode(cmd, raw)
                    results.add(mapOf("step_id" to s.optString("id", ""), "cmd" to cmd, "raw" to raw, "decoded" to decoded))
                }

                val count = results.size
                val db = SessionDb(this@MainActivity)
                val sid = db.createSession(scriptJson, "Диагностика", "https://obdai.ru")
                results.forEach { r -> db.addResponse(sid, r["step_id"] ?: "", r["cmd"] ?: "", r["raw"] ?: "", r["decoded"] ?: "") }

                // Динамические сэмплы, если есть
                val dynForUpload = dynamicSamples?.map { batch -> batch.map { r -> mapOf("step_id" to r.stepId, "cmd" to r.cmd, "raw" to r.raw, "decoded" to r.decoded) } }
                dynamicSamples = null

                ui { updateLastLine("📤 Отправка $count ответов...") }
                val clientInfo = mapOf("phone_model" to Build.MODEL, "app_version" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"))
                val resp = client.uploadSession(sid, results, clientInfo, carInfo, dynForUpload)
                timer.set(false)

                if (resp != null) {
                    db.markUploaded(sid)
                    val d = resp.optString("diagnosis", "")
                    if (d.isNotEmpty()) {
                        db.saveDiagnosis(sid, d)
                        ui { appendStatus("\n\n🩺 ДИАГНОЗ\n$d") }
                    }
                } else {
                    ui { appendStatus("\n⚠️ Сервер недоступен. Данные сохранены.") }
                }
            } catch (e: Exception) {
                timer.set(false)
                ui { appendStatus("\n❌ ${e.message}") }
            }
            ui { setActionState(State.DIAG) }
            runningDiag = false
        }
    }

    private fun startDynamicRecording() {
        if (runningDiag) return
        runningDiag = true
        val dev = elmDevice ?: run { appendStatus("\n❌ ELM не найден"); return }
        appendStatus("\n\n⏱ ТЕСТ")
        appendStatus("\n⚠️ Газ до ~3000 об/мин, 3-4 сек, резко сбросьте.")
        appendStatus("\n⚠️ Нажмите СТОП после завершения.")
        setActionState(State.START)

        thread(name = "DynamicTest", isDaemon = true) {
            var timer: java.util.concurrent.atomic.AtomicBoolean? = null
            try {
                val checker = elmChecker
                if (checker == null) {
                    ui { appendStatus("\n❌ Сначала считайте ошибки") }
                    runningDiag = false
                    return@thread
                }
                if (!checker.ensureConnected()) {
                    Thread.sleep(500)
                    if (!checker.ensureConnected() || checker.getElm() == null) {
                        ui { appendStatus("\n❌ Нет связи с ELM") }; ui { setActionState(State.INIT) }; runningDiag = false; return@thread
                    }
                }
                val elmProto = checker.getElm()!!
                val steps = listOf(
                    "0104" to "pid_04", "0105" to "pid_05", "0106" to "pid_06",
                    "0107" to "pid_07", "010B" to "pid_0B", "010C" to "pid_0C",
                    "010D" to "pid_0D", "010E" to "pid_0E", "010F" to "pid_0F",
                    "0110" to "pid_10", "0111" to "pid_11", "011F" to "pid_1F"
                ).map { ru.elmer.client.script.DynamicCollector.ElmStep(it.second, it.first, it.second) }
                val interval = 250L

                timer = startTimer()
                val collector = ru.elmer.client.script.DynamicCollector(elmProto, steps, interval,
                    onSample = { idx -> ui { updateLastLine("📊 ${idx + 1} отсчётов") } },
                    onLog = { })
                dynamicCollector = collector
                collector.start()

                while (state == State.START && collector.isRunning()) Thread.sleep(200)
                val samples = collector.stop()
                timer?.set(false)
                dynamicSamples = samples.toMutableList()
                ui { appendStatus("\n📊 Записано: ${samples.size} отсчётов") }
                ui { appendStatus("\nНажмите ➤ для отправки на сервер.") }
                runningDiag = false
            } catch (e: Exception) {
                timer?.set(false)
                ui { appendStatus("\n❌ ${e.message}") }
                runningDiag = false
            }
        }
    }

    private fun stopDynamicRecording() {
        dynamicCollector?.let { if (it.isRunning()) { state = State.STOP } }
        appendStatus("\n⏹ Запись завершена")
        setActionState(State.DIAG)
    }

    // ── Отправка ➤ ──────────────────────────────────────

    private fun onSend() {
        val text = etUserInput.text.toString().trim()
        if (text.isEmpty() && dynamicSamples == null) return

        if (dynamicSamples != null && dynamicSamples!!.isNotEmpty()) {
            // Отправка данных теста
            appendStatus("\n📤 Отправка данных теста...")
            thread(name = "Upload", isDaemon = true) {
                try {
                    val db = SessionDb(this@MainActivity)
                    val sid = db.createSession("{}", "Тест", "https://obdai.ru")
                    val samples = dynamicSamples!!
                    samples.forEachIndexed { i, batch ->
                        batch.forEach { r -> db.addResponse(sid, "sample_$i", r.cmd, r.raw, r.decoded) }
                    }
                    val responses = db.getResponses(sid)
                    val clientInfo = mapOf("phone_model" to Build.MODEL, "app_version" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"))
                    val client = ru.elmer.client.server.ServerClient("https://obdai.ru", "https://obdai.ru/api/v1/script", "")
                    val dynForUpload = samples.map { batch -> batch.map { r -> mapOf("step_id" to r.stepId, "cmd" to r.cmd, "raw" to r.raw, "decoded" to r.decoded) } }
                    val resp = client.uploadSession(sid, emptyList(), clientInfo, text, dynForUpload)
                    dynamicSamples = null
                    if (resp != null) {
                        db.markUploaded(sid)
                        val d = resp.optString("diagnosis", "")
                        if (d.isNotEmpty()) {
                            db.saveDiagnosis(sid, d)
                            ui { appendStatus("\n🩺 $d") }
                        }
                    } else {
                        ui { appendStatus("\n⚠️ Сервер недоступен") }
                    }
                } catch (e: Exception) {
                    ui { appendStatus("\n❌ ${e.message}") }
                }
            }
        } else if (text.isNotEmpty()) {
            // Отправка текста в чат
            chatHistory.add("user" to text)
            appendStatus("\n👤 $text")
            etUserInput.text.clear()
            thread(name = "Chat", isDaemon = true) {
                try {
                    val json = org.json.JSONObject().apply {
                        put("question", text)
                        put("history", org.json.JSONArray().apply {
                            for ((role, msg) in chatHistory) put(org.json.JSONObject().apply { put("role", role); put("content", msg) })
                        })
                    }
                    val req = java.net.URL("https://obdai.ru/api/v1/chat").openConnection() as java.net.HttpURLConnection
                    req.connectTimeout = 10000; req.readTimeout = 60000; req.doOutput = true
                    req.setRequestProperty("Content-Type", "application/json")
                    req.outputStream.write(json.toString().toByteArray())
                    val body = if (req.responseCode == 200) req.inputStream.bufferedReader().readText() else ""
                    req.disconnect()
                    val answer = try { org.json.JSONObject(body).optString("answer", "(пусто)") } catch (_: Exception) { body.take(200) }
                    runOnUiThread { chatHistory.add("assistant" to answer); appendStatus("\n🤖 $answer") }
                } catch (e: Exception) {
                    runOnUiThread { appendStatus("\n❌ ${e.message}") }
                }
            }
        }
    }

    // ── История ──────────────────────────────────────────

    private fun showHistory() {
        thread(name = "LoadHistory", isDaemon = true) {
            var sessions: List<Map<String, String?>>? = null
            // Пробуем сервер
            if (indServer.text.contains("🟢")) {
                try {
                    val req = java.net.URL("https://obdai.ru/api/v1/sessions").openConnection() as java.net.HttpURLConnection
                    req.connectTimeout = 3000; req.readTimeout = 5000
                    req.setRequestProperty("X-Api-Key", ru.elmer.client.BuildConfig.API_KEY)
                    val body = if (req.responseCode == 200) req.inputStream.bufferedReader().readText() else ""
                    req.disconnect()
                    val json = org.json.JSONArray(body)
                    val list = mutableListOf<Map<String, String?>>()
                    for (i in 0 until json.length()) {
                        val j = json.getJSONObject(i)
                        list.add(mapOf(
                            "id" to j.optString("id"), "title" to j.optString("title"),
                            "created_at" to j.optString("created_at"), "uploaded" to "1",
                            "diagnosis" to j.optString("diagnosis")
                        ))
                    }
                    sessions = list
                } catch (_: Exception) { }
            }
            // Fallback: локальная БД
            if (sessions == null || sessions.isEmpty()) {
                val db = SessionDb(this@MainActivity)
                sessions = db.getSessions()
            }
            val s = sessions
            if (s == null || s.isEmpty()) {
                runOnUiThread { appendStatus("\n📋 История пуста") }; return@thread
            }
            val recent = s.take(10)
            val items = recent.map { x ->
                val ts = x["created_at"]?.let {
                    try { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.toLong() * 1000)) }
                    catch (_: Exception) { it.take(16) }
                } ?: "?"
                val title = x["title"] ?: "Диагностика"
                val prefix = if (x["uploaded"] == "1") "✅" else "⏳"
                "$prefix [$ts] $title"
            }.toTypedArray()
            val diagnoses = recent.map { x -> x["diagnosis"] ?: "(нет данных)" }
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("📋 История (${recent.size})")
                    .setItems(items) { _, which ->
                        val d = diagnoses[which]; val title = items[which]
                        val scrollView = android.widget.ScrollView(this@MainActivity).apply {
                            setBackgroundColor(0xFF0d0d1a.toInt())
                        }
                        val tv = android.widget.TextView(this@MainActivity).apply {
                            text = d; textSize = 15f; setTextColor(0xFFe0e0e0.toInt())
                            setPadding(24, 16, 24, 16); setBackgroundColor(0xFF0d0d1a.toInt())
                        }
                        scrollView.addView(tv)
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(title).setView(scrollView)
                            .setPositiveButton("📤 Поделиться") { _, _ ->
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, "elmAI: $title")
                                    putExtra(android.content.Intent.EXTRA_TEXT, "elmAI диагностика\n$title\n\n$d")
                                }
                                startActivity(android.content.Intent.createChooser(intent, "Поделиться"))
                            }
                            .setNegativeButton("Закрыть", null).show()
                    }
                    .setNegativeButton("Закрыть", null).show()
            }
        }
    }

    // ── Помощники ────────────────────────────────────────

    private fun findElmDevice(): BluetoothDevice? {
        if (btAdapter == null || !btAdapter!!.isEnabled) return null
        val paired = btAdapter!!.bondedDevices.toList()
        return if (paired.size == 1) paired[0] else {
            if (paired.size > 1) {
                val names = paired.map { "${it.name}\n${it.address}" }.toTypedArray()
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Выберите устройство").setItems(names) { _, which -> elmDevice = paired[which] }
                        .setNegativeButton("Отмена", null).show()
                }
            }
            null
        }
    }

    private var timerRunning: java.util.concurrent.atomic.AtomicBoolean? = null

    private fun startTimer(): java.util.concurrent.atomic.AtomicBoolean {
        timerRunning?.set(false)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        timerRunning = running
        val tvTimer = findViewById<TextView>(R.id.tv_timer)
        runOnUiThread { tvTimer.visibility = View.VISIBLE }
        thread(name = "Timer", isDaemon = true) {
            var sec = 0
            while (running.get()) { Thread.sleep(1000); sec++; runOnUiThread { if (running.get()) tvTimer.text = "⏱ ${sec}с" } }
            runOnUiThread { tvTimer.visibility = View.GONE }
        }
        return running
    }

    private fun appendStatus(text: String) {
        tvStatus.append(text)
        scrollOutput.post { scrollOutput.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateLastLine(text: String) {
        val current = tvStatus.text.toString()
        val lastNewline = current.lastIndexOf('\n')
        if (lastNewline >= 0) tvStatus.text = current.substring(0, lastNewline + 1) + text
    }

    private fun ui(block: () -> Unit) { runOnUiThread(block) }

    private fun syncPendingSessions() {
        thread(name = "SyncPending", isDaemon = true) {
            try {
                val db = SessionDb(this@MainActivity)
                val pending = db.getPendingSessions()
                if (pending.isEmpty()) return@thread
                debugLog("Синхронизация ${pending.size} сессий...")
                val client = ru.elmer.client.server.ServerClient("https://obdai.ru", "https://obdai.ru/api/v1/script", "")
                for (sid in pending) {
                    val responses = db.getResponses(sid)
                    if (responses.isEmpty()) continue
                    val resp = client.uploadSession(sid, responses)
                    if (resp != null) db.markUploaded(sid) else break
                }
            } catch (_: Exception) { }
        }
    }

    // ── ScriptReceiver ───────────────────────────────────

    private fun registerScriptReceiver() {
        if (scriptRegistered) return
        scriptRegistered = true
        registerReceiver(scriptStatusReceiver, IntentFilter(ScriptRunnerService.BROADCAST_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        registerReceiver(scriptStageReceiver, IntentFilter(ScriptRunnerService.BROADCAST_STAGE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val scriptStageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stage = intent?.getStringExtra("stage") ?: return
            val detail = intent.getStringExtra("detail") ?: ""
            when (stage) {
                "done" -> { appendStatus("\n$detail"); setActionState(State.DIAG) }
                else -> appendStatus("\n$detail")
            }
        }
    }

    private val scriptStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            runOnUiThread { appendStatus("\n$msg") }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(scriptStatusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scriptStageReceiver) } catch (_: Exception) {}
        scriptRegistered = false
        super.onDestroy()
    }
}
