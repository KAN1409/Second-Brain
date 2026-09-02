package com.kareem.promptfoundry

object AdaptiveInterviewEngine {
    private enum class Kind { DESIGN, IDEA, CODE, DECISION, GENERAL }

    fun build(seed: String, depth: ForgeDepth, answers: List<InterviewAnswer>, variant: Int = 0): List<InterviewQuestion> {
        val kind = classify(seed)
        val questions = listOf(
            intentQuestion(kind),
            lensQuestion(kind, variant),
            intensityQuestion(kind),
            methodQuestion(variant),
            outputQuestion(),
            antiGenericQuestion(),
            unknownsQuestion(),
            constraintsQuestion(),
            decisionQuestion(),
            finishQuestion()
        )
        return questions.take(depth.questionCount)
    }

    fun autoPick(question: InterviewQuestion, seed: String, answers: List<InterviewAnswer>): InterviewOption {
        val preferred = when (question.id) {
            "intent" -> if (classify(seed) == Kind.DESIGN) "redesign" else if (classify(seed) == Kind.DECISION) "decide" else "develop"
            "intensity" -> "bold"
            "method" -> "auto_method"
            "output" -> "winner_plus_alts"
            else -> "auto"
        }
        return question.options.firstOrNull { it.id == preferred || it.id.startsWith(preferred) } ?: question.options.first()
    }

    private fun classify(seed: String): Kind {
        val q = seed.lowercase()
        return when {
            listOf("design", "ui", "ux", "screen", "layout", "واجهة", "تصميم").any(q::contains) -> Kind.DESIGN
            listOf("idea", "product", "feature", "startup", "business", "فكرة", "منتج").any(q::contains) -> Kind.IDEA
            listOf("code", "architecture", "api", "android", "kotlin", "bug", "كود", "برمجة").any(q::contains) -> Kind.CODE
            listOf("choose", "compare", "decision", "decide", "اختار", "قرار", "قارن").any(q::contains) -> Kind.DECISION
            else -> Kind.GENERAL
        }
    }

    private fun o(id: String, label: String, desc: String, vararg tags: String) = InterviewOption(id, label, desc, tags.toSet())

    private fun intentQuestion(kind: Kind) = when (kind) {
        Kind.DESIGN -> InterviewQuestion("intent", "01 · DIRECTION", "What should happen to this design?", "Pick the outcome. The next questions adapt to it.", listOf(
            o("critique", "Critique it", "Expose what is weak and why", "RED_TEAM"),
            o("redesign", "Redesign it", "Keep the goal, rethink the execution", "FIRST_PRINCIPLES"),
            o("reinvent", "Reinvent it", "Challenge the current product assumptions", "INVERSION"),
            o("simplify", "Simplify it", "Reduce noise without losing capability", "COMPOSER"),
            o("surprise_intent", "Surprise me", "Choose a non-obvious but useful direction", "UNKNOWN_UNKNOWNS")
        ))
        Kind.IDEA -> InterviewQuestion("intent", "01 · DIRECTION", "What do you want from the idea?", "No long brief needed — choose a direction.", listOf(
            o("generate", "Generate", "Create strong new concepts", "CONCEPT_BREEDING"),
            o("reinvent", "Reinvent", "Make the obvious approach illegal", "INVERSION"),
            o("develop", "Develop", "Turn the seed into a complete concept", "FIRST_PRINCIPLES"),
            o("breakthrough", "Find a breakthrough", "Search for the hidden leap", "UNKNOWN_UNKNOWNS"),
            o("surprise_intent", "Surprise me", "Let the Foundry choose", "COMPOSER")
        ))
        Kind.DECISION -> InterviewQuestion("intent", "01 · DIRECTION", "What decision behavior do you want?", "Choose how the prompt should attack the decision.", listOf(
            o("decide", "Pick a winner", "Converge on one recommendation", "JUDGE"),
            o("tradeoffs", "Expose trade-offs", "Make hidden costs visible", "FIRST_PRINCIPLES"),
            o("challenge", "Challenge my preference", "Fight confirmation bias", "RED_TEAM"),
            o("options", "Create better options", "Refuse a false binary", "CONCEPT_BREEDING"),
            o("surprise_intent", "Surprise me", "Choose the strongest route", "COMPOSER")
        ))
        else -> InterviewQuestion("intent", "01 · DIRECTION", "What are we trying to do?", "Choose one. You can mutate it later.", listOf(
            o("develop", "Solve / develop it", "Get to a useful result", "FIRST_PRINCIPLES"),
            o("analyze", "Analyze deeply", "Map the problem before deciding", "UNKNOWN_UNKNOWNS"),
            o("create", "Create something new", "Diverge before converging", "CONCEPT_BREEDING"),
            o("improve", "Improve what exists", "Diagnose then rebuild", "RED_TEAM"),
            o("surprise_intent", "Surprise me", "Let the Foundry choose", "COMPOSER")
        ))
    }

    private fun lensQuestion(kind: Kind, variant: Int): InterviewQuestion {
        val normal = when (kind) {
            Kind.DESIGN -> listOf(
                o("ux", "UX Architect", "Flow, hierarchy, usability"),
                o("product", "Product Strategist", "Value, focus, behavior"),
                o("creative", "Creative Director", "Taste, composition, distinctiveness"),
                o("behavioral", "Behavioral Designer", "Attention, friction, motivation"),
                o("forensic", "Forensic Reviewer", "Treat every choice as evidence")
            )
            Kind.CODE -> listOf(
                o("architect", "Systems Architect", "Boundaries, failure modes, evolution"),
                o("maintainer", "Principal Maintainer", "Clarity, operability, debt"),
                o("attacker", "Adversarial Engineer", "Break assumptions before users do"),
                o("minimalist", "Minimalist Engineer", "Delete before adding"),
                o("product_engineer", "Product Engineer", "Technical choices serving UX")
            )
            Kind.IDEA -> listOf(
                o("inventor", "Inventor", "Novel mechanisms over feature lists"),
                o("founder", "Founder", "Value, wedge, adoption"),
                o("scientist", "Mad Scientist", "Aggressive experimentation"),
                o("anthropologist", "Anthropologist", "Human behavior and unmet needs"),
                o("futurist", "Futurist", "Plausible near-future shifts")
            )
            else -> listOf(
                o("strategist", "Strategist", "Goals, trade-offs, leverage"),
                o("scientist", "Scientist", "Evidence, falsification, causality"),
                o("skeptic", "Skeptic", "Attack weak assumptions"),
                o("inventor", "Inventor", "Non-obvious alternatives"),
                o("operator", "Operator", "Turn thought into action")
            )
        }
        val alternate = listOf(
            o("detective", "Detective", "Infer what the brief is not saying"),
            o("outsider", "Intelligent Outsider", "Ignore category habits"),
            o("editor", "Ruthless Editor", "Keep only what earns its place"),
            o("contrarian", "Contrarian", "Assume consensus is wrong"),
            o("systems", "Systems Thinker", "Trace second-order effects")
        )
        return InterviewQuestion("lens", "02 · LENS", "Which mind should lead?", "This changes the voice and the questions inside the prompt.", (if (variant % 2 == 0) normal else alternate) + o("auto_lens", "Auto", "Let the Foundry choose"))
    }

    private fun intensityQuestion(kind: Kind) = InterviewQuestion("intensity", "03 · INTENSITY", if (kind == Kind.IDEA) "How far can the thinking go?" else "How aggressive should the thinking be?", "This changes architecture, not just wording.", listOf(
        o("safe", "Safe", "Stay close to proven patterns"),
        o("smart", "Smart", "Fresh but disciplined"),
        o("bold", "Bold", "Challenge defaults and take useful risks"),
        o("wild", "Wild", "Prefer surprising mechanisms"),
        o("unhinged", "Unhinged", "Explore alien ideas, then bring back what survives")
    ), allowMore = false)

    private fun methodQuestion(variant: Int): InterviewQuestion {
        val a = listOf(
            o("first", "First Principles", "Strip assumptions and rebuild", "FIRST_PRINCIPLES"),
            o("breed", "Concept Breeding", "Cross functional DNA", "CONCEPT_BREEDING"),
            o("invert", "Inversion", "Design failure, then reverse it", "INVERSION"),
            o("unknown", "Unknown Unknowns", "Search outside the brief", "UNKNOWN_UNKNOWNS"),
            o("heist", "AI Heist", "Use a specialist crew", "AI_HEIST")
        )
        val b = listOf(
            o("parallel", "Parallel Universes", "Explore materially different worlds", "PARALLEL_UNIVERSES"),
            o("destroy", "Destroy & Rebuild", "Kill weak assumptions first", "DESTROY"),
            o("darwin", "Darwin", "Mutate and select", "DARWIN"),
            o("red", "Red Team", "Adversarially attack the plan", "RED_TEAM"),
            o("composer", "Cognitive Composer", "Combine methods deliberately", "COMPOSER")
        )
        return InterviewQuestion("method", "04 · THINKING ENGINE", "How should the prompt think?", "Pick one, or let Auto compose methods.", (if (variant % 2 == 0) a else b) + o("auto_method", "Auto compose", "Choose and sequence complementary methods", "COMPOSER"))
    }

    private fun outputQuestion() = InterviewQuestion("output", "05 · OUTPUT", "What should the answer feel like?", "The Foundry will build an explicit output contract.", listOf(
        o("winner", "One winner", "Converge and commit"),
        o("winner_plus_alts", "Winner + alternatives", "Recommend one and keep strong fallbacks"),
        o("concepts", "3 distinct concepts", "Force meaningful divergence"),
        o("blueprint", "Blueprint", "Structured, buildable specification"),
        o("decision_pack", "Decision pack", "Trade-offs, risks, recommendation, next move")
    ))

    private fun antiGenericQuestion() = InterviewQuestion("anti_generic", "06 · ANTI-GENERIC", "What should the prompt refuse to do?", "Negative-space rules often improve prompts more than another role sentence.", listOf(
        o("no_fluff", "No generic advice", "Reject anything that could fit any problem"),
        o("no_obvious", "No obvious ideas", "Force a non-default mechanism"),
        o("no_validation", "No automatic agreement", "Challenge weak premises"),
        o("no_jargon", "No decorative jargon", "Prefer concrete mechanisms"),
        o("auto_guard", "Auto", "Infer the most dangerous generic failure")
    ))

    private fun unknownsQuestion() = InterviewQuestion("unknowns", "07 · BLIND SPOTS", "How much should it look beyond what you asked?", "Useful when the seed is intentionally short.", listOf(
        o("strict", "Stay inside my seed", "Do not infer extra objectives"),
        o("bounded", "Find likely missing pieces", "Infer only high-confidence gaps"),
        o("hunt", "Hunt unknown unknowns", "Actively search for blind spots", "UNKNOWN_UNKNOWNS"),
        o("cross_domain", "Steal from other domains", "Look for mechanisms elsewhere", "CONCEPT_BREEDING"),
        o("auto_unknown", "Auto", "Adapt to the seed")
    ))

    private fun constraintsQuestion() = InterviewQuestion("constraints", "08 · CONSTRAINTS", "What kind of pressure improves the result?", "Use constraints as creative pressure, not bureaucracy.", listOf(
        o("realistic", "Immediately buildable", "Prefer practical moves"),
        o("zero_budget", "Zero-budget pressure", "Find leverage before resources"),
        o("one_day", "One-day pressure", "Prioritize the smallest decisive move"),
        o("break_rules", "Break category rules", "Ban the default solution"),
        o("auto_constraints", "Auto", "Infer useful constraints")
    ))

    private fun decisionQuestion() = InterviewQuestion("decision", "09 · CONVERGENCE", "How should it choose between good ideas?", "Make the prompt land instead of debating forever.", listOf(
        o("criteria", "Explicit criteria", "Score against declared criteria", "JUDGE"),
        o("tradeoff", "Trade-off first", "Name what each option sacrifices"),
        o("taste", "Expert taste", "Allow a strong editorial judgment"),
        o("evidence", "Evidence weighted", "Prefer supported claims"),
        o("auto_decision", "Auto", "Choose the right convergence rule")
    ))

    private fun finishQuestion() = InterviewQuestion("finish", "10 · FINAL POLISH", "What final pass should the Foundry add?", "One finishing move — no recursive loops.", listOf(
        o("compress", "Compress", "Remove redundancy without losing behavior"),
        o("redteam_final", "Red-team once", "Attack the compiled prompt then repair it", "RED_TEAM"),
        o("omega", "Omega synthesis", "Synthesize the strongest structure", "COMPOSER", "JUDGE"),
        o("elegant", "Make it elegant", "Improve clarity and rhythm"),
        o("auto_finish", "Auto", "Pick the best finishing pass")
    ))
}
