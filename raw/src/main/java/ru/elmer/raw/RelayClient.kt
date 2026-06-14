package ru.elmer.raw

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * HTTP-клиент для Raw Relay — только cmd/response.
 *
 * GET  /api/v1/elm/raw/cmd       — забрать команду
 * POST /api/v1/elm/raw/response  — отправить ответ
 * POST /api/v1/elm/raw/hello     — зарегистрироваться
 */
class RelayClient(private val serverUrl: String) {
    companion object {
        private const val TAG = "RelayClient"
    }

    private val deviceId = "android-${UUID.randomUUID().toString().take(8)}"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun deviceId(): String = deviceId

    /** Сообщить серверу «я готов». */
    fun hello(elmVersion: String, protocol: String, voltage: String): Boolean {
        Log.i(TAG, "hello → server")
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("elm_version", elmVersion)
            put("protocol", protocol)
            put("voltage", voltage)
        }
        return try {
            val req = Request.Builder()
                .url("$serverUrl/api/v1/elm/raw/hello")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: IOException) {
            Log.w(TAG, "hello failed: ${e.message}")
            false
        }
    }

    /** Забрать команду из очереди. null = нет команды. */
    fun pollCommand(): JSONObject? {
        return try {
            val req = Request.Builder()
                .url("$serverUrl/api/v1/elm/raw/cmd?device_id=$deviceId")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            if (resp.code == 204) { resp.close(); return null }
            val body = resp.body?.string() ?: ""
            resp.close()
            if (body.isNotEmpty()) JSONObject(body) else null
        } catch (e: IOException) {
            Log.w(TAG, "poll failed: ${e.message}")
            null
        }
    }

    /** Отправить ответ ELM327 на сервер. */
    fun postResponse(seq: Int, cmd: String, raw: String, prompt: Boolean,
                     elapsedMs: Long, bytes: Int, error: String?): Boolean {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("seq", seq)
            put("cmd", cmd)
            put("raw", raw)
            put("prompt", prompt)
            put("elapsed_ms", elapsedMs)
            put("bytes", bytes)
            if (error != null) put("error", error)
        }
        return try {
            val req = Request.Builder()
                .url("$serverUrl/api/v1/elm/raw/response")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: IOException) {
            Log.w(TAG, "postResponse failed: ${e.message}")
            false
        }
    }
}
