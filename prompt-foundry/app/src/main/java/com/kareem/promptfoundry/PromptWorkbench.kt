package com.kareem.promptfoundry

data class PromptTuning(
    val creativity: Int = 72,
    val criticism: Int = 72,
    val autonomy: Int = 68,
    val divergence: Int = 70,
    val practicality: Int = 76,
    val verbosity: Int = 52
)

enum class BuildModule(val label: String, val heading: String, val hint: String) {
    COGNITION("Cognitive stack", "COGNITIVE ARCHITECTURE", "Which thinking primitives are active."),
    WORKFLOW("Workflow", "OPERATING WORKFLOW", "How the prompt sequences the work."),
    CONSTRAINTS("Decision rules", "DECISION RULES", "Rules that prevent generic or weak output."),
    USER_CONTROL("User control", "USER CONTROL", "How the user can steer the protocol."),
    FAILURE_RESISTANCE("Failure resistance", "FAILURE MODES TO AVOID", "Known ways the prompt can go wrong."),
    OUTPUT("Output contract", "OUTPUT CONTRACT", "The shape and standard of the final answer."),
    CONTINUITY("Continuity", "CONVERSATION CONTINUITY", "How behavior persists across turns."),
    STOP("Stop conditions", "STOP CONDITIONS", "When the prompt should stop expanding or ask for input.")
}

enum class PromptMutation(val label: String) {
    WILDER("Wilder"), SHARPER("Sharper"), LEANER("Leaner")
}

object PromptWorkbenchEngine {
    private val sectionOrder = listOf(
        "MISSION",
        "SOURCE INTENT",
        "ENGINEERED CHOICES",
        "SCOPE",
        "COGNITIVE ARCHITECTURE",
        "OPERATING WORKFLOW",
        "DECISION RULES",
        "USER CONTROL",
        "FAILURE MODES TO AVOID",
        "OUTPUT CONTRACT",
        "CONVERSATION CONTINUITY",
        "STOP CONDITIONS",
        "WORKBENCH TUNING",
        "BOOT BEHAVIOR",
        "FORGE MODE"
    )

    fun initialTuning(result: ForgeResult): PromptTuning {
        val labels = result.recipe.map { it.option.label.lowercase() }
        val tags = result.recipe.flatMap { it.option.tags }
        var t = PromptTuning()
        if (labels.any { "wild" in it || "unhinged" in it }) t = t.copy(creativity = 90, divergence = 92, practicality = 62)
        if (labels.any { "brutal" in it || "critical" in it } || "RED_TEAM" in tags) t = t.copy(criticism = 90)
        if (labels.any { "auto" in it || "decide" in it }) t = t.copy(autonomy = 84)
        if (labels.any { "real" in it || "build" in it || "decision" in it }) t = t.copy(practicality = 88)
        if (labels.any { "compact" in it || "concise" in it }) t = t.copy(verbosity = 32)
        return t
    }

    fun mutate(tuning: PromptTuning, mutation: PromptMutation): PromptTuning = when (mutation) {
        PromptMutation.WILDER -> tuning.copy(
            creativity = (tuning.creativity + 18).coerceAtMost(100),
            divergence = (tuning.divergence + 20).coerceAtMost(100),
            practicality = (tuning.practicality - 8).coerceAtLeast(25)
        )
        PromptMutation.SHARPER -> tuning.copy(
            criticism = (tuning.criticism + 18).coerceAtMost(100),
            autonomy = (tuning.autonomy + 8).coerceAtMost(100),
            verbosity = (tuning.verbosity - 10).coerceAtLeast(15)
        )
        PromptMutation.LEANER -> tuning.copy(
            verbosity = (tuning.verbosity - 24).coerceAtLeast(10),
            practicality = (tuning.practicality + 10).coerceAtMost(100),
            creativity = (tuning.creativity - 5).coerceAtLeast(20)
        )
    }

    fun rebuild(
        result: ForgeResult,
        activeQuestionIds: Set<String>,
        tuning: PromptTuning,
        disabledModules: Set<BuildModule>
    ): String {
        var prompt = result.prompt
        val activeAnswers = result.recipe.filter { it.questionId in activeQuestionIds }
        prompt = replaceRecipe(prompt, activeAnswers)
        disabledModules.forEach { prompt = removeSection(prompt, it.heading) }
        prompt = removeSection(prompt, "WORKBENCH TUNING")
        prompt = injectBefore(prompt, "BOOT BEHAVIOR", tuningBlock(tuning))
        return prompt.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun compact(result: ForgeResult, tuning: PromptTuning): String = buildString {
        val g = result.genome
        appendLine("You are now operating as ${g.identity}.")
        appendLine()
        appendLine("MISSION")
        appendLine(g.mission)
        appendLine()
        appendLine("COGNITIVE ARCHITECTURE")
        g.primitives.take(4).forEachIndexed { i, p -> appendLine("${i + 1}. ${p.id.replace('_', ' ')} — ${p.purpose}") }
        appendLine()
        appendLine("OPERATING WORKFLOW")
        g.workflow.take(8).forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
        appendLine()
        appendLine("DECISION RULES")
        g.constraints.take(5).forEach { appendLine("- $it") }
        appendLine()
        appendLine("OUTPUT CONTRACT")
        g.outputContract.forEach { appendLine("- $it") }
        appendLine()
        append(tuningBlock(tuning))
        appendLine()
        appendLine("BOOT BEHAVIOR")
        appendLine("Adopt this protocol immediately. Use the lightest workflow that preserves quality. Ask only short adaptive questions with ready-made choices when clarification is genuinely necessary.")
    }.trim()

    fun whyRecipe(result: ForgeResult): String {
        val p = result.genome.primitives.map { it.id }
        val reasons = mutableListOf<String>()
        if ("FIRST_PRINCIPLES" in p) reasons += "First Principles rebuilds the seed from fundamentals instead of accepting its first framing."
        if ("INVERSION" in p) reasons += "Inversion finds guaranteed failure modes and turns them into safeguards."
        if ("UNKNOWN_UNKNOWNS" in p) reasons += "Unknown Unknowns searches for important gaps the seed did not mention."
        if ("CONCEPT_BREEDING" in p) reasons += "Concept Breeding combines mechanisms instead of merely stacking features."
        if ("RED_TEAM" in p || "DESTROY" in p) reasons += "Red Team pressure attacks weak assumptions before the final answer."
        if ("JUDGE" in p) reasons += "Judge forces convergence on a winner and explicit trade-offs."
        return reasons.take(3).joinToString(" ").ifBlank { "The recipe was selected to match the seed, the chosen intensity and the requested output shape." }
    }

    fun scoreWithWorkbench(base: Int, tuning: PromptTuning, disabledModules: Set<BuildModule>): Int {
        var score = base
        score -= disabledModules.size * 2
        if (tuning.creativity > 94 && tuning.practicality < 45) score -= 3
        if (tuning.criticism > 95 && tuning.creativity < 35) score -= 2
        if (tuning.verbosity > 90) score -= 2
        return score.coerceIn(0, 100)
    }

    private fun tuningBlock(t: PromptTuning): String = buildString {
        appendLine("WORKBENCH TUNING")
        appendLine("- Creativity ${t.creativity}/100: ${band(t.creativity, "prefer proven patterns", "seek fresh mechanisms", "push beyond category defaults")}")
        appendLine("- Criticism ${t.criticism}/100: ${band(t.criticism, "challenge only obvious weaknesses", "stress-test important assumptions", "aggressively attack weak premises before convergence")}")
        appendLine("- Autonomy ${t.autonomy}/100: ${band(t.autonomy, "stay close to explicit instructions", "make bounded high-confidence decisions", "take initiative on missing low-risk decisions instead of asking")}")
        appendLine("- Divergence ${t.divergence}/100: ${band(t.divergence, "keep the search narrow", "consider materially different approaches", "deliberately search distant and non-obvious solution spaces")}")
        appendLine("- Practicality ${t.practicality}/100: ${band(t.practicality, "allow speculative exploration", "balance novelty with feasibility", "prefer mechanisms that can survive real constraints")}")
        appendLine("- Verbosity ${t.verbosity}/100: ${band(t.verbosity, "be compact and dense", "use enough detail to make decisions", "show richer structure without filler")}")
    }.trim()

    private fun band(value: Int, low: String, mid: String, high: String): String = when {
        value < 40 -> low
        value < 75 -> mid
        else -> high
    }

    private fun replaceRecipe(prompt: String, answers: List<InterviewAnswer>): String {
        val start = prompt.indexOf("ENGINEERED CHOICES\n")
        val scope = prompt.indexOf("\nSCOPE\n")
        if (start < 0 || scope < 0 || scope <= start) return prompt
        val replacement = if (answers.isEmpty()) "" else buildString {
            appendLine("ENGINEERED CHOICES")
            answers.forEach { appendLine("- ${it.option.label}: ${it.option.description}") }
        }.trimEnd() + "\n"
        return prompt.substring(0, start) + replacement + prompt.substring(scope + 1)
    }

    private fun removeSection(prompt: String, heading: String): String {
        val marker = "$heading\n"
        val start = prompt.indexOf(marker)
        if (start < 0) return prompt
        val next = sectionOrder
            .filter { it != heading }
            .mapNotNull { h -> prompt.indexOf("\n$h\n", start + marker.length).takeIf { it >= 0 } }
            .minOrNull() ?: prompt.length
        val end = if (next < prompt.length) next + 1 else next
        return prompt.removeRange(start, end)
    }

    private fun injectBefore(prompt: String, heading: String, block: String): String {
        val marker = "$heading\n"
        val at = prompt.indexOf(marker)
        return if (at < 0) "$prompt\n\n$block" else prompt.substring(0, at) + block + "\n\n" + prompt.substring(at)
    }
}
