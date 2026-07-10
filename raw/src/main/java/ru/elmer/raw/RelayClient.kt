package ru.elmer.raw

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class RelayClient(context: Context) {
    companion object {
        private const val TAG = "RelayClient"
        private const val PREFS_NAME = "elmer_raw"
        private const val KEY_DEVICE_ID = "device_id"
        private const val MAX_RETRIES = 3
    }

    val deviceId: String

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = "android-${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            Log.i(TAG, "New deviceId: $id")
            id
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val serverUrl = Config.SERVER_URL

    private fun auth(b: Request.Builder) {
        if (Config.API_KEY.isNotEmpty()) b.header("X-Api-Key", Config.API_KEY)
    }

    fun hello(elmVersion: String, protocol: String, voltage: String): Boolean {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("elm_version", elmVersion)
            put("protocol", protocol)
            put("voltage", voltage)
        }
        return tryPost("$serverUrl/api/v1/elm/raw/hello", json.toString())
    }

    fun pollCommand(): JSONObject? = withRetry("poll") {
        val req = Request.Builder()
            .url("$serverUrl/api/v1/elm/raw/cmd?device_id=$deviceId")
            .also { auth(it) }.get().build()
        val resp = http.newCall(req).execute()
        if (resp.code == 204) { resp.close(); return@withRetry null }
        val body = resp.body?.string() ?: ""
        resp.close()
        if (body.isNotEmpty()) JSONObject(body) else null
    }

    fun postResponse(
        seq: Int, cmd: String, raw: String, prompt: Boolean,
        elapsedMs: Long, bytes: Int, error: String?
    ): Boolean {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("seq", seq); put("cmd", cmd); put("raw", raw)
            put("prompt", prompt); put("elapsed_ms", elapsedMs)
            put("bytes", bytes)
            if (error != null) put("error", error)
        }
        return tryPost("$serverUrl/api/v1/elm/raw/response", json.toString())
    }

    private fun tryPost(url: String, jsonBody: String): Boolean {
        for (attempt in 1..MAX_RETRIES) {
            try {
                val req = Request.Builder().url(url).also { auth(it) }
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = http.newCall(req).execute()
                val ok = resp.isSuccessful; resp.close()
                if (ok) return true
            } catch (e: IOException) {
                Log.w(TAG, "POST $url #$attempt: ${e.message}")
            }
            if (attempt < MAX_RETRIES)
                Thread.sleep(1000L * (1 shl (attempt - 1)))
        }
        return false
    }

    private fun <T> withRetry(tag: String, block: () -> T): T? {
        for (attempt in 1..MAX_RETRIES) {
            try { return block() }
            catch (e: IOException) {
                Log.w(TAG, "$tag #$attempt: ${e.message}")
            }
            if (attempt < MAX_RETRIES)
                Thread.sleep(1000L * (1 shl (attempt - 1)))
        }
        return null
    }
}
