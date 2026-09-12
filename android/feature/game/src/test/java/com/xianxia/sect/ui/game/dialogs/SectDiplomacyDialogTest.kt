package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.model.SectRelationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectDiplomacyDialogTest {

    // ==================== dialogueTextForRelation ====================

    @Test
    fun `dialogueTextForRelation - ally returns ally greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.NORMAL, isAlly = true)
        assertEquals("盟友亲至，有何要事但说无妨。", text)
    }

    @Test
    fun `dialogueTextForRelation - ally overrides relation level`() {
        val hostileAsAlly = dialogueTextForRelation(SectRelationLevel.HOSTILE, isAlly = true)
        assertEquals("盟友亲至，有何要事但说无妨。", hostileAsAlly)
    }

    @Test
    fun `dialogueTextForRelation - HOSTILE returns hostile greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.HOSTILE, isAlly = false)
        assertEquals("......阁下竟敢踏足本宗地界？", text)
    }

    @Test
    fun `dialogueTextForRelation - ANTAGONISTIC returns antagonistic greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.ANTAGONISTIC, isAlly = false)
        assertEquals("哼，有话快说，本宗不欢迎你。", text)
    }

    @Test
    fun `dialogueTextForRelation - NORMAL returns normal greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.NORMAL, isAlly = false)
        assertEquals("贵宗来访，不知有何贵干？", text)
    }

    @Test
    fun `dialogueTextForRelation - FRIENDLY returns friendly greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.FRIENDLY, isAlly = false)
        assertEquals("原来是友宗到访，快请一叙。", text)
    }

    @Test
    fun `dialogueTextForRelation - INTIMATE returns intimate greeting`() {
        val text = dialogueTextForRelation(SectRelationLevel.INTIMATE, isAlly = false)
        assertEquals("哈哈，老友来访，真是蓬荜生辉！", text)
    }

    // ==================== getAiResponseText ====================

    @Test
    fun `getAiResponseText - accept at favor 100`() {
        val text = getAiResponseText(100, success = true)
        assertEquals("哈哈！得贵宗为盟实乃我宗之幸！从此你我二宗同气连枝，共进退！", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 90`() {
        val text = getAiResponseText(90, success = true)
        assertEquals("哈哈！得贵宗为盟实乃我宗之幸！从此你我二宗同气连枝，共进退！", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 85`() {
        val text = getAiResponseText(85, success = true)
        assertEquals("善！道友诚意可嘉，我宗愿与贵宗结为盟友，共图大业！", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 80`() {
        val text = getAiResponseText(80, success = true)
        assertEquals("善！道友诚意可嘉，我宗愿与贵宗结为盟友，共图大业！", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 70`() {
        val text = getAiResponseText(70, success = true)
        assertEquals("哈哈，道友盛情相邀，我宗自然乐意之至！", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 60`() {
        val text = getAiResponseText(60, success = true)
        assertEquals("哈哈，道友盛情相邀，我宗自然乐意之至！", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 50`() {
        val text = getAiResponseText(50, success = true)
        assertEquals("贵宗既有此意，我宗也愿与贵宗携手共进，就此结盟。", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 40`() {
        val text = getAiResponseText(40, success = true)
        assertEquals("贵宗既有此意，我宗也愿与贵宗携手共进，就此结盟。", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 30`() {
        val text = getAiResponseText(30, success = true)
        assertEquals("...罢了，既然你们有此诚意，我宗便答应这次结盟。", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 20`() {
        val text = getAiResponseText(20, success = true)
        assertEquals("...罢了，既然你们有此诚意，我宗便答应这次结盟。", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 10`() {
        val text = getAiResponseText(10, success = true)
        assertEquals("哼...虽然你我两宗素无交情，但既然你们放低身段来求，本宗就勉为其难应了吧。", text)
    }

    @Test
    fun `getAiResponseText - accept at favor 0`() {
        val text = getAiResponseText(0, success = true)
        assertEquals("哼...虽然你我两宗素无交情，但既然你们放低身段来求，本宗就勉为其难应了吧。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 100`() {
        val text = getAiResponseText(100, success = false)
        assertEquals("唉，道友厚爱本宗铭感五内。只是天意难违，结盟之缘未到，还望见谅。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 90`() {
        val text = getAiResponseText(90, success = false)
        assertEquals("唉，道友厚爱本宗铭感五内。只是天意难违，结盟之缘未到，还望见谅。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 85`() {
        val text = getAiResponseText(85, success = false)
        assertEquals("道友盛情，本宗心领。然此事还需从长计议，非一时之功。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 80`() {
        val text = getAiResponseText(80, success = false)
        assertEquals("道友盛情，本宗心领。然此事还需从长计议，非一时之功。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 70`() {
        val text = getAiResponseText(70, success = false)
        assertEquals("道友厚爱，只是此事关系重大，容我宗再作考虑。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 60`() {
        val text = getAiResponseText(60, success = false)
        assertEquals("道友厚爱，只是此事关系重大，容我宗再作考虑。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 50`() {
        val text = getAiResponseText(50, success = false)
        assertEquals("贵宗好意心领，但我宗暂不考虑结盟之事。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 40`() {
        val text = getAiResponseText(40, success = false)
        assertEquals("贵宗好意心领，但我宗暂不考虑结盟之事。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 30`() {
        val text = getAiResponseText(30, success = false)
        assertEquals("...我宗对贵宗并无兴趣，请回吧。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 20`() {
        val text = getAiResponseText(20, success = false)
        assertEquals("...我宗对贵宗并无兴趣，请回吧。", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 10`() {
        val text = getAiResponseText(10, success = false)
        assertEquals("哼！就凭你们也配与我宗结盟？速速离去！", text)
    }

    @Test
    fun `getAiResponseText - reject at favor 0`() {
        val text = getAiResponseText(0, success = false)
        assertEquals("哼！就凭你们也配与我宗结盟？速速离去！", text)
    }

    // 边界验证：所有 12 种组合互不相同
    @Test
    fun `getAiResponseText - all 12 combinations are distinct`() {
        val texts = mutableSetOf<String>()
        for (favor in listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 85, 90, 100)) {
            texts.add(getAiResponseText(favor, success = true))
            texts.add(getAiResponseText(favor, success = false))
        }
        // 至少应有 12 种不同文本（实际为12）
        assertTrue("12种组合应有12种不同文本，实际${texts.size}", texts.size >= 12)
    }

    // ==================== buildPlayerGiftText ====================

    @Test
    fun `buildPlayerGiftText - tier 4 returns 大礼 text`() {
        val text = buildPlayerGiftText("青云宗", 4)
        assertTrue("大礼文本应包含'大礼'", text.contains("大礼"))
    }

    @Test
    fun `buildPlayerGiftText - tier 3 returns 重礼 text`() {
        val text = buildPlayerGiftText("青云宗", 3)
        assertTrue("重礼文本应包含'重礼'", text.contains("重礼"))
    }

    @Test
    fun `buildPlayerGiftText - tier 2 returns 厚礼 text`() {
        val text = buildPlayerGiftText("青云宗", 2)
        assertTrue("厚礼文本应包含'厚礼'", text.contains("厚礼"))
    }

    @Test
    fun `buildPlayerGiftText - tier 1 returns 薄礼 text`() {
        val text = buildPlayerGiftText("青云宗", 1)
        assertTrue("薄礼文本应包含'薄礼'", text.contains("薄礼"))
    }

    @Test
    fun `buildPlayerGiftText - invalid tier returns fallback text`() {
        val text = buildPlayerGiftText("青云宗", 0)
        assertTrue("无效档位应返回含宗门名的文本", text.contains("青云宗"))
        assertTrue("无效档位应返回含'心意'的文本", text.contains("心意"))
    }

    @Test
    fun `buildPlayerGiftText - non-empty for all tiers`() {
        for (tier in 0..5) {
            assertTrue("tier=$tier 应返回非空文本", buildPlayerGiftText("宗门", tier).isNotEmpty())
        }
    }

    // ==================== getGiftAiAcceptText ====================

    @Test
    fun `getGiftAiAcceptText - HOSTILE returns hostile accept text`() {
        val text = getGiftAiAcceptText(SectRelationLevel.HOSTILE)
        assertTrue("HOSTILE接受文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiAcceptText - ANTAGONISTIC returns antagonistic accept text`() {
        val text = getGiftAiAcceptText(SectRelationLevel.ANTAGONISTIC)
        assertTrue("ANTAGONISTIC接受文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiAcceptText - NORMAL returns normal accept text`() {
        val text = getGiftAiAcceptText(SectRelationLevel.NORMAL)
        assertTrue("NORMAL接受文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiAcceptText - FRIENDLY returns friendly accept text`() {
        val text = getGiftAiAcceptText(SectRelationLevel.FRIENDLY)
        assertTrue("FRIENDLY接受文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiAcceptText - INTIMATE returns intimate accept text`() {
        val text = getGiftAiAcceptText(SectRelationLevel.INTIMATE)
        assertTrue("INTIMATE接受文本应非空", text.isNotEmpty())
    }

    // ==================== getGiftAiRejectText ====================

    @Test
    fun `getGiftAiRejectText - HOSTILE returns hostile reject text`() {
        val text = getGiftAiRejectText(SectRelationLevel.HOSTILE)
        assertTrue("HOSTILE拒绝文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiRejectText - ANTAGONISTIC returns antagonistic reject text`() {
        val text = getGiftAiRejectText(SectRelationLevel.ANTAGONISTIC)
        assertTrue("ANTAGONISTIC拒绝文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiRejectText - NORMAL returns normal reject text`() {
        val text = getGiftAiRejectText(SectRelationLevel.NORMAL)
        assertTrue("NORMAL拒绝文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiRejectText - FRIENDLY returns friendly reject text`() {
        val text = getGiftAiRejectText(SectRelationLevel.FRIENDLY)
        assertTrue("FRIENDLY拒绝文本应非空", text.isNotEmpty())
    }

    @Test
    fun `getGiftAiRejectText - INTIMATE returns intimate reject text`() {
        val text = getGiftAiRejectText(SectRelationLevel.INTIMATE)
        assertTrue("INTIMATE拒绝文本应非空", text.isNotEmpty())
    }

    // ==================== buildPlayerReplyText ====================

    @Test
    fun `buildPlayerReplyText - success returns accept reply`() {
        val text = buildPlayerReplyText(true)
        assertTrue("接受回应应非空", text.isNotEmpty())
    }

    @Test
    fun `buildPlayerReplyText - failure returns reject reply`() {
        val text = buildPlayerReplyText(false)
        assertTrue("拒绝回应应非空", text.isNotEmpty())
    }

    @Test
    fun `buildPlayerReplyText - accept and reject texts are different`() {
        val acceptTexts = (1..5).map { buildPlayerReplyText(true) }.toSet()
        val rejectTexts = (1..5).map { buildPlayerReplyText(false) }.toSet()
        // 两个集合不应有交集
        assertTrue("接受和拒绝文本应完全不同", acceptTexts.intersect(rejectTexts).isEmpty())
    }
}
