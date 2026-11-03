package com.eazyradius.crm.ez

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class EzApiException(message: String, val code: Int = 0) : Exception(message)

/** Talks to system/plugin/mobile_api.php on the PHPNuxBill panel. Uses only built-in Android classes. */
object EzApi {
    private const val PREFS = "ez_session"
    var baseUrl: String = ""
    var token: String = ""
    var onUnauthorized: (() -> Unit)? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl = p.getString("base", "") ?: ""
        token = p.getString("token", "") ?: ""
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("base", baseUrl).putString("token", token).apply()
    }

    fun clearToken(ctx: Context) {
        token = ""
        save(ctx)
    }

    fun normalize(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    /** Calls one plugin action. Throws EzApiException with the server's message when ok=false. */
    suspend fun call(
        action: String,
        params: Map<String, Any?> = emptyMap(),
        anonymous: Boolean = false
    ): JSONObject {
        try {
            return raw(action, params, anonymous)
        } catch (e: EzApiException) {
            if (e.code == 401 && !anonymous) onUnauthorized?.invoke()
            throw e
        }
    }

    private suspend fun raw(action: String, params: Map<String, Any?>, anonymous: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw EzApiException("Enter your panel address first")
            val url = URL("$baseUrl/?_route=plugin/mobile_api&a=" + URLEncoder.encode(action, "UTF-8"))
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                if (!anonymous && token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")

                val body = JSONObject()
                for ((k, v) in params) {
                    if (v != null) body.put(k, toJson(v))
                }
                if (!anonymous && token.isNotEmpty()) body.put("token", token)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw EzApiException(
                        "The server did not return JSON (HTTP $code). Check the panel address and that mobile_api.php is installed.",
                        code
                    )
                }
                if (!json.optBoolean("ok", false)) {
                    throw EzApiException(json.optString("error", "Request failed"), code)
                }
                json
            } finally {
                conn.disconnect()
            }
        }

    private fun toJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is Boolean, is Number, is String, is JSONObject, is JSONArray -> v
        else -> v.toString()
    }
}

/* ---- small JSON helpers ---- */

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

fun JSONObject.arr(k: String): List<JSONObject> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

fun JSONObject.strList(k: String): List<String> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).map { a.optString(it, "") }
}

fun ezLvl(mods: Map<String, String>, key: String): Int = when (mods[key]) {
    "manage" -> 3
    "view" -> 2
    "mine" -> 1
    else -> 0
}

fun pretty(s: String): String = s.replace('_', ' ').replaceFirstChar { it.uppercase() }
