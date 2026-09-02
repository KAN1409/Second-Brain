@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivityV3 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { V3Theme { V3App() } }
    }
}

private val V3Colors = darkColorScheme(
    primary = Color(0xFFFFB15A), onPrimary = Color(0xFF211609), primaryContainer = Color(0xFF4A3016), onPrimaryContainer = Color(0xFFFFDDB6),
    secondary = Color(0xFFC7B9FF), tertiary = Color(0xFF7EDDC5), background = Color(0xFF0C0D10), surface = Color(0xFF16181D),
    surfaceVariant = Color(0xFF22252B), onSurface = Color(0xFFF5F2F6), onSurfaceVariant = Color(0xFFB8BBC4), outline = Color(0xFF3C4048)
)

@Composable private fun V3Theme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = V3Colors, content = content) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V3App() {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(FoundryTool.FORGE) }
    var latest by remember { mutableStateOf<ForgeResult?>(null) }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.width(310.dp)) {
            Column(Modifier.fillMaxHeight().padding(vertical = 18.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Default.AutoAwesome, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(12.dp)); Column { Text("PROMPT FOUNDRY", fontWeight = FontWeight.Black, letterSpacing = 1.sp); Text("Prompt engineering workbench", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                FoundryTool.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Column { Text(item.label, fontWeight = if (item == tool) FontWeight.Bold else FontWeight.Medium); Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        selected = item == tool, onClick = { tool = item; scope.launch { drawer.close() } }, icon = { Icon(v3Icon(item), null) }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.weight(1f)); Text("v0.3.0 · PROMPT WORKBENCH", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) {
        Scaffold(topBar = {
            CenterAlignedTopAppBar(
                title = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(tool.label.uppercase(), fontWeight = FontWeight.Black, letterSpacing = 1.sp); if (tool in v3WorkTools) Text("Adaptive prompt engineering", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                actions = { if (tool != FoundryTool.MODELS) IconButton(onClick = { tool = FoundryTool.MODELS }) { Icon(Icons.Default.Memory, "Models") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tool) {
                    FoundryTool.FORGE, FoundryTool.TRANSFORM, FoundryTool.BREED, FoundryTool.MUTATE, FoundryTool.SYNTHESIZE -> V3Forge(tool) { latest = it }
                    FoundryTool.DNA -> V3DnaOverview(latest)
                    FoundryTool.LIBRARY -> V3Library()
                    FoundryTool.MODELS -> V3Models()
                    FoundryTool.SETTINGS -> V3Settings()
                }
            }
        }
    }
}

private val v3WorkTools = setOf(FoundryTool.FORGE, FoundryTool.TRANSFORM, FoundryTool.BREED, FoundryTool.MUTATE, FoundryTool.SYNTHESIZE)
private fun v3Icon(tool: FoundryTool): ImageVector = when (tool) { FoundryTool.FORGE -> Icons.Default.AutoAwesome; FoundryTool.TRANSFORM -> Icons.Default.Transform; FoundryTool.BREED -> Icons.Default.CallMerge; FoundryTool.MUTATE -> Icons.Default.Shuffle; FoundryTool.SYNTHESIZE -> Icons.Default.MergeType; FoundryTool.DNA -> Icons.Default.Science; FoundryTool.LIBRARY -> Icons.Default.LibraryBooks; FoundryTool.MODELS -> Icons.Default.Memory; FoundryTool.SETTINGS -> Icons.Default.Settings }
private fun v3Mode(tool: FoundryTool) = when (tool) { FoundryTool.BREED -> ForgeMode.BREED; FoundryTool.SYNTHESIZE, FoundryTool.MUTATE -> ForgeMode.COMPOSER; FoundryTool.TRANSFORM -> ForgeMode.IMPROVE; else -> ForgeMode.FORGE }

@Composable
private fun V3Forge(tool: FoundryTool, onResult: (ForgeResult) -> Unit) {
    var seed by remember(tool) { mutableStateOf("") }; var depth by remember(tool) { mutableStateOf(ForgeDepth.SMART) }; var started by remember(tool) { mutableStateOf(false) }
    var answers by remember(tool) { mutableStateOf<List<InterviewAnswer>>(emptyList()) }; var index by remember(tool) { mutableIntStateOf(0) }; var variant by remember(tool) { mutableIntStateOf(0) }; var result by remember(tool) { mutableStateOf<ForgeResult?>(null) }
    val questions = remember(seed, depth, answers, variant) { AdaptiveInterviewEngine.build(seed, depth, answers, variant) }
    result?.let { forged -> V3Workbench(forged) { result = null; answers = emptyList(); index = 0; started = false }; return }
    if (!started) { V3Seed(tool, seed, { seed = it }, depth, { depth = it }) { answers = emptyList(); index = 0; started = true }; return }
    val q = questions.getOrElse(index) { questions.last() }
    V3Interview(seed, depth, q, index, questions.size, answers, onBack = { if (index > 0) index-- else started = false }, onMore = { variant++ }, onAuto = {
        val picked = AdaptiveInterviewEngine.autoPick(q, seed, answers); val next = answers.filterNot { it.questionId == q.id } + InterviewAnswer(q.id, picked); answers = next
        if (index >= questions.lastIndex) { val forged = PromptEngine.forge(seed, v3Mode(tool), next); result = forged; onResult(forged) } else index++
    }, onPick = { picked ->
        val next = answers.filterNot { it.questionId == q.id } + InterviewAnswer(q.id, picked); answers = next
        if (index >= questions.lastIndex) { val forged = PromptEngine.forge(seed, v3Mode(tool), next); result = forged; onResult(forged) } else index++
    })
}

@Composable
private fun V3Seed(tool: FoundryTool, seed: String, onSeed: (String) -> Unit, depth: ForgeDepth, onDepth: (ForgeDepth) -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(8.dp)); Text("GIVE ME THE SEED.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, lineHeight = 42.sp)
        Text(when (tool) { FoundryTool.BREED -> "Drop the concepts or prompt DNA. The Foundry will ask only the decisions that matter."; FoundryTool.MUTATE -> "Drop the prompt or idea. Shape the mutation with taps, not a long brief."; FoundryTool.SYNTHESIZE -> "Drop the material. The Foundry will compose one Omega prompt from it."; FoundryTool.TRANSFORM -> "Drop what exists. The Foundry will determine how it should change."; else -> "A few words are enough. The Foundry will interview the idea with adaptive choices." }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = seed, onValueChange = onSeed, modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp), placeholder = { Text("Cortex home screen\nAI Heist\nMake photos look better\nCrazy product idea…") }, label = { Text("Seed") }, shape = RoundedCornerShape(18.dp))
                Text("INTERVIEW DEPTH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { ForgeDepth.entries.forEach { d -> FilterChip(selected = d == depth, onClick = { onDepth(d) }, label = { Text("${d.label} · ${d.questionCount}") }) } }
                Button(onClick = onStart, enabled = seed.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("START ADAPTIVE FORGE", fontWeight = FontWeight.Black) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) { V3Status("AUTO ROUTER", "Free-tier first", Icons.Default.Memory, Modifier.weight(1f)); V3Status("CHATGPT", "External expert", Icons.Default.OpenInNew, Modifier.weight(1f)) }
    }
}

@Composable private fun V3Status(title: String, subtitle: String, icon: ImageVector, modifier: Modifier) { Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column { Text(title, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }

@Composable
private fun V3Interview(seed: String, depth: ForgeDepth, q: InterviewQuestion, step: Int, total: Int, answers: List<InterviewAnswer>, onBack: () -> Unit, onMore: () -> Unit, onAuto: () -> Unit, onPick: (InterviewOption) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Column(Modifier.weight(1f)) { Text("${depth.label.uppercase()} FORGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black); Text(seed, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) }; Text("${step + 1}/$total", fontWeight = FontWeight.Black) }
        LinearProgressIndicator(progress = { (step + 1f) / total }, modifier = Modifier.fillMaxWidth()); Text(q.eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black); Text(q.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); Text(q.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        q.options.forEach { option -> ElevatedCard(Modifier.fillMaxWidth().clickable { onPick(option) }, shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(option.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(option.description, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { OutlinedButton(onClick = onAuto, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(5.dp)); Text("AUTO PICK") }; if (q.allowMore) OutlinedButton(onClick = onMore, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(5.dp)); Text("MORE OPTIONS") } }
        Button(onClick = { v3ShareChatGPT(context, V3Handoff.question(seed, answers, q)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color(0xFF191429))) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("TRY THIS DECISION WITH CHATGPT", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun V3Workbench(result: ForgeResult, onReset: () -> Unit) {
    val context = LocalContext.current; var tab by remember { mutableIntStateOf(0) }; var tuning by remember(result) { mutableStateOf(PromptWorkbenchEngine.initialTuning(result)) }; var activeRecipe by remember(result) { mutableStateOf(result.recipe.map { it.questionId }.toSet()) }; var disabled by remember(result) { mutableStateOf(emptySet<BuildModule>()) }; var editing by remember(result) { mutableStateOf(false) }; var manualPrompt by remember(result) { mutableStateOf<String?>(null) }; var mutationIndex by remember(result) { mutableIntStateOf(0) }
    val generated = PromptWorkbenchEngine.rebuild(result, activeRecipe, tuning, disabled); val prompt = manualPrompt ?: generated; val liveScore = PromptWorkbenchEngine.scoreWithWorkbench(result.validation.score, tuning, disabled)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(4.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) { Icon(Icons.Default.AutoAwesome, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("OMEGA PROMPT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black); Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis) }; Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.background.copy(alpha = .35f)) { Text("$liveScore/100", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Black) } }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    V3Action(Icons.Default.ContentCopy, "Copy", Modifier.weight(1f)) { v3Copy(context, prompt, "Omega prompt copied") }
                    V3Action(Icons.Default.OpenInNew, "ChatGPT", Modifier.weight(1f)) { v3ShareChatGPT(context, V3Handoff.execute(prompt)) }
                    V3Action(Icons.Default.Shuffle, "Mutate", Modifier.weight(1f)) { val mutation = PromptMutation.entries[mutationIndex % PromptMutation.entries.size]; tuning = PromptWorkbenchEngine.mutate(tuning, mutation); manualPrompt = null; mutationIndex++; Toast.makeText(context, "${mutation.label} mutation applied", Toast.LENGTH_SHORT).show() }
                    V3Action(Icons.Default.Edit, if (editing) "Done" else "Edit", Modifier.weight(1f)) { if (!editing && manualPrompt == null) manualPrompt = prompt; editing = !editing }
                }
            }
        }
        if (result.recipe.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("RECIPE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black); Text("Tap a chip to include or exclude it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = { activeRecipe = result.recipe.map { it.questionId }.toSet(); manualPrompt = null }) { Text("RESET") } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { result.recipe.forEach { answer -> FilterChip(selected = answer.questionId in activeRecipe, onClick = { activeRecipe = if (answer.questionId in activeRecipe) activeRecipe - answer.questionId else activeRecipe + answer.questionId; manualPrompt = null }, label = { Text(answer.option.label) }, leadingIcon = if (answer.questionId in activeRecipe) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null) } }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("WHY THIS RECIPE?", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Text(PromptWorkbenchEngine.whyRecipe(result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) { listOf("Prompt", "DNA", "Build", "Quality").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium) }) } }
        when (tab) {
            0 -> if (editing) OutlinedTextField(value = manualPrompt ?: prompt, onValueChange = { manualPrompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp), label = { Text("Edit Omega Prompt") }, shape = RoundedCornerShape(18.dp)) else ElevatedCard(shape = RoundedCornerShape(20.dp)) { SelectionContainer { Text(prompt, Modifier.padding(18.dp), lineHeight = 21.sp) } }
            1 -> V3Dna(tuning) { tuning = it; manualPrompt = null }
            2 -> V3Build(result, disabled) { disabled = it; manualPrompt = null }
            else -> V3Quality(result.validation, liveScore, tuning, disabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f)); Text("NEXT MOVE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { OutlinedButton(onClick = { tuning = PromptWorkbenchEngine.mutate(tuning, PromptMutation.WILDER); manualPrompt = null; tab = 1 }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Whatshot, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("WILDER") }; OutlinedButton(onClick = { manualPrompt = PromptWorkbenchEngine.compact(result, tuning); editing = false; tab = 0; Toast.makeText(context, "Compact variant created", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Compress, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("COMPRESS") } }
        Button(onClick = { v3ShareChatGPT(context, V3Handoff.reengineer(prompt, tuning)) }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color(0xFF191429)), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text("ASK CHATGPT TO RE-ENGINEER", fontWeight = FontWeight.Black) }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("FORGE ANOTHER") }; Spacer(Modifier.height(18.dp))
    }
}

@Composable private fun V3Action(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) { Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background.copy(alpha = .38f)) { Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } } }

@Composable private fun V3Dna(t: PromptTuning, onChange: (PromptTuning) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { V3Notice("LIVE PROMPT DNA", "Move a slider and the Omega Prompt changes immediately."); V3Slider("Creativity", t.creativity, "Proven", "Inventive", "Alien") { onChange(t.copy(creativity = it)) }; V3Slider("Criticism", t.criticism, "Gentle", "Critical", "Ruthless") { onChange(t.copy(criticism = it)) }; V3Slider("Autonomy", t.autonomy, "Literal", "Proactive", "Agentic") { onChange(t.copy(autonomy = it)) }; V3Slider("Divergence", t.divergence, "Narrow", "Explore", "Far-field") { onChange(t.copy(divergence = it)) }; V3Slider("Practicality", t.practicality, "Speculative", "Balanced", "Buildable") { onChange(t.copy(practicality = it)) }; V3Slider("Verbosity", t.verbosity, "Dense", "Balanced", "Expansive") { onChange(t.copy(verbosity = it)) } } }

@Composable private fun V3Slider(label: String, value: Int, low: String, mid: String, high: String, onValue: (Int) -> Unit) { ElevatedCard(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(value.toString(), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }; Slider(value = value.toFloat(), onValueChange = { onValue(it.toInt()) }, valueRange = 0f..100f); Row(Modifier.fillMaxWidth()) { Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(mid, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }

@Composable
private fun V3Build(result: ForgeResult, disabled: Set<BuildModule>, onDisabled: (Set<BuildModule>) -> Unit) {
    var expanded by remember { mutableStateOf<BuildModule?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        V3Notice("PROMPT ARCHITECTURE", "Turn modules on or off. Identity and Mission remain locked because they anchor the prompt."); V3Locked("Identity", result.genome.identity); V3Locked("Mission", result.genome.mission)
        BuildModule.entries.forEach { module -> val enabled = module !in disabled; ElevatedCard(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountTree, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(module.label, fontWeight = FontWeight.Bold); Text(module.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = enabled, onCheckedChange = { checked -> onDisabled(if (checked) disabled - module else disabled + module) }) }; TextButton(onClick = { expanded = if (expanded == module) null else module }) { Text(if (expanded == module) "COLLAPSE" else "INSPECT"); Spacer(Modifier.width(4.dp)); Icon(if (expanded == module) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(17.dp)) }; if (expanded == module) Text(v3ModulePreview(result, module), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
}

@Composable private fun V3Locked(label: String, value: String) { ElevatedCard(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Bold); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) } } } }
private fun v3ModulePreview(r: ForgeResult, m: BuildModule): String = when (m) { BuildModule.COGNITION -> r.genome.primitives.joinToString("\n") { "• ${it.id.replace('_', ' ')}" }; BuildModule.WORKFLOW -> r.genome.workflow.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"); BuildModule.CONSTRAINTS -> r.genome.constraints.joinToString("\n") { "• $it" }; BuildModule.USER_CONTROL -> r.genome.userControls.joinToString("\n") { "• $it" }; BuildModule.FAILURE_RESISTANCE -> r.genome.failureModes.joinToString("\n") { "• $it" }; BuildModule.OUTPUT -> r.genome.outputContract.joinToString("\n") { "• $it" }; BuildModule.CONTINUITY -> r.genome.continuityRules.joinToString("\n") { "• $it" }; BuildModule.STOP -> r.genome.stopConditions.joinToString("\n") { "• $it" } }

@Composable private fun V3Quality(v: ValidationResult, live: Int, t: PromptTuning, disabled: Set<BuildModule>) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("ENGINEERING SCORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black); Text("$live / 100", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black) }; Icon(Icons.Default.Verified, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) } }; V3Field("DNA balance", "Creativity ${t.creativity} · Criticism ${t.criticism} · Autonomy ${t.autonomy} · Divergence ${t.divergence} · Practicality ${t.practicality} · Verbosity ${t.verbosity}"); if (disabled.isNotEmpty()) V3Field("Workbench changes", "Disabled modules: ${disabled.joinToString { it.label }}"); if (v.missing.isEmpty() && v.warnings.isEmpty() && disabled.isEmpty()) V3Field("Status", "Structural checks passed. This score evaluates prompt structure, not the quality of any model's future answer."); if (v.missing.isNotEmpty()) V3Field("Missing", v.missing.joinToString("\n") { "• $it" }); if (v.warnings.isNotEmpty()) V3Field("Warnings", v.warnings.joinToString("\n") { "• $it" }) } }

@Composable private fun V3DnaOverview(result: ForgeResult?) { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("PROMPT DNA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); if (result == null) V3Notice("No genome yet", "Forge a prompt first. Its DNA and architecture will appear here.") else { val t = PromptWorkbenchEngine.initialTuning(result); V3Field("Identity", result.genome.identity); V3Field("Cognitive stack", result.genome.primitives.joinToString(" · ") { it.id.replace('_', ' ') }); V3Field("Default DNA", "Creativity ${t.creativity} · Criticism ${t.criticism} · Autonomy ${t.autonomy} · Divergence ${t.divergence} · Practicality ${t.practicality} · Verbosity ${t.verbosity}") } } }
@Composable private fun V3Library() { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("COGNITIVE PRIMITIVES", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); Text("Reusable thinking operations the Foundry composes into prompt architectures.", color = MaterialTheme.colorScheme.onSurfaceVariant); PrimitiveLibrary.all.forEach { p -> ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(p.id.replace('_', ' '), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Text(p.purpose); Text(p.workflow.take(3).joinToString("  →  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } }
@Composable private fun V3Models() { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("CAPABILITY ROUTER", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); V3Notice("FREE-TIER FIRST", "No automatic paid fallback. Live provider calls are still disabled in this build."); ModelRegistry.candidates.forEach { m -> ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(m.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black); Text(m.provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; AssistChip(onClick = {}, label = { Text(m.badge) }) }; Text(m.bestFor); Text(m.capabilities.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(m.freeTier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary); Text(m.caveat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } }
@Composable private fun V3Settings() { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); V3Field("Default interview", "Smart · 6 adaptive questions"); V3Field("Model policy", "Auto route · Free-tier first · No automatic paid fallback"); V3Field("External expert", "ChatGPT handoff through Android share / app intent"); V3Field("Workbench", "Live DNA tuning · editable recipe · architecture modules · compact variants") } }
@Composable private fun V3Notice(title: String, body: String) { Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun V3Field(label: String, value: String) { ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black); Text(value) } } }

private object V3Handoff {
    fun question(seed: String, answers: List<InterviewAnswer>, q: InterviewQuestion) = buildString { appendLine("I am engineering a prompt in Prompt Foundry."); appendLine("Seed: $seed"); if (answers.isNotEmpty()) { appendLine("Choices so far:"); answers.forEach { appendLine("- ${it.option.label}: ${it.option.description}") } }; appendLine(); appendLine("Current decision: ${q.title}"); appendLine("Available options:"); q.options.forEach { appendLine("- ${it.label}: ${it.description}") }; appendLine(); appendLine("Choose the strongest option for this exact seed. If none is strong enough, propose one better option. Give the choice first and keep the explanation concise.") }
    fun execute(prompt: String) = "Use the prompt below as the operating protocol for this conversation. If a tiny repair is needed for clarity, repair it without changing the intent, then execute it.\n\n$prompt"
    fun reengineer(prompt: String, t: PromptTuning) = buildString { appendLine("You are the external senior prompt engineer for Prompt Foundry."); appendLine("Re-engineer the prompt below. Preserve its objective, remove redundancy, resolve contradictions, and make each instruction behaviorally useful."); appendLine("Desired DNA: creativity ${t.creativity}/100, criticism ${t.criticism}/100, autonomy ${t.autonomy}/100, divergence ${t.divergence}/100, practicality ${t.practicality}/100, verbosity ${t.verbosity}/100."); appendLine("Return only the improved first-message prompt, ready to paste into a fresh ChatGPT conversation."); appendLine(); appendLine("CURRENT PROMPT"); append(prompt) }
}

private fun v3Copy(context: Context, text: String, toast: String) { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Prompt Foundry", text)); Toast.makeText(context, toast, Toast.LENGTH_SHORT).show() }
private fun v3ShareChatGPT(context: Context, text: String) { val direct = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); setPackage("com.openai.chatgpt"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; try { context.startActivity(direct) } catch (_: ActivityNotFoundException) { val generic = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }; context.startActivity(Intent.createChooser(generic, "Try with ChatGPT")) } }
