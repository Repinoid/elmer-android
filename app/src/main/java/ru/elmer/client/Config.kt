package ru.elmer.client

import android.content.Context

object Config {
    const val HOST = "https://obdai.ru"
    const val SCRIPT_URL = "$HOST/api/v1/script"

    fun defaultScript(ctx: Context): String =
        ctx.assets.open("default_script.json").bufferedReader().use { it.readText() }

    fun client(ctx: Context) = server.ServerClient(HOST, SCRIPT_URL, defaultScript(ctx))
}
