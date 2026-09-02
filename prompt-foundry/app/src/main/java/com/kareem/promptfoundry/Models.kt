package com.kareem.promptfoundry

enum class ForgeMode { FORGE, IMPROVE, BREED, RED_TEAM, COMPOSER }
enum class ForgeDepth(val label: String, val questionCount: Int) {
    QUICK("Quick", 3), SMART("Smart", 6), DEEP("Deep", 10)
}

enum class FoundryTool(val label: String, val subtitle: String) {
    FORGE("Forge", "Seed → engineered prompt"),
    TRANSFORM("Transform", "Change the thinking style"),
    BREED("Breed", "Cross useful prompt DNA"),
    MUTATE("Mutate", "Create deliberate variations"),
    SYNTHESIZE("Synthesize", "Build an Omega prompt"),
    DNA("Prompt DNA", "Inspect the prompt genome"),
    LIBRARY("Library", "Recipes and primitives"),
    MODELS("Models", "Capability-based routing"),
    SETTINGS("Settings", "Foundry preferences")
}

data class CognitivePrimitive(
    val id: String,
    val purpose: String,
    val workflow: List<String>,
    val strengths: List<String>,
    val risks: List<String>
)

data class InterviewOption(
    val id: String,
    val label: String,
    val description: String,
    val tags: Set<String> = emptySet()
)

data class InterviewQuestion(
    val id: String,
    val eyebrow: String,
    val title: String,
    val hint: String,
    val options: List<InterviewOption>,
    val allowMore: Boolean = true
)

data class InterviewAnswer(
    val questionId: String,
    val option: InterviewOption
)

data class AgentGenome(
    val identity: String,
    val mission: String,
    val scope: String,
    val primitives: List<CognitivePrimitive>,
    val workflow: List<String>,
    val constraints: List<String>,
    val userControls: List<String>,
    val failureModes: List<String>,
    val outputContract: List<String>,
    val continuityRules: List<String>,
    val stopConditions: List<String>
)

data class ValidationResult(
    val score: Int,
    val missing: List<String>,
    val warnings: List<String>
)

data class ForgeResult(
    val title: String,
    val genome: AgentGenome,
    val prompt: String,
    val validation: ValidationResult,
    val recipe: List<InterviewAnswer> = emptyList()
)

data class ModelCandidate(
    val provider: String,
    val model: String,
    val badge: String,
    val bestFor: String,
    val capabilities: List<String>,
    val freeTier: String,
    val caveat: String
)
