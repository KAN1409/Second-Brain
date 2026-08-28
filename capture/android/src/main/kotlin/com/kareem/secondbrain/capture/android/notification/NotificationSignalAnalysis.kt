package com.kareem.secondbrain.capture.android.notification

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

enum class RelaySignalType {
    HUMAN_MESSAGE,
    EMAIL,
    CALL,
    SMS,
    OTP,
    BANKING,
    DELIVERY,
    CALENDAR,
    SECURITY,
    DOWNLOAD,
    SYSTEM_NOISE,
    OTHER,
}

enum class NotificationMeaningfulChange {
    NEW_POST,
    NEW_MESSAGES,
    CONTENT_CHANGED,
    EXACT_DUPLICATE,
    MACHINE_CHURN_ONLY,
}

data class NotificationAnalysisMessage(
    val sender: String?,
    val text: String,
    val timestamp: Instant?,
)

data class NotificationAnalysisPerson(
    val name: String?,
    val key: String?,
    val uri: String?,
)

data class NotificationAnalysisFacts(
    val packageName: String,
    val notificationKey: String,
    val androidUserId: Int,
    val uid: Int,
    val tag: String?,
    val shortcutId: String?,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val title: String?,
    val body: String?,
    val expandedText: String?,
    val conversationTitle: String?,
    val messages: List<NotificationAnalysisMessage>,
    val people: List<NotificationAnalysisPerson>,
    val replyable: Boolean,
)

data class RelayEvidenceEntity(
    val type: String,
    val value: String,
    val sourceField: String,
    val start: Int,
    val endExclusive: Int,
    val confidence: Double = 1.0,
)

data class NotificationSignalAnalysis(
    val sourceProfileIdentity: String,
    val notificationIdentity: String,
    val notificationInstanceIdentity: String,
    val conversationIdentity: String,
    val conversationIdentityBasis: String,
    val logicalSignalId: String,
    val signalType: RelaySignalType,
    val change: NotificationMeaningfulChange,
    val changeReason: String,
    val newMessageFingerprints: Set<String>,
    val entities: List<RelayEvidenceEntity>,
)

object NotificationSignalAnalyzer {
    private val whitespace = Regex("\\s+")
    private val percentage = Regex("(?<!\\d)(?:100|[1-9]?\\d)(?:[.,]\\d+)?%(?!\\d)")
    private val progressRatio = Regex("(?<!\\d)\\d+\\s*/\\s*\\d+(?!\\d)")
    private val urlRegex = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("(?<![A-Za-z0-9])(?:\\+?\\d[\\d\\s().-]{6,}\\d)(?![A-Za-z0-9])")
    private val dateRegex = Regex("\\b(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?)\\b")
    private val timeRegex = Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s?[ap]\\.?m\\.?)?\\b", RegexOption.IGNORE_CASE)
    private val moneyRegex = Regex(
        "(?:(?:EGP|USD|EUR|GBP|LE|ج\\.?م\\.?|[$€£])\\s?\\d[\\d,]*(?:\\.\\d{1,2})?|\\d[\\d,]*(?:\\.\\d{1,2})?\\s?(?:EGP|USD|EUR|GBP|LE))",
        RegexOption.IGNORE_CASE,
    )
    private val otpRegex = Regex(
        "(?:\\b(?:otp|verification|verify|code|رمز|كود)\\b\\D{0,20})(\\d{4,8})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val referenceRegex = Regex(
        "(?:\\b(?:order|tracking|reference|ref|shipment|booking|رقم\\s*الطلب|التتبع|مرجع)\\b\\s*(?:#|:|-)?\\s*)([A-Z0-9][A-Z0-9-]{3,})",
        RegexOption.IGNORE_CASE,
    )

    fun notificationIdentity(facts: NotificationAnalysisFacts): String = stableId(
        "notification",
        facts.packageName,
        facts.androidUserId.toString(),
        facts.uid.toString(),
        facts.notificationKey,
    )

    fun messageFingerprint(message: NotificationAnalysisMessage): String = stableId(
        "message",
        normalize(message.sender.orEmpty()),
        normalize(message.text),
        message.timestamp?.toEpochMilli()?.toString().orEmpty(),
    )

    fun visibleFingerprint(facts: NotificationAnalysisFacts): String = stableId("visible", canonicalVisibleText(facts))

    fun stableChurnFingerprint(facts: NotificationAnalysisFacts): String {
        val canonical = canonicalVisibleText(facts)
            .replace(percentage, "<percent>")
            .replace(progressRatio, "<progress>")
        return stableId("stable-visible", canonical)
    }

    fun analyze(facts: NotificationAnalysisFacts, lifecycle: NotificationLifecycleDecision): NotificationSignalAnalysis {
        val notificationIdentity = notificationIdentity(facts)
        val sourceProfileIdentity = stableId(
            "source-profile",
            facts.packageName,
            facts.androidUserId.toString(),
            facts.uid.toString(),
        )
        val notificationInstanceIdentity = stableId(
            "notification-instance",
            notificationIdentity,
            lifecycle.generation.toString(),
            lifecycle.instanceStartedAtEpochMs.toString(),
        )
        val (conversationBasis, conversationEvidence) = conversationBasis(facts)
        val conversationIdentity = stableId("conversation", sourceProfileIdentity, conversationBasis)
        val change = when {
            lifecycle.isNewInstance -> NotificationMeaningfulChange.NEW_POST
            lifecycle.newMessageFingerprints.isNotEmpty() -> NotificationMeaningfulChange.NEW_MESSAGES
            lifecycle.unchanged -> NotificationMeaningfulChange.EXACT_DUPLICATE
            lifecycle.stableChurnOnly && facts.isOngoing && isDeterministicProgressSurface(facts) -> {
                NotificationMeaningfulChange.MACHINE_CHURN_ONLY
            }
            else -> NotificationMeaningfulChange.CONTENT_CHANGED
        }
        val evidenceFacts = if (change == NotificationMeaningfulChange.NEW_MESSAGES) {
            val newMessages = facts.messages.filter { messageFingerprint(it) in lifecycle.newMessageFingerprints }
            facts.copy(
                body = newMessages.lastOrNull()?.text,
                expandedText = null,
                messages = newMessages,
            )
        } else {
            facts
        }
        val entities = extractEntities(evidenceFacts)
        val signalType = classifySignalType(evidenceFacts, entities)
        val changeReason = when (change) {
            NotificationMeaningfulChange.NEW_POST -> "New Android notification instance"
            NotificationMeaningfulChange.NEW_MESSAGES -> "Updated notification contains ${lifecycle.newMessageFingerprints.size} new structured message(s)"
            NotificationMeaningfulChange.EXACT_DUPLICATE -> "Same notification snapshot already observed"
            NotificationMeaningfulChange.MACHINE_CHURN_ONLY -> "Only deterministic progress/percentage fields changed"
            NotificationMeaningfulChange.CONTENT_CHANGED -> "Existing notification changed; uncertain changes are preserved"
        }
        val logicalSignalId = when (change) {
            NotificationMeaningfulChange.NEW_MESSAGES -> stableId(
                "signal-message-delta",
                notificationInstanceIdentity,
                lifecycle.newMessageFingerprints.sorted().joinToString("|"),
            )
            else -> stableId("signal-notification", notificationInstanceIdentity)
        }
        return NotificationSignalAnalysis(
            sourceProfileIdentity = sourceProfileIdentity,
            notificationIdentity = notificationIdentity,
            notificationInstanceIdentity = notificationInstanceIdentity,
            conversationIdentity = conversationIdentity,
            conversationIdentityBasis = conversationEvidence,
            logicalSignalId = logicalSignalId,
            signalType = signalType,
            change = change,
            changeReason = changeReason,
            newMessageFingerprints = lifecycle.newMessageFingerprints,
            entities = entities,
        )
    }

    private fun conversationBasis(facts: NotificationAnalysisFacts): Pair<String, String> {
        facts.shortcutId?.takeIf(String::isNotBlank)?.let { return "shortcut:$it" to "Android shortcutId" }
        val participantTokens = buildList {
            facts.people.forEach { person ->
                add(person.key ?: person.uri ?: person.name.orEmpty())
            }
            facts.messages.mapNotNullTo(this) { it.sender?.takeIf(String::isNotBlank) }
        }.map(::normalize).filter(String::isNotBlank).distinct().sorted()
        val conversationTitle = normalize(facts.conversationTitle.orEmpty())
        if (conversationTitle.isNotBlank() || participantTokens.isNotEmpty()) {
            return "conversation:$conversationTitle|${participantTokens.joinToString("|")}" to "Conversation title / explicit participants"
        }
        facts.tag?.takeIf(String::isNotBlank)?.let { return "tag:$it" to "Android notification tag" }
        return "notification-key:${facts.notificationKey}" to "Android notification key fallback"
    }

    private fun classifySignalType(facts: NotificationAnalysisFacts, entities: List<RelayEvidenceEntity>): RelaySignalType {
        val text = canonicalVisibleText(facts).lowercase(Locale.ROOT)
        val pkg = facts.packageName.lowercase(Locale.ROOT)
        val category = facts.category.orEmpty().lowercase(Locale.ROOT)
        if (entities.any { it.type == "OTP" }) return RelaySignalType.OTP
        if (category == "call") return RelaySignalType.CALL
        if (category == "msg") {
            if (pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms")) return RelaySignalType.SMS
            return RelaySignalType.HUMAN_MESSAGE
        }
        if (pkg == "com.google.android.gm" || pkg.contains("email") || category == "email") return RelaySignalType.EMAIL
        if (pkg.contains("calendar") || category == "event") return RelaySignalType.CALENDAR
        if (pkg.contains("download") || text.contains("download complete") || text.contains("downloaded")) return RelaySignalType.DOWNLOAD
        if (listOf("debit", "credited", "credit", "transaction", "payment", "تحويل", "خصم", "تم ايداع", "تم إيداع").any(text::contains) &&
            entities.any { it.type == "MONEY" }
        ) return RelaySignalType.BANKING
        if (listOf("delivery", "delivered", "shipment", "tracking", "out for delivery", "توصيل", "شحنة", "التتبع").any(text::contains)) {
            return RelaySignalType.DELIVERY
        }
        if (listOf("security alert", "new sign-in", "login attempt", "password changed", "رمز أمان", "تسجيل دخول", "محاولة دخول").any(text::contains)) {
            return RelaySignalType.SECURITY
        }
        if (facts.isOngoing && category in setOf("transport", "progress", "service")) return RelaySignalType.SYSTEM_NOISE
        return RelaySignalType.OTHER
    }

    private fun extractEntities(facts: NotificationAnalysisFacts): List<RelayEvidenceEntity> {
        val fields = buildList {
            facts.title?.let { add("title" to it) }
            facts.body?.let { add("body" to it) }
            facts.expandedText?.let { add("expanded_text" to it) }
            facts.conversationTitle?.let { add("conversation_title" to it) }
            facts.messages.forEachIndexed { index, message -> add("messages[$index].text" to message.text) }
        }
        val entities = mutableListOf<RelayEvidenceEntity>()
        fields.forEach { (field, value) ->
            urlRegex.findAll(value).forEach { match -> entities += entity("URL", match.value, field, match.range) }
            phoneRegex.findAll(value).forEach { match ->
                val digits = match.value.count(Char::isDigit)
                if (digits in 8..15) entities += entity("PHONE", match.value.trim(), field, match.range)
            }
            dateRegex.findAll(value).forEach { match -> entities += entity("DATE", match.value, field, match.range) }
            timeRegex.findAll(value).forEach { match -> entities += entity("TIME", match.value, field, match.range) }
            moneyRegex.findAll(value).forEach { match -> entities += entity("MONEY", match.value, field, match.range) }
            otpRegex.findAll(value).forEach { match ->
                val group = match.groups[1] ?: return@forEach
                entities += RelayEvidenceEntity("OTP", group.value, field, group.range.first, group.range.last + 1)
            }
            referenceRegex.findAll(value).forEach { match ->
                val group = match.groups[1] ?: return@forEach
                entities += RelayEvidenceEntity("REFERENCE", group.value, field, group.range.first, group.range.last + 1)
            }
        }
        facts.people.forEachIndexed { index, person ->
            person.name?.takeIf(String::isNotBlank)?.let { name ->
                entities += RelayEvidenceEntity("PERSON", name, "people[$index].name", 0, name.length)
            }
        }
        facts.messages.forEachIndexed { index, message ->
            message.sender?.takeIf(String::isNotBlank)?.let { sender ->
                entities += RelayEvidenceEntity("PERSON", sender, "messages[$index].sender", 0, sender.length)
            }
        }
        return entities.distinctBy { listOf(it.type, it.value, it.sourceField, it.start, it.endExclusive) }
    }

    private fun entity(type: String, value: String, field: String, range: IntRange) = RelayEvidenceEntity(
        type = type,
        value = value,
        sourceField = field,
        start = range.first,
        endExclusive = range.last + 1,
    )

    private fun canonicalVisibleText(facts: NotificationAnalysisFacts): String = buildList {
        facts.title?.let(::add)
        facts.body?.let(::add)
        facts.expandedText?.let(::add)
        facts.conversationTitle?.let(::add)
        facts.messages.forEach { message ->
            add(listOfNotNull(message.sender, message.text).joinToString(": "))
        }
    }.joinToString("\n") { normalize(it) }

    private fun isDeterministicProgressSurface(facts: NotificationAnalysisFacts): Boolean {
        val category = facts.category.orEmpty().lowercase(Locale.ROOT)
        if (category == "progress") return true
        if (facts.packageName == "com.android.systemui") {
            val text = canonicalVisibleText(facts).lowercase(Locale.ROOT)
            return percentage.containsMatchIn(text) && listOf("charging", "charge", "شحن", "الشحن").any(text::contains)
        }
        return false
    }

    private fun normalize(value: String): String = value.replace(whitespace, " ").trim().lowercase(Locale.ROOT)

    internal fun stableId(prefix: String, vararg values: String): String {
        val joined = values.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return prefix + "_" + digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
    }
}
