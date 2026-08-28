package com.kareem.secondbrain.ai.gemini

import com.kareem.secondbrain.ai.api.AiAnswerRequest
import com.kareem.secondbrain.ai.api.AiAnswerResponse
import com.kareem.secondbrain.ai.api.AiClaim
import com.kareem.secondbrain.ai.api.AiProvider
import com.kareem.secondbrain.ai.api.AiProviderUnavailableException
import com.kareem.secondbrain.ai.api.AiQueryPlanRequest
import com.kareem.secondbrain.ai.api.AiQueryPlanResponse
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

    override suspend fun planQuery(request: AiQueryPlanRequest): AiQueryPlanResponse {
        require(request.question.isNotBlank()) { "Question cannot be blank" }
        val apiKey = keyStore.read() ?: throw AiProviderUnavailableException("Gemini API key is not configured")
        val responseText = postGenerateContent(apiKey, buildQueryPlanPrompt(request))
        return parseQueryPlan(responseText)
    }

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

    private fun buildQueryPlanPrompt(request: AiQueryPlanRequest): String = """
        You are the query-understanding layer of a personal second-brain search system.
        You DO NOT answer the question and you DO NOT decide what event happened.
        Your job is only to propose broad semantic retrieval variants.

        Open-world rules:
        - Never collapse an ambiguous question into one intent or one source type.
        - A verb such as send, call, save, see, discuss, share, or do is a SOFT clue, never proof of an event type.
        - Preserve explicit people, names, places, products, files, topics, and literal phrases.
        - Do not invent people, apps, dates, times, events, or facts.
        - Do not produce hard time/date constraints. The app derives explicit temporal constraints locally.
        - softKindHints are OPTIONAL ranking hints only. They never exclude other memory types.
        - Prefer 1-4 concise semanticQueries that explore plausible meanings while staying faithful to the question.
        - relationHints may contain broad relations such as BEFORE, AFTER, AROUND, SEQUENCE, SAME_CONTEXT when the wording supports them.

        Allowed softKindHints:
        NOTIFICATION, SCREEN_CONTEXT, VOICE_TRANSCRIPT, OCR, NOTE, WEB_LINK, APP_ACTIVITY, DAILY_SUMMARY, LONG_TERM_FACT, TASK

        Return ONLY this JSON shape:
        {"semanticQueries":["query"],"softKindHints":[],"relationHints":[]}

        CURRENT_TIME_EPOCH_MS: ${request.nowEpochMs}
        TIME_ZONE: ${request.zoneId}
        QUESTION:
        ${request.question}
    """.trimIndent()

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

    private fun parseQueryPlan(text: String): AiQueryPlanResponse {
        val json = JSONObject(extractJsonObject(text))
        fun strings(name: String, max: Int): List<String> {
            val array = json.optJSONArray(name) ?: JSONArray()
            return buildList {
                for (index in 0 until minOf(array.length(), max)) {
                    array.optString(index).trim().takeIf { it.isNotBlank() && it.length <= MAX_PLAN_ITEM_CHARS }?.let(::add)
                }
            }.distinct()
        }
        return AiQueryPlanResponse(
            semanticQueries = strings("semanticQueries", 4),
            softKindHints = strings("softKindHints", 6).filter(ALLOWED_KIND_HINTS::contains),
            relationHints = strings("relationHints", 4).map(String::uppercase).filter(ALLOWED_RELATION_HINTS::contains),
        )
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
        private const val MAX_PLAN_ITEM_CHARS = 240
        private val ALLOWED_KIND_HINTS = setOf(
            "NOTIFICATION", "SCREEN_CONTEXT", "VOICE_TRANSCRIPT", "OCR", "NOTE", "WEB_LINK",
            "APP_ACTIVITY", "DAILY_SUMMARY", "LONG_TERM_FACT", "TASK",
        )
        private val ALLOWED_RELATION_HINTS = setOf("BEFORE", "AFTER", "AROUND", "SEQUENCE", "SAME_CONTEXT")
    }
}
