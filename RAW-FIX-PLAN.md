# Raw-реле — план исправлений (2026-07-10)

## Текущее состояние

v0.4.1-dev, 5 классов, отдельный APK. Тупой ретранслятор команд ELM327.

## Что надо сделать

### 1. deviceId в SharedPreferences (🔴 критично)

**Проблема:** `RelayClient.deviceId` генерится заново при каждом создании — сервер не может идентифицировать устройство.

**Файл:** `raw/.../RelayClient.kt`

**План:**
- Читать `deviceId` из SharedPreferences при создании
- Если нет — сгенерировать и сохранить
- Формат: `"android-XXXX"` (8 случайных символов)

### 2. X-Api-Key в RelayClient (🔴 критично)

**Проблема:** `BuildConfig.API_KEY` определён, но `RelayClient` его не шлёт. Запросы без аутентификации.

**Файл:** `raw/.../RelayClient.kt`

**План:**
- Добавить `authHeaders()` по аналогии с `app/ServerClient`
- Передавать `X-Api-Key` во всех запросах

### 3. Retry при ошибках HTTP (🟡 важно)

**Проблема:** При временной недоступности сервера запрос падает без повторных попыток.

**Файл:** `raw/.../RelayClient.kt`

**План:**
- 3 попытки с exponential backoff (1с, 2с, 4с)
- Для `hello`, `pollCommand`, `postResponse`

### 4. Убрать дублирование SERVER_URL (🟡 важно)

**Проблема:** URL захардкожен в `build.gradle.kts` и передаётся через Intent. Два источника правды.

**Файлы:** `raw/build.gradle.kts`, `raw/.../RawRelayService.kt`, `raw/.../MainActivity.kt`

**План:**
- Оставить только `BuildConfig.SERVER_URL`
- Убрать `EXTRA_SERVER_URL` из Intent
- RawRelayService читает из BuildConfig

### 5. drainInput() в write() (🟡 важно, сложно)

**Проблема:** `ElmProtocol.write()` вызывает `drainInput()` перед отправкой — может съедать ответ от предыдущей команды (Неудача #5).

**Файл:** `raw/.../ElmProtocol.kt`

**План:**
- Сверить с `app/.../ElmProtocol.kt`
- Убрать `drainInput()` из `write()`
- Дренаж только в `handle()` при необходимости

### 6. Общий Config для raw (🟢 косметика)

**Проблема:** Нет единого места для констант.

**Файл:** создать `raw/.../Config.kt`

**План:**
- Вынести `SERVER_URL`, `VERSION_NAME` и т.д. (или использовать BuildConfig)

## Порядок выполнения

1 → 2 → 3 → 4 → 5 → 6 (от критичного к косметике)

## Не делаем

- v1.5 клоны — ограничение железа, не чинится
- Общий ElmProtocol между app и raw — raw временный, не оправдано
