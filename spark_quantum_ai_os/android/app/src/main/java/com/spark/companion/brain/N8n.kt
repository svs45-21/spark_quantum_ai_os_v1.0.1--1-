package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Triggers n8n workflows through their webhook URLs, so Spark can drive
 * anything the user has built in n8n (email, calendar, home automation, any
 * API) and read back the result.
 */
class N8n(
    private val baseUrl: () -> String,
    private val authHeader: () -> String,
) {
    /** True once the user has set an n8n webhook address. */
    val configured: Boolean get() = baseUrl().isBlank().not()

    /** Builds the full webhook URL for [workflow] (a path or a full URL). */
    fun webhookUrl(workflow: String): String {
        val wf = workflow.trim()
        if (wf.startsWith("http://") || wf.startsWith("https://")) return wf
        val base = baseUrl().trim().trimEnd('/')
        val path = wf.trimStart('/')
        // Allow the base to already include /webhook.
        return if (base.contains("/webhook")) "$base/$path" else "$base/webhook/$path"
    }

    suspend fun trigger(workflow: String, input: String): JSONObject = runInterruptible(Dispatchers.IO) {
        val base = baseUrl().trim()
        if (base.isEmpty()) {
            return@runInterruptible JSONObject().put("ok", false).put("error", "No n8n webhook address is set in Spark settings.")
        }
        try {
            val conn = URI(webhookUrl(workflow)).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                authHeader().trim().takeIf { it.isNotEmpty() }?.let {
                    val (name, value) = if (it.contains(":")) it.substringBefore(":").trim() to it.substringAfter(":").trim()
                    else "Authorization" to it
                    conn.setRequestProperty(name, value)
                }
                val body = JSONObject().put("input", input).put("source", "spark").toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                when {
                    code in 200..299 -> JSONObject().put("ok", true).put("result", text.take(4000).ifBlank { "Workflow run." })
                    code == 404 -> JSONObject().put("ok", false)
                        .put("error", "No n8n workflow is listening at that name. Check the webhook path, and that the workflow is active.")
                    code == 401 || code == 403 -> JSONObject().put("ok", false)
                        .put("error", "n8n refused the request. Check the auth header in Spark settings.")
                    else -> JSONObject().put("ok", false).put("error", "n8n answered with error $code.")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", "Couldn't reach n8n. Is the server on and on the same network?")
        }
    }
}
