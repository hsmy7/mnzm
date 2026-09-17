package com.xianxia.sect.core.domain.battle

import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EncounterBattleServiceRouteTest — 遭遇战 native 路由接线回归测试（R4.1）。
 *
 * 守护目标：`encounter` 的两阶段战斗执行经 [com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter]
 * 路由后，在 JVM 测试环境（native bridge 未加载 → 路由器返回 null）必须无回退
 * 障碍地走 Kotlin [BattleSystem] 完成两阶段结算，日志/好感度/死亡语义不回退。
 */
class EncounterBattleServiceRouteTest {

    private fun buildService(): EncounterBattleService {
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        return EncounterBattleService(
            battleSystem = BattleSystem(rngManager),
            deathHandler = DiscipleDeathHandler()
        )
    }

    private fun buildState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun disciple(id: String, realm: Int) = Disciple(
        id = id,
        name = "弟子$id",
        realm = realm,
        realmLayer = realm * 10,
        status = DiscipleStatus.IDLE,
        isAlive = true
    )

    private val playerAttacker = EncounterAttacker(
        sectId = "player",
        sectName = "青云宗",
        isPlayer = true,
        teamDisciples = listOf(
            disciple("p1", 9), disciple("p2", 9), disciple("p3", 8)
        )
    )

    private val aiAttacker = EncounterAttacker(
        sectId = "ai_1",
        sectName = "血煞宗",
        isPlayer = false,
        teamDisciples = listOf(disciple("a1", 2), disciple("a2", 2))
    )

    private val beast = WorldLevel(
        id = "beast_1",
        type = LevelType.BEAST,
        beastName = "赤炎虎",
        realm = 3,
        count = 2
    )

    @Test
    fun `encounter - bridge absent falls back to Kotlin and completes phases`() {
        val service = buildService()
        val state = buildState()

        service.encounter(state, playerAttacker, aiAttacker, beast, year = 2, month = 3)

        // 回退回归：路由器在 JVM 环境（bridge 未加载）返回 null，必须无障碍
        // 走 Kotlin BattleSystem 完成两阶段编排——Phase 1 遭遇战日志必然写入
        //（无论谁胜），且服务不抛异常。战斗内部属性细节属 BattleSystem 域
        //（battle_test 覆盖），此处只守护路由/回退编排面。
        assertTrue("Phase 1 遭遇战日志必须写入", state.battleLogs.isNotEmpty())
        val p1Log = state.battleLogs.first()
        assertEquals(BattleType.ENCOUNTER, p1Log.type)
        assertTrue("Phase 1 必须产出胜负结论", p1Log.details.isNotBlank())
    }

    @Test
    fun `encounter - player vs AI favor delta applied once per month with dedup`() {
        val service = buildService()
        val state = buildState()
        val dedup = mutableSetOf<String>()

        service.encounter(state, playerAttacker, aiAttacker, beast, year = 2, month = 3, favorDedup = dedup)
        val relationsAfterFirst = state.gameData.sectRelations

        service.encounter(state, playerAttacker, aiAttacker, beast, year = 2, month = 3, favorDedup = dedup)

        assertTrue(
            "首次 playerVsAI 遭遇必须建立宗门关系",
            relationsAfterFirst.isNotEmpty()
        )
        assertEquals(
            "同月同 AI 宗门第二次遭遇必须去重，好感度不再变化",
            relationsAfterFirst,
            state.gameData.sectRelations
        )
        assertTrue(
            "好感度去重 key 必须登记（aiSectId_absMonth）",
            dedup.contains("ai_1_${2 * 12 + 3}")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encounter - same sect id throws`() {
        val service = buildService()
        val state = buildState()
        val attacker = EncounterAttacker("same", "同宗", isPlayer = true, teamDisciples = emptyList())
        service.encounter(state, attacker, attacker.copy(isPlayer = false), beast, year = 2, month = 3)
    }
}
