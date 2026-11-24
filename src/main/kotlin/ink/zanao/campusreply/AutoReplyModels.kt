package ink.zanao.campusreply

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 逸问真实 API 基座：bundle ge = pe("/api/ly-ai-agent") => /chat/api/ly-ai-agent/api。 */
internal const val YIWEN_API_BASE = "https://chat.sysu.edu.cn/chat/api/ly-ai-agent/api"
/** 旧假基座（appgw 网关诱饵命名空间，任何票恒 401/302）；升级时自动迁移。 */
internal const val YIWEN_API_BASE_LEGACY = "https://chat.sysu.edu.cn/chat/api"

@Serializable
data class AutoReplyConfig(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 15,
    val minGapSeconds: Int = 60,
    val maxPerRun: Int = 3,
    val maxPerDay: Int = 20,
    val boardAllow: String = "",
    val boardDeny: String = "",
    val knownBoards: String = "",
    val lastScanMarker: Long = 0L,
    val lastScanAt: Long = 0L,
    val lastScanFetched: Int = 0,
    val lastScanSelected: Int = 0,
    val lastScanPublished: Int = 0,
    val lastScanError: String = "",
    val keywordAllow: String = "",
    val keywordDeny: String = "",
    val requireMention: Boolean = false,
    val mentionNames: String = "",
    val rejectTerms: String = "",
    val specialTerms: String = "",
    val specialInstruction: String = "",
    val authorAllow: String = "",
    val authorDeny: String = "",
    val includeNested: Boolean = true,
    val tokenBudget: Int = 800,
    val maxLength: Int = 200,
    val includeAiDisclosure: Boolean = true,
    val externalLookup: Boolean = false,
    val searchUrl: String = "",
    val showThinking: Boolean = false,
    val authorHistoryEnabled: Boolean = true,
    val historyDays: Int = 730,
    val historyLimit: Int = 20,
    val historyTriggerTerms: String = "根据我,从我,关于我,评价我,怎么看我,了解我,我的性格,我是什么样的人",
    val apiBaseUrl: String = "",
    val apiModel: String = "",
    val apiTokenSecret: String = "campus_reply_api_token",
    val apiTokenConfigured: Boolean = false,
    val searchTokenSecret: String = "campus_reply_search_token",
    val sysuMaterialsEnabled: Boolean = true,
    val sysuMaterialsSearchApi: String = "https://arxiv.jaison.ink/api/materials",
    val sysuMaterialsWebBase: String = "https://arxiv.jaison.ink",
    val sysuGithubRepo: String = "thinktraveller/SYSU_freshman_materials",
    val sysuMaterialsLimit: Int = 5,
    val deleteEnabled: Boolean = false,
    val deleteAfterMinutes: Int = 0,
    val featuredEnabled: Boolean = false,
    val featuredIntervalMinutes: Int = 10,
    val featuredKeywords: String = "",
    val featuredBoards: String = "",
    val featuredAuthors: String = "",
    val featuredMinWatchers: Int = 0,
    val featuredMinDunDun: Int = 0,
    val featuredHotOnly: Boolean = false,
    val featuredPerRun: Int = 10,
    val featuredDestinations: String = "webhook",
    val webhookUrls: String = "",
    val webhookTemplate: String = "{\"msgtype\":\"text\",\"text\":{\"content\":\"{title}\\n{content}\\n{url}\"}}",
    val emailChannelId: String = "official-email",
    val emailTo: String = "",
    val wechatUrls: String = "",
    val qqUrls: String = "",
    val yiwenBridgeUrl: String = "",
    val yiwenBridgeTokenSecret: String = "campus_reply_yiwen_bridge_token",
    val yiwenModel: String = "V3",
    val yiwenSearchSource: String = "model",
    val yiwenSourceEnabled: Boolean = false,
    val yiwenSourceApiBase: String = YIWEN_API_BASE,
    val yiwenSourceTokenSecret: String = "campus_reply_yiwen_source_token",
    /** bootstrap 返回的会话身份 JSON {userId,userName,orgId}，请求头 loginX 必需。 */
    val yiwenIdentitySecret: String = "campus_reply_yiwen_identity",
)

@Serializable
data class FeaturedDelivery(
    val key: String,
    val threadId: String,
    val title: String = "",
    val destination: String,
    val state: String = "pending",
    val sentAt: Long = 0L,
    val error: String = "",
)

@Serializable
data class ReplyRecord(
    val key: String,
    val threadId: String,
    val threadTitle: String = "",
    val board: String = "",
    val commentId: String = "",
    val rootCommentId: String = "",
    val postedCommentId: String = "",
    val basis: String = "",
    val authorAccount: String = "",
    val content: String,
    val source: String = "",
    val state: String = "draft",
    val createdAt: Long = 0L,
    val publishedAt: Long = 0L,
    val deleteAt: Long = 0L,
    val deletedAt: Long = 0L,
    val error: String = "",
)

internal object AutoReplyStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun config(host: io.github.whywood.deepdeep.plugin.api.PluginHost): AutoReplyConfig {
        val raw = host.secrets().get(CampusReplyPlugin.CONFIG_KEY).orEmpty()
        val decoded = if (raw.isBlank()) AutoReplyConfig()
        else runCatching { json.decodeFromString<AutoReplyConfig>(raw) }
            .getOrDefault(AutoReplyConfig(enabled = false, lastScanError = "配置无法解码"))
        // 一次性迁移：旧假基座 -> 真实 ly-ai-agent 基座，写回后旧配置不再遮蔽新值。
        if (decoded.yiwenSourceApiBase.trimEnd('/') == YIWEN_API_BASE_LEGACY) {
            val next = decoded.copy(yiwenSourceApiBase = YIWEN_API_BASE)
            runCatching { putConfig(host, next) }
            return next
        }
        return decoded
    }
    suspend fun putConfig(host: io.github.whywood.deepdeep.plugin.api.PluginHost, value: AutoReplyConfig) =
        host.secrets().put(CampusReplyPlugin.CONFIG_KEY, json.encodeToString(value))
    suspend fun records(host: io.github.whywood.deepdeep.plugin.api.PluginHost): List<ReplyRecord> =
        host.secrets().get(CampusReplyPlugin.RECORDS_KEY).orEmpty().let {
            if (it.isBlank()) emptyList() else runCatching { json.decodeFromString<List<ReplyRecord>>(it) }.getOrDefault(emptyList())
        }
    suspend fun putRecords(host: io.github.whywood.deepdeep.plugin.api.PluginHost, value: List<ReplyRecord>) =
        host.secrets().put(CampusReplyPlugin.RECORDS_KEY, json.encodeToString(value.takeLast(2000)))
    suspend fun deliveries(host: io.github.whywood.deepdeep.plugin.api.PluginHost): List<FeaturedDelivery> =
        host.secrets().get(CampusReplyPlugin.DELIVERIES_KEY).orEmpty().let {
            if (it.isBlank()) emptyList() else runCatching { json.decodeFromString<List<FeaturedDelivery>>(it) }.getOrDefault(emptyList())
        }
    suspend fun putDeliveries(host: io.github.whywood.deepdeep.plugin.api.PluginHost, value: List<FeaturedDelivery>) =
        host.secrets().put(CampusReplyPlugin.DELIVERIES_KEY, json.encodeToString(value.takeLast(2000)))
    fun csv(value: String): Set<String> = value.split(',', '\n', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun matches(text: String, allow: String, deny: String): Boolean {
        val haystack = text.lowercase()
        if (csv(deny).any { haystack.contains(it.lowercase()) }) return false
        val allowed = csv(allow)
        return allowed.isEmpty() || allowed.any { haystack.contains(it.lowercase()) }
    }
}
