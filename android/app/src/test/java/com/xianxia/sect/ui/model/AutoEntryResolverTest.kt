package com.xianxia.sect.ui.model

import com.xianxia.sect.data.model.SaveSlot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动进入决策守卫（登录/验证通过后经加载界面直达游戏的判定核心）。
 *
 * 守护目标：
 * - 老玩家（本地有档）必读时间戳最新一档，且不发云端查询（hasLoadableLocal=false 才查）；
 * - 换机/重装（本地全空）走云端兜底；云端无档/未判定才自动新建；
 * - **存档安全红线**：本地存在损坏档（isLoadError）时永不静默新建——损坏档交给读档链
 *   （修复或显式「删除存档并重新开始」），防止查询异常被误判为空库后覆盖 1 号槽；
 * - slot 0（云会话伪槽）永不参与本地判定。
 */
class AutoEntryResolverTest {

    private fun slot(
        slotId: Int,
        timestamp: Long,
        isEmpty: Boolean = false,
        isLoadError: Boolean = false
    ) = SaveSlot(
        slot = slotId,
        name = "",
        timestamp = timestamp,
        gameYear = 1,
        gameMonth = 1,
        sectName = if (isEmpty || isLoadError) "" else "青云宗",
        discipleCount = 0,
        spiritStones = 0L,
        isEmpty = isEmpty,
        isLoadError = isLoadError
    )

    // ── 老玩家：本地最新档 ──

    @Test
    fun `本地多档时读时间戳最新的一档`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(1, 100L), slot(3, 300L), slot(2, 200L)),
            cloudHasSave = true
        )
        assertEquals(AutoEntry.LoadLocal(3), entry)
    }

    @Test
    fun `时间戳并列取槽位号小者`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(4, 100L), slot(2, 100L)),
            cloudHasSave = null
        )
        assertEquals(AutoEntry.LoadLocal(2), entry)
    }

    @Test
    fun `云伪槽与空槽不参与本地判定`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(0, 999L), slot(5, 0L, isEmpty = true)),
            cloudHasSave = false
        )
        assertEquals(AutoEntry.CreateNew, entry)
    }

    // ── 换机/重装：云端兜底 ──

    @Test
    fun `本地全空且云端有档时读云`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(1, 0L, isEmpty = true), slot(2, 0L, isEmpty = true)),
            cloudHasSave = true
        )
        assertEquals(AutoEntry.LoadCloud, entry)
    }

    @Test
    fun `本地全空且云端无档时自动新建`() {
        val entry = AutoEntryResolver.resolve(emptyList(), cloudHasSave = false)
        assertEquals(AutoEntry.CreateNew, entry)
    }

    @Test
    fun `云端未判定（查询超时或失败）按无云端档处理`() {
        val entry = AutoEntryResolver.resolve(emptyList(), cloudHasSave = null)
        assertEquals(AutoEntry.CreateNew, entry)
    }

    // ── 存档安全红线：损坏档 ──

    @Test
    fun `有可读档时损坏档不参与选取`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(1, 500L, isLoadError = true), slot(2, 300L)),
            cloudHasSave = null
        )
        assertEquals(AutoEntry.LoadLocal(2), entry)
    }

    @Test
    fun `只剩损坏档且云端无档时交读档链处置而非静默新建`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(1, 500L, isLoadError = true)),
            cloudHasSave = false
        )
        assertEquals("损坏档必须交给读档链（修复或显式删档），不得自动新建覆盖", AutoEntry.LoadLocal(1), entry)
    }

    @Test
    fun `只剩损坏档但云端有档时优先读云`() {
        val entry = AutoEntryResolver.resolve(
            listOf(slot(1, 500L, isLoadError = true)),
            cloudHasSave = true
        )
        assertEquals(AutoEntry.LoadCloud, entry)
    }

    // ── 云端查询门控 ──

    @Test
    fun `有可读本地档时跳过云端查询`() {
        assertEquals(
            true,
            AutoEntryResolver.hasLoadableLocal(listOf(slot(1, 100L)))
        )
    }

    @Test
    fun `本地无可读档时才发起云端查询`() {
        assertEquals(
            false,
            AutoEntryResolver.hasLoadableLocal(listOf(slot(1, 0L, isEmpty = true)))
        )
        assertEquals(
            false,
            AutoEntryResolver.hasLoadableLocal(
                listOf(slot(1, 100L, isLoadError = true))
            )
        )
    }
}
