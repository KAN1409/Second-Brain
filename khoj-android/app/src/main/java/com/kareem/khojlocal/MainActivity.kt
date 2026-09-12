package com.kareem.khojlocal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        private const val PICK_FILES = 4101
        private const val EXPORT_TEXT = 4102

        private val BG = Color.rgb(12, 13, 16)
        private val SURFACE = Color.rgb(20, 23, 28)
        private val SURFACE_2 = Color.rgb(27, 32, 39)
        private val BORDER = Color.rgb(42, 48, 56)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(164, 171, 181)
        private val ACCENT = Color.rgb(244, 166, 90)
        private val ACCENT_DARK = Color.rgb(54, 37, 24)
        private val SUCCESS = Color.rgb(114, 201, 158)
        private val DANGER = Color.rgb(229, 107, 119)
    }

    private enum class Tab(val label: String, val icon: String) {
        HOME("Home", "⌂"),
        ASK("Ask", "✦"),
        SEARCH("Search", "⌕"),
        LIBRARY("Library", "▤"),
        SETTINGS("Settings", "⚙")
    }

    private data class ChatMessage(
        val role: String,
        val text: String,
        val evidence: List<SearchHit> = emptyList(),
        val usedCloud: Boolean = false,
    )

    private lateinit var store: LocalBrainStore
    private lateinit var searchEngine: LocalSearchEngine
    private lateinit var aiSettings: AiSettings
    private lateinit var importer: FileImporter
    private lateinit var cloudClient: CloudAiClient
    private lateinit var answerEngine: BrainAnswerEngine

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var memoryStatus: TextView
    private lateinit var bottomBar: LinearLayout
    private var currentTab = Tab.HOME
    private val chatMessages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        store = LocalBrainStore(applicationContext)
        searchEngine = LocalSearchEngine(store)
        aiSettings = AiSettings(applicationContext)
        importer = FileImporter(applicationContext, store)
        cloudClient = CloudAiClient(aiSettings)
        answerEngine = BrainAnswerEngine(searchEngine, cloudClient, aiSettings)

        setContentView(buildShell())
        handleIncomingIntent(intent)
        selectTab(Tab.HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun configureWindow() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun buildShell(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setOnApplyWindowInsetsListener { view, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                view.setPadding(0, top, 0, bottom)
                insets
            }
        }

        root.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(6))
            setBackgroundColor(SURFACE)
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(13), dp(20), dp(11))
            setBackgroundColor(BG)
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(label("Khoj Local", 22f, TEXT, true))
        memoryStatus = label("", 11.5f, MUTED, false).apply { setPadding(0, dp(2), 0, 0) }
        titles.addView(memoryStatus)
        bar.addView(titles)

        val badge = label("PRIVATE", 10f, SUCCESS, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.rgb(18, 42, 34), dp(16).toFloat(), Color.TRANSPARENT)
        }
        bar.addView(badge)
        return bar
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        content.removeAllViews()
        val screen = when (tab) {
            Tab.HOME -> homeScreen()
            Tab.ASK -> askScreen()
            Tab.SEARCH -> searchScreen()
            Tab.LIBRARY -> libraryScreen("all")
            Tab.SETTINGS -> settingsScreen()
        }
        content.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rebuildBottomBar()
        refreshStatus()
    }

    private fun rebuildBottomBar() {
        bottomBar.removeAllViews()
        Tab.entries.forEach { tab ->
            val selected = tab == currentTab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener { selectTab(tab) }
            }

            val indicator = View(this).apply {
                setBackgroundColor(if (selected) ACCENT else Color.TRANSPARENT)
            }
            item.addView(indicator, LinearLayout.LayoutParams(dp(22), dp(2)).apply { bottomMargin = dp(4) })
            item.addView(label(tab.icon, 17f, if (selected) ACCENT else MUTED, false).apply { gravity = Gravity.CENTER })
            item.addView(label(tab.label, 10.5f, if (selected) TEXT else MUTED, selected).apply { gravity = Gravity.CENTER })
            bottomBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun refreshStatus() {
        val ai = if (aiSettings.isConfigured()) "AI connected" else "local answers"
        memoryStatus.text = "${store.count()} memories  •  $ai  •  on-device"
    }

    private fun homeScreen(): View {
        val (scroll, column) = scrollColumn()
        column.apply {
            addView(sectionEyebrow("YOUR BRAIN"))
            addView(sectionTitle("Private memory, ready when you are."))
            addView(label("Capture ideas, files and fragments. Khoj Local retrieves them without depending on a hosted service.", 14.5f, MUTED, false).withBottom(18))

            val brainCard = card().apply {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val left = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                left.addView(label(store.count().toString(), 30f, TEXT, true))
                left.addView(label("memories in your local brain", 12.5f, MUTED, false))
                row.addView(left)
                row.addView(statusPill(if (aiSettings.isConfigured()) "AI CONNECTED" else "LOCAL", if (aiSettings.isConfigured()) SUCCESS else ACCENT))
                addView(row)
            }
            addView(brainCard)

            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            actions.addView(primaryButton("Capture memory") { showMemoryEditor(null) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            actions.addView(secondaryButton("Import files") { openFilePicker() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
            addView(actions)

            addView(sectionTitle("Recent").withTop(26))
            val recent = store.listRecent(5)
            if (recent.isEmpty()) {
                addView(emptyState("Your brain is empty", "Add a memory, share text into Khoj Local, or import files. Your first items will appear here."))
            } else {
                recent.forEach { addView(memoryRow(it)) }
                if (store.count() > recent.size) {
                    addView(textButton("Open library") { selectTab(Tab.LIBRARY) }.withTop(6))
                }
            }
        }
        return scroll
    }

    private fun askScreen(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }
        header.addView(sectionEyebrow("ASK"))
        header.addView(sectionTitle("Talk to your memory."))
        header.addView(label(if (aiSettings.isConfigured()) "Answers use your retrieved evidence and your configured AI provider." else "Answers are built locally from retrieved evidence. Connect an AI provider only if you want generative RAG.", 13.5f, MUTED, false))
        outer.addView(header)

        val chatScroll = ScrollView(this).apply { isFillViewport = true }
        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(14)) }
        chatScroll.addView(chat, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (chatMessages.isEmpty()) {
            chat.addView(emptyState("Ask naturally", "Try a question about something you saved, a file you imported, or a detail you don't want to hunt for again."))
            val suggestions = listOf("What have I saved recently?", "Find something about…", "What do I know about…?")
            suggestions.forEach { prompt ->
                chat.addView(chipButton(prompt) { runAsk(prompt, chat, chatScroll) }.withTop(8))
            }
        } else {
            renderChat(chat)
        }

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = topBorderDrawable(SURFACE, BORDER)
        }
        val input = EditText(this).apply {
            hint = "Ask your memory…"
            setHintTextColor(Color.rgb(112, 119, 130))
            setTextColor(TEXT)
            textSize = 15f
            maxLines = 5
            minLines = 1
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(SURFACE_2, dp(18).toFloat(), BORDER)
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = Button(this).apply {
            text = "↑"
            textSize = 21f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, dp(24).toFloat(), Color.TRANSPARENT)
            setOnClickListener {
                val q = input.text.toString().trim()
                if (q.isNotBlank()) {
                    input.setText("")
                    runAsk(q, chat, chatScroll)
                }
            }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        outer.addView(composer)
        return outer
    }

    private fun runAsk(question: String, chat: LinearLayout, scroll: ScrollView) {
        chatMessages += ChatMessage("user", question)
        renderChat(chat)
        val loading = label("Thinking from your memory…", 12.5f, MUTED, false).apply { setPadding(dp(12), dp(10), 0, dp(8)) }
        chat.addView(loading)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        thread {
            val answer = try {
                answerEngine.answer(question)
            } catch (t: Throwable) {
                BrainAnswerEngine.Answer("Something went wrong while searching your memory: ${t.message ?: t.javaClass.simpleName}", emptyList(), false)
            }
            runOnUiThread {
                chatMessages += ChatMessage("assistant", answer.text, answer.evidence, answer.usedCloud)
                renderChat(chat)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun renderChat(chat: LinearLayout) {
        chat.removeAllViews()
        chatMessages.forEach { message ->
            if (message.role == "user") {
                val bubble = label(message.text, 15f, TEXT, false).apply {
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    background = rounded(Color.rgb(35, 31, 27), dp(18).toFloat(), Color.TRANSPARENT)
                }
                val wrap = LinearLayout(this).apply { gravity = Gravity.END; setPadding(dp(42), dp(5), 0, dp(5)); addView(bubble) }
                chat.addView(wrap)
            } else {
                val answerCard = card().apply {
                    addView(label(message.text, 14.5f, TEXT, false))
                    addView(label(if (message.usedCloud) "AI answer • grounded in local evidence" else "Local answer • extracted from evidence", 10.5f, if (message.usedCloud) SUCCESS else MUTED, true).withTop(10))
                    if (message.evidence.isNotEmpty()) {
                        addView(divider().withTop(12))
                        addView(label("Sources", 11f, MUTED, true).withTop(10))
                        message.evidence.take(4).forEach { hit ->
                            val source = textButton("${hit.memory.title.ifBlank { "Memory ${hit.memory.id}" }}  ·  ${(hit.score * 100).toInt()}%") { showMemoryEditor(hit.memory) }
                            addView(source.withTop(4))
                        }
                    }
                }
                chat.addView(answerCard.withTop(6))
            }
        }
    }

    private fun searchScreen(): View {
        val (scroll, column) = scrollColumn()
        column.addView(sectionEyebrow("RETRIEVE"))
        column.addView(sectionTitle("Find anything fast."))
        column.addView(label("Search titles, text, tags and file content already extracted into your brain.", 13.5f, MUTED, false).withBottom(14))

        val input = EditText(this).apply {
            hint = "Search your memory"
            setHintTextColor(Color.rgb(112, 119, 130))
            setTextColor(TEXT)
            textSize = 15f
            singleLine = true
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(SURFACE_2, dp(16).toFloat(), BORDER)
        }
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val count = label("Recent memories", 11.5f, MUTED, true).withTop(14)
        column.addView(count)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(results)

        fun render(query: String) {
            results.removeAllViews()
            val hits = if (query.isBlank()) store.listRecent(20).map { SearchHit(it, 0.0, 0.0, 0.0) } else searchEngine.search(query, 30)
            count.text = if (query.isBlank()) "Recent memories" else "${hits.size} results"
            if (hits.isEmpty()) {
                results.addView(emptyState("No match yet", "Try different words, or add/import more information into your brain."))
            } else {
                hits.forEach { hit -> results.addView(searchResultRow(hit, query.isNotBlank())) }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
        return scroll
    }

    private fun libraryScreen(filter: String): View {
        val (scroll, column) = scrollColumn()
        column.addView(sectionEyebrow("LIBRARY"))
        column.addView(sectionTitle("Everything your brain remembers."))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(primaryButton("New") { showMemoryEditor(null) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(secondaryButton("Import") { openFilePicker() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        column.addView(actions)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("all" to "All", "notes" to "Notes", "files" to "Files").forEach { (key, text) ->
            chips.addView(filterChip(text, key == filter) { content.removeAllViews(); content.addView(libraryScreen(key)) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
        }
        column.addView(chips.withTop(14))

        val search = EditText(this).apply {
            hint = "Filter this library"
            setHintTextColor(Color.rgb(112, 119, 130))
            setTextColor(TEXT)
            textSize = 14f
            singleLine = true
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(SURFACE_2, dp(15).toFloat(), BORDER)
        }
        column.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)

        fun render(q: String) {
            list.removeAllViews()
            val base = store.listRecent(500).filter {
                when (filter) {
                    "notes" -> it.source != "file"
                    "files" -> it.source == "file"
                    else -> true
                }
            }
            val shown = if (q.isBlank()) base else base.filter {
                val hay = "${it.title}\n${it.body}\n${it.tags}".lowercase()
                q.lowercase().split(Regex("\\s+")).filter(String::isNotBlank).all(hay::contains)
            }
            list.addView(label("${shown.size} shown", 11.5f, MUTED, true).withTop(14))
            if (shown.isEmpty()) list.addView(emptyState("Nothing here yet", "Use New, Import, or Android Share to add information."))
            else shown.forEach { list.addView(memoryRow(it)) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
        return scroll
    }

    private fun settingsScreen(): View {
        val (scroll, column) = scrollColumn()
        column.apply {
            addView(sectionEyebrow("SETTINGS"))
            addView(sectionTitle("Your brain, your rules."))

            addView(settingsRow("Brain & data", "${store.count()} memories stored privately on this device", "Export") { exportMemories() })
            addView(settingsRow("AI provider", if (aiSettings.isConfigured()) "${aiSettings.model} • connected configuration" else "Optional • local answers work without it", "Configure") { showAiSettingsDialog() })
            addView(settingsRow("Privacy", "No Khoj Cloud dependency. Imported files stay in app-private storage.", "Details") { showPrivacyDialog() })

            addView(sectionEyebrow("ABOUT").withTop(26))
            val about = card().apply {
                addView(label("Khoj Local 1.1", 16f, TEXT, true))
                addView(label("Native local-first Android second brain inspired by the open-source Khoj project. Search, retrieval and offline answers remain on-device by default.", 13f, MUTED, false).withTop(6))
                addView(label("Retrieval: FTS + lightweight local similarity. Generative AI is optional.", 11.5f, MUTED, false).withTop(10))
            }
            addView(about)
        }
        return scroll
    }

    private fun settingsRow(title: String, subtitle: String, action: String, onClick: () -> Unit): View {
        val row = card().apply {
            val horizontal = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            texts.addView(label(title, 15f, TEXT, true))
            texts.addView(label(subtitle, 12.5f, MUTED, false).withTop(4))
            horizontal.addView(texts)
            horizontal.addView(label(action, 12f, ACCENT, true).apply { setPadding(dp(12), dp(8), dp(6), dp(8)) })
            addView(horizontal)
            setOnClickListener { onClick() }
            isClickable = true
        }
        return row.withTop(8)
    }

    private fun showAiSettingsDialog() {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val endpoint = dialogInput("Endpoint", aiSettings.endpoint, false)
        val model = dialogInput("Model", aiSettings.model, false)
        val key = dialogInput("API key", aiSettings.apiKey, true)
        wrapper.addView(field("AI endpoint", endpoint))
        wrapper.addView(field("Model", model))
        wrapper.addView(field("API key (optional for local endpoints)", key))
        wrapper.addView(label("Works with OpenAI-compatible /v1/chat/completions endpoints, including many local servers.", 11.5f, MUTED, false).withTop(8))

        val dialog = AlertDialog.Builder(this)
            .setTitle("AI provider")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                aiSettings.endpoint = endpoint.text.toString()
                aiSettings.model = model.text.toString()
                aiSettings.apiKey = key.text.toString()
                refreshStatus()
                Toast.makeText(this, "AI settings saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (currentTab == Tab.SETTINGS) selectTab(Tab.SETTINGS)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                aiSettings.endpoint = endpoint.text.toString()
                aiSettings.model = model.text.toString()
                aiSettings.apiKey = key.text.toString()
                if (!aiSettings.isConfigured()) {
                    Toast.makeText(this, "Enter endpoint and model first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                thread {
                    val message = try { cloudClient.testConnection() } catch (t: Throwable) { "Connection failed: ${t.message ?: t.javaClass.simpleName}" }
                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Privacy")
            .setMessage("Memories and imported attachments live in this app's private storage. Local search and offline answers do not send your memory anywhere. If you configure an external AI endpoint, retrieved evidence used for a question is sent to that endpoint to generate the answer. Uninstalling the app removes its private data unless you export first.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showMemoryEditor(memory: Memory?) {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val title = dialogInput("Title", memory?.title.orEmpty(), false)
        val body = dialogInput("What do you want to remember?", memory?.body.orEmpty(), false).apply { minLines = 6; maxLines = 12; gravity = Gravity.TOP; singleLine = false }
        val tags = dialogInput("Tags", memory?.tags.orEmpty(), false)
        wrapper.addView(field("Title", title))
        wrapper.addView(field("Memory", body))
        wrapper.addView(field("Tags", tags))

        val builder = AlertDialog.Builder(this)
            .setTitle(if (memory == null) "New memory" else "Edit memory")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
        if (memory != null) builder.setNeutralButton("Delete", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                val b = body.text.toString().trim()
                if (t.isBlank() && b.isBlank()) {
                    Toast.makeText(this, "Memory can't be empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (memory == null) store.addMemory(t, b, "manual", tags.text.toString())
                else store.updateMemory(memory.id, t, b, tags.text.toString())
                dialog.dismiss()
                selectTab(currentTab)
            }
            if (memory != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(DANGER)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    store.deleteMemory(memory.id)
                    memory.attachmentPath?.let { runCatching { File(it).delete() } }
                    dialog.dismiss()
                    selectTab(currentTab)
                }
            }
        }
        dialog.show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_FILES)
    }

    private fun exportMemories() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "Khoj-Local-export-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.md")
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, EXPORT_TEXT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PICK_FILES -> {
                val uris = mutableListOf<Uri>()
                data.data?.let(uris::add)
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
                importUris(uris)
            }
            EXPORT_TEXT -> data.data?.let { uri ->
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(store.exportPlainText()) }
                }.onSuccess { Toast.makeText(this, "Export complete", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dialog = AlertDialog.Builder(this).setTitle("Importing").setView(ProgressBar(this).apply { isIndeterminate = true; setPadding(dp(24), dp(24), dp(24), dp(24)) }).setCancelable(false).create()
        dialog.show()
        thread {
            val result = importer.importUris(uris)
            runOnUiThread {
                dialog.dismiss()
                val message = buildString {
                    append("${result.imported} imported")
                    if (result.skipped > 0) append(" • ${result.skipped} duplicate")
                    if (result.errors.isNotEmpty()) append(" • ${result.errors.size} failed")
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                selectTab(Tab.LIBRARY)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedStream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    @Suppress("DEPRECATION")
    private fun sharedStreams(intent: Intent): ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotBlank()) {
                    store.addMemory(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text, "android-share")
                    Toast.makeText(this, "Saved to Khoj Local", Toast.LENGTH_SHORT).show()
                }
                sharedStream(intent)?.let { importUris(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                sharedStreams(intent)?.takeIf { it.isNotEmpty() }?.let { importUris(it) }
            }
        }
        refreshStatus()
    }

    private fun memoryRow(memory: Memory): View {
        val row = card().apply {
            isClickable = true
            setOnClickListener { showMemoryEditor(memory) }
            val top = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val source = if (memory.source == "file") "FILE" else if (memory.source == "android-share") "SHARED" else "NOTE"
            top.addView(statusPill(source, if (memory.source == "file") SUCCESS else MUTED))
            top.addView(label(relativeTime(memory.updatedAt), 10.5f, MUTED, false).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(top)
            addView(label(memory.title.ifBlank { "Untitled memory" }, 15f, TEXT, true).withTop(8))
            if (memory.body.isNotBlank()) addView(label(memory.body.replace("\n", " ").take(150), 12.5f, MUTED, false).withTop(4))
        }
        return row.withTop(8)
    }

    private fun searchResultRow(hit: SearchHit, showScore: Boolean): View {
        val row = card().apply {
            isClickable = true
            setOnClickListener { showMemoryEditor(hit.memory) }
            val top = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(hit.memory.title.ifBlank { "Untitled memory" }, 14.5f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (showScore) top.addView(statusPill("${(hit.score * 100).toInt()}%", ACCENT))
            addView(top)
            if (hit.memory.body.isNotBlank()) addView(label(hit.memory.body.replace("\n", " ").take(190), 12.5f, MUTED, false).withTop(5))
        }
        return row.withTop(8)
    }

    private fun emptyState(title: String, subtitle: String): View = card().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(label(title, 15f, TEXT, true))
        addView(label(subtitle, 12.5f, MUTED, false).withTop(5))
    }.withTop(8)

    private fun scrollColumn(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(column, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll to column
    }

    private fun sectionEyebrow(text: String) = label(text, 10.5f, ACCENT, true).apply { letterSpacing = 0.12f }
    private fun sectionTitle(text: String) = label(text, 25f, TEXT, true).apply { setPadding(0, dp(5), 0, dp(10)) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(SURFACE, dp(18).toFloat(), BORDER)
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(20, 16, 12))
        background = rounded(ACCENT, dp(16).toFloat(), Color.TRANSPARENT)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        background = rounded(SURFACE_2, dp(16).toFloat(), BORDER)
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, onClick: () -> Unit): TextView = label(text, 12.5f, ACCENT, true).apply {
        setPadding(dp(2), dp(7), dp(2), dp(7))
        setOnClickListener { onClick() }
        isClickable = true
    }

    private fun chipButton(text: String, onClick: () -> Unit): TextView = label(text, 12.5f, TEXT, false).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(SURFACE_2, dp(16).toFloat(), BORDER)
        setOnClickListener { onClick() }
        isClickable = true
    }

    private fun filterChip(text: String, selected: Boolean, onClick: () -> Unit): TextView = label(text, 12f, if (selected) ACCENT else MUTED, selected).apply {
        gravity = Gravity.CENTER
        background = rounded(if (selected) ACCENT_DARK else SURFACE, dp(15).toFloat(), if (selected) ACCENT_DARK else BORDER)
        setOnClickListener { onClick() }
        isClickable = true
    }

    private fun statusPill(text: String, color: Int): TextView = label(text, 9.5f, color, true).apply {
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = rounded(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)), dp(13).toFloat(), Color.TRANSPARENT)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    private fun dialogInput(hint: String, value: String, password: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSelection(text.length)
        setTextColor(Color.BLACK)
        setHintTextColor(Color.DKGRAY)
        textSize = 14f
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (password) transformationMethod = PasswordTransformationMethod.getInstance()
    }

    private fun field(name: String, input: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(TextView(this@MainActivity).apply { text = name; textSize = 12f; setTextColor(Color.DKGRAY); typeface = Typeface.DEFAULT_BOLD })
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(BORDER) }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun topBorderDrawable(fill: Int, border: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), border)
    }

    private fun relativeTime(timestamp: Long): String {
        val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 1_440 -> "${minutes / 60}h"
            minutes < 10_080 -> "${minutes / 1_440}d"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun <T : View> T.withTop(value: Int): T = apply {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply { topMargin = dp(value) }
    }

    private fun <T : View> T.withBottom(value: Int): T = apply {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply { bottomMargin = dp(value) }
    }
}
