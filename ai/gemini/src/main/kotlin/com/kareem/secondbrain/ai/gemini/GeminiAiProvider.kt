package com.kareem.secondbrain.ai.gemini

import com.kareem.secondbrain.ai.api.AiAnswerRequest
import com.kareem.secondbrain.ai.api.AiAnswerResponse
import com.kareem.secondbrain.ai.api.AiClaim
import com.kareem.secondbrain.ai.api.AiProvider
import com.kareem.secondbrain.ai.api.AiProviderUnavailableException
import com.kareem.secondbrain.ai.api.Citation
import com.kareem.secondbrain.ai.api.SummaryRequest
import com.kareem.secondbrain.ai.api.SummaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiAiProvider(
    private val keyStore: GeminiApiKeyStore,
    private val model: String = DEFAULT_MODEL,
) : AiProvider {

    override suspend fun answer(request: AiAnswerRequest): AiAnswerResponse {
        require(request.question.isNotBlank()) { "Question cannot be blank" }
        require(request.evidence.isNotEmpty()) { "Evidence cannot be empty" }
        val apiKey = keyStore.read() ?: throw AiProviderUnavailableException("Gemini API key is not configured")
        val prompt = buildPrompt(request)
        val responseText = postGenerateContent(apiKey, prompt)
        return parseAnswer(responseText)
    }

    override suspend fun summarize(request: SummaryRequest): SummaryResponse {
        if (request.evidence.isEmpty()) return SummaryResponse("", emptyList())
        val result = answer(
            AiAnswerRequest(
                question = "Summarize the supplied memories. Include only facts directly supported by the evidence.",
                evidence = request.evidence,
            ),
        )
        val memoryByEvidence = request.evidence.associate { it.evidenceId to it.memoryId }
        return SummaryResponse(
            summary = result.claims.joinToString(" ") { it.text },
            citations = result.claims
                .flatMap(AiClaim::evidenceIds)
                .mapNotNull(memoryByEvidence::get)
                .distinct()
                .map(::Citation),
        )
    }

    private fun buildPrompt(request: AiAnswerRequest): String {
        val evidenceJson = JSONArray().apply {
            request.evidence.forEach { evidence ->
                put(
                    JSONObject()
                        .put("id", evidence.evidenceId)
                        .put("memoryId", evidence.memoryId)
                        .put("source", evidence.source)
                        .put("occurredAtMs", evidence.occurredAtMs)
                        .put("text", evidence.text),
                )
            }
        }
        return """
            You are the synthesis layer of a personal second-brain app.
            Answer ONLY from EVIDENCE_JSON. Do not use outside knowledge, guesses, or unstated personal history.
            Every factual claim must cite one or more evidence IDs that directly support it.
            If the evidence does not support an answer, return insufficientEvidence=true and an empty claims array.
            Return ONLY one JSON object in this exact shape:
            {"insufficientEvidence":false,"claims":[{"text":"supported sentence","evidenceIds":["E1"]}]}

            QUESTION:
            ${request.question}

            EVIDENCE_JSON:
            $evidenceJson
        """.trimIndent()
    }

    private suspend fun postGenerateContent(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.0)
                        .put("maxOutputTokens", 1600),
                )
                .toString()

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "HTTP $status" }
                throw IOException("Gemini request failed: ${message.take(240)}")
            }
            extractCandidateText(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCandidateText(raw: String): String {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("Gemini returned no candidates")
        val content = candidates.optJSONObject(0)?.optJSONObject("content")
            ?: throw IOException("Gemini returned no content")
        val parts = content.optJSONArray("parts") ?: throw IOException("Gemini returned no text parts")
        val text = buildString {
            for (index in 0 until parts.length()) {
                val partText = parts.optJSONObject(index)?.optString("text").orEmpty()
                if (partText.isNotBlank()) append(partText)
            }
        }.trim()
        if (text.isBlank()) throw IOException("Gemini returned an empty answer")
        return text
    }

    private fun parseAnswer(text: String): AiAnswerResponse {
        val normalized = extractJsonObject(text)
        val json = JSONObject(normalized)
        val claimsJson = json.optJSONArray("claims") ?: JSONArray()
        val claims = buildList {
            for (index in 0 until claimsJson.length()) {
                val item = claimsJson.optJSONObject(index) ?: continue
                val claimText = item.optString("text").trim()
                if (claimText.isBlank()) continue
                val idsJson = item.optJSONArray("evidenceIds") ?: JSONArray()
                val evidenceIds = buildList {
                    for (idIndex in 0 until idsJson.length()) {
                        idsJson.optString(idIndex).trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }.distinct()
                add(AiClaim(claimText, evidenceIds))
            }
        }
        return AiAnswerResponse(
            claims = claims,
            insufficientEvidence = json.optBoolean("insufficientEvidence", claims.isEmpty()),
        )
    }

    private fun extractJsonObject(text: String): String {
        val trimmed = text.trim()
        val first = trimmed.indexOf('{')
        val last = trimmed.lastIndexOf('}')
        if (first < 0 || last <= first) throw IOException("Gemini answer was not valid JSON")
        return trimmed.substring(first, last + 1)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 35_000
    }
}
