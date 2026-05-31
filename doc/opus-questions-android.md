# Вопросы к Opus 4.8 по elmer-android

> v0.35.0-dev, 31 мая 2026
> Android: Kotlin, minSdk 24, OkHttp 4.12.0
> Пакет: ru.elmer.client
> Сервер: obdai.ru (Flask + gunicorn + nginx)

---

## Какие файлы смотреть (и только их)

Все файлы лежат в `/home/naeel/elmer-android/`:

### elm/ — ELM327
- `app/src/main/java/ru/elmer/client/elm/ElmProtocol.kt` — стейт-машина (1:1 копия AndrOBD ElmProt.java)
- `app/src/main/java/ru/elmer/client/elm/ObdDecoder.kt` — декодер PID/DTC/VIN

### script/ — движок скриптов
- `app/src/main/java/ru/elmer/client/script/ScriptRunnerService.kt` — foreground-сервис, жизненный цикл диагностики
- `app/src/main/java/ru/elmer/client/script/ScriptEngine.kt` — движок выполнения шагов скрипта
- `app/src/main/java/ru/elmer/client/script/UploadProgress.kt` — таймер прогресса загрузки (1с поллинг)

### server/ — HTTP
- `app/src/main/java/ru/elmer/client/server/ServerClient.kt` — OkHttp-клиент (downloadScript + uploadSession, 3 ретрая × 2с)

### db/ — локальное хранилище
- `app/src/main/java/ru/elmer/client/db/SessionDb.kt` — SQLiteOpenHelper, таблицы sessions + responses

### ui/ — экран
- `app/src/main/java/ru/elmer/client/ui/MainActivity.kt` — одна кнопка, чат с LLM, история сессий

### Конфигурация
- `app/build.gradle.kts` — зависимости, minSdk 24, compileSdk 34
- `app/src/main/AndroidManifest.xml` — permissions, service, activity

---

## Вопрос 1. Стейт-машина ElmProtocol.kt: отличия от Python и баги

**Файлы**: `elm/ElmProtocol.kt`, сравни с `obd/protocol.py` (Python)

Ключевые отличия Kotlin-версии от Python:
- `State` — enum внутри класса (в Python — отдельный класс)
- Таймауты — константы в `companion object` (в Python — `AdaptiveTiming` как отдельный класс)
- `handle()` — один большой `when` (в Python — `_handle()` с отдельным `Rsp.identify()`)
- Нет класса `Rsp` — классификация ответа встроена прямо в `handle()` через строки

Вопросы:
1. В Kotlin `handle()` проверяет `u.startsWith("ERROR") && !u.startsWith("DATA ERROR")` — это корректно обрабатывает все варианты ELM-ошибок? Не пропускает ли `ERROR` в начале данных (например, PID с именем «ERROR_xxx»)?
2. `sendCommand()` безусловно ставит `state = State.READY` после `exec()`, но `exec()` может установить `State.ERROR` — маскируется ли ошибка (как в Python)?
3. `init()` не проверяет результат `ATSP0`, `ATAT1` и т.д. — если одна из команд молча провалилась, машина объявит READY с неинициализированным ELM. Это баг или фича AndrOBD?
4. Kotlin-версия не сбрасывает `input`-буфер перед `write()` — есть ли риск десинхронизации (как в Python)?
5. `isDataError` включает `DATA ERROR`, `BUFFER FULL`, `RX ERROR`, но `BUFFER FULL` в Python-версии обрабатывается отдельно (warm start). В Kotlin он попадает в `isDataError` → warm start — это правильное поведение?

---

## Вопрос 2. ScriptRunnerService: жизненный цикл и утечки

**Файл**: `script/ScriptRunnerService.kt`

Сервис — foreground, `START_STICKY`. Жизненный цикл:
1. `ACTION_RUN` → `connectBt()` → `executeScript()` → `done()`
2. `ACTION_RESUME` → сброс флага `paused`
3. `ACTION_STOP` → `disconnect()`

Вопросы:
1. `START_STICKY` — при убийстве системы сервис пересоздастся с `null` intent. `onStartCommand` с `null` intent упадёт или молча повиснет? Как правильно обработать?
2. `thread(name = "ScriptRunner", isDaemon = true)` — демон-поток. Если MainActivity убита, а сервис ещё работает, демон продолжит выполняться? Не убьёт ли система демона при нехватке памяти?
3. `btSocket` не закрывается в `onDestroy()`. Есть ли утечка Bluetooth-сокета?
4. `disconnect()` вызывается при `ACTION_STOP`, но при остановке сервиса системой (не через интент) — ресурсы не освобождаются. Нужен ли `onDestroy()` с очисткой?
5. Флаг `@Volatile private var paused` объявлен, но нигде не проверяется в `ScriptEngine.run()` или `executeScript()`. Зачем он, используется ли где-то ещё?

---

## Вопрос 3. ServerClient.kt: ретраи и 499

**Файл**: `server/ServerClient.kt`

- `connectTimeout=30s, readTimeout=180s, writeTimeout=60s`
- `downloadScript()` — один запрос, при ошибке → fallback-скрипт
- `uploadSession()` — 3 ретрая с `Thread.sleep(2000)`, OkHttp создаёт **новый запрос** на каждой попытке (метод `http.newCall(req).execute()` не переиспользует тело)

Вопросы:
1. `uploadSession` на каждом ретрае создаёт `http.newCall(req).execute()` — **но тело `req` уже прочитано**. OkHttp не буферизует тело запроса по умолчанию. Не будет ли второй и третий ретрай слать пустое тело? Как это работает в OkHttp 4.x?
2. Exponential backoff вместо фиксированных 2с — насколько критично для мобильной сети?
3. `downloadScript()` не имеет ретраев — при плохой сети сразу fallback. Это ок или стоит добавить 1-2 ретрая?
4. Клиент не добавляет `Content-Encoding: gzip` — стоит ли сжимать тело upload'а?
5. `resp.close()` вызывается до `resp.body?.string()`? Посмотри на порядок: сначала `.string()`, потом `.close()` — порядок правильный?
6. **Важно (из отчёта Опуса Q2):** сервер не имеет идемпотентности — при 499 и ретрае создаются дубликаты сессий и двойной расход LLM. Клиент не шлёт `request_id` (UUID) в теле запроса. Где и как правильно добавить `request_id`: в `uploadSession()` на клиенте и в `save_session()` на сервере?

---

## Вопрос 4. ObdDecoder.kt: корректность декодирования

**Файл**: `elm/ObdDecoder.kt`

Декодер поддерживает:
- VIN (mode 09 PID 02) — `decodeVin()`
- DTC stored/pending (mode 03/07) — `decodeDtc()`
- PID (mode 01) — `decodePid()` → `pidValue()`

Вопросы:
1. `decode()` проверяет `"490201" in clean` для VIN — но VIN может прийти с пробелами (`49 02 01`), которые уже удалены `replace(" ", "")`. Правильно ли?
2. `decodeDtc()` начинает с `i = 2` (skip byte count), но ISO 15765-2 ответы mode 03/07 могут иметь разный формат: `43 02 01 00...` (CAN 11-bit) vs `43 06 01 00...` (CAN 29-bit). Пропускает ли `i = 2` правильное число байт для всех протоколов?
3. `decodePid()` берёт `b0, b1` из hex, но некоторые PID (например, `0100` — supported PIDs) возвращают 4 байта, не 2. Обрабатывается ли это?
4. `pidValue()` поддерживает только 10 PID'ов (05, 0C, 0D, 11, 0B, 0F, 1F, 04, 06, 07). Для неподдерживаемых возвращает `"PID $pid: raw"`. Это ок? AndrOBD поддерживает 100+ PID'ов.
5. STFT/LTFT формула `(b0 - 128) * 100.0 / 128.0` — корректна? Стандарт SAE J1979: `(A - 128) * 100 / 128` для одного байта. Да, формула верная, но только для первого байта — второй байт (b1) игнорируется. Это правильно для STFT/LTFT?

---

## Вопрос 5. SessionDb.kt: схема и конкурентный доступ

**Файл**: `db/SessionDb.kt`

- SQLiteOpenHelper, версия 2
- `sessions` (id, script_json, title, created_at, uploaded, diagnosis, server_url)
- `responses` (id, session_id, step_id, cmd, raw, decoded, timestamp)
- `onUpgrade()` — DROP TABLE + пересоздание (теряет данные при миграции!)

Вопросы:
1. `onUpgrade()` делает DROP TABLE — при обновлении приложения все старые сессии теряются. Это сознательное решение или баг?
2. `getResponses()` и `addResponse()` используют `writableDatabase`/`readableDatabase` без явного закрытия. SQLiteOpenHelper кэширует соединение — это ок?
3. `getPendingSessions()` возвращает ID незагруженных сессий — но метод нигде не вызывается в коде. Мёртвый код или задел на будущее?
4. `created_at` хранится как `INTEGER` (unix timestamp через `strftime`), а на сервере — как `TEXT` (ISO 8601). Проблема при синхронизации?
5. Нет индексов кроме PRIMARY KEY. `WHERE session_id = ?` в `getResponses()` при большом числе ответов — нужен ли индекс?

---

## Вопрос 6. MainActivity.kt: чат и UI

**Файл**: `ui/MainActivity.kt`

- Одна кнопка «Диагностика», чат с LLM, история сессий
- `chatHistory` — `MutableList<Pair<String, String>>` в памяти
- Чат: `HttpURLConnection` напрямую (не OkHttp)

Вопросы:
1. `chatHistory` хранится в памяти Activity — при повороте экрана теряется? `onSaveInstanceState` сохраняет только `status_text`, но не `chatHistory`.
2. Чат использует `HttpURLConnection` вручную вместо `ServerClient` (OkHttp) — дублирование HTTP-логики. Почему?
3. `startTest()` (кнопка «Тест») дёргает `/ping` и `/ping-llm` — `/ping-llm` вызывает реальный LLM-запрос. Это расходует платный токен при каждом нажатии «Тест». Оправдано?
4. `registerScriptReceiver()` вызывает `registerReceiver` при каждом `startScript()`, но проверяет `scriptRegistered` — в `onCreate` он тоже регистрируется, а в `onDestroy` — отрегистрируется. Может ли быть двойная регистрация?
5. `btnClose` делает `visibility = GONE` и `tvStatus.text = "Готов"`, но не очищает `chatHistory` и не останавливает сервис. Это нормально?

---

## Вопрос 7. UploadProgress.kt: таймер и батарея

**Файл**: `script/UploadProgress.kt`

- Отдельный поток, `Thread.sleep(1000)` в цикле, шлёт broadcast каждую секунду
- `AtomicBoolean` для флага `running`

Вопросы:
1. Broadcast каждую секунду во время upload'а (может длиться до 180с) — насколько это затратно для батареи/UI-потока?
2. `thread(name = "UploadTimer", isDaemon = true)` — демон. Если upload завершился, но `stop()` не вызван (исключение), поток продолжит висеть?
3. Стоит ли использовать `Handler.postDelayed` вместо отдельного потока + sleep?

---

## Вопрос 8. Общая архитектура Android-приложения

**Файлы**: все *.kt

Вопросы:
1. `MainActivity` напрямую знает про `ScriptRunnerService` (через Intents), но также напрямую использует `SessionDb` для истории. Это нарушает single responsibility?
2. `ScriptEngine` получает `sendCommand` как лямбду — это хорошо для тестируемости. Но `ElmProtocol` жёстко привязан к `InputStream/OutputStream`. Можно ли его замокать для unit-тестов?
3. В проекте нет ViewModel, нет DI, нет Navigation Component. Для MVP с одной кнопкой это ок или уже пора?
4. Приложение требует minSdk 24 (Android 7.0). Поддерживает ли `BluetoothAdapter.getDefaultAdapter()` и `createRfcommSocketToServiceRecord` все устройства с Android 7+?
5. `usesCleartextTraffic` не объявлен в манифесте — значит по умолчанию `false` для Android 9+. Все запросы идут на `https://obdai.ru` — ок ли это для debug-сборки?
6. **Важно (из отчёта Опуса Q6):** серверные эндпоинты `/chat`, `/upload`, `/ping-llm` открыты без аутентификации. Клиент (`ServerClient` и `MainActivity.sendToLlm()`) не шлёт никакой API-ключ или токен приложения в заголовках. Как правильно добавить статический ключ приложения в APK (с учётом что его могут извлечь) и передавать в заголовке `X-Api-Key`? Достаточно ли этого для MVP или нужна подпись запросов?

---

## Формат ответа

Запиши ответ в файл `/home/naeel/elmer/doc/opus-review-android.md`.

По каждому вопросу:
- 🔴 Критическая проблема (если есть)
- 🟡 Потенциальная проблема / улучшение
- 🟢 Всё ок
- Конкретные рекомендации с примерами кода (Kotlin) где уместно
