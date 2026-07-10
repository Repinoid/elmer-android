Ты — senior Android-разработчик. Проанализируй архитектуру Android-приложения.
10 классов, Kotlin, minSdk 24, OkHttp 4.12.0, без Room, без Coroutines, без DI.

Ниже — ПОЛНОЕ описание каждого файла с сигнатурами и зависимостями.
НЕ додумывай чего нет. Отвечай ТОЛЬКО на то, что видишь в описанных файлах.

────────────────────────────────────

## Файл 1: ScriptRunnerService.kt
Путь: app/src/main/java/ru/elmer/client/script/ScriptRunnerService.kt
~400 строк. Это фоновый Service, запускается из MainActivity.
В одном классе СВАЛЕНО:

- BT-подключение: connectBt(), connectBtSocket() — поиск bonded-устройств,
  RFCOMM-сокет, fallback на createRfcommSocket(1)
- ELM-инициализация: elm = ElmProtocol(stream, stream), elm.init(), elm.detectClone()
- Выполнение скрипта: скачивание JSON через ServerClient.downloadScript(),
  создание ScriptEngine(callbacks), engine.run(scriptJson)
- Парсинг ответов: buildClientInfo() — собирает 20+ полей (модель, версия, 
  Android ID, device_uuid, локаль, таймзона, разрешение, MAC ELM, длительность...)
- Отправка на сервер: ServerClient.uploadSession(), UploadProgress
- Broadcast'ы: BROADCAST_STATUS (строки лога), BROADCAST_STAGE (этапы), 
  BROADCAST_PROMPT
- Android-нотификации: createNotificationChannel(), buildNotification(),
  startForeground()
- Управление состоянием: running, errorCount, retryCount, timeoutCount
- 13 констант в companion object: ACTION_RUN, ACTION_STOP, EXTRA_*, BROADCAST_*,
  DEFAULT_SCRIPT (полный JSON на 25 строк)
- Жёсткая ссылка на MainActivity: PendingIntent.getActivity(this, 0, 
  Intent(this, MainActivity::class.java), ...)
- Прямое создание зависимостей:
    db = SessionDb(this)
    elm = ElmProtocol(btSocket.inputStream, btSocket.outputStream)
    client = ServerClient(serverUrl, scriptUrl, DEFAULT_SCRIPT)
    engine = ScriptEngine(callbacks...)
    progress = UploadProgress(this, packageName, count, dataSizeKB)

## Файл 2: MainActivity.kt
Путь: app/src/main/java/ru/elmer/client/ui/MainActivity.kt
~350+ строк. В одном Activity СВАЛЕНО:

- UI: кнопки, индикаторы-светофоры, ScrollView, EditText
- BT-поиск: findElmDevice() — поиск среди bonded-устройств
- Проверка ELM: ElmChecker(dev, bt).checkDevice(), checkEcu()
- Speed-test: ElmChecker.measureResponseTime(), ServerClient.saveProfile()
- Пинг сервера: HttpURLConnection (НЕ OkHttp!) на /api/v1/ping
- Пинг LLM: HttpURLConnection на /api/v1/ping-llm
- Чат с LLM: onSend() — история диалога chatHistory
- State-машина UI: enum State { INIT, DTC, DIAG, START, STOP }, setActionState()
- Запуск диагностики напрямую (НЕ через ScriptRunnerService): scanDtc(),
  runDiagnostics() — сама создаёт ElmProtocol, ServerClient, SessionDb,
  выполняет скрипт, шлёт на сервер
- Динамический тест: DynamicCollector, startDynamicRecording(), 
  stopDynamicRecording()
- Приём broadcast'ов: registerScriptReceiver() — слушает BROADCAST_STATUS и 
  BROADCAST_STAGE
- История: showHistory() — через SessionDb
- FQN-ссылки на классы БЕЗ import'ов:
    ru.elmer.client.elm.ElmChecker
    ru.elmer.client.server.ServerClient
    ru.elmer.client.elm.ObdDecoder
    ru.elmer.client.script.DynamicCollector
    (при этом ru.elmer.client.db.SessionDb и ScriptRunnerService — 
     импортированы нормально через import)

## Файл 3: ScriptEngine.kt
Путь: app/src/main/java/ru/elmer/client/script/ScriptEngine.kt
~35 строк. Чистый класс. Зависит только от ObdDecoder.
Принимает 4 callback'а в конструкторе: sendCommand, onStage, onResult, onLog.
Метод run(scriptJson: String): Boolean — итерирует JSON-шаги, шлёт команды,
декодирует, вызывает колбэки.

## Файл 4: ElmChecker.kt
Путь: app/src/main/java/ru/elmer/client/elm/ElmChecker.kt
~150+ строк. Обёртка над ElmProtocol для проверок. Содержит:
- BT-подключение: connect()
- ELM-инициализация: ElmProtocol.init()
- checkDevice(): DeviceInfo — ATI, AT@1, AT@2, ATRV, ATDP
- checkEcu(): EcuData — 0100, 0101, 0902
- scanDtc(): List<String> — mode 03
- measureResponseTime(): SpeedTestResult — замер скорости
- getElm(): ElmProtocol — доступ к голому протоколу

## Файл 5: ElmProtocol.kt
Путь: app/src/main/java/ru/elmer/client/elm/ElmProtocol.kt
~150 строк. Полная стейт-машина AndrOBD.
Методы: init(), detectClone(), sendCommand(cmd), updateAtst(), 
recover(), exec(), write(), tryRead(), drainInput().
Ни от кого не зависит.

## Файл 6: ObdDecoder.kt
Путь: app/src/main/java/ru/elmer/client/elm/ObdDecoder.kt
object-синглтон. Метод decode(cmd, raw): String.
Ни от кого не зависит.

## Файл 7: DynamicCollector.kt
Путь: app/src/main/java/ru/elmer/client/script/DynamicCollector.kt
~80 строк. Циклический опрос PID'ов с интервалом.
Зависит от ElmProtocol и ObdDecoder.
Методы: start(), stop(): List<List<SampleResponse>>.

## Файл 8: UploadProgress.kt
Путь: app/src/main/java/ru/elmer/client/script/UploadProgress.kt
~50 строк. Таймер прогресса загрузки.
Шлёт broadcast'ы с elapsed-временем.
Зависит от ScriptRunnerService.BROADCAST_STAGE (константа).

## Файл 9: ServerClient.kt
Путь: app/src/main/java/ru/elmer/client/server/ServerClient.kt
~120 строк. HTTP-клиент на OkHttp.
Методы: downloadScript(), uploadSession(), ping(), pingLlm(),
saveProfile(), getProfileResponseTime().
Зависит только от BuildConfig.API_KEY.

## Файл 10: SessionDb.kt
Путь: app/src/main/java/ru/elmer/client/db/SessionDb.kt
~80 строк. SQLiteOpenHelper.
Методы: createSession(), addResponse(), getResponses(), saveDiagnosis(),
markUploaded(), getSessions(), getSessionDiagnosis().
Ни от кого не зависит.

────────────────────────────────────

## ВОПРОСЫ (отвечай строго по пунктам, без введения и выводов)

### Вопрос 1 — FQN в MainActivity
MainActivity использует полные qualified-имена для ElmChecker, ServerClient,
ObdDecoder, DynamicCollector, но нормальные import'ы для SessionDb и 
ScriptRunnerService.
Это баг (забыли import) или осознанное решение (конфликт имён, 
что-то ещё)? Если конфликт — с чем?

### Вопрос 2 — Два пути диагностики
Диагностика запускается ДВУМЯ разными способами:

Способ А (через Service): MainActivity → Intent(ACTION_RUN) → 
ScriptRunnerService → connectBt → ElmProtocol → ScriptEngine → 
ServerClient.uploadSession()

Способ Б (напрямую из Activity): MainActivity.runDiagnostics() → 
ElmChecker → ElmProtocol → цикл по шагам → ServerClient.uploadSession()

Это дублирование или осознанное разделение? Если осознанное — 
в чём разница и зачем оба? Если дублирование — какое оставить?

### Вопрос 3 — Разбивка ScriptRunnerService
В ScriptRunnerService смешаны BT-подключение, ELM-управление, 
выполнение скрипта, сбор client_info, upload, broadcast'ы, 
нотификации. Что конкретно выносить в отдельные классы?

Варианты:
- BtConnector: connectBt() + connectBtSocket()
- ScriptExecutor: executeScript() + buildClientInfo() + parseDynamicSamples()
- DiagnosisOrchestrator: startRun() + весь workflow
- Ничего не трогать

Что из этого реально полезно, а что — оверинжиниринг для 10-классового 
проекта?

### Вопрос 4 — Диагностика напрямую из MainActivity
runDiagnostics() в MainActivity (~80 строк) выполняет полный цикл:
скачивание скрипта, итерация шагов, вызов ObdDecoder.decode(), 
сохранение в SessionDb, upload на сервер, отображение диагноза.
Это дублирует ScriptRunnerService.executeScript(). 

Можно ли этот код убрать из Activity, оставив только запуск через Service?
Или есть причины держать прямой режим (например, для динамического теста)?

### Вопрос 5 — Broadcast vs callback
Сейчас Service → Activity через sendBroadcast с action-строками.
Стоит ли заменить на callback-интерфейс при bindService()?
Учитывая что Service и так использует startForeground() (не bound service).

### Вопрос 6 — DI для 10 классов
Без Hilt/Koin. Сейчас зависимости создаются прямо в месте использования:
    db = SessionDb(this)
    elm = ElmProtocol(stream, stream)
    client = ServerClient(url, url, fallback)

Для проекта такого размера: оставить как есть, сделать ручной 
ServiceLocator/Factory, или всё же mini-DI?

### Вопрос 7 — HttpURLConnection в MainActivity
MainActivity.checkServer() и checkLlm() используют HttpURLConnection
напрямую, а НЕ ServerClient (который на OkHttp). Рядом есть готовый
ServerClient.ping() и pingLlm(). Это просто недоделка или 
осознанный выбор (быстрее/легче для простого пинга)?

### Вопрос 8 — ElmChecker.getElm()
ElmChecker предоставляет метод getElm(): ElmProtocol — голый доступ
к протоколу. Это нарушает инкапсуляцию: MainActivity.runDiagnostics()
и checkEcu() используют elmChecker.getElm()?.sendCommand() напрямую,
минуя методы ElmChecker.

Нормально ли это для такого проекта, или стоит убрать getElm() и
добавить нужные методы в ElmChecker?

### Вопрос 9 — DEFAULT_SCRIPT в ScriptRunnerService
Полный JSON-скрипт (25 строк, 13 шагов) вшит в companion object
ScriptRunnerService как константа DEFAULT_SCRIPT. Используется
как fallback если сервер недоступен.

Куда его вынести? В отдельный файл assets/default_script.json?
В resources? Оставить как есть?

### Вопрос 10 — Общая архитектура
10 классов, NO интерфейсов. ВСЕ зависимости — concrete-классы.
С учётом размера проекта — это проблема или норма?
Где граница: когда МОЖНО без интерфейсов, а когда УЖЕ пора?
