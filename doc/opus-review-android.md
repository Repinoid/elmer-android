# Ответ Opus 4.8 — ревью elmer-android

> v0.35.0-dev, 31 мая 2026
> Проверены файлы: ElmProtocol.kt, ObdDecoder.kt, ScriptRunnerService.kt, ScriptEngine.kt,
> ServerClient.kt, SessionDb.kt, MainActivity.kt, UploadProgress.kt, AndroidManifest.xml

---

## Вопрос 1. Стейт-машина ElmProtocol.kt

### 1.1 `startsWith("ERROR") && !startsWith("DATA ERROR")`
🟡 **Потенциальная проблема.** `handle()` работает с уже распарсенными строками-ответами, а не с PID-именами, поэтому коллизии с «ERROR_xxx» в данных нет — декодирование имён происходит позже в `ObdDecoder`. НО: реальные ELM-ошибки не всегда начинаются с `ERROR`. Например `?` (неизвестная команда), `UNABLE TO CONNECT` (ловится в `isBusError`), `<RX ERROR` (с префиксом `<`). Строка `<DATA ERROR` из-за лидирующего `<` **не** сматчится `startsWith("DATA ERROR")`. ELM327 при ошибке кадра иногда шлёт `<` перед сообщением.

Рекомендация — нормализовать перед классификацией:
```kotlin
val u = raw.uppercase().trim().trimStart('<', '>').trim()
```

### 1.2 `sendCommand()` безусловно ставит READY после exec()
🔴 **Критично — маскирование ошибки.**
```kotlin
fun sendCommand(cmd: String): String {
    if (state == State.ERROR) recover()
    state = State.BUSY
    val result = exec(cmd, timeoutMs)   // exec может выставить State.ERROR/DISCONNECTED
    state = State.READY                  // ← затирает ошибку
    return result
}
```
`exec()` при исчерпании ретраев ставит `state = State.ERROR`, а `handle()` — `ERROR`/`DISCONNECTED`. Следующая строка безусловно перетирает это на `READY`. Ошибка «теряется» до следующего вызова. В AndrOBD состояние не сбрасывается слепо.

Рекомендация:
```kotlin
val result = exec(cmd, timeoutMs)
if (state == State.BUSY) state = State.READY   // только если не было ошибки
return result
```

### 1.3 `init()` не проверяет результат AT-команд
🟡 **Поведение AndrOBD, но рискованное.** AndrOBD действительно прогоняет init-цепочку «оптимистично», полагаясь на то, что первые реальные OBD-команды отловят BUS ERROR. Для MVP допустимо, но `ATSP0` (выбор протокола) стоит проверять — если адаптер вернул `?`, дальнейшие команды бессмысленны. Минимум — логировать ответ и считать в `errorCount`.

### 1.4 Нет сброса input-буфера перед write()
🟡 **Риск десинхронизации есть.** В `read()` чтение идёт до `>` (prompt), но если предыдущая команда оставила хвост в буфере (например после таймаута пришёл запоздалый ответ), он прилипнет к следующему чтению. Рекомендация — дренировать буфер перед записью:
```kotlin
private fun write(cmd: String) {
    while (input.available() > 0) input.read()   // drain stale bytes
    output.write((cmd + "\r").toByteArray())
    output.flush()
}
```

### 1.5 `BUFFER FULL` → warm start
🟡 **Спорно.** В AndrOBD `BUFFER FULL` — это переполнение буфера ELM при большом ответе, лечится **повторным запросом**, а не полным `ATWS` (warm start сбрасывает протокол и теряет адаптацию таймингов). Здесь `BUFFER FULL` попадает в `isDataError` → `ATWS`, что излишне тяжело. Лучше выделить:
```kotlin
u.startsWith("BUFFER FULL") -> { increaseTimeout() /* retry */ }
```

---

## Вопрос 2. ScriptRunnerService — жизненный цикл

### 2.1 `START_STICKY` + null intent
🔴 **Падение при пересоздании.** При рестарте системой `onStartCommand` получает `intent == null`. Сейчас `when (intent?.action)` отрабатывает в `else`-ветку (ничего не делает) и возвращает `START_STICKY` — краша нет, но сервис висит в foreground без работы и без уведомления о реальной задаче. Лучше:
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) { stopSelf(); return START_NOT_STICKY }
    ...
}
```
Для разовой диагностики вообще логичнее `START_NOT_STICKY` — нет смысла воскрешать прерванную сессию.

### 2.2 Демон-поток `ScriptRunner`
🟡 Демон-поток живёт пока жив процесс. Если Activity убита, а сервис foreground — процесс жив, поток работает. Но при нехватке памяти система может убить весь процесс (вместе с потоком) несмотря на foreground. Это нормально для разовой задачи. Замечание: исключения внутри потока никуда не пробрасываются — добавьте `try/catch` обёртку с `errorDone()`.

### 2.3 / 2.4 `btSocket` и `onDestroy()`
🟢 **Уже закрывается.** `onDestroy()` вызывает `disconnect()`, который закрывает `btSocket` и снимает foreground. Утечки сокета нет. ✅

### 2.5 Флаг `paused`
🔴 **Мёртвый код / недоделанная фича.** `paused` выставляется в `false` по `ACTION_RESUME`, но **нигде не проверяется** — ни в `ScriptEngine.run()`, ни в `executeScript()`. Механизм паузы «водитель ответил» (broadcast `BROADCAST_PROMPT`, `scriptPromptReceiver` в UI) фактически не реализован на стороне движка. Либо удалить флаг и UI-приёмник промптов, либо доделать: `ScriptEngine` должен уметь блокироваться на шаге до сброса `paused`.

---

## Вопрос 3. ServerClient — ретраи и идемпотентность

### 3.1 Повторное использование тела запроса на ретраях
🟢 **Работает корректно.** Тело создано через `String.toRequestBody(...)` — это `RequestBody` поверх неизменяемой строки. В OkHttp 4.x такой `RequestBody` **stateless**: `writeTo()` вызывается заново на каждой попытке и пишет ту же строку. Пустого тела на 2-3 ретрае **не будет**. (Проблема была бы только с одноразовым стримом, например `InputStream.source()`.)

### 3.2 Exponential backoff
🟡 Для мобильной сети фиксированные 2с приемлемы, но джиттер + рост лучше против «retry storm»:
```kotlin
if (attempt < 3) Thread.sleep(1000L * (1 shl (attempt - 1)) + Random.nextLong(0, 500))
```

### 3.3 `downloadScript()` без ретраев
🟢 Это сознательный и правильный выбор: есть качественный `DEFAULT_SCRIPT` fallback, поэтому мгновенный переход к нему при оффлайне — корректное поведение. 1 ретрай можно добавить, но не критично.

### 3.4 gzip на upload
🟡 При `count * 200` байт типичный батч < 5 KB — выигрыш от gzip минимален, а overhead на сжатие/совместимость с nginx добавляет риск. Не нужно для MVP.

### 3.5 Порядок `.string()` / `.close()`
🟢 **Корректно.** `val body = resp.body?.string()` сначала читает (и закрывает поток тела), затем `resp.close()`. Порядок верный, двойного закрытия нет. ✅

### 3.6 Идемпотентность / `request_id`
🔴 **Критично (подтверждаю отчёт Q2).** При 499/таймауте и ретрае сервер создаёт дубликат сессии и повторно тратит LLM-токен. Клиент должен генерировать UUID **один раз до цикла ретраев** и слать его в теле:

```kotlin
fun uploadSession(...): JSONObject? {
    val requestId = java.util.UUID.randomUUID().toString()   // один на все 3 попытки
    val json = JSONObject().apply {
        put("request_id", requestId)
        put("session_id", sessionId)
        ...
    }
    val req = Request.Builder()
        .url("$serverUrl/api/v1/session/upload")
        .header("Idempotency-Key", requestId)
        .post(json.toString().toRequestBody("application/json".toMediaType()))
        .build()
    ...
}
```
На сервере (`save_session()`): UNIQUE-индекс по `request_id`, при повторе — вернуть **сохранённый** результат (включая готовый диагноз), не вызывая LLM повторно:
```python
existing = db.execute("SELECT diagnosis FROM sessions WHERE request_id=?", [rid]).fetchone()
if existing:
    return jsonify(diagnosis=existing["diagnosis"], llm_success=True, cached=True)
```

---

## Вопрос 4. ObdDecoder — корректность декодирования

### 4.1 VIN с пробелами
🟢 **Корректно.** `replace(" ", "")` снимает пробелы до проверки `"490201" in clean`, плюс убраны `:` (ISO-TP индикаторы кадров `0:`, `1:`...). Работает и для multi-frame. ✅

### 4.2 `decodeDtc()` начинает с `i = 2`
🟡 **Не всегда верно.** `hex = clean.substring(2)` снимает байт режима (`43`), затем `i = 2` снимает **байт count** (число DTC). Это корректно для классического формата `43 NN <dtc>...`. Но:
- Multi-frame CAN ISO-TP: ответ может содержать байты длины PCI (`007`, `10 0E`...), которые здесь **не вычищены** (убраны только пробелы и `:`). Тогда `i=2` указывает не на тот байт.
- Некоторые адаптеры на mode 03 не шлют байт count вовсе.

Для надёжности стоит парсить DTC по парам байт от конца режима и отбрасывать `0000`, что код уже делает (фильтр `P0000`). Главный риск — невычищенные PCI-заголовки multi-frame. Для коротких ответов (1-2 DTC, single frame) работает.

### 4.3 PID `0100` (4 байта supported)
🟡 `decodePid()` читает только `b0, b1`. PID `00/20/40...` (битовые маски supported PIDs, 4 байта) не входят в `pidValue()` → вернётся `"PID 00: raw"`. Поскольку скрипт их не запрашивает — не баг сейчас, но при расширении скрипта декодер их не покажет.

### 4.4 Только 10 PID
🟢 **Ок для MVP.** Скрипт `DEFAULT_SCRIPT` запрашивает ровно эти PID. Для неподдерживаемых — `"PID $pid: raw"`, сырьё всё равно уходит на сервер и в LLM. Расширять по мере надобности.

### 4.5 STFT/LTFT формула
🟢 Формула `(A - 128) * 100 / 128` верна по SAE J1979. Для PID 06/07 это однобайтовые значения (банк 1), `b1` игнорируется правильно. ✅ (Замечание: PID 06/07 — это банк 1 short/long; банки 2 — это 08/09, в скрипте их нет.)

---

## Вопрос 5. SessionDb — схема и доступ

### 5.1 `onUpgrade()` DROP TABLE
🔴 **Потеря данных при апдейте.** Любое повышение `DB_VERSION` сотрёт всю историю пользователя. Для продакшена недопустимо. Минимальная безопасная миграция:
```kotlin
override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
    if (oldV < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN server_url TEXT")
    // будущие версии — ALTER, не DROP
}
```

### 5.2 Без явного закрытия соединений
🟢 **Ок.** `SQLiteOpenHelper` кэширует одно соединение на хелпер; курсоры закрываются (`cursor.close()`). Не закрывать сам `db` — правильно. ⚠️ Замечание: `SessionDb` создаётся и в сервисе, и в `MainActivity.showHistory()` — два хелпера на одну БД. Лучше один экземпляр (синглтон), иначе при одновременном write возможен `SQLiteDatabaseLockedException`.

### 5.3 `getPendingSessions()` — мёртвый код
🟡 Метод нигде не вызывается. Это задел под «дослать неотправленные сессии при следующем запуске», но фича не реализована. Либо удалить, либо доделать ретрай-аплоад оффлайн-сессий в `onCreate` сервиса.

### 5.4 `created_at` INTEGER vs сервер TEXT
🟡 Несогласованность форматов. На клиенте unix-секунды, на сервере ISO 8601. При синхронизации сервер должен конвертировать. Лучше слать с клиента ISO-8601 (или явно `unix_ts` с понятным именем) в `client_info`/`responses`, чтобы не было путаницы с часовыми поясами. Сейчас `timestamp` ответов уходит как строка unix-секунд — сервер должен это знать.

### 5.5 Индексы
🟡 `WHERE session_id = ?` в `getResponses()` без индекса — full scan. При сотнях ответов на сессию заметно. Добавьте:
```kotlin
db.execSQL("CREATE INDEX idx_resp_session ON responses(session_id)")
```

---

## Вопрос 6. MainActivity — чат и UI

### 6.1 `chatHistory` теряется при повороте
🔴 `onSaveInstanceState` сохраняет только `status_text`, `chatHistory` живёт в поле Activity → при повороте/пересоздании теряется, и LLM теряет контекст диалога. Варианты: сохранить в Bundle (сериализовать в JSON), либо вынести в `ViewModel` (`SavedStateHandle`). Минимум:
```kotlin
outState.putString("chat", JSONArray(chatHistory.map { ... }).toString())
```

### 6.2 Чат на `HttpURLConnection` вместо OkHttp
🟡 Дублирование HTTP-логики и таймаутов. `ServerClient` уже инкапсулирует OkHttp — `sendToLlm()` и `startTest()` стоит перевести на него (общие таймауты, ретраи, будущий `X-Api-Key`). Сейчас три места шлют HTTP по-разному.

### 6.3 `startTest()` дёргает `/ping-llm` (платный токен)
🔴 **Расход денег на каждом «Тест».** `/ping-llm` делает реальный LLM-запрос. Кнопку «Тест» пользователь может жать многократно. Варианты: на сервере сделать `/ping-llm` дешёвой проверкой доступности (HEAD к API провайдера / кэш на 60с), либо на клиенте троттлить (не чаще раза в N минут) и предупреждать.

### 6.4 Двойная регистрация receiver
🟡 `scriptStatusReceiver`/`scriptStageReceiver` защищены флагом `scriptRegistered` — двойной регистрации этих двух нет. НО: `statusReceiver` (отдельный, для `BROADCAST_STATUS`) регистрируется в `onCreate` **и** `scriptStatusReceiver` тоже слушает `BROADCAST_STATUS` — два приёмника на один экшен → **каждое сообщение `log()` обработается дважды** (дублирование строк в UI). Также `scriptPromptReceiver` регистрируется... — на самом деле **нигде не регистрируется**, только разрегистрируется в `onDestroy`. Промпты не приходят (связано с мёртвым `paused`, Q2.5).

Рекомендация: оставить один приёмник на `BROADCAST_STATUS`.

### 6.5 `btnClose` не чистит `chatHistory` и не стопит сервис
🟡 Кнопка ✕ только прячет UI и пишет «Готов». Если сервис ещё работает — он продолжит и пришлёт новые статусы поверх. Для «закрыть» логично слать `ACTION_STOP` в сервис. `chatHistory` чистить не обязательно (диалог отдельный от диагностики), но сервис стоит остановить.

---

## Вопрос 7. UploadProgress — таймер и батарея

### 7.1 Broadcast каждую секунду до 180с
🟡 Незначительно для батареи (≤180 broadcast на сессию), но это локальный `sendBroadcast` с `setPackage` — дёшево. Не проблема.

### 7.2 Поток висит при исключении
🟡 **Реальный риск.** В `executeScript()` `progress.start()` → `uploadSession()` → `progress.stop()`. Если `uploadSession()` бросит непойманное исключение, `stop()` не вызовется и `UploadTimer` останется крутиться (демон, до смерти процесса). Оберните в `try/finally`:
```kotlin
val progress = UploadProgress(...); progress.start()
val resp = try { client.uploadSession(...) } finally { progress.stop() }
```

### 7.3 `Handler.postDelayed` вместо потока
🟢 Можно, но текущий вариант с `AtomicBoolean` + демон-поток корректен и проще. Не критично. Главное — гарантировать `stop()` (см. 7.2).

---

## Вопрос 8. Общая архитектура

### 8.1 MainActivity знает про Service и SessionDb
🟡 Нарушение SRP есть, но для MVP с одним экраном терпимо. При росте — вынести историю в `Repository`, а UI-логику в `ViewModel`.

### 8.2 ElmProtocol замокать для тестов
🟡 `ScriptEngine` отлично тестируется (lambdas) — это сильная сторона. `ElmProtocol` жёстко завязан на `InputStream/OutputStream`, но это **тестируемо**: подайте `ByteArrayInputStream`/`ByteArrayOutputStream` с заскриптованными ответами ELM. Интерфейс выделять не нужно, потоки — уже абстракция. Рекомендую написать unit-тест на `handle()`-классификацию и таймаут-адаптацию.

### 8.3 Нет ViewModel/DI/Navigation
🟢 Для MVP с одной кнопкой — ок. ViewModel стоит ввести первым (решает 6.1, 6.4). DI/Navigation — преждевременно.

### 8.4 minSdk 24 + BluetoothAdapter.getDefaultAdapter
🟡 `getDefaultAdapter()` deprecated с API 31, но работает на 24+. `createRfcommSocketToServiceRecord` + reflection-fallback `createRfcommSocket(1)` — стандартный надёжный приём для китайских ELM327, покрывает большинство устройств. Замечание: на Android 12+ (API 31) для `connect()` нужен рантайм-`BLUETOOTH_CONNECT` — в манифесте он есть, проверьте что он реально запрашивается в рантайме (в показанном коде `MainActivity` запрос пермишенов есть в константах, но самого `requestPermissions` в прочитанном фрагменте не видно — убедитесь, что вызывается).

### 8.5 `usesCleartextTraffic` не объявлен
🟢 По умолчанию `false` на API 28+, все запросы на `https://obdai.ru` — ок. ✅ Замечание: жёстко зашитый хост `obdai.ru` в нескольких местах (Service, MainActivity) — вынесите в `BuildConfig`/константу.

### 8.6 Эндпоинты без аутентификации (X-Api-Key)
🔴 **Критично (подтверждаю отчёт Q6).** `/chat`, `/upload`, `/ping-llm` открыты → любой может тратить ваши LLM-токены.

Статический ключ в APK **извлекаем** (reverse engineering), поэтому он защищает только от случайных/ленивых злоупотреблений, не от целевой атаки. Для MVP это разумный первый рубеж:

```kotlin
// BuildConfig.API_KEY из gradle (не в git, через local.properties / CI secret)
val req = Request.Builder()
    .url(...)
    .header("X-Api-Key", BuildConfig.API_KEY)
    .post(...)
    .build()
```
build.gradle.kts:
```kotlin
buildConfigField("String", "API_KEY", "\"${project.findProperty("ELMER_API_KEY") ?: ""}\"")
```
Сервер — отклонять без верного `X-Api-Key` (401) + **rate-limit по IP/ключу** + квота на LLM. Для серьёзной защиты позже: подпись запроса (HMAC от тела + nonce + timestamp), либо Play Integrity API / device attestation. Но для MVP: `X-Api-Key` + rate-limit + серверная квота на LLM — достаточный минимум, при этом главную защиту денег даёт именно **серверный лимит**, а не ключ.

---

## Сводка приоритетов

🔴 **Чинить сейчас:**
1. `request_id`/идемпотентность upload (Q3.6) — дубли сессий и двойной расход LLM.
2. `X-Api-Key` + серверный rate-limit/квота (Q8.6) — открытые платные эндпоинты.
3. `sendCommand()` маскирует ERROR-состояние (Q1.2).
4. `onUpgrade()` DROP TABLE — потеря истории (Q5.1).
5. `/ping-llm` тратит токен на каждом «Тест» (Q6.3).
6. Двойной приёмник `BROADCAST_STATUS` → дублирование строк (Q6.4).

🟡 **Желательно:**
- `paused` — мёртвый код / недоделанная пауза (Q2.5, Q6.4-prompt).
- `try/finally` вокруг `UploadProgress` (Q7.2).
- Дренаж BT-буфера перед write (Q1.4).
- `chatHistory` в onSaveInstanceState/ViewModel (Q6.1).
- Индекс `responses(session_id)` (Q5.5).
- Единый HTTP-клиент (OkHttp) для чата/теста (Q6.2).
- null-intent guard в onStartCommand (Q2.1).

🟢 **Хорошо как есть:** закрытие сокета (2.3), повторное тело OkHttp (3.1), порядок string/close (3.5), VIN-декод (4.1), STFT/LTFT (4.5), cleartext off (8.5), fallback-скрипт без ретраев (3.3).
