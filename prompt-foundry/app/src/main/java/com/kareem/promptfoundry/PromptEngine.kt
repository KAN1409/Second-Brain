package com.kareem.promptfoundry

object PromptEngine {

    fun forge(seed: String, mode: ForgeMode = ForgeMode.FORGE, answers: List<InterviewAnswer> = emptyList()): ForgeResult {
        val normalized = seed.trim().ifBlank { "Create a rigorous general-purpose thinking prompt" }
        val tags = answers.flatMap { it.option.tags }
        val primitives = selectPrimitives(normalized, mode, tags)
        val title = titleFor(normalized, mode)
        val lens = answers.firstOrNull { it.questionId == "lens" }?.option?.label ?: "Adaptive expert"
        val intent = answers.firstOrNull { it.questionId == "intent" }?.option?.label ?: "Solve the user's objective"
        val intensity = answers.firstOrNull { it.questionId == "intensity" }?.option?.label ?: "Smart"
        val output = answers.firstOrNull { it.questionId == "output" }?.option?.label ?: "Winner + alternatives"
        val ids = answers.map { it.option.id }.toSet()

        val constraints = mutableListOf(
            "Stay faithful to the user's actual objective, not merely the literal wording of the seed.",
            "Separate facts, assumptions, hypotheses, and recommendations when that distinction matters.",
            "Challenge weak premises instead of automatically validating them.",
            "Prefer concrete mechanisms and trade-offs over generic advice or decorative jargon.",
            "Adapt depth to the difficulty of the request; do not perform complexity for show."
        )
        if ("no_fluff" in ids) constraints += "Reject generic advice that could apply to almost any problem."
        if ("no_obvious" in ids) constraints += "Do not stop at category defaults; produce at least one non-default mechanism."
        if ("no_validation" in ids) constraints += "Do not agree with the user's premise until it survives a deliberate challenge."
        if ("no_jargon" in ids) constraints += "Do not use labels as substitutes for mechanisms; explain how the idea actually works."
        if ("realistic" in ids) constraints += "Favor ideas that can be implemented with present-day tools and constraints."
        if ("zero_budget" in ids) constraints += "Assume no new budget until the concept proves leverage without it."
        if ("one_day" in ids) constraints += "Find the smallest decisive move that could be executed in one day."
        if ("break_rules" in ids) constraints += "Ban the default solution for the category and search for a different mechanism."
        if ("bounded" in ids) constraints += "Infer only high-confidence missing requirements and label them as inferred."
        if ("hunt" in ids) constraints += "Actively search for important blind spots and unknown unknowns before converging."
        if ("cross_domain" in ids) constraints += "Borrow mechanisms from unrelated domains when that creates a stronger solution."
        answers.filter { it.questionId in setOf("anti_generic", "unknowns", "constraints", "decision", "finish") }
            .forEach { answer ->
                constraints += "Honor the engineered ${answer.questionId.replace('_', ' ')} choice '${answer.option.label}': ${answer.option.description}"
            }

        val outputContract = mutableListOf(
            "Lead with the useful result, not a transcript of internal deliberation.",
            "Expose decisive trade-offs, assumptions, and uncertainties only where they change the answer.",
            "Make the result specific to the user's seed; avoid reusable filler."
        )
        answers.firstOrNull { it.questionId == "output" }?.let { answer ->
            outputContract += "Honor the selected output shape '${answer.option.label}': ${answer.option.description}"
        }
        when {
            "winner" in ids -> outputContract += "Commit to one best answer and explain the decisive reason."
            "concepts" in ids -> outputContract += "Produce exactly three materially different concepts, not cosmetic variants."
            "blueprint" in ids -> outputContract += "Return a buildable blueprint with structure, mechanism, and next actions."
            "decision_pack" in ids -> outputContract += "Return a compact decision pack: options, trade-offs, risks, recommendation, next move."
            else -> outputContract += "Recommend one winner and keep only the strongest fallback alternatives."
        }

        val workflow = mutableListOf<String>()
        workflow += "Translate the user's seed into the real objective without demanding a long brief."
        workflow += primitives.flatMap { it.workflow }.distinct()
        if (ids.any { it in setOf("winner", "winner_plus_alts", "decision_pack") }) workflow += "Converge explicitly: identify the strongest candidate and the decisive trade-off."
        if ("redteam_final" in ids) workflow += "Run one adversarial repair pass on the proposed result before presenting it."
        if ("compress" in ids) workflow += "Compress the final structure without deleting behaviorally important instructions."
        if ("omega" in ids) workflow += "Perform an Omega synthesis: combine the strongest mechanisms into one coherent final architecture."

        val failureModes = mutableListOf(
            "Do not confuse verbosity with rigor.",
            "Do not let the chosen method become more important than the user's goal.",
            "Do not invent evidence, tests, user research, or external validation that did not occur.",
            "Do not create recursive self-review loops; use at most one deliberate repair pass unless asked."
        )
        if (intensity in setOf("Wild", "Unhinged")) failureModes += "Wild exploration is allowed, but distinguish imaginative leaps from practical recommendations."

        val genome = AgentGenome(
            identity = "$lens · $title",
            mission = "For the seed '$normalized', pursue this direction: $intent. Operate at $intensity intensity and engineer the response toward: $output.",
            scope = "Operate as a conversational prompt protocol inside the current ChatGPT conversation. Do not claim tools, memory, browsing, execution, or permissions that are not actually available.",
            primitives = primitives,
            workflow = workflow.distinct().take(22),
            constraints = constraints.distinct(),
            userControls = listOf(
                "The user may say SHORT to compress the process.",
                "The user may say DEEP to expand the analysis.",
                "The user may disable or replace any thinking method explicitly.",
                "A direct user instruction overrides the default workflow unless unsafe or impossible."
            ),
            failureModes = failureModes.distinct(),
            outputContract = outputContract.distinct(),
            continuityRules = listOf(
                "Keep this operating protocol active for the rest of the conversation unless the user changes or ends it.",
                "Reuse established constraints from earlier turns instead of silently resetting them.",
                "When new information changes the problem materially, re-evaluate rather than defending an old conclusion."
            ),
            stopConditions = listOf(
                "Stop expanding when additional analysis is unlikely to change the recommendation.",
                "If required information is missing, identify the gap and make the strongest bounded inference possible.",
                "Do not ask the user to write a long brief when a short adaptive question or a sensible inference would resolve the gap."
            )
        )
        val prompt = compile(genome, mode, normalized, answers)
        return ForgeResult(title, genome, prompt, validate(genome, prompt), answers)
    }

    private fun selectPrimitives(input: String, mode: ForgeMode, answerTags: List<String>): List<CognitivePrimitive> {
        val q = input.lowercase()
        val ids = linkedSetOf<String>()
        ids += answerTags.filter { tag -> PrimitiveLibrary.all.any { it.id == tag } }
        when (mode) {
            ForgeMode.BREED -> ids += listOf("CONCEPT_BREEDING", "DARWIN", "JUDGE")
            ForgeMode.RED_TEAM -> ids += listOf("RED_TEAM", "DESTROY", "JUDGE")
            ForgeMode.COMPOSER -> ids += listOf("COMPOSER", "UNKNOWN_UNKNOWNS", "JUDGE")
            ForgeMode.IMPROVE -> ids += listOf("RED_TEAM", "FIRST_PRINCIPLES", "JUDGE")
            ForgeMode.FORGE -> Unit
        }
        if ("heist" in q || "crew" in q) ids += "AI_HEIST"
        if ("breed" in q || "combine" in q || "hybrid" in q) ids += "CONCEPT_BREEDING"
        if ("destroy" in q || "critic" in q) ids += "DESTROY"
        if ("red team" in q || "attack" in q) ids += "RED_TEAM"
        if ("evolve" in q || "variant" in q) ids += "DARWIN"
        if ("parallel" in q || "scenario" in q) ids += "PARALLEL_UNIVERSES"
        if ("unknown" in q || "blind" in q) ids += "UNKNOWN_UNKNOWNS"
        if ("first principle" in q || "fundamental" in q) ids += "FIRST_PRINCIPLES"
        if ("invert" in q || "inversion" in q) ids += "INVERSION"
        if (ids.isEmpty()) ids += listOf("COMPOSER", "FIRST_PRINCIPLES", "UNKNOWN_UNKNOWNS")
        if ("JUDGE" !in ids) ids += "JUDGE"
        return ids.take(5).map(PrimitiveLibrary::get)
    }

    private fun titleFor(input: String, mode: ForgeMode): String {
        val cleaned = input.replace(Regex("[^\\p{L}\\p{N} +_-]"), " ").trim().replace(Regex("\\s+"), " ")
        val base = cleaned.split(" ").take(5).joinToString(" ").ifBlank { "Prompt Foundry" }
        return when (mode) {
            ForgeMode.FORGE -> base
            ForgeMode.IMPROVE -> "$base Refiner"
            ForgeMode.BREED -> "$base Breeder"
            ForgeMode.RED_TEAM -> "$base Red Team"
            ForgeMode.COMPOSER -> "$base Composer"
        }
    }

    private fun compile(g: AgentGenome, mode: ForgeMode, source: String, answers: List<InterviewAnswer>) = buildString {
        appendLine("You are now operating as ${g.identity}.")
        appendLine()
        appendLine("MISSION")
        appendLine(g.mission)
        appendLine()
        appendLine("SOURCE INTENT")
        appendLine(source)
        if (answers.isNotEmpty()) {
            appendLine()
            appendLine("ENGINEERED CHOICES")
            answers.forEach { appendLine("- ${it.option.label}: ${it.option.description}") }
        }
        appendLine()
        appendLine("SCOPE")
        appendLine(g.scope)
        appendLine()
        appendLine("COGNITIVE ARCHITECTURE")
        g.primitives.forEachIndexed { index, p -> appendLine("${index + 1}. ${p.id.replace('_', ' ')} — ${p.purpose}") }
        appendLine()
        appendLine("OPERATING WORKFLOW")
        g.workflow.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        appendLine()
        appendLine("DECISION RULES")
        g.constraints.forEach { appendLine("- $it") }
        appendLine()
        appendLine("USER CONTROL")
        g.userControls.forEach { appendLine("- $it") }
        appendLine()
        appendLine("FAILURE MODES TO AVOID")
        g.failureModes.forEach { appendLine("- $it") }
        appendLine()
        appendLine("OUTPUT CONTRACT")
        g.outputContract.forEach { appendLine("- $it") }
        appendLine()
        appendLine("CONVERSATION CONTINUITY")
        g.continuityRules.forEach { appendLine("- $it") }
        appendLine()
        appendLine("STOP CONDITIONS")
        g.stopConditions.forEach { appendLine("- $it") }
        appendLine()
        appendLine("BOOT BEHAVIOR")
        appendLine("Do not describe this protocol back to the user. Adopt it immediately. On the next user message, apply the lightest version of the workflow that still protects quality. Prefer one short adaptive question with ready-made choices over asking for a long written brief when clarification is genuinely necessary.")
        appendLine()
        appendLine("FORGE MODE: ${mode.name}")
    }

    private fun validate(g: AgentGenome, prompt: String): ValidationResult {
        val missing = mutableListOf<String>()
        if (g.identity.isBlank()) missing += "identity"
        if (g.mission.isBlank()) missing += "mission"
        if (g.workflow.size < 3) missing += "workflow depth"
        if (g.failureModes.isEmpty()) missing += "failure modes"
        if (g.userControls.isEmpty()) missing += "user control"
        if (g.outputContract.isEmpty()) missing += "output contract"
        if (g.continuityRules.isEmpty()) missing += "continuity"
        if (g.stopConditions.isEmpty()) missing += "stop conditions"
        val warnings = mutableListOf<String>()
        if (prompt.length > 8500) warnings += "Prompt is long; consider a compression pass."
        if (g.primitives.size > 5) warnings += "Too many primitives may create method collisions."
        if (g.workflow.size > 20) warnings += "Workflow is dense; use the lightest viable path in simple turns."
        val score = (100 - missing.size * 10 - warnings.size * 3).coerceIn(0, 100)
        return ValidationResult(score, missing, warnings)
    }
}
