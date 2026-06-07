package ru.elmer.client.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import ru.elmer.client.BuildConfig
import ru.elmer.client.R
import ru.elmer.client.db.SessionDb
import ru.elmer.client.script.ScriptRunnerService

/**
 * Тонкий клиент Elmer — одна кнопка.
 * Подключается к ELM327 по Bluetooth, шлёт сырые OBD-ответы на сервер.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnScript: Button
    private lateinit var btnDtc: Button
    private lateinit var tvDtcStatus: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnCheckElm: Button
    private lateinit var btnCheckEcu: Button
    private var elmChecker: ru.elmer.client.elm.ElmChecker? = null
    private lateinit var cbFullMode: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var etUserInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnClose: Button
    private lateinit var btnDynIdle: Button
    private lateinit var btnDynDrive: Button
    private lateinit var dynamicButtons: LinearLayout
    private lateinit var scrollOutput: ScrollView

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var elmDevice: BluetoothDevice? = null
    private var scriptRegistered = false
    private val chatHistory = mutableListOf<Pair<String, String>>()  // (role, text)

    companion object {
        const val REQUEST_BT_PERMISSIONS = 1
        const val REQUEST_ENABLE_BT = 2
        private const val PING_THROTTLE_MS = 7_000L       // 7с при ошибках
        private const val PING_OK_THROTTLE_MS = 60_000L   // 60с при успехе
    }

    private var lastPingTime = 0L
    private var lastPingLlmTime = 0L
    private var lastPingOk = false

    private fun addApiKey(conn: java.net.HttpURLConnection) {
        val key = BuildConfig.API_KEY
        if (key.isNotEmpty()) conn.setRequestProperty("X-Api-Key", key)
    }

    // ⚠️ statusReceiver удалён — scriptStatusReceiver уже слушает BROADCAST_STATUS (дубликат)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Восстановление chatHistory после поворота
        savedInstanceState?.getString("chat_json")?.let { json ->
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    chatHistory.add(obj.getString("role") to obj.getString("content"))
                }
            } catch (_: Exception) {}
        }

        // Android 12+ Bluetooth разрешения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT_PERMISSIONS)
            }
        }

        btnScript = findViewById(R.id.btn_script)
        btnDtc = findViewById(R.id.btn_dtc)
        tvDtcStatus = findViewById(R.id.tv_dtc_status)
        cbFullMode = findViewById(R.id.cb_full_mode)
        btnHistory = findViewById(R.id.btn_history)
        btnCheckElm = findViewById(R.id.btn_check_elm)
        btnCheckEcu = findViewById(R.id.btn_check_ecu)

        btnDtc.setOnClickListener { scanDtc() }
        btnHistory.setOnClickListener { showHistory() }
        btnCheckElm.setOnClickListener { checkElm() }
        btnCheckEcu.setOnClickListener { checkEcu() }
        tvStatus = findViewById(R.id.tv_status)
        tvPrompt = findViewById(R.id.tv_prompt)
        etUserInput = findViewById(R.id.et_user_input)
        btnSend = findViewById(R.id.btn_send)
        btnClose = findViewById(R.id.btn_close)
        btnDynIdle = findViewById(R.id.btn_dyn_idle)
        btnDynDrive = findViewById(R.id.btn_dyn_drive)
        dynamicButtons = findViewById(R.id.dynamic_buttons)
        scrollOutput = findViewById(R.id.scroll_output)

        btnSend.setOnClickListener { sendToLlm() }
        btnClose.setOnClickListener {
            appendStatus("\nГотов")
            btnClose.visibility = android.view.View.GONE
            btnSend.visibility = android.view.View.GONE
            dynamicButtons.visibility = android.view.View.GONE
            // Вернуть кнопки
            findViewById<LinearLayout>(R.id.top_buttons).visibility = android.view.View.VISIBLE
            findViewById<LinearLayout>(R.id.check_section).visibility = android.view.View.VISIBLE
            btnHistory.visibility = android.view.View.VISIBLE
            cbFullMode.visibility = android.view.View.VISIBLE
            tvDtcStatus.visibility = android.view.View.VISIBLE
        }

        btnDynIdle.setOnClickListener { startDynamicTest("idle") }
        btnDynDrive.setOnClickListener { startDynamicTest("drive") }

        // Версия из build.gradle (versionName)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "v$version"

        // ТЕСТОВАЯ КНОПКА
        val btnTest = findViewById<Button>(R.id.btn_test)
        btnTest.setOnClickListener { startTest() }

        // СКРИПТ
        btnScript.setOnClickListener { startScript() }

        // Промпты от ScriptRunnerService
        registerScriptReceiver()

        // Восстановление вывода после поворота экрана
        savedInstanceState?.getString("status_text")?.let {
            tvStatus.text = it
        } ?: run {
            tvStatus.text = "⏳ Загрузка...\n\nНажмите 📡 Сервер для проверки связи.\nНажмите 🔍 ДИАГНОСТИКА для запуска."
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("status_text", tvStatus.text.toString())
    }

    private fun startTest() {
        thread(name = "ServerTest", isDaemon = true) {
            val client = ru.elmer.client.server.ServerClient(
                "https://obdai.ru",
                "https://obdai.ru/api/v1/script",
                ""
            )

            // Этап 1: сервер (с троттлингом)
            val now = System.currentTimeMillis()
            val throttle = if (lastPingOk) PING_OK_THROTTLE_MS else PING_THROTTLE_MS
            if (now - lastPingTime < throttle) {
                ui { appendStatus("\n⏱ Сервер: проверка не чаще ${throttle/1000}с") }
                return@thread
            }
            lastPingTime = now
            ui { appendStatus("\n📡 Сервер...") }
            val ping = client.ping()
            if (ping.ok) {
                lastPingOk = true
                ui { appendStatus("\n✅ Сервер: ${ping.ms}мс") }
            } else {
                lastPingOk = false
                ui { appendStatus("\n❌ Сервер: ${ping.error}") }
                return@thread
            }

            // Этап 2: LLM
            ui { appendStatus("\n🧠 LLM...") }
            val llm = client.pingLlm()
            if (llm.ok) {
                ui { appendStatus("\n✅ LLM: ${llm.ms}мс") }
            } else {
                ui { appendStatus("\n⚠️ LLM: ${llm.error}") }
            }
        }
    }

    private fun ui(block: () -> Unit) { runOnUiThread(block) }

    private fun startScript() {
        val mode = if (cbFullMode.isChecked) "full" else "test"
        val carInfo = etUserInput.text.toString().trim()
        val intent = Intent(this, ScriptRunnerService::class.java).apply {
            action = ScriptRunnerService.ACTION_RUN
            putExtra(ScriptRunnerService.EXTRA_SCRIPT_URL, "https://obdai.ru/api/v1/script?mode=$mode")
            if (carInfo.isNotEmpty()) putExtra(ScriptRunnerService.EXTRA_CAR_INFO, carInfo)
        }
        ContextCompat.startForegroundService(this, intent)

        tvStatus.text = ""
        tvStatus.text = "⏳ Инициализация ELM327..."
        scriptStartTime = System.currentTimeMillis()
        scriptTimer = startTimer()
        registerScriptReceiver()
    }

    // ── Сканирование ошибок ────────────────────────────────

    private var dtcCodes = listOf<String>()
    private var dtcChecked = false

    private fun scanDtc() {
        val dev = findElmDevice() ?: return
        appendStatus("\n─── Сканирование ошибок ───")
        tvDtcStatus.visibility = android.view.View.GONE
        val timer = startTimer()
        val t0 = System.currentTimeMillis()
        thread(name = "DtcScan", isDaemon = true) {
            val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
            val r = checker.scanDtc()
            timer.set(false)
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            runOnUiThread {
                if (r == null) {
                    appendStatus("\n❌ ELM не отвечает [${elapsed}с]")
                    appendStatus("\nЛог: ${checker.getLog()}")
                } else {
                    dtcCodes = r
                    dtcChecked = true
                    if (r.isEmpty()) {
                        appendStatus("\n✅ Ошибок нет [${elapsed}с]")
                    } else {
                        appendStatus("\n⚠️ Обнаружены ошибки (${r.size}): ${r.joinToString(", ")} [${elapsed}с]")
                    }
                    btnScript.isEnabled = true
                    tvDtcStatus.text = "✅ Ошибки считаны — можно диагностировать"
                    tvDtcStatus.setTextColor(0xFF4CAF50.toInt())
                    tvDtcStatus.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun checkElm() {
        val dev = findElmDevice() ?: return
        appendStatus("\n─── Опрос ELM327 ───")
        val timer = startTimer()
        val t0 = System.currentTimeMillis()
        thread(name = "ElmCheck", isDaemon = true) {
            val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
            val r = checker.checkDevice()
            timer.set(false)
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            runOnUiThread {
                if (r == null) {
                    appendStatus("\n❌ ELM не отвечает [${elapsed}с]")
                    appendStatus("\nЛог: ${checker.getLog()}")
                } else {
                    elmChecker = checker  // сохраняем для ЭБУ
                    val deviceLine = if (r.deviceId != "—") "\n🔹 Устройство: ${r.deviceId}" else ""
                    val good = r.hasAdaptive && r.version.contains("v2")
                    val result = if (good) {
                        "\n✅ Чёткое устройство! [${elapsed}с]" +
                        "${deviceLine}" +
                        "\n🔹 Версия: ${r.version}" +
                        "\n🔹 Протокол: ${r.protocol}" +
                        "\n🔹 Напряжение: ${r.voltage}" +
                        "\n🔹 Адаптивный тайминг: ✅"
                    } else {
                        "\n⚠️ Клон или слабый ELM327 [${elapsed}с]" +
                        "${deviceLine}" +
                        "\n🔹 Версия: ${r.version}" +
                        "\n🔹 Протокол: ${r.protocol}" +
                        "\n🔹 Адаптивный тайминг: ❌"
                    }
                    appendStatus(result)
                }
            }
        }
    }

    private fun checkEcu() {
        val checker = elmChecker
        if (checker == null) {
            appendStatus("\n⚠️ Сначала нажми \"🔌 ELM\"")
            return
        }
        appendStatus("\n─── Опрос ЭБУ ───")
        val timer = startTimer()
        val t0 = System.currentTimeMillis()
        thread(name = "EcuCheck", isDaemon = true) {
            val r = checker.checkEcu()
            timer.set(false)
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            runOnUiThread {
                val obd = if (r.supportsObd) "✅" else "❌"
                appendStatus("\n🚗 ЭБУ: OBD $obd, PID: ${r.pidMask} [${elapsed}с]" +
                    if (r.vin != null) "\n🔹 VIN: ${r.vin}" else "\n🔹 VIN: не определился")
            }
        }
    }

    private fun findElmDevice(): BluetoothDevice? {
        if (btAdapter == null) { appendStatus("\n❌ BT не поддерживается"); return null }
        if (!btAdapter!!.isEnabled) { appendStatus("\n❌ Включите Bluetooth"); return null }
        val paired = btAdapter!!.bondedDevices
        val names = paired.map { it.name }.joinToString(", ")
        val dev = paired.find { it.name.uppercase().let { n -> n.contains("OBD") || n.contains("ELM") || n.contains("CBT") || n.contains("V-LINK") || n.contains("VLINK") || n.contains("ANDROID-VLINK") || n.contains("BTSCAN") || n.contains("CARBT") || n.contains("AUTO") } }
        if (dev == null) appendStatus("\n❌ ELM не найден\nСопряжено: ${paired.size} шт.\nИмена: $names")
        return dev
    }

    /** Запускает общий таймер в tv_timer. Возвращает флаг для остановки. */
    private fun startTimer(): java.util.concurrent.atomic.AtomicBoolean {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val tvTimer = findViewById<TextView>(R.id.tv_timer)
        runOnUiThread { tvTimer.visibility = android.view.View.VISIBLE }
        thread(name = "Timer", isDaemon = true) {
            var sec = 0
            while (running.get()) {
                Thread.sleep(1000)
                sec++
                runOnUiThread {
                    if (running.get()) tvTimer.text = "⏱ ${sec}с"
                }
            }
            runOnUiThread { tvTimer.visibility = android.view.View.GONE }
        }
        return running
    }

    private fun registerScriptReceiver() {
        if (scriptRegistered) return
        scriptRegistered = true
        registerReceiver(scriptStatusReceiver,
            IntentFilter(ScriptRunnerService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registerReceiver(scriptStageReceiver,
            IntentFilter(ScriptRunnerService.BROADCAST_STAGE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private var scriptStartTime: Long = 0
    private var scriptTimer: java.util.concurrent.atomic.AtomicBoolean? = null

    private val scriptStageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stage = intent?.getStringExtra("stage") ?: return
            val detail = intent.getStringExtra("detail") ?: ""
            val elapsed = if (scriptStartTime > 0)
                " [${(System.currentTimeMillis() - scriptStartTime) / 1000}с]"
            else ""
            when (stage) {
                "ecu" -> appendStatus("\n🔌 Соединение с ЭБУ...$elapsed")
                "upload" -> appendStatus("\n📤 $detail$elapsed")
                "llm" -> appendStatus("\n🧠 $detail$elapsed")
                "done" -> {
                    scriptTimer?.set(false)
                    appendStatus("\n$detail$elapsed")
                    btnClose.visibility = android.view.View.VISIBLE
                    btnSend.visibility = android.view.View.VISIBLE
                    dynamicButtons.visibility = android.view.View.VISIBLE
                    // Скрыть верхние элементы для максимизации вывода
                    findViewById<LinearLayout>(R.id.top_buttons).visibility = android.view.View.GONE
                    findViewById<LinearLayout>(R.id.check_section).visibility = android.view.View.GONE
                    btnHistory.visibility = android.view.View.GONE
                    findViewById<CheckBox>(R.id.cb_full_mode).visibility = android.view.View.GONE
                    findViewById<TextView>(R.id.tv_dtc_status).visibility = android.view.View.GONE
                }
                else -> appendStatus("\n📡 $detail$elapsed")
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
        scriptRegistered = false  // сброс для перерегистрации после поворота
        super.onDestroy()
    }

    // ── Помощники ───────────────────────────────────────

    private fun appendStatus(text: String) {
        tvStatus.append(text)
        scrollOutput.post { scrollOutput.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ── Чат с LLM ────────────────────────────────────────

    private fun sendToLlm() {
        val text = etUserInput.text.toString().trim()
        if (text.isEmpty()) return
        etUserInput.text.clear()
        chatHistory.add("user" to text)
        appendStatus("\n👤 $text")
        thread(name = "LlmChat", isDaemon = true) {
            try {
                val json = org.json.JSONObject().apply {
                    put("question", text)
                    put("history", org.json.JSONArray().apply {
                        for ((role, msg) in chatHistory) {
                            put(org.json.JSONObject().apply {
                                put("role", role); put("content", msg)
                            })
                        }
                    })
                }
                val req = java.net.URL("https://obdai.ru/api/v1/chat").openConnection() as java.net.HttpURLConnection
                req.connectTimeout = 10000; req.readTimeout = 60000
                req.doOutput = true; req.setRequestProperty("Content-Type", "application/json")
                addApiKey(req)
                req.outputStream.write(json.toString().toByteArray())
                val body = if (req.responseCode == 200)
                    req.inputStream.bufferedReader().readText() else ""
                req.disconnect()
                val answer = try {
                    org.json.JSONObject(body).optString("answer", "(пусто)")
                } catch (_: Exception) { body.take(200) }
                runOnUiThread {
                    chatHistory.add("assistant" to answer)
                    appendStatus("\n🤖 $answer")
                }
            } catch (e: Exception) {
                runOnUiThread { appendStatus("\n❌ ${e.message}") }
            }
        }
    }

    // ── Динамический тест ────────────────────────────────

    private var dynamicCollector: ru.elmer.client.script.DynamicCollector? = null

    private fun startDynamicTest(mode: String) {
        val dev = elmDevice ?: findElmDevice() ?: return
        elmDevice = dev
        val isDrive = mode == "drive"
        val label = if (isDrive) "🚗 В движении" else "⏱ На месте"

        // Подсказка
        if (isDrive) {
            appendStatus("\n\n🚗 ТЕСТ В ДВИЖЕНИИ")
            appendStatus("\n⚠️ Остановитесь. Нажмите СТАРТ ДО начала движения.")
            appendStatus("\n⚠️ Ничего не нажимайте в движении — программа пишет сама.")
            appendStatus("\n⚠️ Включите 2-ю передачу, ~3000 об/мин, затем сбросьте газ.")
            appendStatus("\n⚠️ После полной остановки нажмите СТОП.")
        } else {
            appendStatus("\n\n⏱ ТЕСТ НА МЕСТЕ")
            appendStatus("\n⚠️ Нажмите СТАРТ, затем нажмите педаль газа.")
            appendStatus("\n⚠️ Поднимите до ~3000 об/мин, держите 3-4 сек, резко сбросьте.")
            appendStatus("\n⚠️ Нажмите СТОП после завершения.")
        }

        // Превращаем кнопки в СТАРТ
        dynamicButtons.visibility = android.view.View.GONE
        btnScript.text = "▶ СТАРТ $label"
        btnScript.isEnabled = true
        btnScript.visibility = android.view.View.VISIBLE
        findViewById<LinearLayout>(R.id.top_buttons).visibility = android.view.View.VISIBLE

        // Флаг чтобы отличить повторное нажатие
        var started = false

        btnScript.setOnClickListener {
            if (!started) {
                // СТАРТ
                started = true
                btnScript.text = "⏹ СТОП $label"
                btnScript.setBackgroundColor(0xFFd32f2f.toInt())
                appendStatus("\n▶ Запись началась...")

                thread(name = "DynamicTest", isDaemon = true) {
                    try {
                        // Скачиваем скрипт dynamic
                        val client = ru.elmer.client.server.ServerClient(
                            "https://obdai.ru",
                            "https://obdai.ru/api/v1/script?mode=dynamic",
                            ""
                        )
                        val scriptJson = client.downloadScript()
                        val script = org.json.JSONObject(scriptJson)
                        val stepsArr = script.getJSONArray("steps")
                        val interval = script.optLong("interval_ms", 250)
                        val steps = (0 until stepsArr.length()).map {
                            val s = stepsArr.getJSONObject(it)
                            ru.elmer.client.script.DynamicCollector.ElmStep(
                                s.getString("id"), s.getString("cmd"), s.getString("desc")
                            )
                        }

                        // Подключаемся к ELM если ещё нет
                        val checker = ru.elmer.client.elm.ElmChecker(dev, btAdapter!!)
                        if (!checker.isConnected()) {
                            ui { appendStatus("\n⏳ Подключение к ELM...") }
                            checker.connect()
                        }
                        val elmProto = checker.getElm()
                            ?: run { ui { appendStatus("\n❌ Нет связи с ELM") }; return@thread }

                        val timer = startTimer()
                        val collector = ru.elmer.client.script.DynamicCollector(
                            elm = elmProto,
                            steps = steps,
                            intervalMs = interval,
                            onSample = { idx -> ui { appendStatus("\r📊 ${idx + 1} отсчётов") } },
                            onLog = { }  // не спамим
                        )
                        dynamicCollector = collector
                        collector.start()

                        // Ждём СТОП
                        while (started && collector.isRunning()) {
                            Thread.sleep(200)
                        }
                        val samples = collector.stop()
                        timer.set(false)

                        val totalCount = samples.sumOf { it.size }
                        ui { appendStatus("\n📊 Записано: ${samples.size} отсчётов ($totalCount ответов)") }

                        // Сохраняем в БД и отправляем
                        ui { appendStatus("\n📤 Отправка на сервер...") }
                        val db = SessionDb(this@MainActivity)
                        val sid = db.createSession(scriptJson, "Динамический тест", "https://obdai.ru")
                        samples.forEachIndexed { i, batch ->
                            batch.forEach { r ->
                                db.addResponse(sid, "sample_$i", r.cmd, r.raw, r.decoded)
                            }
                        }
                        val allResponses = db.getResponses(sid)
                        val clientInfo = buildSimpleClientInfo()
                        val uploadResp = client.uploadSession(sid, allResponses, clientInfo, "")
                        if (uploadResp != null) {
                            db.markUploaded(sid)
                            val d = uploadResp.optString("diagnosis", "")
                            if (d.isNotEmpty()) {
                                ui {
                                    appendStatus("\n🩺 Диагноз:")
                                    d.chunked(60).forEach { appendStatus(it.trim()) }
                                }
                            }
                        } else {
                            ui { appendStatus("\n⚠️ Сервер недоступен. Данные сохранены локально.") }
                        }
                    } catch (e: Exception) {
                        ui { appendStatus("\n❌ ${e.message}") }
                    }
                }
            } else {
                // СТОП
                started = false
                appendStatus("\n⏹ Запись остановлена")
                btnScript.visibility = android.view.View.GONE
                dynamicButtons.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun buildSimpleClientInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        info["phone_model"] = android.os.Build.MODEL
        info["phone_maker"] = android.os.Build.MANUFACTURER
        info["android_version"] = android.os.Build.VERSION.RELEASE
        info["script_mode"] = "dynamic"
        try { info["app_version"] = packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) {}
        return info
    }

    private fun showHistory() {
        val db = SessionDb(this)
        val sessions = db.getSessions()
        if (sessions.isEmpty()) {
            appendStatus("\n📋 История пуста")
            return
        }

        // Берём последние 20, формируем список
        val recent = sessions.take(20)
        val items = recent.map { s ->
            val dt = s["created_at"]?.let {
                java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it.toLong() * 1000))
            } ?: "?"
            val title = s["title"] ?: "Диагностика"
            val uploaded = s["uploaded"] == "1"
            val prefix = if (uploaded) "✅" else "⏳"
            "$prefix [$dt] $title"
        }.toTypedArray()

        val diagnoses = recent.map { s -> s["diagnosis"] ?: "(нет данных)" }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 История (${recent.size})")
            .setItems(items) { _, which ->
                // Показать полный диагноз
                appendStatus("\n📋 ${items[which]}\n\n${diagnoses[which]}")
                btnClose.setOnClickListener {
                    btnClose.visibility = android.view.View.GONE
                    showHistory()
                }
                btnClose.visibility = android.view.View.VISIBLE
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
