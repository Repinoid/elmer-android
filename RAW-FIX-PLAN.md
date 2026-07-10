# Raw-реле — план исправлений (2026-07-10)

## Текущее состояние

v0.4.1-dev, 6 классов (Config.kt добавлен), отдельный APK.

## Выполнено (2026-07-10)

- [x] **Config.kt** — единый источник SERVER_URL, API_KEY, VERSION_NAME
- [x] **deviceId в SharedPreferences** — RelayClient(context), сохраняется между запусками
- [x] **X-Api-Key** — auth() добавляет заголовок ко всем запросам
- [x] **Retry HTTP** — 3 попытки с exponential backoff (1s, 2s, 4s)
- [x] **SERVER_URL** — только BuildConfig.SERVER_URL, EXTRA_SERVER_URL удалён
- [x] **RawRelayService + MainActivity** — полные комментарии, все зависимости явные
- [x] **drainInput()** — закомментирован TODO для портирования detectClone() из app ElmProtocol

## Осталось

- [ ] **detectClone() из app** — v1.5 клоны вешаются на ATAT1 без проверки isClone
- [ ] **drainInput() в write()** — потенциальный сдвиг буфера (Неудача #5), требует портирования handle() из app

## Не делаем

- v1.5 клоны — деградация после 10-12 команд (ограничение железа)
- Общий ElmProtocol между app и raw — raw временный, не оправдано
