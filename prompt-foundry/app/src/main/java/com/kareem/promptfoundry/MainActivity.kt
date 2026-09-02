package com.kareem.promptfoundry

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FoundryTheme { PromptFoundryApp() } }
    }
}

private val FoundryColors = darkColorScheme(
    primary = Color(0xFFFFB15A),
    onPrimary = Color(0xFF211609),
    primaryContainer = Color(0xFF4A3016),
    onPrimaryContainer = Color(0xFFFFDDB6),
    secondary = Color(0xFFC7B9FF),
    tertiary = Color(0xFF7EDDC5),
    background = Color(0xFF0E0F12),
    surface = Color(0xFF16181D),
    surfaceVariant = Color(0xFF22252B),
    onSurface = Color(0xFFF5F2F6),
    onSurfaceVariant = Color(0xFFB8BBC4),
    outline = Color(0xFF3C4048)
)

@Composable
private fun FoundryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FoundryColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptFoundryApp() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(FoundryTool.FORGE) }
    var lastResult by remember { mutableStateOf<ForgeResult?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(308.dp)) {
                Column(Modifier.fillMaxHeight().padding(vertical = 18.dp)) {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("PROMPT FOUNDRY", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text("Prompt engineering laboratory", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    FoundryTool.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(item.label, fontWeight = if (item == tool) FontWeight.Bold else FontWeight.Medium)
                                    Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            selected = item == tool,
                            onClick = {
                                tool = item
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(iconFor(item), null) },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("v0.2.0 · ZERO-COST FIRST", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tool.label.uppercase(), fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            if (tool in workTools) Text("Adaptive prompt engineering", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                    actions = { if (tool != FoundryTool.MODELS) IconButton(onClick = { tool = FoundryTool.MODELS }) { Icon(Icons.Default.Memory, "Models") } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tool) {
                    FoundryTool.FORGE, FoundryTool.TRANSFORM, FoundryTool.BREED, FoundryTool.MUTATE, FoundryTool.SYNTHESIZE -> ForgeStudio(tool) { lastResult = it }
                    FoundryTool.DNA -> DnaScreen(lastResult)
                    FoundryTool.LIBRARY -> LibraryScreen()
                    FoundryTool.MODELS -> ModelsScreen()
                    FoundryTool.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

private val workTools = setOf(FoundryTool.FORGE, FoundryTool.TRANSFORM, FoundryTool.BREED, FoundryTool.MUTATE, FoundryTool.SYNTHESIZE)

private fun iconFor(tool: FoundryTool): ImageVector = when (tool) {
    FoundryTool.FORGE -> Icons.Default.AutoAwesome
    FoundryTool.TRANSFORM -> Icons.Default.Transform
    FoundryTool.BREED -> Icons.Default.CallMerge
    FoundryTool.MUTATE -> Icons.Default.Shuffle
    FoundryTool.SYNTHESIZE -> Icons.Default.MergeType
    FoundryTool.DNA -> Icons.Default.Science
    FoundryTool.LIBRARY -> Icons.Default.LibraryBooks
    FoundryTool.MODELS -> Icons.Default.Memory
    FoundryTool.SETTINGS -> Icons.Default.Settings
}

private fun modeFor(tool: FoundryTool) = when (tool) {
    FoundryTool.BREED -> ForgeMode.BREED
    FoundryTool.SYNTHESIZE, FoundryTool.MUTATE -> ForgeMode.COMPOSER
    FoundryTool.TRANSFORM -> ForgeMode.IMPROVE
    else -> ForgeMode.FORGE
}

@Composable
private fun ForgeStudio(tool: FoundryTool, onResult: (ForgeResult) -> Unit) {
    var seed by remember(tool) { mutableStateOf("") }
    var depth by remember { mutableStateOf(ForgeDepth.SMART) }
    var started by remember(tool) { mutableStateOf(false) }
    var answers by remember(tool) { mutableStateOf<List<InterviewAnswer>>(emptyList()) }
    var index by remember(tool) { mutableIntStateOf(0) }
    var variant by remember(tool) { mutableIntStateOf(0) }
    var result by remember(tool) { mutableStateOf<ForgeResult?>(null) }
    val questions = remember(seed, depth, answers, variant) { AdaptiveInterviewEngine.build(seed, depth, answers, variant) }

    result?.let { forged ->
        ResultScreen(forged) {
            result = null
            answers = emptyList()
            index = 0
            started = false
        }
        return
    }

    if (!started) {
        SeedScreen(tool, seed, { seed = it }, depth, { depth = it }) {
            answers = emptyList()
            index = 0
            started = true
        }
        return
    }

    val question = questions.getOrElse(index) { questions.last() }
    InterviewScreen(
        seed = seed,
        depth = depth,
        question = question,
        step = index,
        total = questions.size,
        answers = answers,
        onBack = { if (index > 0) index-- else started = false },
        onMore = { variant++ },
        onAuto = {
            val picked = AdaptiveInterviewEngine.autoPick(question, seed, answers)
            val next = answers.filterNot { it.questionId == question.id } + InterviewAnswer(question.id, picked)
            answers = next
            if (index >= questions.lastIndex) {
                val forged = PromptEngine.forge(seed, modeFor(tool), next)
                result = forged
                onResult(forged)
            } else index++
        },
        onPick = { picked ->
            val next = answers.filterNot { it.questionId == question.id } + InterviewAnswer(question.id, picked)
            answers = next
            if (index >= questions.lastIndex) {
                val forged = PromptEngine.forge(seed, modeFor(tool), next)
                result = forged
                onResult(forged)
            } else index++
        }
    )
}

@Composable
private fun SeedScreen(tool: FoundryTool, seed: String, onSeed: (String) -> Unit, depth: ForgeDepth, onDepth: (ForgeDepth) -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("GIVE ME THE SEED.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, lineHeight = 42.sp)
        Text(
            when (tool) {
                FoundryTool.BREED -> "Give me the concepts or prompt DNA. The Foundry will ask only what it needs."
                FoundryTool.MUTATE -> "Give me the prompt or idea. Choose mutation pressure with taps, not a long brief."
                FoundryTool.SYNTHESIZE -> "Give me the material. The Foundry will compose an Omega prompt from it."
                FoundryTool.TRANSFORM -> "Give me what exists. The Foundry will figure out how it should change."
                else -> "A few words are enough. The Foundry will interview the idea with adaptive choices."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = seed,
                    onValueChange = onSeed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp),
                    placeholder = { Text("Cortex home screen\nAI Heist\nCrazy AI product idea\nReview this architecture…") },
                    label = { Text("Seed") },
                    shape = RoundedCornerShape(18.dp)
                )
                Text("INTERVIEW DEPTH", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeDepth.entries.forEach { candidate ->
                        FilterChip(selected = depth == candidate, onClick = { onDepth(candidate) }, label = { Text("${candidate.label} · ${candidate.questionCount}") })
                    }
                }
                Button(onClick = onStart, enabled = seed.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("START ADAPTIVE FORGE", fontWeight = FontWeight.Black)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatusCard("AUTO ROUTER", "Free-tier first", Icons.Default.Memory, Modifier.weight(1f))
            StatusCard("CHATGPT", "External expert", Icons.Default.OpenInNew, Modifier.weight(1f))
        }
        Text("You write less. The Foundry asks better questions.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Each answer changes the reasoning architecture, constraints and final output contract.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InterviewScreen(seed: String, depth: ForgeDepth, question: InterviewQuestion, step: Int, total: Int, answers: List<InterviewAnswer>, onBack: () -> Unit, onMore: () -> Unit, onAuto: () -> Unit, onPick: (InterviewOption) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("${depth.label.uppercase()} FORGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(seed, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            }
            Text("${step + 1}/$total", fontWeight = FontWeight.Black)
        }
        LinearProgressIndicator(progress = { (step + 1).toFloat() / total.toFloat() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(question.eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(question.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(question.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)

        question.options.forEach { option ->
            ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onPick(option) }, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(option.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAuto, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.AutoFixHigh, null)
                Spacer(Modifier.width(5.dp))
                Text("AUTO PICK")
            }
            if (question.allowMore) {
                OutlinedButton(onClick = onMore, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(5.dp))
                    Text("MORE OPTIONS")
                }
            }
        }
        Button(
            onClick = { shareToChatGPT(context, ChatGptHandoff.forQuestion(seed, answers, question)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color(0xFF191429))
        ) {
            Icon(Icons.Default.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("TRY THIS DECISION WITH CHATGPT", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultScreen(result: ForgeResult, onReset: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OMEGA PROMPT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                    Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
                AssistChip(onClick = {}, label = { Text("${result.validation.score}/100") })
            }
        }

        if (result.recipe.isNotEmpty()) {
            Text("YOUR RECIPE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            result.recipe.forEach { Text("• ${it.option.label}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Prompt", "DNA", "Quality").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }

        when (tab) {
            0 -> {
                ElevatedCard { SelectionContainer { Text(result.prompt, Modifier.padding(18.dp)) } }
                Button(onClick = { copyText(context, result.prompt, "Omega prompt copied") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("COPY OMEGA PROMPT")
                }
                OutlinedButton(onClick = { shareToChatGPT(context, ChatGptHandoff.forFinal(result)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("TRY / IMPROVE WITH CHATGPT")
                }
            }
            1 -> DnaContent(result.genome)
            else -> QualityContent(result.validation)
        }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("FORGE ANOTHER") }
    }
}

@Composable
private fun DnaScreen(result: ForgeResult?) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("PROMPT DNA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        if (result == null) InfoCard("No genome yet", "Forge a prompt first. The latest prompt DNA will appear here.") else DnaContent(result.genome)
    }
}

@Composable
private fun DnaContent(g: AgentGenome) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GenomeField("Identity", g.identity)
        GenomeField("Mission", g.mission)
        GenomeField("Cognitive stack", g.primitives.joinToString("\n") { "• ${it.id.replace('_', ' ')}" })
        GenomeField("Workflow", g.workflow.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
        GenomeField("Constraints", g.constraints.joinToString("\n") { "• $it" })
        GenomeField("Output contract", g.outputContract.joinToString("\n") { "• $it" })
    }
}

@Composable
private fun GenomeField(label: String, value: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            Text(value)
        }
    }
}

@Composable
private fun QualityContent(v: ValidationResult) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Engineering score · ${v.score}/100", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        if (v.missing.isEmpty() && v.warnings.isEmpty()) GenomeField("Status", "Structural checks passed. This is a deterministic engineering check, not a claim of model quality.")
        if (v.missing.isNotEmpty()) GenomeField("Missing", v.missing.joinToString("\n") { "• $it" })
        if (v.warnings.isNotEmpty()) GenomeField("Warnings", v.warnings.joinToString("\n") { "• $it" })
    }
}

@Composable
private fun LibraryScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("COGNITIVE PRIMITIVES", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Reusable thinking operations the Foundry can compose into one prompt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimitiveLibrary.all.forEach { p ->
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.id.replace('_', ' '), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text(p.purpose)
                    Text(p.workflow.take(3).joinToString("  →  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ModelsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("CAPABILITY ROUTER", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Choose workers for jobs — not models for prestige.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(16.dp)) {
                Text("FREE-TIER FIRST", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                Text("No automatic paid fallback. The model list is a capability registry in this build; live provider API calls are not enabled yet.")
            }
        }
        ModelRegistry.candidates.forEach { m ->
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            Text(m.provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AssistChip(onClick = {}, label = { Text(m.badge) })
                    }
                    Text(m.bestFor)
                    Text(m.capabilities.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(m.freeTier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(m.caveat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        GenomeField("ChatGPT external expert", "Every adaptive decision and final prompt can be handed off to the ChatGPT app. ChatGPT remains optional and separate from paid API usage.")
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        GenomeField("Default interview", "Smart · 6 adaptive questions")
        GenomeField("Model policy", "Auto route · Free-tier first · No automatic paid fallback")
        GenomeField("External expert", "ChatGPT handoff through Android share / app intent")
        GenomeField("Privacy", "v0.2 generates interviews and prompts locally. Provider model cards are routing candidates only.")
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private object ChatGptHandoff {
    fun forQuestion(seed: String, answers: List<InterviewAnswer>, q: InterviewQuestion) = buildString {
        appendLine("I am engineering a prompt in Prompt Foundry.")
        appendLine("Seed: $seed")
        if (answers.isNotEmpty()) {
            appendLine("Choices so far:")
            answers.forEach { appendLine("- ${it.option.label}: ${it.option.description}") }
        }
        appendLine()
        appendLine("Current decision: ${q.title}")
        appendLine("Available options:")
        q.options.forEach { appendLine("- ${it.label}: ${it.description}") }
        appendLine()
        appendLine("Choose the strongest option for this exact seed. If none is strong enough, propose one better option. Give the choice first and keep the explanation concise.")
    }

    fun forFinal(result: ForgeResult) = buildString {
        appendLine("Act as a senior prompt engineer. Improve the prompt below only if you can make its behavior materially stronger. Preserve intent, remove redundancy, repair contradictions, and return a practical first-message prompt for a fresh ChatGPT conversation.")
        appendLine()
        appendLine("PROMPT")
        append(result.prompt)
    }
}

private fun copyText(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Prompt Foundry", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

private fun shareToChatGPT(context: Context, text: String) {
    val direct = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        setPackage("com.openai.chatgpt")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        val generic = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(generic, "Try with ChatGPT"))
    }
}
