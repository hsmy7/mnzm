package com.xianxia.sect.ui.model

import com.xianxia.sect.data.unified.SaveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动进入决策守卫（登录/验证通过后经加载界面直达游戏的判定核心）。
 *
 * 守护目标：
 * - 老玩家（本地有档）必读本地档，且不发云端查询（hasLoadableLocal=false 才查）；
 * - 换机/重装（本地无档）走云端兜底；云端无档/未判定才自动新建；
 * - **存档安全红线**：本地存在损坏档（isLoadError）时永不静默新建——损坏档交给读档链
 *   （修复或显式「删除存档并重新开始」），防止查询异常被误判为空库后覆盖唯一存档。
 */
class AutoEntryResolverTest {

    private fun save(
        timestamp: Long,
        isEmpty: Boolean = false,
        isLoadError: Boolean = false
    ) = SaveInfo(
        timestamp = timestamp,
        gameYear = 1,
        gameMonth = 1,
        sectName = if (isEmpty || isLoadError) "" else "青云宗",
        discipleCount = 0,
        spiritStones = 0L,
        isEmpty = isEmpty,
        isLoadError = isLoadError
    )

    // ── 老玩家：本地有档 ──

    @Test
    fun `本地有档时读本地`() {
        val entry = AutoEntryResolver.resolve(
            save(300L),
            cloudHasSave = true
        )
        assertEquals(AutoEntry.LoadLocal, entry)
    }

    @Test
    fun `空档不参与本地判定`() {
        val entry = AutoEntryResolver.resolve(
            save(0L, isEmpty = true),
            cloudHasSave = false
        )
        assertEquals(AutoEntry.CreateNew, entry)
    }

    // ── 换机/重装：云端兜底 ──

    @Test
    fun `本地无档且云端有档时读云`() {
        val entry = AutoEntryResolver.resolve(
            save(0L, isEmpty = true),
            cloudHasSave = true
        )
        assertEquals(AutoEntry.LoadCloud, entry)
    }

    @Test
    fun `本地无档且云端无档时自动新建`() {
        val entry = AutoEntryResolver.resolve(null, cloudHasSave = false)
        assertEquals(AutoEntry.CreateNew, entry)
    }

    @Test
    fun `云端未判定（查询超时或失败）按无云端档处理`() {
        val entry = AutoEntryResolver.resolve(null, cloudHasSave = null)
        assertEquals(AutoEntry.CreateNew, entry)
    }

    // ── 存档安全红线：损坏档 ──

    @Test
    fun `只剩损坏档且云端无档时交读档链处置而非静默新建`() {
        val entry = AutoEntryResolver.resolve(
            save(500L, isLoadError = true),
            cloudHasSave = false
        )
        assertEquals("损坏档必须交给读档链（修复或显式删档），不得自动新建覆盖", AutoEntry.LoadLocal, entry)
    }

    @Test
    fun `只剩损坏档但云端有档时优先读云`() {
        val entry = AutoEntryResolver.resolve(
            save(500L, isLoadError = true),
            cloudHasSave = true
        )
        assertEquals(AutoEntry.LoadCloud, entry)
    }

    // ── 云端查询门控 ──

    @Test
    fun `有可读本地档时跳过云端查询`() {
        assertTrue(
            AutoEntryResolver.hasLoadableLocal(save(100L))
        )
    }

    @Test
    fun `本地无可读档时才发起云端查询`() {
        assertFalse(
            AutoEntryResolver.hasLoadableLocal(save(0L, isEmpty = true))
        )
        assertFalse(
            AutoEntryResolver.hasLoadableLocal(save(100L, isLoadError = true))
        )
    }
}
