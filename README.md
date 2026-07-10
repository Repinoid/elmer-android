# elmAI Android

OBD2-диагностика автомобиля через ELM327 + LLM (DeepSeek).  
Два APK в одном проекте Gradle.

## Версии

| Модуль | applicationId | versionName | versionCode |
|--------|--------------|-------------|-------------|
| `:app` | `ru.elmer.client` | 1.18.0-dev | 38 |
| `:raw` | `ru.elmer.raw` | 0.4.1-dev | 18 |

---

## Структура проекта

```
android/
├── build.gradle.kts           # Корневой: AGP 8.2.0, Kotlin 1.9.21
├── settings.gradle.kts        # include(":app"), include(":raw")
├── gradle.properties
│
├── app/                       # Основное приложение (диагностика)
│   ├── build.gradle.kts       # minSdk 24, targetSdk 34
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── default_script.json  # Fallback-скрипт (офлайн)
│       ├── res/                    # Layouts, strings, themes
│       └── java/ru/elmer/client/
│           ├── Config.kt              # HOST, SCRIPT_URL, defaultScript(), client()
│           ├── db/
│           │   └── SessionDb.kt       # SQLite: sessions + responses
│           ├── elm/
│           │   ├── BtConnector.kt     # Bluetooth RFCOMM подключение
│           │   ├── ElmProtocol.kt     # Стейт-машина AndrOBD (ElmProt.java)
│           │   ├── ElmChecker.kt      # Проверка ELM: AT-команды, OBD, DTC, скорость
│           │   └── ObdDecoder.kt      # Декодер PID/DTC/VIN (object)
│           ├── script/
│           │   └── DynamicCollector.kt # Циклический опрос PID
│           ├── server/
│           │   └── ServerClient.kt    # HTTP к серверу (OkHttp)
│           └── ui/
│               ├── MainActivity.kt    # UI: индикаторы, кнопка-трансформер
│               ├── ChatController.kt  # Чат с LLM
│               ├── DiagnosisRunner.kt # Цикл диагностики
│               └── IndicatorBar.kt    # Светофоры (Сервер/ELM/ЭБУ/LLM)
│
└── raw/                       # Raw-реле (ретранслятор команд)
    ├── build.gradle.kts       # Отдельный APK
    └── src/main/java/ru/elmer/raw/
        ├── ElmProtocol.kt         # 1:1 копия app/ElmProtocol.kt
        ├── ElmActor.kt            # Single-thread executor
        ├── RawRelayService.kt     # Foreground-сервис: BT→poll→ответ
        ├── RelayClient.kt         # HTTP к /api/v1/elm/raw/*
        └── MainActivity.kt        # Минимальный UI
```

**Всего:** 12 классов в `:app`, 5 в `:raw`.

---

## Зависимости

```kotlin
// Общие для обоих модулей
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1
com.squareup.okhttp3:okhttp:4.12.0
org.json:json:20231013
```

Без Room, Coroutines, Hilt/Koin — намеренно. Минимальный стек.

---

## Архитектура `:app`

### Поток диагностики

```
MainActivity
  ├── IndicatorBar        ← 4 светофора (Сервер/ELM/ЭБУ/LLM)
  ├── ChatController      ← чат с LLM через ServerClient.chat()
  ├── DiagnosisRunner     ← полный цикл: скрипт → команды → upload
  ├── DynamicCollector    ← циклический опрос PID'ов
  └── ElmChecker          ← проверка ELM327
        ├── BtConnector   ← Bluetooth RFCOMM
        └── ElmProtocol   ← стейт-машина AndrOBD
              └── ObdDecoder ← декодирование ответов
```

### Кнопка-трансформер (стейт-машина)

| State | Текст | Действие |
|-------|-------|----------|
| INIT | ⚠️ ОШИБКИ | scanDtc() — mode 03 |
| DTC | 🔍 ДИАГНОСТИКА | DiagnosisRunner.run() |
| DIAG | ▶ СТАРТ | startDynamicRecording() |
| START | ⏹ СТОП | stopDynamicRecording() |

### HTTP-клиент (ServerClient)

Единый клиент на OkHttp. Все запросы с `X-Api-Key`.

| Метод | Эндпоинт | Назначение |
|-------|---------|-----------|
| ping() | GET /api/v1/ping | Проверка сервера |
| pingLlm() | GET /api/v1/ping-llm | Проверка LLM |
| downloadScript() | GET /api/v1/script | Скрипт диагностики |
| uploadSession() | POST /api/v1/session/upload | Батч ответов + LLM |
| chat() | POST /api/v1/chat | Чат с LLM |
| getSessions() | GET /api/v1/sessions | История сессий |
| saveProfile() | PUT /api/v1/elm/profile/:mac | Профиль скорости |
| getProfileResponseTime() | GET /api/v1/elm/profile/:mac | Чтение профиля |

## Архитектура `:raw`

Тупой ретранслятор. Сервер управляет, телефон передаёт.

```
RawRelayService (foreground)
  ├── ElmActor (single-thread executor)
  │     └── ElmProtocol (AndrOBD)
  └── RelayClient (HTTP поллинг)
        ├── POST /api/v1/elm/raw/hello    — «я готов»
        ├── GET  /api/v1/elm/raw/cmd       — забрать команду
        └── POST /api/v1/elm/raw/response  — вернуть ответ
```

Цикл: `hello → pollCommand → sendCommand → postResponse → pollCommand → ...`

---

## ELM327 — правила (НЕ НАРУШАТЬ)

Протокол — **только AndrOBD** (ElmProt.java, fr3ts0n/AndrOBD, 10 лет в проде).

**Init:** `ATSP0 → ATAT1 → ATST → ATS0 → ATL0 → ATE0`  
**НИКОГДА:** `ATI` в init, `ATST96`, `drainInput()` перед `write()`  
**handle():** STOPPED → только state, ERROR → ATWS, BUS_ERROR → ATPC+ATSP0  
**v1.5 клоны:** деградация после 10-12 команд (ограничение железа)

Подробно: `doc/failures-journal.md` (12 задокументированных провалов).

---

## Сборка

```bash
# Основной APK
cd android
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Raw-реле APK
./gradlew :raw:assembleDebug
# → raw/build/outputs/apk/debug/raw-debug.apk
```

Требования: JDK 17+, Android SDK 34, `local.properties` с `sdk.dir`.

---

## Деплой на сервер

```bash
# 1. Bump версии
cd android
# app/build.gradle.kts: versionCode++, versionName "X.Y+1.Z-dev"
# raw/build.gradle.kts: appVersionCode++, appVersionName "X.Y+1.Z-dev"

# 2. Коммит + пуш
git add -A && git commit -m "bump vX.Y+1.Z-dev" && git push origin opus-fixes

# 3. Залить исходники на сервер и собрать
cd /home/naeel/elmer
tar czf /tmp/android-src.tar.gz --exclude='.git' --exclude='build' --exclude='.gradle' android/
scp -i ~/.ssh/naeel_vm_id_ed25519 /tmp/android-src.tar.gz naeel@5.172.178.213:/tmp/
ssh -i ~/.ssh/naeel_vm_id_ed25519 naeel@5.172.178.213 "
  rm -rf /opt/elmer/android && tar xzf /tmp/android-src.tar.gz -C /opt/elmer/
  cd /opt/elmer/android && gradle wrapper --gradle-version 8.7
  export ANDROID_SDK_ROOT=\$HOME/android-sdk
  ./gradlew :app:clean :app:assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk /opt/elmer/web/static/
  ./gradlew :raw:clean :raw:assembleDebug
  cp raw/build/outputs/apk/debug/raw-debug.apk /opt/elmer/web/static/elm-raw-v022.apk
"
# 4. Обновить версию в /opt/elmer/web/templates/index.html
```

---

## Git

- **Remote:** `github.com/Repinoid/elmer-android`
- **Ветка:** `opus-fixes`
- **Формат коммитов:** `fix:`, `feat:`, `refactor:`, `bump:`, `docs:`, `style:`, `chore:`
- **Правило:** коммит + push после каждой правки

---

## Известные проблемы (TODO)

### `:app`
- MainActivity всё ещё 652 строки — динамический тест и история не вынесены
- Нет сохранения состояния при повороте экрана (диагностика в daemon-потоке)

### `:raw`
- **deviceId** генерится заново при каждом создании RelayClient — нужно SharedPreferences
- **Нет аутентификации** — BuildConfig.API_KEY не используется в RelayClient
- **Нет retry** при ошибках HTTP
- **SERVER_URL** захардкожен в build.gradle.kts и дублируется в Intent extra
- **drainInput() в write()** — потенциальный сдвиг буфера (Неудача #5)

### Ограничения железа
- v1.5 клоны деградируют после 10-12 команд (70-80% успех)
- Двигатель заглушен → PID не работают (STOPPED)

---

## Ключевые документы

| Файл | Содержание |
|------|-----------|
| `../AGENTS.md` | Полное описание всего проекта elmAI |
| `../doc/architecture.md` | Архитектура сервера и Android |
| `../doc/diagnostic-logic.md` | 5 режимов диагностики |
| `../doc/failures-journal.md` | 12 провалов raw-реле |
| `doc/opus-response-arch-2026-07-10.md` | Анализ архитектуры от Opus |
| `doc/opus-response-plan-2026-07-10.md` | План рефакторинга от Opus |
| `doc/opus-arch-questions-2026-07-10.md` | Вопросы Opus по архитектуре |
| `doc/opus-refactor-plan-2026-07-10.md` | Запрос плана рефакторинга |
| `doc/opus-review-android.md` | Ранний обзор от Opus (v0.35.0-dev) |
| `doc/opus-questions-android.md` | Ранние вопросы Opus |

---

## Контакты

- Сервер: `5.172.178.213`, домен `obdai.ru`
- GitHub: `github.com/Repinoid/elmer-android`
- Gitea (сервер): `gitea.services.ngcloud.ru/Nail/elmer`
