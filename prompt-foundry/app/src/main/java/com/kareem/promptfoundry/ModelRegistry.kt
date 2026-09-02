package com.kareem.promptfoundry

object ModelRegistry {
    val candidates = listOf(
        ModelCandidate(
            provider = "Google Gemini API",
            model = "Gemini 3.7 Flash",
            badge = "DIRECTOR",
            bestFor = "Adaptive interviewing, long-context synthesis, complex prompt architecture",
            capabilities = listOf("1M context", "multi-step", "agentic workflows", "multilingual"),
            freeTier = "Free input/output tier available",
            caveat = "Free-tier content may be used to improve Google products. Availability and limits can change."
        ),
        ModelCandidate(
            provider = "Groq",
            model = "Qwen 3.8 27B",
            badge = "DIVERGENCE",
            bestFor = "Dynamic options, mutations, creative branches, structured generation",
            capabilities = listOf("fast", "reasoning", "structured output", "131K context"),
            freeTier = "Listed in Groq Free Plan limits",
            caveat = "Preview model; can be discontinued. A provider account can have its own tier."
        ),
        ModelCandidate(
            provider = "Groq",
            model = "GPT-OSS 120B",
            badge = "SYNTHESIS",
            bestFor = "Omega synthesis, difficult prompt reconstruction, critique + rebuild",
            capabilities = listOf("reasoning", "structured output", "131K context", "65K max completion"),
            freeTier = "Listed in Groq Free Plan limits",
            caveat = "No paid fallback should ever be enabled by Prompt Foundry."
        ),
        ModelCandidate(
            provider = "Groq",
            model = "GPT-OSS 20B",
            badge = "UTILITY",
            bestFor = "Classification, extraction, cleanup, compression, JSON transforms",
            capabilities = listOf("very fast", "structured output", "reasoning control"),
            freeTier = "Listed in Groq Free Plan limits",
            caveat = "Use for utility work instead of wasting heavier models."
        ),
        ModelCandidate(
            provider = "OpenRouter",
            model = "Free Models Router",
            badge = "FALLBACK",
            bestFor = "Free fallback that routes only to currently free models",
            capabilities = listOf("capability filtering", "200K router context", "free-only pool"),
            freeTier = "Free plan; current published limit is 50 requests/day",
            caveat = "Exact underlying model varies, so it should not be the primary director."
        )
    )
}
