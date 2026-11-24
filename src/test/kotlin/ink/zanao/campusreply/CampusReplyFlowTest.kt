package ink.zanao.campusreply

import io.github.whywood.deepdeep.plugin.api.NetworkResponse
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.SecretService
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CampusReplyFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private val plugin = CampusReplyPlugin(CampusMarketRequests { _, path, body, method ->
        val parameters = JsonObject(body.mapValues { (_, value) -> kotlinx.serialization.json.JsonPrimitive(value?.toString()) })
        Json.parseToJsonElement(response(method, "https://fixture.example" + path, parameters.toString()).body)
    })
    private val secrets = mutableMapOf("campus_reply_api_token" to "fixture")
    private val requests = mutableListOf<Triple<String, String, String>>()
    private val timestamp = System.currentTimeMillis()
    private var modelReply = "教务系统可以查看。"
    private val yiwenResponses = ArrayDeque<NetworkResponse>()
    private var publicationThrows = false
    private var published = false
    private var deleted = false
    private var usershowOk = false
    private var page2Threads = ""
    private var searchPage2Threads = ""
    private var threads = """[{"thread_id":"21","title":"请问怎么查课表？","content":"需要最新通知","post_time":$timestamp,"cate_id":"7","cate_name":"学习","user_id":"other","c_count":0}]"""
    private var extraComments = ""

    @Test fun yiwenRefreshesOnHttp401AndRetries() = runBlocking {
        secrets["campus_reply_yiwen_source_token"] = "old-ticket"
        yiwenResponses.add(NetworkResponse(401, ""))
        yiwenResponses.add(NetworkResponse(200, """{"code":200,"data":{"token":"new-ticket"}}"""))
        yiwenResponses.add(NetworkResponse(200, """data: {"replySource":"SYSTEM","message":"回答"}

"""))
        val dir = folder.newFolder()
        val result = plugin.yiwenAsk(host(dir), AutoReplyConfig(), "测试问题")
        assertEquals("回答", result["answer"])
        assertEquals("new-ticket", secrets["campus_reply_yiwen_source_token"])
        assertFalse(File(dir, "yiwen-jwt.txt").exists())
        assertEquals(3, requests.size)
    }

    @Test fun yiwenRejectsHttpErrorEvenWithAnswer() = runBlocking {
        secrets["campus_reply_yiwen_source_token"] = "ticket"
        yiwenResponses.add(NetworkResponse(500, """data: {"replySource":"SYSTEM","message":"错误页面"}

"""))
        val error = runCatching { plugin.yiwenAsk(host(folder.newFolder()), AutoReplyConfig(), "测试") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("http=500"))
    }

    private fun response(method: String, address: String, body: String): NetworkResponse {
        val path = address.substringBefore('?')
        requests += Triple(method, address, body)
        if (path.endsWith("/modelQa/say") || path.endsWith("/refreshToken")) return yiwenResponses.removeFirst()
        val json = when {
            path.endsWith("/user/v1/profile") -> """{"errno":0,"data":{"user_info":{"user_id":"mine","nickname":"集市助手"}}}"""
            path.endsWith("/user/usershow") -> if (usershowOk)
                """{"errno":0,"errmsg":"","data":{"user_info":{"user_id":"other","nickname":"真实昵称"}}}"""
            else """{"errno":10,"errmsg":"参数非法","data":[]}"""
            path.endsWith("/thread/v2/list") -> {
                val pg = paramPage(body, "page")
                val rows = if (pg > 1) page2Threads.ifBlank { "[]" } else threads
                """{"errno":0,"data":{"list":$rows}}"""
            }
            path.endsWith("/thread/hot") -> """{"errno":0,"data":{"list":$threads}}"""
            path.endsWith("/thread/v2/search") -> {
                val cp = paramPage(body, "cur_page")
                val rows = if (cp > 1) searchPage2Threads.ifBlank { "[]" } else threads
                """{"errno":0,"data":{"list":$rows}}"""
            }
            path.endsWith("/chat/completions") -> """{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(modelReply)}}}]}"""
            path.endsWith("/comment/post") -> {
                if (publicationThrows) throw IOException("connection closed after request")
                published = true
                """{"errno":0,"data":{"comment_id":"57"}}"""
            }
            path.endsWith("/comment/delete") -> {
                deleted = true
                """{"errno":0,"data":{}}"""
            }
            path.endsWith("/comment/list") -> {
                val own = if (published && !deleted) """{"comment_id":"57","user_id":"mine","content":"回复"}""" else ""
                val items = listOf(own, extraComments).filter(String::isNotBlank).joinToString(",")
                """{"errno":0,"data":{"list":[$items]}}"""
            }
            path.endsWith("failsearch") -> return NetworkResponse(502, "upstream down")
            path.startsWith("https://fixture.example/search") -> """{"results":[{"title":"网页结果","snippet":"联网摘要","url":"https://e.example/x"}]}"""
            path.startsWith("https://fixture.example/hooks") -> """{"errcode":0}"""
            else -> error("Unexpected provider request: $method $address")
        }
        return NetworkResponse(200, json)
    }

    /** GET params arrive as a JSON body of string values in this fixture: {"page":"2",...}. */
    private fun paramPage(body: String, key: String): Int =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private fun host(dataDir: File): PluginHost {
        val storage = proxy<SecretService> { name, args ->
            when (name) {
                "get" -> secrets[args[0] as String]
                "put" -> { secrets[args[0] as String] = args[1] as String; Unit }
                "remove" -> { secrets.remove(args[0] as String); Unit }
                else -> error(name)
            }
        }
        val net = proxy<NetworkService> { name, args ->
            check(name == "request")
            response(args[0] as String, args[1] as String, args[3] as? String ?: "")
        }
        return proxy { name, _ ->
            when (name) {
                "getDataDir" -> dataDir
                "secrets" -> storage
                "network" -> net
                else -> error(name)
            }
        }
    }

    private fun config() = AutoReplyConfig(apiBaseUrl = "https://fixture.example/v1", apiModel = "fixture",
        minGapSeconds = 0, lastScanMarker = timestamp - 10_000, includeNested = false)

    @Test fun previewChecksBoardAndRejectTermsWithoutModelOrPublication() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(boardAllow = "7|旧名", rejectTerms = "二手物品"))
        val result = plugin.scan(host, preview = true)
        assertEquals(1, result["matched"])
        assertFalse(requests.any { (_, url) -> url.contains("/chat/") || url.endsWith("/comment/post") })
        AutoReplyStore.putConfig(host, config().copy(boardDeny = "7|旧名"))
        assertEquals(0, plugin.scan(host, preview = true)["matched"])
    }

    @Test fun publishVerifyDeduplicateAndDeleteSameComment() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(deleteEnabled = true, deleteAfterMinutes = 3))
        assertEquals(1, plugin.scan(host, preview = false)["published"])
        val reply = AutoReplyStore.records(host).single()
        assertEquals("57", reply.postedCommentId)
        assertTrue(reply.content.endsWith("AI 助手"))
        assertTrue(reply.deleteAt >= reply.publishedAt + 180_000L)
        plugin.scan(host, preview = false)
        assertEquals(1, requests.count { it.second.endsWith("/comment/post") })
        assertEquals("deleted", plugin.deleteRecord(host, reply.key).state)
        assertEquals("57", (Json.parseToJsonElement(requests.last { it.second.endsWith("/comment/delete") }.third) as JsonObject).field("comment_id"))
    }

    @Test fun uncertainPublishIsPersistedAndNotRetried() = runBlocking {
        val host = host(folder.newFolder())
        publicationThrows = true
        AutoReplyStore.putConfig(host, config())
        plugin.scan(host, preview = false)
        assertEquals("unknown", AutoReplyStore.records(host).single().state)
        plugin.scan(host, preview = false)
        assertEquals(1, requests.count { it.second.endsWith("/comment/post") })
    }

    @Test fun matchingCommentTargetsItsOwnRoot() = runBlocking {
        val host = host(folder.newFolder())
        threads = """[{"thread_id":"21","title":"活动交流","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":1}]"""
        extraComments = """{"comment_id":"31","content":"无关讨论","comment_time":$timestamp,"reply_list":[{"comment_id":"32","content":"@集市助手 如何查预约？","user_id":"other","comment_time":$timestamp}]}"""
        AutoReplyStore.putConfig(host, config().copy(includeNested = true, requireMention = true))
        plugin.scan(host, preview = false)
        val payload = Json.parseToJsonElement(requests.single { it.second.endsWith("/comment/post") }.third) as JsonObject
        assertEquals("32", payload.field("reply_comment_id"))
        assertEquals("31", payload.field("root_comment_id"))
    }

    @Test fun personalReplyIncludesOnlyVerifiedAuthorHistory() = runBlocking {
        val host = host(folder.newFolder())
        threads = """[
            {"thread_id":"21","title":"从我发的帖，你如何评价我？","post_time":$timestamp,"cate_id":"7","user_id":"other"},
            {"thread_id":"22","title":"长期公开主题","post_time":${timestamp-20_000},"user_id":"other"},
            {"thread_id":"23","title":"另一个账号的专有内容","post_time":${timestamp-20_001},"user_id":"different","nickname":"相同昵称"},
            {"thread_id":"24","title":"匿名内容","post_time":${timestamp-20_002},"user_id":"other","is_anon":1}
        ]""".trimIndent()
        AutoReplyStore.putConfig(host, config().copy(showThinking = true))
        plugin.scan(host, preview = false)
        val model = requests.single { it.second.endsWith("/chat/completions") }.third
        assertTrue(model.contains("长期公开主题"))
        assertFalse(model.contains("另一个账号的专有内容"))
        assertFalse(model.contains("匿名内容"))
        val record = AutoReplyStore.records(host).single()
        // Current post is kept in the history pool by design (no exclusion of the asking post).
        assertTrue(record.content.contains("公开历史 2 条"))
        assertTrue(model.contains(record.threadId) || model.contains("从我发的帖"))
    }

    @Test fun featuredUsesTrueCountsAndSendsOncePerEndpoint() = runBlocking {
        val host = host(folder.newFolder())
        threads = """[
            {"thread_id":"91","title":"标题有引号\"好吗","content":"第一行\n第二行","view_count":500,"dun_count":12,"cate_name":"资讯"},
            {"thread_id":"92","title":"互动不等于围观","l_count":10000,"c_count":10000,"cate_name":"资讯"}]"""
        AutoReplyStore.putConfig(host, config().copy(featuredEnabled = true, featuredHotOnly = true,
            featuredBoards = "资讯", featuredMinWatchers = 300, featuredMinDunDun = 10,
            featuredDestinations = "wechat,qq", wechatUrls = "https://fixture.example/hooks/wechat",
            qqUrls = "https://fixture.example/hooks/qq"))
        plugin.scanFeatured(host)
        plugin.scanFeatured(host)
        val sent = requests.filter { it.second.startsWith("https://fixture.example/hooks/") }
        assertEquals(2, sent.size)
        sent.forEach { assertTrue((Json.parseToJsonElement(it.third) as JsonObject).child("text")!!.field("content").contains("标题有引号\"")) }
        assertEquals(listOf("sent", "sent"), AutoReplyStore.deliveries(host).map { it.state })
    }

    @Test fun finalTextKeepsLengthAndDisclosureWithoutModelReasoning() {
        val result = ReplyPolicy.finalBody("<THINK>不要发布内部内容</THINK>这是最终回答🙂", config().copy(maxLength = 23), "")
        assertTrue(result.endsWith("AI 助手"))
        assertFalse(result.contains("内部内容"))
        assertTrue(result.length <= 23)
    }

    @Test fun requireMentionSkipsPlainPostsAndMatchesOnlyRealMentions() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(requireMention = true, mentionNames = "deepdeep"))
        threads = """[{"thread_id":"41","title":"普通提问","content":"请问怎么预约场馆？","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        plugin.scan(host, preview = false)
        assertEquals(0, AutoReplyStore.records(host).size)
        threads = """[{"thread_id":"42","title":"求助","content":"deepdeeptool 报错了怎么办","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        plugin.scan(host, preview = false)
        assertEquals(0, AutoReplyStore.records(host).size)
        threads = """[{"thread_id":"43","title":"求助","content":"@deepdeep 帮忙看下这个报错","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        plugin.scan(host, preview = false)
        assertEquals(1, AutoReplyStore.records(host).size)
        assertEquals("published", AutoReplyStore.records(host).single().state)
    }

    @Test fun aiDisclosureCanBeDisabled() {
        val result = ReplyPolicy.finalBody("这是普通回答", config().copy(includeAiDisclosure = false, maxLength = 100), "")
        assertEquals("这是普通回答", result)
        assertFalse(result.contains("AI 助手"))
    }

    @Test fun externalLookupOnlyWhenSearchApiConfigured() = runBlocking {
        val host = host(folder.newFolder())
        threads = """[{"thread_id":"51","title":"求助一下","content":"有没有人知道情况","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        AutoReplyStore.putConfig(host, config().copy(externalLookup = true, searchUrl = ""))
        plugin.scan(host, preview = false)
        val withoutApi = requests.single { it.second.endsWith("/chat/completions") }.third
        assertFalse(withoutApi.contains("联网参考"))
        assertFalse(requests.any { it.second.contains("/search") })
        threads = """[{"thread_id":"52","title":"再问一个","content":"有没有人遇到过类似问题","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        AutoReplyStore.putConfig(host, config().copy(externalLookup = true, searchUrl = "https://fixture.example/search"))
        plugin.scan(host, preview = false)
        assertTrue(requests.any { it.second.contains("https://fixture.example/search") })
        val withApi = requests.last { it.second.endsWith("/chat/completions") }.third
        assertTrue(withApi.contains("联网参考") && withApi.contains("网页结果"))
    }

    @Test fun authorHistoryOffSkipsHistoryLookup() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(authorHistoryEnabled = false))
        threads = """[{"thread_id":"61","title":"你如何评价我这个人","content":"根据我平时的发言，怎么看我？","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        plugin.scan(host, preview = false)
        val model = requests.single { it.second.endsWith("/chat/completions") }.third
        assertFalse(model.contains("公开账号发布的历史"))
        assertFalse(model.contains("没有可确认属于提问者的公开历史"))
        val record = AutoReplyStore.records(host).single()
        assertFalse(record.source.contains("帖主公开历史"))
    }

    @Test fun authorHistoryNeedsTriggerPhrase() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(authorHistoryEnabled = true))
        threads = """[{"thread_id":"62","title":"今天操场跑步真舒服","content":"晚上操场还开着吗？","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        plugin.scan(host, preview = false)
        val model = requests.single { it.second.endsWith("/chat/completions") }.third
        assertFalse(model.contains("公开账号发布的历史"))
        assertFalse(model.contains("没有可确认属于提问者的公开历史"))
        val record = AutoReplyStore.records(host).single()
        assertFalse(record.source.contains("帖主公开历史"))
    }

    @Test fun anonymousAuthorResolvesRealIdentityAndHistory() = runBlocking {
        val host = host(folder.newFolder())
        usershowOk = true
        AutoReplyStore.putConfig(host, config().copy(authorHistoryEnabled = true))
        threads = """[
            {"thread_id":"63","title":"根据我发的帖，你怎么评价我？","content":"看看我是什么样的人，怎么看我？","post_time":$timestamp,"cate_id":"7","is_anon":1,"c_count":0},
            {"thread_id":"64","title":"同账号旧帖A","post_time":${timestamp-20_000},"user_id":"other","nickname":"真实昵称"},
            {"thread_id":"65","title":"同账号旧帖B无ID","post_time":${timestamp-20_001},"nickname":"真实昵称"},
            {"thread_id":"66","title":"别的人的帖","post_time":${timestamp-20_002},"user_id":"different","nickname":"别的人"}
        ]""".trimIndent()
        plugin.scan(host, preview = false)
        val model = requests.last { it.second.endsWith("/chat/completions") }.third
        assertTrue(model.contains("同账号旧帖A"))
        assertTrue(model.contains("同账号旧帖B无ID"))
        assertFalse(model.contains("别的人的帖"))
        val record = AutoReplyStore.records(host).single()
        assertEquals("published", record.state)
        assertTrue(record.source.contains("帖主公开历史"))
    }

    @Test fun anonymousAuthorWithoutIdentityResolutionMakesNoInference() = runBlocking {
        val host = host(folder.newFolder())
        usershowOk = false
        AutoReplyStore.putConfig(host, config().copy(authorHistoryEnabled = true))
        threads = """[{"thread_id":"67","title":"根据我发的帖，你怎么评价我？","content":"看看我是什么样的人，怎么看我？","post_time":$timestamp,"cate_id":"7","is_anon":1,"c_count":0}]"""
        plugin.scan(host, preview = false)
        val model = requests.last { it.second.endsWith("/chat/completions") }.third
        assertTrue(model.contains("没有可确认属于提问者的公开历史"))
        val record = AutoReplyStore.records(host).single()
        assertEquals("published", record.state)
        assertFalse(record.source.contains("帖主公开历史"))
    }

    @Test fun listRowsWithoutUserIdMatchByNickname() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(authorHistoryEnabled = true))
        threads = """[
            {"thread_id":"71","title":"你如何评价我这个人？","content":"根据我平时的发言怎么看我？","post_time":$timestamp,"cate_id":"7","nickname":"真实昵称","c_count":0},
            {"thread_id":"72","title":"只按昵称也能找回的历史","post_time":${timestamp-20_000},"nickname":"真实昵称"}
        ]""".trimIndent()
        plugin.scan(host, preview = false)
        val record = AutoReplyStore.records(host).single()
        assertEquals("published", record.state)
        // Current post stays in the pool by design: 71 (itself) + 72 (older, nickname-matched).
        assertTrue(record.source.contains("帖主公开历史 2 条"))
        val model = requests.last { it.second.endsWith("/chat/completions") }.third
        assertTrue(model.contains("只按昵称也能找回的历史"))
    }

    @Test fun showThinkingAppendsBasisSummary() {
        val result = ReplyPolicy.finalBody("回答正文",
            config().copy(showThinking = true, maxLength = 120), "模型、集市相关帖子 3 条")
        assertTrue(result.contains("依据：模型、集市相关帖子 3 条"))
        assertFalse(ReplyPolicy.finalBody("回答正文", config().copy(showThinking = false, maxLength = 120), "模型")
            .contains("依据："))
    }

    @Test fun externalLookupFailureDegradesInsteadOfKillingReply() = runBlocking {
        val host = host(folder.newFolder())
        threads = """[{"thread_id":"73","title":"求助一个联网问题","content":"有没有人知道最新情况？","post_time":$timestamp,"cate_id":"7","user_id":"other","c_count":0}]"""
        AutoReplyStore.putConfig(host, config().copy(externalLookup = true, searchUrl = "https://fixture.example/failsearch"))
        plugin.scan(host, preview = false)
        val record = AutoReplyStore.records(host).single()
        assertEquals("published", record.state)
        assertTrue(record.source.contains("联网检索失败"))
        val model = requests.last { it.second.endsWith("/chat/completions") }.third
        assertFalse(model.contains("联网参考"))
    }

    @Test fun historyWalksPagesAndSearchPagesForBiggerSample() = runBlocking {
        val host = host(folder.newFolder())
        AutoReplyStore.putConfig(host, config().copy(authorHistoryEnabled = true))
        threads = """[{"thread_id":"81","title":"你如何评价我这个人？","content":"根据我平时的发言怎么看我？","post_time":$timestamp,"cate_id":"7","nickname":"真实昵称","c_count":0}]"""
        page2Threads = """[{"thread_id":"82","title":"第2页才有的旧帖","post_time":${timestamp-30_000},"nickname":"真实昵称"}]"""
        searchPage2Threads = """[{"thread_id":"83","title":"搜索第2页的旧帖","post_time":${timestamp-40_000},"nickname":"真实昵称"}]"""
        plugin.scan(host, preview = false)
        assertTrue(requests.any { it.third.contains("\"page\":\"2\"") })
        assertTrue(requests.any { it.third.contains("\"cur_page\":\"2\"") })
        val model = requests.last { it.second.endsWith("/chat/completions") }.third
        assertTrue(model.contains("第2页才有的旧帖"))
        assertTrue(model.contains("搜索第2页的旧帖"))
        val record = AutoReplyStore.records(host).single()
        assertEquals("published", record.state)
        assertTrue(record.source.contains("帖主公开历史"))
    }
}
