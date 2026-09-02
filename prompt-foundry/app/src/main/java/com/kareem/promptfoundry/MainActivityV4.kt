@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivityV4 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { V4Theme { PromptFoundryV4() } }
    }
}

private val V4Colors = darkColorScheme(
    primary = Color(0xFFFFB15A), onPrimary = Color(0xFF211609),
    primaryContainer = Color(0xFF4A3016), onPrimaryContainer = Color(0xFFFFDDB6),
    secondary = Color(0xFFC7B9FF), tertiary = Color(0xFF74E1C3),
    background = Color(0xFF0B0C0F), surface = Color(0xFF15171B),
    surfaceVariant = Color(0xFF22252B), onSurface = Color(0xFFF6F3F7),
    onSurfaceVariant = Color(0xFFB7BBC4), outline = Color(0xFF3A3F48)
)

@Composable private fun V4Theme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = V4Colors, content = content)

private enum class V4Page(val label: String) { FORGE("Forge"), MODELS("Models"), LIBRARY("Library"), SETTINGS("Settings") }

@Composable
private fun PromptFoundryV4() {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(V4Page.FORGE) }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.width(300.dp)) {
            Column(Modifier.fillMaxHeight().padding(vertical = 18.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column { Text("PROMPT FOUNDRY", fontWeight = FontWeight.Black); Text("Adaptive Cascade", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                V4Page.entries.forEach { item ->
                    NavigationDrawerItem(selected = page == item, onClick = { page = item; scope.launch { drawer.close() } },
                        icon = { Icon(when(item){V4Page.FORGE->Icons.Default.AutoAwesome;V4Page.MODELS->Icons.Default.Memory;V4Page.LIBRARY->Icons.Default.LibraryBooks;V4Page.SETTINGS->Icons.Default.Settings}, null) },
                        label = { Text(item.label, fontWeight = if(page==item) FontWeight.Black else FontWeight.Medium) }, modifier = Modifier.padding(horizontal = 10.dp))
                }
                Spacer(Modifier.weight(1f))
                V4Notice("ZERO-COST FIREWALL", "Free-tier routes only. No automatic paid fallback.")
                Text("v0.4.0", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(page.label.uppercase(), fontWeight = FontWeight.Black); Text("Prompt engineering workbench", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } }, actions = { if(page != V4Page.MODELS) IconButton(onClick = { page = V4Page.MODELS }) { Icon(Icons.Default.Memory, "Models") } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when(page) { V4Page.FORGE -> V4Forge(); V4Page.MODELS -> V4Models(); V4Page.LIBRARY -> V4Library(); V4Page.SETTINGS -> V4Settings() }
            }
        }
    }
}

@Composable
private fun V4Forge() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seed by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf(ForgeDepth.SMART) }
    var started by remember { mutableStateOf(false) }
    var answers by remember { mutableStateOf<List<InterviewAnswer>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var variant by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ForgeResult?>(null) }
    var liveQuestion by remember { mutableStateOf<InterviewQuestion?>(null) }
    var source by remember { mutableStateOf("LOCAL ENGINE") }
    var busy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    result?.let { V4Result(it, onReset = { result = null; started = false; answers = emptyList(); index = 0 }) ; return }
    if (!started) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("ONE SEED.\nTHE FOUNDRY DOES THE REST.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, lineHeight = 42.sp)
            Text("اكتب الفكرة الخام بس. الباقي decisions جاهزة بتتغير حسب الفكرة واختياراتك.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ElevatedCard(shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(seed, {seed=it}, Modifier.fillMaxWidth().heightIn(min=120.dp), label={Text("Seed")}, placeholder={Text("مثلاً: عاوز prompt يخلي الصور أجمل من غير ما يغير الشخص")}, shape=RoundedCornerShape(18.dp))
                Text("INTERVIEW DEPTH", style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Black, color=MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { ForgeDepth.entries.forEach { d -> FilterChip(selected=depth==d,onClick={depth=d},label={Text("${d.label} · ${d.questionCount}")}) } }
                Button(onClick={ if(seed.isBlank()) Toast.makeText(context,"Give the Foundry a seed first",Toast.LENGTH_SHORT).show() else { started=true; answers=emptyList(); index=0; liveQuestion=null } }, Modifier.fillMaxWidth().height(54.dp), shape=RoundedCornerShape(17.dp)) { Icon(Icons.Default.AutoAwesome,null); Spacer(Modifier.width(8.dp)); Text("START FORGE",fontWeight=FontWeight.Black) }
            } }
            V4Notice("Adaptive Cascade", "Each answer can change the next decision. With a configured free-tier key, the app asks a live model for seed-specific options; without one, the local engine remains fully usable.")
        }
        return
    }

    val localQuestions = AdaptiveInterviewEngine.build(seed, depth, answers, variant)
    val local = localQuestions.getOrElse(index) { localQuestions.last() }
    val q = liveQuestion ?: local
    LaunchedEffect(seed, index, answers, variant) {
        if (!ProviderPrefs.aiEnabled(context) || !ProviderEngine.hasAnyKey(context)) return@LaunchedEffect
        busy = true
        val r = ProviderEngine.generateQuestion(context, seed, answers, index, depth.questionCount, local)
        busy = false
        if (r.provider != null && r.question != null) { liveQuestion=r.question; source=r.sourceLabel; aiError=r.error }
        else if(!r.error.isNullOrBlank()) aiError=r.error
    }
    fun finish(next: List<InterviewAnswer>) { result = PromptEngine.forge(seed, ForgeMode.FORGE, next) }
    fun pick(option: InterviewOption) {
        val next = answers.filterNot { it.questionId == q.id } + InterviewAnswer(q.id, option)
        answers=next
        if(index >= depth.questionCount-1 || index>=localQuestions.lastIndex) finish(next) else { index++; liveQuestion=null; source="LOCAL ENGINE"; aiError=null }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={ if(index>0){index--;answers=answers.dropLast(1);liveQuestion=null}else started=false }){Icon(Icons.Default.ArrowBack,"Back")}; LinearProgressIndicator(progress={(index+1f)/depth.questionCount},Modifier.weight(1f).height(6.dp)); Spacer(Modifier.width(10.dp)); Text("${index+1}/${depth.questionCount}",fontWeight=FontWeight.Black) }
        Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)){Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){if(busy) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp) else Icon(if(source.startsWith("LOCAL")) Icons.Default.PhoneAndroid else Icons.Default.AutoAwesome,null,Modifier.size(15.dp),tint=MaterialTheme.colorScheme.tertiary);Spacer(Modifier.width(6.dp));Text(if(busy)"GENERATING ADAPTIVE OPTIONS…" else source,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Black)}}
        Text(q.eyebrow,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)
        Text(q.title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
        if(q.hint.isNotBlank()) Text(q.hint,color=MaterialTheme.colorScheme.onSurfaceVariant)
        q.options.forEach { option -> ElevatedCard(onClick={pick(option)},shape=RoundedCornerShape(19.dp)){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(option.label,fontWeight=FontWeight.Black);Text(option.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.primary)}} }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick={pick(AdaptiveInterviewEngine.autoPick(q,seed,answers))},label={Text("AUTO PICK")},leadingIcon={Icon(Icons.Default.Bolt,null,Modifier.size(17.dp))})
            if(q.allowMore) AssistChip(onClick={variant++;liveQuestion=null;source="LOCAL ENGINE"},label={Text("MORE OPTIONS")},leadingIcon={Icon(Icons.Default.Refresh,null,Modifier.size(17.dp))})
            AssistChip(onClick={shareChatGPT(context,decisionPrompt(seed,answers,q))},label={Text("TRY WITH CHATGPT")},leadingIcon={Icon(Icons.Default.OpenInNew,null,Modifier.size(17.dp))})
        }
        OutlinedButton(onClick={var next=answers; for(i in index until depth.questionCount){val qs=AdaptiveInterviewEngine.build(seed,depth,next,variant);val qq=qs.getOrNull(i)?:break;next=next.filterNot{it.questionId==qq.id}+InterviewAnswer(qq.id,AdaptiveInterviewEngine.autoPick(qq,seed,next))};answers=next;finish(next)},Modifier.fillMaxWidth()){Icon(Icons.Default.FastForward,null);Spacer(Modifier.width(7.dp));Text("FORGE THE REST FOR ME")}
        aiError?.let{Text("Fallback: $it",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable
private fun V4Result(result: ForgeResult, onReset:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var tuning by remember{mutableStateOf(PromptWorkbenchEngine.initialTuning(result))}
    var activeIds by remember{mutableStateOf(result.recipe.map{it.questionId}.toSet())}
    var disabled by remember{mutableStateOf<Set<BuildModule>>(emptySet())}
    var aiPrompt by remember{mutableStateOf<String?>(null)}
    var aiSource by remember{mutableStateOf<String?>(null)}
    var aiError by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val localPrompt=remember(result,activeIds,tuning,disabled){PromptWorkbenchEngine.rebuild(result,activeIds,tuning,disabled)}
    val prompt=aiPrompt?:localPrompt
    val score=PromptWorkbenchEngine.scoreWithWorkbench(result.validation.score,tuning,disabled)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("OMEGA PROMPT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Black);Text(result.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Black,maxLines=2,overflow=TextOverflow.Ellipsis)};Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){Text("$score",Modifier.padding(horizontal=14.dp,vertical=9.dp),fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={copyText(context,prompt,"Prompt copied")}){Icon(Icons.Default.ContentCopy,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("COPY")}
            AssistChip(onClick={shareChatGPT(context,"Use this prompt as the operating protocol for this conversation:\n\n$prompt")},label={Text("CHATGPT")},leadingIcon={Icon(Icons.Default.OpenInNew,null,Modifier.size(17.dp))})
            AssistChip(onClick={tuning=PromptWorkbenchEngine.mutate(tuning,PromptMutation.WILDER);aiPrompt=null},label={Text("WILDER")})
            AssistChip(onClick={tuning=PromptWorkbenchEngine.mutate(tuning,PromptMutation.SHARPER);aiPrompt=null},label={Text("SHARPER")})
            AssistChip(onClick={tuning=PromptWorkbenchEngine.mutate(tuning,PromptMutation.LEANER);aiPrompt=null},label={Text("LEANER")})
        }
        ElevatedCard(shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AutoFixHigh,null,tint=MaterialTheme.colorScheme.tertiary);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text("AI RE-ENGINEER",fontWeight=FontWeight.Black);Text(aiSource?:"Optional free-tier polishing pass",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(busy)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)};Button(enabled=!busy,onClick={scope.launch{busy=true;aiError=null;val r=ProviderEngine.enhancePrompt(context,result.title,localPrompt,result.recipe.filter{it.questionId in activeIds},tuning);busy=false;if(r.ok){aiPrompt=r.text;aiSource="Polished by ${r.sourceLabel}"}else aiError=r.error}}){Text("RE-ENGINEER")};aiError?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        Text("RECIPE",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Black)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){result.recipe.forEach{a->FilterChip(selected=a.questionId in activeIds,onClick={activeIds=if(a.questionId in activeIds)activeIds-a.questionId else activeIds+a.questionId;aiPrompt=null},label={Text(a.option.label)})}}
        V4Notice("WHY THIS RECIPE?",PromptWorkbenchEngine.whyRecipe(result))
        Text("PROMPT DNA",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Black)
        V4DnaSlider("Creativity",tuning.creativity){tuning=tuning.copy(creativity=it);aiPrompt=null};V4DnaSlider("Criticism",tuning.criticism){tuning=tuning.copy(criticism=it);aiPrompt=null};V4DnaSlider("Autonomy",tuning.autonomy){tuning=tuning.copy(autonomy=it);aiPrompt=null};V4DnaSlider("Divergence",tuning.divergence){tuning=tuning.copy(divergence=it);aiPrompt=null};V4DnaSlider("Practicality",tuning.practicality){tuning=tuning.copy(practicality=it);aiPrompt=null};V4DnaSlider("Verbosity",tuning.verbosity){tuning=tuning.copy(verbosity=it);aiPrompt=null}
        SelectionContainer{Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)){Text(prompt,Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)}}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={copyText(context,PromptWorkbenchEngine.compact(result,tuning),"Compact variant copied")},Modifier.weight(1f)){Text("COMPRESS")};Button(onClick=onReset,Modifier.weight(1f)){Text("FORGE ANOTHER")}}
    }
}

@Composable private fun V4DnaSlider(label:String,value:Int,onChange:(Int)->Unit){ElevatedCard(shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(horizontal=14.dp,vertical=8.dp)){Row{Text(label,Modifier.weight(1f),fontWeight=FontWeight.Bold);Text("$value",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Black)};Slider(value.toFloat(),{onChange(it.toInt())},valueRange=0f..100f)}}}

@Composable
private fun V4Models(){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var aiEnabled by remember{mutableStateOf(ProviderPrefs.aiEnabled(context))}
    var refresh by remember{mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("FREE-TIER ROUTER",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
        V4Notice("ZERO-COST FIREWALL","Add your own free-tier keys. The app never silently switches to a paid provider. Failed routes fall back to the local compiler.")
        ElevatedCard{Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Live adaptive AI",fontWeight=FontWeight.Black);Text("Generate seed-specific decisions when available",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(aiEnabled,onCheckedChange={aiEnabled=it;ProviderPrefs.setAiEnabled(context,it)})}}
        FreeProvider.entries.forEach{provider->
            var keyText by remember(refresh,provider){mutableStateOf("")}
            var status by remember(refresh,provider){mutableStateOf(if(SecureKeyStore.has(context,provider.slot))"KEY SAVED" else "NOT CONFIGURED")}
            var enabled by remember(refresh,provider){mutableStateOf(ProviderPrefs.providerEnabled(context,provider))}
            var testing by remember(provider){mutableStateOf(false)}
            ElevatedCard(shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(provider.label,fontWeight=FontWeight.Black);Text("${provider.modelLabel} · ${provider.role}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(enabled,onCheckedChange={enabled=it;ProviderPrefs.setProviderEnabled(context,provider,it)})};Text(status,style=MaterialTheme.typography.labelSmall,color=if(SecureKeyStore.has(context,provider.slot))MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Black);OutlinedTextField(keyText,{keyText=it},Modifier.fillMaxWidth(),label={Text("API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(enabled=keyText.isNotBlank(),onClick={SecureKeyStore.put(context,provider.slot,keyText);keyText="";status="KEY SAVED";refresh++}){Text("SAVE")};OutlinedButton(enabled=SecureKeyStore.has(context,provider.slot)&&!testing,onClick={scope.launch{testing=true;val r=ProviderEngine.test(context,provider);testing=false;status=if(r.ok&&r.text?.contains("FOUNDRY_OK")==true)"CONNECTED · ${r.sourceLabel}" else "FAILED · ${r.error?:"Unexpected response"}"}}){if(testing)CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp) else Text("TEST")};TextButton(enabled=SecureKeyStore.has(context,provider.slot),onClick={SecureKeyStore.clear(context,provider.slot);status="NOT CONFIGURED";refresh++}){Text("CLEAR")}}}}
        }
        V4Notice("Routing", "Adaptive options: Groq → Gemini → OpenRouter → Local. Omega re-engineer: Gemini → Groq → OpenRouter.")
    }
}

@Composable private fun V4Library(){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("COGNITIVE LIBRARY",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);Text("Reusable thinking operations the Foundry composes into prompt architectures.",color=MaterialTheme.colorScheme.onSurfaceVariant);PrimitiveLibrary.all.forEach{p->ElevatedCard{Column(Modifier.fillMaxWidth().padding(16.dp)){Text(p.id.replace('_',' '),fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary);Text(p.purpose);Text(p.workflow.take(3).joinToString(" → "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
@Composable private fun V4Settings(){val context=LocalContext.current;var enabled by remember{mutableStateOf(ProviderPrefs.aiEnabled(context))};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("SETTINGS",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);ElevatedCard{Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Adaptive AI questions",fontWeight=FontWeight.Black);Text("Free-tier first with local fallback",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(enabled,onCheckedChange={enabled=it;ProviderPrefs.setAiEnabled(context,it)})}};V4Notice("Input philosophy","Seed first · taps over typing · ready-made choices · ChatGPT optional at major decisions.");V4Notice("Workbench","Recipe toggles · live Prompt DNA · Wilder / Sharper / Leaner · compression · optional AI re-engineer.")}}
@Composable private fun V4Notice(title:String,body:String){Surface(Modifier.padding(horizontal=if(title=="ZERO-COST FIREWALL")14.dp else 0.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f)){Column(Modifier.padding(14.dp)){Text(title,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(4.dp));Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

private fun copyText(context:Context,text:String,toast:String){val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("Prompt Foundry",text));Toast.makeText(context,toast,Toast.LENGTH_SHORT).show()}
private fun shareChatGPT(context:Context,text:String){val direct=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text);setPackage("com.openai.chatgpt");addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)};try{context.startActivity(direct)}catch(_:ActivityNotFoundException){context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Try with ChatGPT"))}}
private fun decisionPrompt(seed:String,answers:List<InterviewAnswer>,q:InterviewQuestion)=buildString{appendLine("I am engineering a prompt in Prompt Foundry.");appendLine("Seed: $seed");if(answers.isNotEmpty()){appendLine("Choices so far:");answers.forEach{appendLine("- ${it.option.label}: ${it.option.description}")}};appendLine();appendLine("Current decision: ${q.title}");appendLine("Available options:");q.options.forEach{appendLine("- ${it.label}: ${it.description}")};appendLine();appendLine("Choose the strongest option for this exact seed. If none is strong enough, propose a better option. Give the choice first.")}
