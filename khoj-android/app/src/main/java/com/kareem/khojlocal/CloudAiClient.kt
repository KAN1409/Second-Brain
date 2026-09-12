package com.kareem.khojlocal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "").orEmpty()
        set(value) = prefs.edit().putString("endpoint", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "").orEmpty()
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    fun isConfigured(): Boolean = endpoint.isNotBlank() && model.isNotBlank()
}

class CloudAiClient(private val settings: AiSettings) {
    fun answer(question: String, evidence: List<SearchHit>): String {
        require(settings.isConfigured()) { "AI endpoint is not configured" }
        val evidenceText = evidence.take(8).joinToString("\n\n") { hit ->
            "[memory:${hit.memory.id}] ${hit.memory.title}\n${hit.memory.body.take(5000)}"
        }
        val system = """
            You are the private assistant inside Khoj Local. Answer only from the supplied local-memory evidence.
            If the evidence is insufficient, say that clearly. Never invent facts.
            Cite useful evidence inline using [memory:ID]. Keep the answer concise and practical.
        """.trimIndent()
        val user = "Question:\n$question\n\nLocal memory evidence:\n$evidenceText"
        return request(system, user)
    }

    fun testConnection(): String {
        require(settings.isConfigured()) { "Enter endpoint and model first" }
        val reply = request(
            "You are a connection test. Reply with exactly: Connected",
            "Confirm the connection.",
            maxTokens = 16,
        )
        return if (reply.isBlank()) "Connected" else "Connected • ${reply.take(80)}"
    }

    private fun request(system: String, user: String, maxTokens: Int? = null): String {
        val endpoint = normalizeEndpoint(settings.endpoint)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (settings.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0.2)
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        connection.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (code !in 200..299) throw IllegalStateException("AI endpoint returned HTTP $code: ${response.take(300)}")

        val json = JSONObject(response)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun normalizeEndpoint(raw: String): String {
        val base = raw.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }
}
