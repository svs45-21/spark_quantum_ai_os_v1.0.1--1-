package com.spark.companion.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Talks to the SPARK server (api/server.py) running on the user's computer. */
class SparkLink(private val baseUrl: () -> String, private val token: () -> String) {

    /** Sends a request to the desktop SPARK and returns its reply text. */
    suspend fun ask(request: String): JSONObject = runInterruptible(Dispatchers.IO) {
        val base = baseUrl().trimEnd('/')
        if (base.isEmpty()) {
            return@runInterruptible JSONObject().put("ok", false).put("error", "No SPARK server address is set.")
        }
        try {
            val conn = URI("$base/chat").toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout = 180_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                token().takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                val body = JSONObject().put("text", request).put("source", "phone").toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                when {
                    code in 200..299 -> {
                        val json = JSONObject(text)
                        JSONObject().put("ok", true)
                            .put("reply", json.optString("reply"))
                            .put("finished", json.optBoolean("finished", true))
                    }
                    code == 401 || code == 403 ->
                        JSONObject().put("ok", false).put("error", "The SPARK server rejected the access token.")
                    else -> JSONObject().put("ok", false).put("error", "The SPARK server answered with error $code.")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", "Couldn't reach the SPARK server at $base. Is the computer on and on the same network?")
        }
    }

    /** Quick reachability check for the settings screen. */
    suspend fun ping(): Boolean = runInterruptible(Dispatchers.IO) {
        val base = baseUrl().trimEnd('/')
        if (base.isEmpty()) return@runInterruptible false
        try {
            val conn = URI("$base/").toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
