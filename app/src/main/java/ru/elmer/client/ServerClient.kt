package ru.elmer.client

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Скачивает скрипт с сервера. При ошибке — fallback. */
    fun downloadScript(): String {
        Log.i(TAG, "Downloading script from $scriptUrl")
        return try {
            val req = Request.Builder().url(scriptUrl).build()
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

    /** Загружает батч ответов на сервер. Возвращает JSON-ответ или null. */
    fun uploadSession(sessionId: Long, responses: List<Map<String, String?>>,
                      clientInfo: Map<String, String> = emptyMap()): JSONObject? {
        Log.i(TAG, "Uploading session $sessionId (${responses.size} responses)")

        val json = JSONObject().apply {
            put("session_id", sessionId)
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
            put("client_info", JSONObject().apply {
                for ((k, v) in clientInfo) put(k, v)
            })
        }

        return try {
            val req = Request.Builder()
                .url("$serverUrl/api/v1/session/upload")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            try { JSONObject(body) } catch (_: Exception) { null }
        } catch (e: IOException) {
            Log.w(TAG, "Server unavailable: ${e.message}")
            null
        }
    }
}
