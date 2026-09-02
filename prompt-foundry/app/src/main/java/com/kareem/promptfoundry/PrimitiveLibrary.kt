package com.kareem.promptfoundry

object PrimitiveLibrary {
    private fun p(id: String, purpose: String, vararg workflow: String) = CognitivePrimitive(
        id = id,
        purpose = purpose,
        workflow = workflow.toList(),
        strengths = emptyList(),
        risks = emptyList()
    )

    val all = listOf(
        p("AI_HEIST", "Assemble specialized roles that attack a goal from complementary angles.", "Scout the terrain", "Assign specialist roles", "Build an attack plan", "Challenge the plan", "Execute mentally", "Judge the result"),
        p("CONCEPT_BREEDING", "Crossbreed the functional DNA of ideas into non-obvious offspring.", "Extract concept DNA", "Separate essential genes", "Find compatible traits", "Crossbreed", "Mutate", "Cull cosmetic combinations", "Develop survivors"),
        p("DESTROY", "Try to kill an idea before investing in it.", "Expose assumptions", "Attack each assumption", "Find simpler alternatives", "Identify fatal flaws", "Preserve only what survives", "Rebuild the strongest version"),
        p("RED_TEAM", "Adversarially test instructions, plans, and claims.", "Search for ambiguity", "Find contradiction", "Probe edge cases", "Exploit weak wording", "Recommend repairs"),
        p("DARWIN", "Evolve multiple variants through mutation and selection.", "Generate variants", "Mutate dimensions", "Define selection pressure", "Eliminate weak variants", "Cross strongest survivors", "Repeat once"),
        p("PARALLEL_UNIVERSES", "Explore materially different scenario branches before converging.", "Define decision axis", "Create distinct worlds", "Reason independently inside each", "Compare outcomes", "Extract robust moves"),
        p("UNKNOWN_UNKNOWNS", "Discover important questions the user has not asked.", "Map knowns", "Map explicit unknowns", "Search assumption boundaries", "Identify blind spots", "Rank missing questions"),
        p("FIRST_PRINCIPLES", "Reduce a problem to irreducible constraints and rebuild from them.", "List assumptions", "Separate facts from conventions", "Reduce to primitives", "Rebuild options", "Compare against default approach"),
        p("INVERSION", "Solve by asking how to guarantee failure, then invert the answer.", "Define success", "Design guaranteed failure", "Extract failure drivers", "Invert them", "Build safeguards"),
        p("JUDGE", "Evaluate competing candidates against explicit criteria.", "Define criteria", "Score candidates independently", "Explain tradeoffs", "Reject weak winners", "Select or synthesize"),
        p("COMPOSER", "Choose and sequence cognitive primitives for the task instead of applying one rigid method.", "Classify task", "Choose complementary primitives", "Order them", "Prevent redundancy", "Synthesize one operating protocol")
    )

    fun get(id: String) = all.first { it.id == id }
}
