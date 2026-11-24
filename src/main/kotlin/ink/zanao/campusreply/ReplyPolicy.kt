package ink.zanao.campusreply

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.field(vararg names: String): String =
    names.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

internal fun JsonObject.child(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

internal fun JsonObject.account(): String =
    field("user_id", "uid", "author_id").takeUnless { it == "0" }.orEmpty()

internal fun JsonObject.isAnonymous(): Boolean = field("is_anon", "use_anon", "is_anonymous") in setOf("1", "true")

internal fun JsonObject.postId(): String = field("thread_id", "id").takeIf { it.all(Char::isDigit) }.orEmpty()

internal fun JsonObject.sentAt(): Long {
    val time = field("p_time", "created_at", "create_time", "timestamp", "post_timestamp", "post_time", "comment_time", "reply_time", "pub_time")
    val value = time.toLongOrNull() ?: return runCatching {
        java.time.LocalDateTime.parse(time.replace(' ', 'T'))
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    }.getOrDefault(0L)
    return if (value in 1L..9_999_999_999L) value * 1000L else value
}

internal data class ReplyCandidate(
    val thread: JsonObject,
    val comment: JsonObject? = null,
    val rootCommentId: String = "",
) {
    val threadId: String get() = thread.postId()
    val commentId: String get() = comment?.field("comment_id", "id").orEmpty()
    val title: String get() = thread.field("title")
    val text: String get() = comment?.field("content") ?: listOf(title, thread.field("content")).joinToString("\n").trim()
    val source: JsonObject get() = comment ?: thread
    val board: String get() = listOf(thread.field("cate_id"), thread.field("cate_name")).filter(String::isNotEmpty).joinToString("|")
}

internal object ReplyPolicy {
    private val question = Regex("[?？]|请问|请教|求助|怎么|怎么办|哪里|如何|能否|有没有|谁知道|想问")

    fun identityMatches(value: String, rules: String): Boolean {
        val terms = AutoReplyStore.csv(rules)
        val observed = value.split('|').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()
        return terms.isEmpty() || terms.any { term ->
            val parts = term.split('|').map { it.trim().lowercase() }.filter(String::isNotEmpty)
            if (parts.size > 1) parts.first() in observed else parts.singleOrNull() in observed
        }
    }

    fun boardMatches(candidate: ReplyCandidate, config: AutoReplyConfig): Boolean =
        identityMatches(candidate.board, config.boardAllow) &&
            (config.boardDeny.isBlank() || !identityMatches(candidate.board, config.boardDeny))

    fun matches(candidate: ReplyCandidate, config: AutoReplyConfig, own: JsonObject): Boolean {
        if (candidate.threadId.isBlank() || candidate.text.isBlank()) return false
        if (candidate.comment != null && (!config.includeNested || candidate.commentId.isBlank() || candidate.rootCommentId.isBlank())) return false
        if (own.account().isNotBlank() && candidate.source.account() == own.account()) return false
        if (candidate.source.field("is_mine", "is_self", "is_myself") in setOf("true", "1")) return false
        val author = if (candidate.source.isAnonymous()) "" else {
            listOf(candidate.source.account(), candidate.source.field("nickname")).filter(String::isNotEmpty).joinToString("|")
        }
        if (!identityMatches(author, config.authorAllow) || (config.authorDeny.isNotBlank() && identityMatches(author, config.authorDeny))) return false
        if (!boardMatches(candidate, config) || !AutoReplyStore.matches(candidate.text, config.keywordAllow, config.keywordDeny)) return false
        if (AutoReplyStore.csv(config.rejectTerms).any { candidate.text.contains(it, ignoreCase = true) }) return false
        val names = AutoReplyStore.csv(config.mentionNames) + own.field("nickname", "name")
        if (config.requireMention && !mentions(candidate, own.account(), names)) return false
        return config.requireMention || config.keywordAllow.isNotBlank() || special(candidate.text, config) ||
            history(candidate.text, config) || question.containsMatchIn(candidate.text)
    }

    private fun mentions(candidate: ReplyCandidate, own: String, names: Set<String>): Boolean {
        val mentions = candidate.source.objects("mentions") + candidate.source.objects("at_users")
        return (own.isNotBlank() && mentions.any { it.account() == own }) || names.filter(String::isNotBlank).any { name ->
            if (name.matches(Regex("[A-Za-z0-9_]+"))) {
                Regex("(?<![a-zA-Z0-9_])" + Regex.escape(name) + "(?![a-zA-Z0-9_])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(candidate.text)
            } else candidate.text.contains(name, ignoreCase = true)
        }
    }

    fun special(text: String, config: AutoReplyConfig): Boolean =
        AutoReplyStore.csv(config.specialTerms).any { text.contains(it, ignoreCase = true) }

    fun history(text: String, config: AutoReplyConfig): Boolean =
        config.authorHistoryEnabled && AutoReplyStore.csv(config.historyTriggerTerms).any { text.contains(it, ignoreCase = true) } &&
            Regex("[?？]|如何|请|评价|怎么看|了解|看法|什么样|能否|总结|分析").containsMatchIn(text)

    fun provenHistory(row: JsonObject, owner: String, nickname: String, cutoff: Long): Boolean {
        if (row.postId().isEmpty() || row.sentAt() < cutoff) return false
        if (row.isAnonymous()) return false // anonymous rows only enter through usershow resolution
        val id = row.account()
        // Real list rows usually carry no user_id; decide by id when both sides have one,
        // otherwise fall back to the visible nickname (identity resolved via usershow).
        if (id.isNotBlank() && owner.isNotBlank()) return id == owner
        val nick = row.field("nickname")
        return nickname.isNotBlank() && nick.isNotBlank() && nick.equals(nickname, ignoreCase = true)
    }

    fun finalBody(text: String, config: AutoReplyConfig, basis: String): String {
        // 集市评论硬上限 200 字（App 校验串「请填写评论内容，200字以内」；超限服务端回
        // errno=10 评论内容长度非法）。无论配置写多大，发布正文一律截到 200 以内。
        val limit = config.maxLength.coerceIn(20, 200)
        val disclosure = if (config.includeAiDisclosure) "\n——AI 助手" else ""
        val summary = if (config.showThinking && basis.isNotBlank()) "\n依据：$basis" else ""
        val suffix = summary.take(limit - disclosure.length - 10) + disclosure
        val withoutReasoning = text.replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        val clean = withoutReasoning.replace(Regex("<think>.*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .trim().removeSuffix("——AI 助手").trim()
        val body = clean.take(limit - suffix.length).trimEnd().trimEnd { it.isHighSurrogate() }
        require(body.isNotBlank()) { "模型未返回可发布正文" }
        return body + suffix
    }

    fun renderWebhook(template: String, values: Map<String, String>): String {
        val json = kotlinx.serialization.json.Json
        val parsed = json.parseToJsonElement(template)
        require(parsed is JsonObject) { "Webhook 模板应为 JSON 对象" }
        fun replace(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { replace(it.value) })
            is JsonArray -> JsonArray(element.map { replace(it) })
            is JsonPrimitive -> if (!element.isString) element else JsonPrimitive(
                Regex("\\{([a-z]+)\\}").replace(element.content) { values[it.groupValues[1]] ?: it.value },
            )
        }
        return replace(parsed).toString()
    }
}

