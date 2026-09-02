package com.kareem.promptfoundry

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class FreeProvider(
    val label: String,
    val slot: String,
    val modelLabel: String,
    val modelId: String,
    val role: String
) {
    GROQ("Groq", "groq", "Qwen 3.8 27B", "qwen/qwen3.8-27b", "Dynamic options + mutations"),
    GEMINI("Gemini", "gemini", "Gemini 3.7 Flash", "gemini-3.7-flash", "Director + Omega synthesis"),
    OPENROUTER("OpenRouter", "openrouter", "Free Models Router", "openrouter/free", "Free-only fallback")
}

data class AiTextResult(
    val text: String? = null,
    val provider: FreeProvider? = null,
    val error: String? = null
) {
    val ok: Boolean get() = !text.isNullOrBlank()
    val sourceLabel: String get() = provider?.let { "${it.label} · ${it.modelLabel}" } ?: "LOCAL"
}

data class AiQuestionResult(
    val question: InterviewQuestion? = null,
    val provider: FreeProvider? = null,
    val error: String? = null
) {
    val ok: Boolean get() = question != null
    val sourceLabel: String get() = provider?.let { "${it.label} · ${it.modelLabel}" } ?: "LOCAL FALLBACK"
}

object ProviderPrefs {
    private const val PREFS = "prompt_foundry_provider_policy"
    private const val AI_ENABLED = "ai_questions_enabled"

    fun aiEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(AI_ENABLED, true)

    fun setAiEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AI_ENABLED, enabled).apply()
    }

    fun providerEnabled(context: Context, provider: FreeProvider): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("provider_${provider.slot}_enabled", true)

    fun setProviderEnabled(context: Context, provider: FreeProvider, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("provider_${provider.slot}_enabled", enabled)
            .apply()
    }
}

object ProviderEngine {
    private const val CONNECT_TIMEOUT = 18_000
    private const val READ_TIMEOUT = 35_000

    fun hasAnyKey(context: Context): Boolean = FreeProvider.entries.any {
        ProviderPrefs.providerEnabled(context, it) && SecureKeyStore.has(context, it.slot)
    }

    fun configuredProviders(context: Context): List<FreeProvider> = FreeProvider.entries.filter {
        ProviderPrefs.providerEnabled(context, it) && SecureKeyStore.has(context, it.slot)
    }

    suspend fun generateQuestion(
        context: Context,
        seed: String,
        answers: List<InterviewAnswer>,
        step: Int,
        total: Int,
        localFallback: InterviewQuestion,
        requestFreshOptions: Boolean = false
    ): AiQuestionResult {
        if (!ProviderPrefs.aiEnabled(context) || !hasAnyKey(context)) return AiQuestionResult(localFallback)
        val prompt = buildQuestionPrompt(seed, answers, step, total, localFallback, requestFreshOptions)
        val failures = mutableListOf<String>()
        for (provider in route(context, questionTask = true)) {
            val result = request(context, provider, prompt, json = true, temperature = if (requestFreshOptions) .95 else .72)
            if (result.ok) {
                parseQuestion(result.text.orEmpty(), localFallback, step)?.let { return AiQuestionResult(it, provider) }
                failures += "${provider.label}: invalid JSON"
            } else failures += "${provider.label}: ${result.error ?: "failed"}"
        }
        return AiQuestionResult(localFallback, error = failures.joinToString(" · ").take(260))
    }

    suspend fun enhancePrompt(
        context: Context,
        seed: String,
        currentPrompt: String,
        recipe: List<InterviewAnswer>,
        tuning: PromptTuning
    ): AiTextResult {
        if (!hasAnyKey(context)) return AiTextResult(error = "Add a free-tier API key in Models first.")
        val choices = recipe.joinToString("\n") { "- ${it.option.label}: ${it.option.description}" }
        val instruction = """
            You are the Prompt Foundry senior compiler. Re-engineer the current prompt into a cleaner, stronger first-message prompt for a fresh AI conversation.

            SEED
            $seed

            ENGINEERED CHOICES
            $choices

            DESIRED DNA
            Creativity ${tuning.creativity}/100
            Criticism ${tuning.criticism}/100
            Autonomy ${tuning.autonomy}/100
            Divergence ${tuning.divergence}/100
            Practicality ${tuning.practicality}/100
            Verbosity ${tuning.verbosity}/100

            RULES
            - Preserve the actual objective and useful constraints.
            - Remove decorative role-play and redundancy that do not change behavior.
            - Resolve contradictions instead of stacking more instructions.
            - Make the operating workflow explicit only where it improves output.
            - Prefer ready-made choices over asking the user for a long brief.
            - Do not claim tools, memory, browsing, permissions, or execution the target assistant may not have.
            - Return only the final prompt. No commentary, no markdown fence.

            CURRENT PROMPT
            $currentPrompt
        """.trimIndent()
        val failures = mutableListOf<String>()
        for (provider in route(context, questionTask = false)) {
            val result = request(context, provider, instruction, json = false, temperature = .55)
            if (result.ok) return result.copy(text = cleanText(result.text.orEmpty()))
            failures += "${provider.label}: ${result.error ?: "failed"}"
        }
        return AiTextResult(error = failures.joinToString(" · ").take(300))
    }

    suspend fun test(context: Context, provider: FreeProvider): AiTextResult {
        if (!SecureKeyStore.has(context, provider.slot)) return AiTextResult(error = "No API key saved")
        return request(context, provider, "Return exactly this word and nothing else: FOUNDRY_OK", json = false, temperature = 0.0)
    }

    private fun route(context: Context, questionTask: Boolean): List<FreeProvider> {
        val preferred = if (questionTask) {
            listOf(FreeProvider.GROQ, FreeProvider.GEMINI, FreeProvider.OPENROUTER)
        } else {
            listOf(FreeProvider.GEMINI, FreeProvider.GROQ, FreeProvider.OPENROUTER)
        }
        return preferred.filter { ProviderPrefs.providerEnabled(context, it) && SecureKeyStore.has(context, it.slot) }
    }

    private suspend fun request(
        context: Context,
        provider: FreeProvider,
        prompt: String,
        json: Boolean,
        temperature: Double
    ): AiTextResult = withContext(Dispatchers.IO) {
        runCatching {
            val key = SecureKeyStore.get(context, provider.slot).orEmpty()
            if (key.isBlank()) error("API key missing")
            when (provider) {
                FreeProvider.GEMINI -> gemini(key, provider, prompt, json, temperature)
                FreeProvider.GROQ -> openAiCompatible(
                    endpoint = "https://api.groq.com/openai/v1/chat/completions",
                    key = key,
                    provider = provider,
                    prompt = prompt,
                    json = json,
                    temperature = temperature
                )
                FreeProvider.OPENROUTER -> openAiCompatible(
                    endpoint = "https://openrouter.ai/api/v1/chat/completions",
                    key = key,
                    provider = provider,
                    prompt = prompt,
                    json = json,
                    temperature = temperature,
                    extraHeaders = mapOf("X-Title" to "Prompt Foundry")
                )
            }
        }.getOrElse { AiTextResult(provider = provider, error = it.message ?: it.javaClass.simpleName) }
    }

    private fun openAiCompatible(
        endpoint: String,
        key: String,
        provider: FreeProvider,
        prompt: String,
        json: Boolean,
        temperature: Double,
        extraHeaders: Map<String, String> = emptyMap()
    ): AiTextResult {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are a precise prompt-engineering worker. Follow the requested output format exactly."))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", provider.modelId)
            .put("messages", messages)
            .put("temperature", temperature)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        val response = postJson(endpoint, body.toString(), mapOf("Authorization" to "Bearer $key") + extraHeaders)
        val root = JSONObject(response)
        val text = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        return if (text.isBlank()) AiTextResult(provider = provider, error = "Empty response") else AiTextResult(text, provider)
    }

    private fun gemini(
        key: String,
        provider: FreeProvider,
        prompt: String,
        json: Boolean,
        temperature: Double
    ): AiTextResult {
        val part = JSONObject().put("text", "You are a precise prompt-engineering worker.\n\n$prompt")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(part))))
            .put("generationConfig", JSONObject().put("temperature", temperature).apply {
                if (json) put("responseMimeType", "application/json")
            })
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${provider.modelId}:generateContent?key=$key"
        val response = postJson(endpoint, body.toString(), emptyMap())
        val root = JSONObject(response)
        val parts = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val text = parts.getJSONObject(0).optString("text")
        return if (text.isBlank()) AiTextResult(provider = provider, error = "Empty response") else AiTextResult(text, provider)
    }

    private fun postJson(endpoint: String, body: String, headers: Map<String, String>): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            error("HTTP $code${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
        }
        return text
    }

    private fun buildQuestionPrompt(
        seed: String,
        answers: List<InterviewAnswer>,
        step: Int,
        total: Int,
        fallback: InterviewQuestion,
        fresh: Boolean
    ): String = buildString {
        appendLine("Design the next adaptive multiple-choice decision for Prompt Foundry.")
        appendLine("The user should almost never need to type more than the seed.")
        appendLine()
        appendLine("SEED: $seed")
        appendLine("STEP: ${step + 1} of $total")
        if (answers.isNotEmpty()) {
            appendLine("CHOICES SO FAR:")
            answers.forEach { appendLine("- ${it.option.label}: ${it.option.description}") }
        }
        appendLine()
        appendLine("LOCAL BASELINE QUESTION: ${fallback.title}")
        appendLine("LOCAL BASELINE OPTIONS: ${fallback.options.joinToString(" | ") { it.label }}")
        if (fresh) appendLine("Generate a materially different option set from the baseline while staying relevant to the exact seed.")
        appendLine()
        appendLine("Return ONLY one JSON object with this exact shape:")
        appendLine("{\"eyebrow\":\"short stage label\",\"title\":\"one natural question\",\"hint\":\"one short hint\",\"options\":[{\"id\":\"snake_case\",\"label\":\"2-5 words\",\"description\":\"one concise sentence\",\"tags\":[\"FIRST_PRINCIPLES\"]}]} ")
        appendLine("Rules: provide 5 or 6 options; make them meaningfully different; include Auto as the last option only when useful; tags may only use known cognitive primitive IDs; do not ask for information already inferable from the seed or prior choices; optimize for taps, not typing.")
    }

    private fun parseQuestion(raw: String, fallback: InterviewQuestion, step: Int): InterviewQuestion? = runCatching {
        val root = JSONObject(cleanJson(raw))
        val optionsJson = root.getJSONArray("options")
        val options = buildList {
            for (i in 0 until optionsJson.length()) {
                val item = optionsJson.getJSONObject(i)
                val label = item.optString("label").trim().take(60)
                if (label.isBlank()) continue
                val tagsJson = item.optJSONArray("tags")
                val tags = buildSet {
                    if (tagsJson != null) for (j in 0 until tagsJson.length()) {
                        val tag = tagsJson.optString(j)
                        if (PrimitiveLibrary.all.any { it.id == tag }) add(tag)
                    }
                }
                val rawId = item.optString("id").ifBlank { label.lowercase().replace(Regex("[^a-z0-9]+"), "_") }
                add(InterviewOption("ai_${step}_${rawId.take(30)}_$i", label, item.optString("description").trim().take(180), tags))
            }
        }.take(7)
        if (options.size < 3) return null
        InterviewQuestion(
            id = fallback.id,
            eyebrow = root.optString("eyebrow").ifBlank { "${step + 1} · ADAPTIVE" }.take(40),
            title = root.optString("title").ifBlank { "Choose the strongest direction" }.take(140),
            hint = root.optString("hint").take(200),
            options = options,
            allowMore = true
        )
    }.getOrNull()

    private fun cleanJson(text: String): String {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
    }

    private fun cleanText(text: String): String = text.trim().removePrefix("```").removeSuffix("```").trim()
}
