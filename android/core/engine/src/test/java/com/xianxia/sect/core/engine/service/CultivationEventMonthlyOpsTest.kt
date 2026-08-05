package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runSectRecruitmentIfDue（AI 宗门弟子三年一度招募差值判据）单元测试。
 *
 * 背景：AI 宗门弟子招募由"每年 0~6 名"改为"每 3 年 1~5 名"（2026-08-06）。
 * 采用差值判据（非模运算）：老存档/跨版本相位漂移自愈；招募失败时
 * lastAiSectRecruitYear 不更新，次年自动重试（与 refreshRecruitList 同款语义）。
 */
class CultivationEventMonthlyOpsTest {

    private fun createState(
        lastAiSectRecruitYear: Int = 0,
        aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
        recruitList: List<Disciple> = emptyList()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                lastAiSectRecruitYear = lastAiSectRecruitYear,
                aiSectDisciples = aiSectDisciples,
                recruitList = recruitList
            ),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    private fun makeDisciple(id: String): Disciple = Disciple(id = id)

    @Test
    fun `runSectRecruitmentIfDue - 老档升级后第3年立即触发一次 自愈`() {
        val state = createState(lastAiSectRecruitYear = 0)
        var calls = 0
        state.runSectRecruitmentIfDue(3) { calls++ }
        assertEquals("year=3 且 lastAiSectRecruitYear=0（老档）应立即触发", 1, calls)
        assertEquals("触发后标记应写为当前年", 3, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 未满3年不触发`() {
        val state = createState(lastAiSectRecruitYear = 0)
        var calls = 0
        state.runSectRecruitmentIfDue(2) { calls++ }
        assertEquals("year=2 未满间隔不应触发", 0, calls)
        assertEquals("未触发时标记保持不变", 0, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 满3年再次触发 未满不触发`() {
        val state = createState(lastAiSectRecruitYear = 5)
        var calls = 0
        state.runSectRecruitmentIfDue(7) { calls++ }
        assertEquals("year=7（距上次仅2年）不应触发", 0, calls)
        assertEquals("未触发时标记保持不变", 5, state.gameData.lastAiSectRecruitYear)

        state.runSectRecruitmentIfDue(8) { calls++ }
        assertEquals("year=8（距上次3年）应触发", 1, calls)
        assertEquals("触发后标记应更新为8", 8, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 招募失败不更新标记 次年自动重试`() {
        val state = createState(lastAiSectRecruitYear = 3)
        var calls = 0
        // 首次招募抛异常——真实链路由 safelyRunInState 捕获后继续年变，
        // 此处用 runCatching 模拟捕获语义（runSectRecruitmentIfDue 自身不吞异常）
        runCatching {
            state.runSectRecruitmentIfDue(6) {
                calls++
                error("模拟招募失败")
            }
        }
        assertEquals("失败时 recruitment 已执行", 1, calls)
        assertEquals("失败后标记不得更新（次年自动重试）", 3, state.gameData.lastAiSectRecruitYear)

        // 次年重试成功
        state.runSectRecruitmentIfDue(7) { calls++ }
        assertEquals("次年（距上次4年）应自动重试成功", 2, calls)
        assertEquals("重试成功后标记更新为7", 7, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 同事务buffer写回不覆盖前序修改`() {
        // 年变单事务内：recruitment 写 aiSectDisciples/recruitList 后，
        // 标记 copy 必须保留这些修改（对齐 processSectDisciplesYearlyRecruitment 的 buffer 语义）
        val state = createState(
            lastAiSectRecruitYear = 0,
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("old"))),
            recruitList = listOf(makeDisciple("recruit_old"))
        )
        state.runSectRecruitmentIfDue(4) {
            gameData = gameData.copy(
                aiSectDisciples = gameData.aiSectDisciples +
                    ("ai1" to listOf(makeDisciple("old"), makeDisciple("new_ai"))),
                recruitList = gameData.recruitList + makeDisciple("recruit_fresh")
            )
        }
        assertEquals("aiSectDisciples 前序修改应保留", 2, state.gameData.aiSectDisciples["ai1"]!!.size)
        assertTrue(
            "recruitList 前序修改应保留",
            state.gameData.recruitList.map { it.id }.containsAll(listOf("recruit_old", "recruit_fresh"))
        )
        assertEquals("标记应写为当前年", 4, state.gameData.lastAiSectRecruitYear)
    }
}
