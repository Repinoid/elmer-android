package ru.elmer.raw

/**
 * Конфигурация raw-реле.
 *
 * Единый источник: SERVER_URL, API_KEY, VERSION_NAME.
 * Используется RawRelayService, RelayClient, MainActivity.
 *
 * НЕ дублировать URL в Intent extra — всегда брать отсюда.
 */
object Config {
    /** URL сервера — из BuildConfig (задаётся в build.gradle.kts) */
    val SERVER_URL: String = BuildConfig.SERVER_URL

    /** API-ключ для аутентификации запросов */
    val API_KEY: String = BuildConfig.API_KEY

    /** Версия приложения */
    const val VERSION_NAME: String = BuildConfig.VERSION_NAME
}
