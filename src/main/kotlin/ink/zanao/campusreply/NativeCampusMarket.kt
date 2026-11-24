package ink.zanao.campusreply

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ScreenNode
import io.github.whywood.deepdeep.plugin.api.ScreenObservation
import io.github.whywood.deepdeep.plugin.api.ScreenService
import io.github.whywood.deepdeep.plugin.api.ScreenTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CampusPostPreview(
    val title: String,
    val body: String,
    val commentCount: Int,
    val comments: List<String>,
) {
    fun asResult(): Map<String, Any?> = mapOf(
        "state" to "read", "target" to "virtual", "title" to title, "body" to body,
        "comment_count" to commentCount, "comments_read" to comments.size, "comments" to comments,
    )
}

internal class NativeCampusMarket {
    private val lock = Mutex()
    private val target = ScreenTarget.VIRTUAL

    suspend fun read(host: PluginHost, title: String? = null): CampusPostPreview = lock.withLock {
        val screen = host.screen()
        var window = openApp(screen)
        var nodes = CampusMarketScreen.labels(window.nodes)
        var chosenTitle = title
        if (CampusMarketScreen.commentHeading(nodes) == null || title != null && nodes.none { it.text == title }) {
            if (CampusMarketScreen.commentHeading(nodes) != null) {
                check(screen.key("back", target)) { "虚拟屏无法返回集市列表" }
                window = awaitPage(screen) { CampusMarketScreen.feedMark(it) }
                nodes = CampusMarketScreen.labels(window.nodes)
            }
            val chosen = CampusMarketScreen.pickPost(nodes, title)
                ?: error(if (title == null) "集市列表里暂时没有可打开的帖子" else "当前集市列表没找到指定标题")
            chosenTitle = chosen.text
            val accepted = chosen.ref.isNotBlank() && try {
                screen.tapNode(chosen.ref, target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            val readBack = if (accepted) awaitPageOrNull(screen, attempts = 5) {
                CampusMarketScreen.commentHeading(it) != null
            } else null
            window = readBack ?: run {
                val current = screen.observe(target, PACKAGE)
                val same = CampusMarketScreen.pickPost(CampusMarketScreen.labels(current.nodes), chosenTitle)
                    ?: error("集市列表已变化，请重新读取")
                check(tapBounds(screen, same)) { "虚拟屏没有接收打开帖子的操作" }
                awaitPage(screen) { CampusMarketScreen.commentHeading(it) != null }
            }
            nodes = CampusMarketScreen.labels(window.nodes)
        }
        val result = CampusMarketScreen.parse(nodes, chosenTitle)
        if (result.commentCount > 0 && result.comments.isEmpty() && screen.scroll(420, target)) {
            val scrolled = awaitPage(screen) {
                CampusMarketScreen.commentHeading(it) != null && CampusMarketScreen.commentRows(it).isNotEmpty()
            }
            result.copy(comments = CampusMarketScreen.commentRows(CampusMarketScreen.labels(scrolled.nodes)).take(20))
        } else result
    }

    private suspend fun openApp(screen: ScreenService): ScreenObservation {
        if (!screen.status.virtual.available) screen.startVirtual()
        check(screen.status.virtual.available) { "Lab 虚拟屏没有启动，请在系统访问中检查虚拟屏权限" }
        val existing = observe(screen)
        val labels = CampusMarketScreen.labels(existing?.nodes.orEmpty())
        if (existing?.packageName != PACKAGE || labels.isEmpty()) {
            check(screen.launch(PACKAGE, target)) { "Lab 无法在虚拟屏打开校园集市" }
        }
        val visible = awaitPage(screen) { it.any { node -> node.text == "集市" } ||
            CampusMarketScreen.feedMark(it) || CampusMarketScreen.commentHeading(it) != null }
        val seen = CampusMarketScreen.labels(visible.nodes)
        if (CampusMarketScreen.feedMark(seen) || CampusMarketScreen.commentHeading(seen) != null) return visible
        val tab = seen.filter { it.text == "集市" }.maxByOrNull { it.bounds.top } ?: error("未找到集市标签")
        check(tapBounds(screen, tab)) { "虚拟屏无法打开集市标签" }
        return awaitPage(screen) { CampusMarketScreen.feedMark(it) }
    }

    private suspend fun observe(screen: ScreenService): ScreenObservation? = try {
        screen.observe(target, PACKAGE)
    } catch (e: IllegalStateException) {
        val diagnostic = e.message.orEmpty()
        if (diagnostic.contains("screen has no windows", ignoreCase = true) ||
            diagnostic.startsWith("No window of $PACKAGE is on this screen") ||
            diagnostic.startsWith("This screen has no readable application window")
        ) null else throw e
    }

    private suspend fun tapBounds(screen: ScreenService, node: ScreenNode): Boolean {
        val bounds = node.bounds
        return screen.tap((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2, target)
    }

    private suspend fun awaitPage(screen: ScreenService, accept: (List<ScreenNode>) -> Boolean): ScreenObservation =
        awaitPageOrNull(screen, 25, accept)
            ?: error("校园集市虚拟屏未返回所需的节点。检查 App 是否完成加载及 Lab 的无障碍状态")

    private suspend fun awaitPageOrNull(screen: ScreenService, attempts: Int,
        accept: (List<ScreenNode>) -> Boolean): ScreenObservation? {
        repeat(attempts) { attempt ->
            val snapshot = observe(screen)
            val labels = CampusMarketScreen.labels(snapshot?.nodes.orEmpty())
            if (snapshot?.packageName == PACKAGE) {
                if (labels.any { it.text == "微信授权登录" || it.text == "微信登录" }) {
                    error("校园集市需要在 App 内完成微信授权")
                }
                if (accept(labels)) return snapshot
            }
            if (attempt + 1 < attempts) delay(420)
        }
        return null
    }

    companion object {
        const val PACKAGE = "com.comingx.zanao"
    }
}

internal object CampusMarketScreen {
    private val heading = Regex("(?:共\\s*)?(\\d+)\\s*条评论")
    private val time = Regex("(?:\\d{1,2}:\\d{2}|刚刚|昨天|前天|\\d+\\s*[分小时天]+钟?前|\\d{1,2}[-月]\\d{1,2}日?)(?:\\s*[·・].*)?")
    private val visibleText = Regex("[\\p{L}\\p{N}\\p{S}]")
    private val imageKey = Regex("[a-z0-9]{14,27}|[A-Za-z0-9/+]{19,}={0,2}")
    private val ignored = setOf("按赞数", "按时间", "分享", "马住", "帮顶", "举报", "拉黑", "私聊", "avatar",
        "友善评论 传递温暖", "说好话的人运气都很好", "最新", "评论", "暂无评论", "集市", "消息", "我的", "初中生", "小学生")
    private const val HOT = "集市24小时火文"

    fun labels(nodes: List<ScreenNode>): List<ScreenNode> =
        nodes.flatMap { node ->
            val children = labels(node.children)
            if (!node.text.isNullOrBlank() && children.none { it.text == node.text && it.bounds == node.bounds }) {
                listOf(node) + children
            } else children
        }.distinctBy { it.text to it.bounds }

    fun feedMark(nodes: List<ScreenNode>): Boolean =
        nodes.any { it.text == HOT } || (nodes.any { it.text == "最新" } && nodes.any { it.text == "集市" })

    fun commentHeading(nodes: List<ScreenNode>): ScreenNode? =
        nodes.firstOrNull { heading.matches(it.text.orEmpty().trim()) }
            ?: nodes.firstOrNull { it.text == "暂无评论" }

    fun pickPost(nodes: List<ScreenNode>, title: String?): ScreenNode? {
        if (!title.isNullOrBlank()) return nodes.firstOrNull { it.text == title &&
            it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top }
        val hot = nodes.firstOrNull { it.text == HOT } ?: return null
        val latest = nodes.firstOrNull { it.text == "最新" && it.bounds.top > hot.bounds.top }
        return nodes.filter { node ->
            node.bounds.top >= hot.bounds.bottom && (latest == null || node.bounds.bottom <= latest.bounds.top) &&
                meaningful(node.text.orEmpty())
        }.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    fun parse(nodes: List<ScreenNode>, preferredTitle: String?): CampusPostPreview {
        val start = commentHeading(nodes) ?: error("未定位到帖子评论区")
        val report = nodes.firstOrNull { it.text == "举报" && it.bounds.bottom < start.bounds.top }
        val postMeta = nodes.firstOrNull { it.bounds.top < start.bounds.top && it.text.orEmpty().contains("人围观") }
        val post = nodes.filter { n ->
            n.bounds.top > (report?.bounds?.bottom ?: 0) &&
                n.bounds.bottom <= (postMeta?.bounds?.top ?: start.bounds.top) && meaningful(n.text.orEmpty())
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val observedTitle = post.firstOrNull { it.text == preferredTitle }?.text
            ?: post.firstOrNull()?.text ?: error("没有读到帖子标题")
        val anchor = post.indexOfFirst { it.text == observedTitle }
        val body = post.drop(anchor + 1).mapNotNull { it.text?.takeIf { text -> !text.startsWith("#") } }
            .distinct().take(10).joinToString("\n")
        val count = heading.matchEntire(start.text.orEmpty().trim())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return CampusPostPreview(observedTitle, body, count, commentRows(nodes).take(20))
    }

    fun commentRows(nodes: List<ScreenNode>): List<String> {
        val start = commentHeading(nodes) ?: return emptyList()
        val composer = nodes.firstOrNull { it.text == "友善评论 传递温暖" }?.bounds?.top ?: Int.MAX_VALUE
        val rows = nodes.filter { it.bounds.top > start.bounds.bottom && it.bounds.bottom <= composer }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val timestamps = rows.filter { time.matches(it.text.orEmpty().trim()) }
        return timestamps.mapIndexedNotNull { index, stamp ->
            val nextStamp = timestamps.getOrNull(index + 1)?.bounds?.top ?: composer
            rows.firstOrNull { row ->
                row.bounds.top >= stamp.bounds.bottom - 2 && row.bounds.top < nextStamp &&
                    row.bounds.left >= stamp.bounds.left - 4 && meaningful(row.text.orEmpty())
            }?.text?.trim()
        }.distinct()
    }

    private fun meaningful(raw: String): Boolean {
        val text = raw.trim()
        return text.length > 1 && text !in ignored && !time.matches(text) && !imageKey.matches(text) &&
            !text.all(Char::isDigit) && !text.contains("人围观") && visibleText.containsMatchIn(text)
    }
}
