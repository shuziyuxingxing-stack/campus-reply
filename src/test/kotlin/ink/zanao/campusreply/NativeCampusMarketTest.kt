package ink.zanao.campusreply

import io.github.whywood.deepdeep.plugin.api.ScreenBounds
import io.github.whywood.deepdeep.plugin.api.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeCampusMarketTest {
    @Test fun selectsVisibleTitleUnderHotPosts() {
        val feed = listOf(node("集市24小时火文", 337), node("", 341), node("校园测试帖子", 378), node("最新", 501))
        assertEquals("校园测试帖子", CampusMarketScreen.pickPost(feed, title = null)?.text)
        assertNull(CampusMarketScreen.pickPost(feed, title = "其他标题"))
    }

    @Test fun readsBodyAndActualCommentsBelowTheirTimestamp() {
        val detail = listOf(
            node("举报", 106, 888), node("校园测试帖子", 152), node("这里是帖子正文", 183),
            node("8ttr7wmflyp4cu5", 222), node("#校园趣事#", 454), node("5682人围观", 454, 308),
            node("分享", 486, 498), node("共5条评论", 591), node("按时间", 595, 825),
            node("说好话的人运气都很好", 639, 150), node("测试昵称", 702, 137),
            node("10:21·来自APP端", 724, 137), node("这是一楼的评论", 747, 137),
            node("10:24·来自APP端", 851, 213), node("这是楼中楼的评论", 875, 213),
            node("友善评论 传递温暖", 918, 172),
        )
        val result = CampusMarketScreen.parse(detail, "校园测试帖子")
        assertEquals("校园测试帖子", result.title)
        assertEquals("这里是帖子正文", result.body)
        assertEquals(5, result.commentCount)
        assertEquals(listOf("这是一楼的评论", "这是楼中楼的评论"), result.comments)
    }

    private fun node(text: String, top: Int, left: Int = 38) =
        ScreenNode(text, "android.widget.TextView", text, bounds = ScreenBounds(left, top, left + 100, top + 19))
}

