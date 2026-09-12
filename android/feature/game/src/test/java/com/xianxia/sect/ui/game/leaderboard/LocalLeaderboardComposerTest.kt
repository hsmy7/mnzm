package com.xianxia.sect.ui.game.leaderboard

import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.taptap.LocalLeaderboardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地榜（天下宗门）组装纯函数测试：排序、过滤、兜底与玩家标记。
 */
class LocalLeaderboardComposerTest {

    private fun sect(id: String, name: String, isPlayer: Boolean = false) = WorldSect(
        id = id,
        name = name,
        isPlayerSect = isPlayer
    )

    // ── 排序 ──

    @Test
    fun `compose - 按战力降序排列`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 500,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("a" to 900L, "b" to 300L),
            worldSects = listOf(sect("a", "太玄门"), sect("b", "天机阁"))
        )
        assertEquals(listOf("太玄门", "青云宗", "天机阁"), result.map { it.name })
        assertEquals(listOf(900L, 500L, 300L), result.map { it.power })
    }

    @Test
    fun `compose - 同战力按名称升序（确定性）`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 500,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("a" to 500L, "b" to 500L),
            worldSects = listOf(sect("b", "天机阁"), sect("a", "太玄门"))
        )
        // 同战力按名称升序（Unicode 码点：天 < 太 < 青），与输入顺序无关
        assertEquals(listOf("天机阁", "太玄门", "青云宗"), result.map { it.name })
    }

    // ── 玩家标记与幂等 ──

    @Test
    fun `compose - 玩家宗门带 isPlayer 标记且只出现一次`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf(
                "player" to 9999L,  // 世界地图中玩家的 AI 条目应被过滤
                "a" to 50L
            ),
            worldSects = listOf(sect("player", "青云宗", isPlayer = true), sect("a", "太玄门"))
        )
        val playerEntries = result.filter { it.isPlayer }
        assertEquals(1, playerEntries.size)
        assertEquals("青云宗", playerEntries.single().name)
        assertEquals(100L, playerEntries.single().power)
    }

    // ── 过滤 ──

    @Test
    fun `compose - 不在 worldMapSects 中的 AI 宗门被过滤`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("ghost" to 9999L, "real" to 200L),
            worldSects = listOf(sect("real", "太玄门"))
        )
        assertEquals(listOf("太玄门", "青云宗"), result.map { it.name })
    }

    @Test
    fun `compose - AI 战力 0 的宗门保留（真实存在）`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("weak" to 0L),
            worldSects = listOf(sect("weak", "衰败门"))
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.sectId == "weak" && it.power == 0L })
    }

    // ── 兜底 ──

    @Test
    fun `compose - AI 宗门 name 缺失时用 id 兜底`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("no_name_1" to 300L),
            worldSects = listOf(sect("no_name_1", ""))
        )
        assertEquals("no_name_1", result.first().name)
    }

    @Test
    fun `compose - 玩家宗门名为空时用默认名兜底`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "",
            aiSectCombatPowers = emptyMap(),
            worldSects = emptyList()
        )
        assertEquals(1, result.size)
        assertEquals("我的宗门", result.single().name)
        assertTrue(result.single().isPlayer)
    }

    @Test
    fun `compose - 空输入仅玩家宗门`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "青云宗",
            aiSectCombatPowers = emptyMap(),
            worldSects = emptyList()
        )
        assertEquals(1, result.size)
        assertEquals("青云宗", result.single().name)
    }

    @Test
    fun `compose - 负战力归一为 0`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = -5,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("a" to -10L),
            worldSects = listOf(sect("a", "太玄门"))
        )
        assertTrue(result.all { it.power >= 0L })
        assertFalse(result.any { it.sectId == LocalLeaderboardComposer.PLAYER_SECT_ID && it.power < 0L })
    }

    @Test
    fun `compose - 结果类型与字段映射正确`() {
        val result = LocalLeaderboardComposer.compose(
            playerPower = 100,
            playerSectName = "青云宗",
            aiSectCombatPowers = mapOf("a" to 300L),
            worldSects = listOf(sect("a", "太玄门"))
        )
        val ai = result.first { it.sectId == "a" }
        assertTrue(ai is LocalLeaderboardEntry)
        assertEquals(300L, ai.power)
        assertFalse(ai.isPlayer)
    }
}
