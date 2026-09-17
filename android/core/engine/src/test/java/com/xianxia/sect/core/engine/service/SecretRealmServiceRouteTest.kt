package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmAIMember
import com.xianxia.sect.core.model.SecretRealmAITeam
import com.xianxia.sect.core.model.SecretRealmEventParams
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmOption
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * SecretRealmServiceRouteTest — 秘境战斗 native 路由接线回归测试（R4.2）。
 *
 * 守护目标：`chooseOption` 的两条战斗执行段（妖兽战 PvE / AI 宗门遭遇 PvP）
 * 经 [com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter] 路由后，
 * 在 JVM 测试环境（native bridge 未加载 → 路由器返回 null）与灰度旗标 OFF
 * 两条回退路径下，必须无回退障碍地走 Kotlin [BattleSystem] 完成结算——
 * 战报记录/成员写回/奖励结算/会话推进语义不回退。
 *
 * 等价性：AUTHORITATIVE + 生产桥已加载路径的 C++ 执行语义由
 * DiffBattleExecutionTest（桌面对拍桥，同源 battle_execution.h）逐位守护。
 */
class SecretRealmServiceRouteTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var service: SecretRealmService
    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        tables = DiscipleTables()
        val inventorySystem = mock(InventorySystem::class.java)
        whenever(inventorySystem.withTrackingSource<Any>(any(), any())).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
        service = SecretRealmService(
            rngManager = rngManager,
            battleSystem = BattleSystem(rngManager),
            inventorySystem = inventorySystem,
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            overflowMailSender = mock(OverflowMailSender::class.java),
            assignmentGate = mock(DiscipleAssignmentGate::class.java)
        )
    }

    private fun buildState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun insertDisciple(id: Int, realm: Int): Disciple {
        val disciple = Disciple(
            id = id.toString(),
            name = "弟子$id",
            realm = realm,
            realmLayer = 1,
            age = 25,
            lifespan = 90,
            skills = SkillStats(comprehension = 100),
            combat = CombatAttributes(currentHp = -1)
        )
        tables.insert(disciple)
        tables.isAlive[id] = 1
        tables.statuses[id] = DiscipleStatus.IDLE
        return disciple
    }

    /** 出发探索（生产同路径）：4 名弟子组队，会话携带初始妖兽事件。 */
    private fun startBeastSession(state: MutableGameState) {
        val ids = (1..4).map { insertDisciple(it, realm = 9).id }
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "realm_1", spawnYear = 1)
        )
        val started = service.startSession(ids, state)
        assertTrue("出发必须成功", started is DomainResult.Success)
        assertEquals(
            SecretRealmEventType.BEAST_ENCOUNTER.name,
            state.gameData.secretRealmSession.currentEvent?.eventType
        )
    }

    /** 构造 AI 宗门遭遇会话：存活 AI 探索队伍 + AI_SECT_ENCOUNTER 当前事件。 */
    private fun setupAIEncounterSession(state: MutableGameState) {
        val ids = (1..4).map { insertDisciple(it, realm = 9).id }
        val aiMembers = listOf(SecretRealmAIMember(discipleId = "a1", name = "剑尘", realm = 1))
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(id = "realm_1", spawnYear = 1),
            secretRealmAITeams = listOf(
                SecretRealmAITeam(
                    id = "team_1", sectId = "sect1", sectName = "青云宗",
                    sectLevel = 1, members = aiMembers
                )
            ),
            aiSectDisciples = mapOf(
                "sect1" to listOf(
                    Disciple(
                        id = "a1", name = "剑尘", realm = 1, realmLayer = 1,
                        age = 30, lifespan = 90,
                        skills = SkillStats(comprehension = 100),
                        combat = CombatAttributes(currentHp = -1)
                    ).copy(isAlive = true)
                )
            ),
            secretRealmSession = SecretRealmExplorationSession(
                secretRealmId = "realm_1",
                members = ids.map { did ->
                    SecretRealmMemberState(
                        discipleId = did, name = "弟子", realm = 9, realmName = "合体"
                    )
                },
                stamina = 20,
                currentEvent = SecretRealmEventRecord(
                    eventType = SecretRealmEventType.AI_SECT_ENCOUNTER.name,
                    title = "遭遇青云宗探索队伍",
                    description = "前方发现青云宗的探索队伍，狭路相逢",
                    options = listOf(
                        SecretRealmOption("向左避让", ""),
                        SecretRealmOption("与之交战", ""),
                        SecretRealmOption("向右避让", "")
                    ),
                    params = SecretRealmEventParams(
                        aiSectId = "sect1", aiSectName = "青云宗", aiSectLevel = 1,
                        aiMembers = aiMembers
                    )
                )
            )
        )
    }

    /** 回退臂结算面断言：进入战斗 + 战报重建 + 记录 + 胜负一致 + 成员写回。 */
    private fun assertFallbackSettled(
        state: MutableGameState,
        result: SecretRealmChoiceResult.Success,
        type: BattleType
    ) {
        assertTrue("必须进入战斗", result.enteredCombat)
        assertTrue("战报数据必须重建", result.combatLog != null)
        val log = state.battleLogs.single()
        assertEquals(type, log.type)
        assertEquals("玩家探索队伍", log.attackerName)
        assertEquals("结果记录与结算胜负一致", result.victory, log.result == BattleResult.WIN)
        // 存活/濒死成员 HP 写回表级口径（阵亡成员经死亡入口物化，无表级残值要求）
        state.gameData.secretRealmSession.members
            .filter { !it.isDead }
            .forEach { ms ->
                val idInt = ms.discipleId.toIntOrNull()
                assertTrue(
                    "成员 ${ms.discipleId} 表级 HP 必须已写回",
                    idInt == null || tables.currentHps[idInt] >= 0
                )
            }
    }

    @Test
    fun `chooseOption - beast battle bridge absent falls back to Kotlin and settles`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val state = buildState()
            startBeastSession(state)
            // AUTHORITATIVE + 桥未加载：路由器返回 null → Kotlin 妖兽战斗臂完成结算
            val result = service.chooseOption(1, state)
            assertTrue("选择必须成功", result is SecretRealmChoiceResult.Success)
            val success = result as SecretRealmChoiceResult.Success
            assertFallbackSettled(state, success, BattleType.PVE)
            // 体力正常扣除（全灭自动结束时会话已清空，跳过会话级断言）
            if (!success.sessionEnded) {
                assertEquals(19, state.gameData.secretRealmSession.stamina)
            }
        }
    }

    @Test
    fun `chooseOption - flag OFF keeps Kotlin fallback arm for beast battle`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val state = buildState()
            startBeastSession(state)
            // 灰度旗标 OFF：回退臂与生产前行为一致（不删臂契约）
            val result = service.chooseOption(1, state)
            assertTrue("选择必须成功", result is SecretRealmChoiceResult.Success)
            assertFallbackSettled(state, result as SecretRealmChoiceResult.Success, BattleType.PVE)
        }
    }

    @Test
    fun `chooseOption - AI sect pvp bridge absent falls back to Kotlin and settles`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val state = buildState()
            setupAIEncounterSession(state)
            // AUTHORITATIVE + 桥未加载：PvP 臂走 Kotlin BattleSystem 完成结算
            val result = service.chooseOption(1, state)
            assertTrue("选择必须成功", result is SecretRealmChoiceResult.Success)
            val success = result as SecretRealmChoiceResult.Success
            assertFallbackSettled(state, success, BattleType.PVP)
            // 胜利 → AI 队伍被移除（markAiTeamDefeated）；战败 → 保留
            assertEquals(
                "AI 队伍移除必须与胜负一致",
                !success.victory,
                state.gameData.secretRealmAITeams.isNotEmpty()
            )
        }
    }
}
