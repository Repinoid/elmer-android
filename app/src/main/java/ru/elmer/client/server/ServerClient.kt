package ru.elmer.client.server

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.elmer.client.BuildConfig
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/**
 * HTTP-клиент для сервера Elmer.
 *
 * Отвечает за:
 *   - скачивание скрипта (GET /api/v1/script)
 *   - загрузку батча сессии (POST /api/v1/session/upload)
 */
class ServerClient(
    private val serverUrl: String,
    private val scriptUrl: String,
    private val fallbackScript: String
) {
    companion object {
        private const val TAG = "ServerClient"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun authHeaders(builder: Request.Builder) {
        val key = BuildConfig.API_KEY
        if (key.isNotEmpty()) {
            builder.header("X-Api-Key", key)
        }
    }

    /** Скачивает скрипт с сервера. При ошибке — fallback. */
    fun downloadScript(): String {
        Log.i(TAG, "Downloading script from $scriptUrl")
        return try {
            val req = Request.Builder().url(scriptUrl).also { authHeaders(it) }.build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} — using fallback")
                fallbackScript
            } else body
        } catch (e: IOException) {
            Log.w(TAG, "Offline — using fallback: ${e.message}")
            fallbackScript
        }
    }

    /** Скачивает тестовый скрипт для отладки таймингов. */
    fun downloadTestScript(): String {
        val url = "$serverUrl/api/v1/script?mode=test&wait=400&repeat=10"
        Log.i(TAG, "Downloading test script from $url")
        return try {
            val req = Request.Builder().url(url).also { authHeaders(it) }.build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: "{}"
            resp.close()
            body
        } catch (e: IOException) {
            Log.w(TAG, "Test script failed: ${e.message}")
            "{}"
        }
    }

    /** Проверка доступности сервера. */
    fun ping(): PingResult {
        Log.i(TAG, "Ping server...")
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder().url("$serverUrl/api/v1/ping").also { authHeaders(it) }.build()
            val resp = http.newCall(req).execute()
            val ms = System.currentTimeMillis() - t0
            resp.close()
            if (resp.isSuccessful) PingResult(true, ms.toInt(), "")
            else PingResult(false, ms.toInt(), "HTTP ${resp.code}")
        } catch (e: IOException) {
            PingResult(false, (System.currentTimeMillis() - t0).toInt(), e.message ?: "?")
        }
    }

    /** Проверка доступности LLM. */
    fun pingLlm(): PingResult {
        Log.i(TAG, "Ping LLM...")
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder().url("$serverUrl/api/v1/ping-llm").also { authHeaders(it) }.build()
            val resp = http.newCall(req).execute()
            val ms = System.currentTimeMillis() - t0
            val body = resp.body?.string() ?: ""
            resp.close()
            if (resp.isSuccessful) {
                val j = try { JSONObject(body) } catch (_: Exception) { null }
                if (j != null && j.optBoolean("ok")) PingResult(true, j.optInt("ms", ms.toInt()), "")
                else PingResult(false, ms.toInt(), j?.optString("error", "?") ?: "?")
            } else PingResult(false, ms.toInt(), "HTTP ${resp.code}")
        } catch (e: IOException) {
            PingResult(false, (System.currentTimeMillis() - t0).toInt(), e.message ?: "?")
        }
    }

    data class PingResult(val ok: Boolean, val ms: Int, val error: String)

    /** Загружает батч ответов на сервер. Возвращает JSON-ответ или null. */
    fun uploadSession(sessionId: Long, responses: List<Map<String, String?>>,
                      clientInfo: Map<String, String> = emptyMap(),
                      carInfo: String = "",
                      dynamicSamples: List<List<Map<String, String?>>>? = null): JSONObject? {
        Log.i(TAG, "Uploading session $sessionId (${responses.size} responses)")

        // UUID генерируется один раз до ретраев (идемпотентность)
        val requestId = UUID.randomUUID().toString()

        val json = JSONObject().apply {
            put("request_id", requestId)
            put("session_id", sessionId)
            if (carInfo.isNotEmpty()) put("car_info", carInfo)
            put("responses", JSONArray().apply {
                for (r in responses) {
                    put(JSONObject().apply {
                        put("step_id", r["step_id"] ?: "")
                        put("cmd", r["cmd"] ?: "")
                        put("raw", r["raw"] ?: "")
                        put("decoded", r["decoded"] ?: "")
                        put("timestamp", r["timestamp"] ?: "")
                    })
                }
            })
            if (dynamicSamples != null && dynamicSamples.isNotEmpty()) {
                put("dynamic_samples", JSONArray().apply {
                    for (sample in dynamicSamples) {
                        put(JSONArray().apply {
                            for (r in sample) {
                                put(JSONObject().apply {
                                    put("step_id", r["step_id"] ?: "")
                                    put("cmd", r["cmd"] ?: "")
                                    put("raw", r["raw"] ?: "")
                                    put("decoded", r["decoded"] ?: "")
                                    put("timestamp", r["timestamp"] ?: "")
                                })
                            }
                        })
                    }
                })
            }
            put("client_info", JSONObject().apply {
                for ((k, v) in clientInfo) put(k, v)
            })
        }

        val req = Request.Builder()
            .url("$serverUrl/api/v1/session/upload")
            .header("Idempotency-Key", requestId)
            .also { authHeaders(it) }
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var lastEx: IOException? = null
        for (attempt in 1..3) {
            try {
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                resp.close()
                val result = try { JSONObject(body) } catch (_: Exception) { null }
                if (result != null) return result
                Log.w(TAG, "Upload attempt $attempt: invalid JSON response")
            } catch (e: IOException) {
                lastEx = e
                Log.w(TAG, "Upload attempt $attempt: ${e.message}")
                if (attempt < 3) {
                    val delay = 1000L * (1 shl (attempt - 1)) + Random.nextLong(0, 500)
                    Thread.sleep(delay)
                }
            }
        }
        Log.w(TAG, "Server unavailable after 3 attempts: ${lastEx?.message}")
        return null
    }

    /** Сохраняет response_time_ms в профиль ELM-устройства. */
    fun saveProfile(mac: String, responseTimeMs: Int) {
        Log.i(TAG, "Saving profile $mac responseTimeMs=$responseTimeMs")
        try {
            val json = JSONObject().apply { put("response_time_ms", responseTimeMs) }
            val req = Request.Builder()
                .url("$serverUrl/api/v1/elm/profile/$mac")
                .also { authHeaders(it) }
                .put(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            resp.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save profile: ${e.message}")
        }
    }

    /** Запрашивает профиль ELM по MAC. Возвращает response_time_ms или null. */
    fun getProfileResponseTime(mac: String): Int? {
        return try {
            val req = Request.Builder()
                .url("$serverUrl/api/v1/elm/profile/$mac")
                .also { authHeaders(it) }
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: "{}"
            resp.close()
            val j = JSONObject(body)
            if (j.has("response_time_ms")) j.getInt("response_time_ms") else null
        } catch (_: Exception) { null }
    }
}
