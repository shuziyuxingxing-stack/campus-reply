package ink.zanao.campusreply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.assetIcon
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull


private val KEYWORD_PRESETS = listOf("求购", "出售", "转让", "招聘", "租房", "拼车", "失物", "资料", "课程", "兼职")

internal fun campusReplyPage(host: PluginHost, plugin: CampusReplyPlugin): PluginPage = pluginPage(
    "campus-reply-panel",
    localizedText("回复助手", "回复助手", "Reply Assistant"),
    assetIcon("icon.png"),
) { CampusReplyPanel(host, plugin) }

@Composable
private fun CampusReplyPanel(host: PluginHost, plugin: CampusReplyPlugin) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AutoReplyConfig()) }
    var records by remember { mutableStateOf(emptyList<ReplyRecord>()) }
    var deliveries by remember { mutableStateOf(emptyList<FeaturedDelivery>()) }
    var status by remember { mutableStateOf("正在加载自动回复策略…") }
    var preview by remember { mutableStateOf<CampusPostPreview?>(null) }
    var apiToken by remember { mutableStateOf("") }
    var yiwenToken by remember { mutableStateOf("") }
    var yiwenJwt by remember { mutableStateOf("") }
    var searchToken by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    suspend fun loadRecords() {
        records = AutoReplyStore.records(host)
        deliveries = AutoReplyStore.deliveries(host)
    }

    fun save(next: AutoReplyConfig) {
        config = next
        scope.launch {
            runCatching {
                plugin.saveConfig(host) { current ->
                    next.copy(
                        knownBoards = current.knownBoards,
                        lastScanMarker = current.lastScanMarker, lastScanAt = current.lastScanAt,
                        lastScanFetched = current.lastScanFetched, lastScanSelected = current.lastScanSelected,
                        lastScanPublished = current.lastScanPublished, lastScanError = current.lastScanError,
                    )
                }
            }.onFailure { status = "保存失败：${it.message}" }
        }
    }

    fun refresh() {
        scope.launch {
            busy = true
            runCatching {
                config = AutoReplyStore.config(host)
                if (config.knownBoards.isBlank() && plugin.backgroundActionsAvailable) {
                    runCatching { plugin.refreshBoards(host) }
                    config = AutoReplyStore.config(host)
                }
                status = "会话与 App 签名接口就绪后，点下方按钮读取最新帖子和评论。"
                loadRecords()
                ready = true
            }.onFailure { status = "读取失败：${it.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("校园集市自动回复助手", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Button(enabled = ready && !busy, onClick = {
            scope.launch {
                busy = true
                status = "正在通过 App 签名接口读取帖子与评论…"
                status = runCatching {
                    val result = plugin.readApp(host)
                    preview = result
                    "已读到帖子 · 读取 ${result.comments.size}/${result.commentCount} 条评论"
                }.getOrElse {
                    preview = null
                    "读取失败：${it.message}"
                }
                busy = false
            }
        }) { Text("读取帖子和评论") }
        Button(enabled = ready && !busy, onClick = {
            scope.launch {
                busy = true
                status = "正在按策略扫描（预览目标，不发布）…"
                status = runCatching {
                    val m = plugin.scan(host, preview = true)
                    val cfg = AutoReplyStore.config(host)
                    "手动扫描：state=${m["state"]} 抓取=${m["fetched"]} 命中=${m["matched"]} ｜ " +
                        (cfg.lastScanError.ifBlank { "上次扫描无错误" })
                }.getOrElse { "手动扫描失败：${it.message}" }
                busy = false
            }
        }) { Text("立即扫描（预览）") }
        preview?.let { result ->
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            if (result.body.isNotBlank()) Text(result.body)
            result.comments.forEach { comment -> Text(comment, style = MaterialTheme.typography.bodySmall) }
        }
        if (!plugin.backgroundActionsAvailable) {
            Text("自动回复需要集市签名接口可用；请先运行 VPN token 捕捉器导入会话。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (config.lastScanError.isNotBlank()) Text("上次扫描错误：${config.lastScanError}", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用自动回复", modifier = Modifier.weight(1f))
            Switch(checked = config.enabled && plugin.backgroundActionsAvailable, enabled = ready && !busy && plugin.backgroundActionsAvailable, onCheckedChange = { save(config.copy(enabled = it)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { refresh() }) { Text("刷新") }
            Button(enabled = !busy, onClick = { save(config.copy(enabled = false)) }) { Text("立即暂停") }
        }

        Section("扫描与频率") {
            NumberField("扫描间隔（分钟）", config.intervalMinutes) { save(config.copy(intervalMinutes = it.coerceIn(1, 1440))) }
            NumberField("单次最多回复", config.maxPerRun) { save(config.copy(maxPerRun = it.coerceIn(1, 20))) }
            NumberField("每日最多回复", config.maxPerDay) { save(config.copy(maxPerDay = it.coerceIn(1, 200))) }
            NumberField("两次回复最小间隔（秒）", config.minGapSeconds) { save(config.copy(minGapSeconds = it.coerceAtLeast(0))) }
        }

        Section("帖子筛选") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("校园集市板块", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Button(enabled = !busy && plugin.backgroundActionsAvailable, onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            val names = plugin.refreshBoards(host)
                            config = AutoReplyStore.config(host)
                            status = "已从官方 cate_list 更新 ${names.size} 个模块。"
                        }.onFailure { status = "板块刷新失败：${it.message}" }
                        busy = false
                    }
                }) { Text("刷新") }
            }
            val knownBoards = AutoReplyStore.csv(config.knownBoards).sorted()
            if (knownBoards.isEmpty()) Text("暂未读到板块目录：token 导入后点“刷新”，或发布一条帖子后自动收录。", style = MaterialTheme.typography.bodySmall)
            Text("板块白名单（勾选要回复的板块；全部不勾=回复全部）", style = MaterialTheme.typography.labelLarge)
            if (knownBoards.isNotEmpty()) BoardChecks(knownBoards, config.boardAllow) { save(config.copy(boardAllow = it)) }
            Text("板块黑名单（勾选永不回复的板块）", style = MaterialTheme.typography.labelLarge)
            if (knownBoards.isNotEmpty()) BoardChecks(knownBoards, config.boardDeny) { save(config.copy(boardDeny = it)) }
            Text("关键词白名单", style = MaterialTheme.typography.labelLarge)
            PresetChecks(KEYWORD_PRESETS, config.keywordAllow) { save(config.copy(keywordAllow = it)) }
            Text("关键词黑名单", style = MaterialTheme.typography.labelLarge)
            PresetChecks(KEYWORD_PRESETS, config.keywordDeny) { save(config.copy(keywordDeny = it)) }
            TextField("拒绝回复的字词或段落", config.rejectTerms) { save(config.copy(rejectTerms = it)) }
            TextField("专门回复的触发词", config.specialTerms) { save(config.copy(specialTerms = it)) }
            TextField("命中专门触发词时的回复要求", config.specialInstruction) { save(config.copy(specialInstruction = it)) }
            TextField("帖主白名单（账号 ID 或昵称）", config.authorAllow) { save(config.copy(authorAllow = it)) }
            TextField("帖主黑名单（账号 ID 或昵称）", config.authorDeny) { save(config.copy(authorDeny = it)) }
            TextField("只回复提及这些名称（逗号分隔）", config.mentionNames) { save(config.copy(mentionNames = it)) }
            SwitchLine("只回复 @ 或提及本账号", config.requireMention) { save(config.copy(requireMention = it)) }
            SwitchLine("允许回复楼中楼", config.includeNested) { save(config.copy(includeNested = it)) }
        }

        Section("生成偏好") {
            NumberField("Token 上限", config.tokenBudget) { save(config.copy(tokenBudget = it.coerceIn(64, 8000))) }
            NumberField("回复最大长度（集市评论上限 200 字）", config.maxLength) { save(config.copy(maxLength = it.coerceIn(20, 200))) }
            SwitchLine("携带 AI 声明", config.includeAiDisclosure) { save(config.copy(includeAiDisclosure = it)) }
            SwitchLine("附上回答依据摘要", config.showThinking) { save(config.copy(showThinking = it)) }
            SwitchLine("一般回复允许联网检索", config.externalLookup) { save(config.copy(externalLookup = it)) }
            TextField("外部搜索服务 HTTPS URL", config.searchUrl) { save(config.copy(searchUrl = it)) }
            MaskedTextField("外部搜索 API Token（留空则不带鉴权头）", searchToken) { searchToken = it }
            Button(enabled = searchToken.isNotBlank(), onClick = {
                scope.launch {
                    host.secrets().put(config.searchTokenSecret, searchToken)
                    searchToken = ""
                    status = "已保存联网检索 API Token。"
                }
            }) { Text("保存联网 Token") }
            SwitchLine("自动删除插件已发送的回复", config.deleteEnabled) { save(config.copy(deleteEnabled = it)) }
            NumberField("保留回复时间（分钟）", config.deleteAfterMinutes) { save(config.copy(deleteAfterMinutes = it.coerceIn(1, 525600))) }
            SwitchLine("回答帖主主动提出的个人历史问题", config.authorHistoryEnabled) { save(config.copy(authorHistoryEnabled = it)) }
            NumberField("历史检索天数", config.historyDays) { save(config.copy(historyDays = it.coerceIn(1, 3650))) }
            NumberField("最多纳入历史帖子", config.historyLimit) { save(config.copy(historyLimit = it.coerceIn(1, 50))) }
            TextField("历史路径触发词（逗号分隔）", config.historyTriggerTerms) { save(config.copy(historyTriggerTerms = it)) }
            Button(enabled = ready && !busy, onClick = {
                scope.launch {
                    busy = true
                    status = runCatching {
                        val probe = plugin.probeHistory(host)
                        "历史检索探测：帖子 ${probe["thread_id"]}《${probe["title"]}》 匿名=${probe["anon"]} " +
                            "身份=${probe["nickname"]}(${(probe["owner"] as? String)?.ifBlank { "无账号" } ?: "无账号"}) " +
                            "来源=${probe["source"]} 样本=${probe["sample_rows"]}行 · 命中历史 ${probe["history_count"]} 条：" +
                            ((probe["titles"] as? List<*>)?.joinToString("、").orEmpty().ifBlank { "（无）" })
                    }.getOrElse { "历史检索探测失败：${it.message}" }
                    busy = false
                }
            }) { Text("测试历史检索（真接口）") }
            SwitchLine("中大资料上游（真题/课程资料）", config.sysuMaterialsEnabled) { save(config.copy(sysuMaterialsEnabled = it)) }
            TextField("资料库检索 API", config.sysuMaterialsSearchApi) { save(config.copy(sysuMaterialsSearchApi = it)) }
            TextField("资料库网页地址", config.sysuMaterialsWebBase) { save(config.copy(sysuMaterialsWebBase = it)) }
            TextField("GitHub 资料仓库 (owner/repo)", config.sysuGithubRepo) { save(config.copy(sysuGithubRepo = it)) }
            NumberField("单次最多资料条数", config.sysuMaterialsLimit) { save(config.copy(sysuMaterialsLimit = it.coerceIn(1, 20))) }
            Button(enabled = ready && !busy, onClick = {
                scope.launch {
                    busy = true
                    status = runCatching {
                        val probe = plugin.probeSysuMaterials(host)
                        val preview = (probe["preview"] as? String).orEmpty().replace('\n', ' ')
                        "资料源探测：enabled=${probe["enabled"]} 命中=${probe["lines"]} 条 ｜ $preview"
                    }.getOrElse { "资料源探测失败：${it.message}" }
                    busy = false
                }
            }) { Text("测试中大资料源（真接口）") }
            Text("默认每 15 分钟扫描一次；启用后会读取当前账号昵称，检查帖子正文和评论中的提及。", style = MaterialTheme.typography.bodySmall)
            TextField("模型 API 地址（例如 https://api.example.com/v1）", config.apiBaseUrl) { save(config.copy(apiBaseUrl = it)) }
            TextField("模型名称（例如 gpt-4o-mini）", config.apiModel) { save(config.copy(apiModel = it)) }
            MaskedTextField("模型 API Token", apiToken) { apiToken = it }
            Button(enabled = apiToken.isNotBlank(), onClick = {
                scope.launch {
                    host.secrets().put(config.apiTokenSecret, apiToken)
                    config = plugin.saveConfig(host) { it.copy(apiTokenConfigured = true) }
                    apiToken = ""
                    status = "已保存模型 API Token。"
                }
            }) { Text("保存模型 Token") }
            Text("逸问 companion 是可选的本机辅助服务地址；只有你在电脑或手机上运行了它才需要填写，例如 http://127.0.0.1:8013。没有该服务就留空，插件直接使用上面的模型 API。", style = MaterialTheme.typography.bodySmall)
            TextField("逸问 companion 地址（可选）", config.yiwenBridgeUrl) { save(config.copy(yiwenBridgeUrl = it)) }
            MaskedTextField("逸问 companion 授权 Token", yiwenToken) { yiwenToken = it }
            Button(enabled = yiwenToken.isNotBlank(), onClick = {
                scope.launch {
                    host.secrets().put(config.yiwenBridgeTokenSecret, yiwenToken)
                    yiwenToken = ""
                    status = "已保存逸问 companion 授权。"
                }
            }) { Text("保存逸问授权") }
            TextField("逸问模型名称", config.yiwenModel) { save(config.copy(yiwenModel = it)) }
            TextField("逸问搜索源", config.yiwenSearchSource) { save(config.copy(yiwenSearchSource = it)) }
            SwitchLine("逸问来源库（需校园网/VPN）", config.yiwenSourceEnabled) { save(config.copy(yiwenSourceEnabled = it)) }
            TextField("逸问 API 基址", config.yiwenSourceApiBase) { save(config.copy(yiwenSourceApiBase = it)) }
            MaskedTextField("逸问票（Authorization JWT）", yiwenJwt) { yiwenJwt = it }
            Button(enabled = yiwenJwt.isNotBlank(), onClick = {
                scope.launch {
                    host.secrets().put(config.yiwenSourceTokenSecret, yiwenJwt)
                    yiwenJwt = ""
                    status = "已保存逸问票。"
                }
            }) { Text("保存逸问票") }
            Button(enabled = ready && !busy, onClick = {
                scope.launch {
                    busy = true
                    status = runCatching {
                        val ask = plugin.yiwenAsk(host, config, "中山大学缓考申请流程是什么？")
                        val src = ask["source"].orEmpty().ifBlank { "未标注" }
                        val sid = ask["sessionId"].orEmpty().ifBlank { "-" }.take(10)
                        "逸问上游探测：来源=$src 会话=$sid ｜ ${ask["answer"].orEmpty().take(280).ifBlank { "(空)" }}"
                    }.getOrElse { "逸问上游探测失败：${it.message}" }
                    busy = false
                }
            }) { Text("测试逸问来源（真接口）") }
        }

        Section("精选订阅与分发") {
            SwitchLine("启用精选订阅", config.featuredEnabled) { save(config.copy(featuredEnabled = it)) }
            NumberField("精选扫描间隔（分钟）", config.featuredIntervalMinutes) { save(config.copy(featuredIntervalMinutes = it.coerceIn(1, 1440))) }
            TextField("精选关键词（逗号分隔）", config.featuredKeywords) { save(config.copy(featuredKeywords = it)) }
            TextField("精选板块（逗号分隔）", config.featuredBoards) { save(config.copy(featuredBoards = it)) }
            TextField("精选帖主（逗号分隔）", config.featuredAuthors) { save(config.copy(featuredAuthors = it)) }
            NumberField("最低围观数", config.featuredMinWatchers) { save(config.copy(featuredMinWatchers = it.coerceAtLeast(0))) }
            NumberField("最低蹲蹲数", config.featuredMinDunDun) { save(config.copy(featuredMinDunDun = it.coerceAtLeast(0))) }
            SwitchLine("只接收热搜榜帖子", config.featuredHotOnly) { save(config.copy(featuredHotOnly = it)) }
            NumberField("单次最多分发", config.featuredPerRun) { save(config.copy(featuredPerRun = it.coerceIn(1, 50))) }
            TextField("分发渠道：webhook,wechat,qq,email", config.featuredDestinations) { save(config.copy(featuredDestinations = it)) }
            MaskedTextField("通用 Webhook URL（逗号分隔）", config.webhookUrls) { save(config.copy(webhookUrls = it)) }
            MaskedTextField("微信群机器人 Webhook URL", config.wechatUrls) { save(config.copy(wechatUrls = it)) }
            MaskedTextField("QQ 机器人或中转 Webhook URL", config.qqUrls) { save(config.copy(qqUrls = it)) }
            TextField("Webhook JSON 模板", config.webhookTemplate) { save(config.copy(webhookTemplate = it)) }
            Button(enabled = ready && !busy, onClick = {
                scope.launch {
                    busy = true
                    status = runCatching { "分发测试成功：已向 ${plugin.testDelivery(host)} 投递最新帖子。" }
                        .getOrElse { "分发测试失败：${it.message}" }
                    busy = false
                }
            }) { Text("测试分发（真实投递一条）") }
            Text("当前宿主的官方邮箱频道未接通。", style = MaterialTheme.typography.bodySmall)
            TextField("邮箱收件人（逗号分隔）", config.emailTo) { save(config.copy(emailTo = it)) }
            Text("保存后按精选订阅周期检查和分发。QQ 地址须支持所配置的 JSON 格式。", style = MaterialTheme.typography.bodySmall)
        }

        Section("精选分发记录") {
            if (deliveries.isEmpty()) Text("暂无分发记录。")
            deliveries.asReversed().take(50).forEach { delivery ->
                Text("${delivery.destination} · ${delivery.state} · ${delivery.title.ifBlank { delivery.threadId }} · ${formatTime(delivery.sentAt)}")
                if (delivery.error.isNotBlank()) Text(delivery.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("自动回复记录") {
            if (records.isEmpty()) Text("暂无记录。", style = MaterialTheme.typography.bodyMedium)
            records.asReversed().take(50).forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.state} · 帖子 ${record.threadId}", style = MaterialTheme.typography.titleSmall)
                        if (record.threadTitle.isNotBlank()) Text(record.threadTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(record.content, style = MaterialTheme.typography.bodySmall)
                        Text("板块：${record.board}  来源：${record.source}", style = MaterialTheme.typography.bodySmall)
                        Text("创建 ${formatTime(record.createdAt)} · 发布 ${formatTime(record.publishedAt)} · 删除 ${formatTime(record.deletedAt)}", style = MaterialTheme.typography.bodySmall)
                        if (record.deleteAt > 0L && record.deletedAt == 0L) Text("计划删除 ${formatTime(record.deleteAt)}")
                        if (record.postedCommentId.isNotBlank() && record.state in setOf("published", "posted_unverified", "delete_unknown")) {
                            if (pendingDelete == record.key) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !busy, onClick = {
                                        scope.launch {
                                            busy = true
                                            status = runCatching {
                                                val result = plugin.deleteRecord(host, record.key)
                                                loadRecords()
                                                if (result.state == "deleted") "回复已删除。" else result.error
                                            }.getOrElse { "删除失败：${it.message}" }
                                            pendingDelete = null
                                            busy = false
                                        }
                                    }) { Text("确认删除") }
                                    Button(onClick = { pendingDelete = null }) { Text("取消") }
                                }
                            } else Button(onClick = { pendingDelete = record.key }) {
                                Text(if (record.state == "delete_unknown") "核验删除结果" else "删除这条回复")
                            }
                        }
                        if (record.error.isNotBlank()) Text("错误：${record.error}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PresetChecks(options: List<String>, csv: String, onChange: (String) -> Unit) {
    val selected = AutoReplyStore.csv(csv).toMutableSet()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = option in selected, onCheckedChange = { checked ->
                    if (checked) selected.add(option) else selected.remove(option)
                    onChange(selected.joinToString(","))
                })
                Text(option)
            }
        }
    }
}

@Composable
private fun BoardChecks(options: List<String>, csv: String, onChange: (String) -> Unit) {
    val selected = AutoReplyStore.csv(csv).toMutableSet()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    val label = option.substringAfter('|', option)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = option in selected, onCheckedChange = { checked ->
                            if (checked) selected.add(option) else selected.remove(option)
                            onChange(selected.joinToString(","))
                        })
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun TextField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun MaskedTextField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation())
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    TextField(label, value.toString()) { onChange(it.filter(Char::isDigit).toIntOrNull() ?: value) }
}

private fun formatTime(time: Long): String =
    if (time <= 0L) "—" else java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(time))
