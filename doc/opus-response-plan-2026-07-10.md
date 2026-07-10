# Ответ Opus — план рефакторинга (2026-07-10)

1. **Убрать хардкод хоста.**
   - Создать: `app/src/main/java/ru/elmer/client/Config.kt` — `object Config { const val HOST = "https://obdai.ru"; const val SCRIPT_URL = "$HOST/api/v1/script" }`.
   - Изменить: MainActivity.kt — все 5 конструкций `ServerClient("https://obdai.ru", "https://obdai.ru/api/v1/script", "")` (в `checkElm`, `runDiagnostics`, `startDynamicRecording`, `onSend`, `syncPendingSessions`) и голые `java.net.URL("https://obdai.ru/...")` (в `checkServer`, `checkLlm`, `onSend` чат, `showHistory`) — на `Config.HOST`/`Config.SCRIPT_URL`. В ScriptRunnerService.kt три дефолта `"https://obdai.ru"` → `Config.HOST`.
   - Результат: хост в одном месте, никаких «obdai.ru» по коду.

2. **DEFAULT_SCRIPT → assets.**
   - Создать: `app/src/main/assets/default_script.json` — содержимое `DEFAULT_SCRIPT` из ScriptRunnerService.kt.
   - Изменить: добавить в `Config.kt` функцию `fun defaultScript(ctx: Context): String = ctx.assets.open("default_script.json").bufferedReader().use { it.readText() }`. Удалить константу `DEFAULT_SCRIPT` из companion object сервиса.
   - Результат: 25 строк JSON не висят в коде; fallback читается из assets.

3. **Расширить ServerClient, убрать второй транспорт.**
   - Изменить: ServerClient.kt — добавить `fun chat(question: String, history: List<Pair<String,String>>): String` (перенести тело `onSend`-чата, POST `/api/v1/chat`, с `authHeaders`) и `fun getSessions(): JSONArray?` (перенести из `showHistory`, GET `/api/v1/sessions`, с `authHeaders`). Добавить в `Config.kt` фабрику `fun client(ctx: Context) = ServerClient(Config.HOST, Config.SCRIPT_URL, defaultScript(ctx))`.
   - Результат: весь HTTP на OkHttp с `X-Api-Key`; готовые методы для Activity.

4. **MainActivity: выпилить HttpURLConnection.**
   - Изменить: MainActivity.kt — `checkServer` → `Config.client(this).ping()`; `checkLlm` → `.pingLlm()`; чат в `onSend` → `.chat(...)`; `showHistory` серверная ветка → `.getSessions()`. Все `ServerClient(...)` заменить на `Config.client(this)`.
   - Результат: один транспорт, все запросы аутентифицированы (закрывает баг №2 из журнала), пинги LLM/сервера получают `X-Api-Key`.

5. **ElmChecker: закрыть течь инкапсуляции.**
   - Изменить: ElmChecker.kt — добавить `fun sendRaw(cmd: String): String = elm?.sendCommand(cmd) ?: "(no elm)"` и публичный `fun close()` (обёртка над приватным `disconnect()`). Пока НЕ удалять `getElm()`.
   - Результат: появилась легальная точка отправки команд и явного освобождения сокета (нужна для handoff в шаге 6).

6. **Оживить ScriptRunnerService + handoff сокета.**
   - Изменить: ScriptRunnerService.kt — в `executeScript` fallback брать из `Config.defaultScript(this)` вместо константы; проверить, что `elm_mac` читается из intent (уже читается, ScriptRunnerService.kt).
   - Изменить: MainActivity.kt — перед запуском сервиса вызвать `elmChecker?.close()` (освободить RFCOMM, иначе сервис не откроет второй сокет к тому же ELM), затем `startForegroundService(Intent(this, ScriptRunnerService::class.java).apply { action = ScriptRunnerService.ACTION_RUN; putExtra("elm_mac", elmDevice?.address); putExtra(EXTRA_CAR_INFO, carInfo); dynamicSamples?.let { putExtra(EXTRA_DYNAMIC_SAMPLES, ...toJson) } })`.
   - Результат: длинная диагностика идёт в foreground-сервисе (переживает поворот/сворачивание), сокет не конфликтует.

7. **Удалить прямую диагностику из Activity.**
   - Изменить: MainActivity.kt — удалить тело `runDiagnostics()` (~80 строк, MainActivity.kt); в `onAction()` ветку `State.DTC` направить на запуск сервиса из шага 6. Приём результата — уже есть `scriptStageReceiver`/`scriptStatusReceiver`.
   - Результат: диагностика в одном месте (сервис), дубль `runDiagnostics`↔`executeScript` устранён; `MainActivity` худеет.

8. **Дин.тест: убрать getElm() совсем.**
   - Изменить: MainActivity.kt — в `startDynamicRecording` и `checkEcu` заменить `checker.getElm()?.sendCommand(...)`/`elmProto.sendCommand(...)` на `checker.sendRaw(...)`; убрать `val elmProto = checker.getElm()`.
   - Изменить: ElmChecker.kt — удалить `getElm()` после того как исчезли все вызовы.
   - Результат: `ElmProtocol` инкапсулирован в `ElmChecker`, дин.тест и чат сохранены.

9. **Финальная чистка.**
   - Удалить: пустую папку `app/src/main/java/ru/elmer/client/ui/parts/`.
   - Проверить: `UploadProgress`/`ScriptEngine` теперь снова живые (сервис их использует) — оставить. `grep` по `obdai.ru`, `getElm`, `HttpURLConnection` в `app/src/main` должен вернуть 0 совпадений.
   - Результат: мёртвой пустой папки нет; инварианты рефакторинга подтверждены поиском.
