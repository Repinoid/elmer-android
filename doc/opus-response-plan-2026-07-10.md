# Ответ Opus — план рефакторинга (2026-07-10)

## Шаг 1: Убрать хардкод хоста
- Создать: `app/src/main/java/ru/elmer/client/Config.kt` — `object Config { const val HOST = "https://obdai.ru"; const val SCRIPT_URL = "$HOST/api/v1/script" }`
- Изменить: MainActivity.kt — все 5 конструкций `ServerClient("https://obdai.ru", ...)` и голые `java.net.URL("https://obdai.ru/...")` → `Config.HOST`/`Config.SCRIPT_URL`
- В ScriptRunnerService.kt три дефолта `"https://obdai.ru"` → `Config.HOST`
- Результат: хост в одном месте

## Шаг 2: DEFAULT_SCRIPT → assets
- Создать: `app/src/main/assets/default_script.json` — содержимое `DEFAULT_SCRIPT` из ScriptRunnerService.kt
- Изменить: добавить в `Config.kt` функцию `fun defaultScript(ctx: Context): String`
- Удалить константу `DEFAULT_SCRIPT` из companion object сервиса
- Результат: 25 строк JSON не висят в коде

## Шаг 3: Расширить ServerClient, убрать второй транспорт
- Изменить: ServerClient.kt — добавить `fun chat(question, history): String` и `fun getSessions(): JSONArray?`
- Добавить в `Config.kt` фабрику `fun client(ctx: Context) = ServerClient(Config.HOST, Config.SCRIPT_URL, defaultScript(ctx))`
- Результат: весь HTTP на OkHttp с `X-Api-Key`

## Шаг 4: MainActivity — выпилить HttpURLConnection
- Изменить: MainActivity.kt — `checkServer` → `Config.client(this).ping()`, `checkLlm` → `.pingLlm()`, чат → `.chat(...)`, `showHistory` → `.getSessions()`
- Все `ServerClient(...)` заменить на `Config.client(this)`
- Результат: один транспорт, все запросы аутентифицированы

## Шаг 5: ElmChecker — закрыть течь инкапсуляции
- Изменить: ElmChecker.kt — добавить `fun sendRaw(cmd: String): String` и публичный `fun close()`
- Пока НЕ удалять `getElm()`
- Результат: легальная точка отправки команд и явного освобождения сокета

## Шаг 6: Оживить ScriptRunnerService + handoff сокета
- Изменить: ScriptRunnerService.kt — fallback брать из `Config.defaultScript(this)` вместо константы
- Изменить: MainActivity.kt — перед запуском сервиса вызвать `elmChecker?.close()` (освободить RFCOMM), затем `startForegroundService(Intent(this, ScriptRunnerService::class.java).apply { action = ScriptRunnerService.ACTION_RUN; putExtra("elm_mac", ...); ... })`
- Результат: длинная диагностика идёт в foreground-сервисе (переживает поворот/сворачивание)

## Шаг 7: Удалить прямую диагностику из Activity
- Изменить: MainActivity.kt — удалить тело `runDiagnostics()` (~80 строк)
- В `onAction()` ветку `State.DTC` направить на запуск сервиса из шага 6
- Приём результата — уже есть `scriptStageReceiver`/`scriptStatusReceiver`
- Результат: диагностика в одном месте (сервис), дубль устранён, `MainActivity` худеет

## Шаг 8: Дин.тест — убрать getElm() совсем
- Изменить: MainActivity.kt — в `startDynamicRecording` и `checkEcu` заменить `checker.getElm()?.sendCommand(...)` на `checker.sendRaw(...)`
- Изменить: ElmChecker.kt — удалить `getElm()` после того как исчезли все вызовы
- Результат: `ElmProtocol` инкапсулирован в `ElmChecker`

## Шаг 9: Финальная чистка
- Удалить: пустую папку `app/src/main/java/ru/elmer/client/ui/parts/`
- Проверить: `UploadProgress`/`ScriptEngine` теперь снова живые (сервис их использует) — оставить
- `grep` по `obdai.ru`, `getElm`, `HttpURLConnection` в `app/src/main` должен вернуть 0 совпадений
- Результат: инварианты рефакторинга подтверждены поиском
