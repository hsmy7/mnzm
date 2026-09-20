package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * ExplorationPatrolRouteTest — 探索/巡逻生产 native 路由接线回归测试（R4.3）。
 *
 * 守护目标：妖兽防守生产（[ExplorationService.createBeastBattle]）与巡逻生产
 * （[PatrolBattleSystem] 三处战斗执行段：普通 PvE / 冲突战 Phase 1 PvP /
 * Phase 2 PvE）经 BattleExecutionRouter 路由后，在 JVM 测试环境（native
 * bridge 未加载 → 路由器返回 null）与灰度旗标 OFF 两条回退路径下，必须无
 * 回退障碍地走 Kotlin [BattleSystem] 完成结算——击败标记/伤亡写回/战报
 * 记录/奖励结算/AI 目标清理语义不回退。
 *
 * 等价性：AUTHORITATIVE + 生产桥已加载路径的 C++ 执行语义由
 * DiffBattleExecutionTest（桌面对拍桥，同源 battle_execution.h）逐位守护；
 * 抽取序不变性由"同区同序"路由契约保证（C++ 消费 BATTLE 分区单一真相源，
 * 与 Kotlin NativeBackedRng 委托同一分区，战斗前后抽取序逐位一致）。
 */
class ExplorationPatrolRouteTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var stateStore: FakeAtomicStateStore

    @Before
    fun setUp() {
        stateStore = FakeAtomicStateStore()
    }

    // ── 共享夹具 ─────────────────────────────────────────────────────────

    private fun teamCombatant(id: String, hp: Int) = Combatant(
        id = id, name = "弟子$id", side = CombatantSide.DEFENDER,
        hp = hp, maxHp = 100, mp = 60, maxMp = 60,
        physicalAttack = 30, magicAttack = 30, physicalDefense = 20,
        magicDefense = 20, speed = 15, critRate = 0.05, skills = emptyList()
    )

    private fun beastCombatant(id: String, hp: Int) = Combatant(
        id = id, name = "妖兽", side = CombatantSide.ATTACKER,
        hp = hp, maxHp = 200, mp = 100, maxMp = 100,
        physicalAttack = 40, magicAttack = 40, physicalDefense = 25,
        magicDefense = 25, speed = 12, critRate = 0.05, skills = emptyList(),
        isBeast = true
    )

    private fun stubInventoryAddMaterial(inventorySystem: InventorySystem) {
        // doReturn 风格：addMaterial 返回 sealed interface DomainResult
        //（ByteBuddy 无法代理，when 风格首次调用触发 smart nulls 抛 MockitoException）
        Mockito.doReturn(DomainResult.Success(Material())).`when`(inventorySystem).addMaterial(any())
        whenever(inventorySystem.withTrackingSource<Any>(any(), any())).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }
    }

    private fun insertDisciple(tables: DiscipleTables, id: Int, realm: Int) {
        tables.insert(
            Disciple(
                id = id.toString(), name = "弟子$id", realm = realm, realmLayer = 1,
                age = 25, lifespan = 90,
                skills = SkillStats(comprehension = 100),
                combat = CombatAttributes(currentHp = -1)
            )
        )
        tables.isAlive[id] = 1
        tables.statuses[id] = DiscipleStatus.IDLE
    }

    // ── Part A：妖兽防守生产（ExplorationService.createBeastBattle 路由） ──

    private fun buildExplorationService(): ExplorationService {
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        val battleSystem = mockSmart(BattleSystem::class.java)
        val inventorySystem = mockSmart(InventorySystem::class.java)
        stubInventoryAddMaterial(inventorySystem)

        val finalBattle = Battle(
            team = listOf(teamCombatant("1", hp = 50)),
            beasts = listOf(beastCombatant("beast_0", hp = 0))
        )
        whenever(
            battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        ).thenReturn(finalBattle)
        whenever(battleSystem.executeBattle(any(), any())).thenReturn(
            BattleSystemResult(
                battle = finalBattle, victory = true,
                rewards = mapOf("spiritStones" to 50), turnCount = 3
            )
        )

        return ExplorationService(
            stateStore = stateStore,
            battleSystem = battleSystem,
            rngManager = rngManager,
            inventorySystem = inventorySystem,
            cultivationService = mockSmart(CultivationService::class.java),
            spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java),
            subSystems = ExplorationSubSystems(
                worldLevelManager = mockSmart(WorldLevelManager::class.java),
                beastAttackDetector = mockSmart(BeastAttackDetector::class.java),
                patrolBattleSystem = mockSmart(PatrolBattleSystem::class.java),
                lootCalculator = mockSmart(LootCalculator::class.java),
                encounterBattleService = mockSmart(EncounterBattleService::class.java),
                deathHandler = mockSmart(DiscipleDeathHandler::class.java)
            )
        )
    }

    /** 妖兽防守生产夹具：玩家宗门 + 未击败妖兽关卡 + 2 名存活弟子。 */
    private fun setupBeastDefenseState() {
        insertDisciple(stateStore.discipleTables, 1, realm = 9)
        insertDisciple(stateStore.discipleTables, 2, realm = 8)
        stateStore.setGameData(
            GameData(
                gameYear = 2, gameMonth = 3,
                worldLevels = listOf(
                    WorldLevel(
                        id = "b1", type = LevelType.BEAST, beastName = "妖兽甲",
                        beastType = 0, realm = 5, count = 2,
                        beastMaxHp = 200, beastMaxMp = 100,
                        beastPhysicalAttack = 40, beastMagicAttack = 40,
                        beastPhysicalDefense = 25, beastMagicDefense = 25,
                        beastSpeed = 12, realmLayer = 1,
                        expiryYear = 99, expiryMonth = 12
                    )
                ),
                worldMapSects = listOf(
                    WorldSect(id = "player", name = "玩家宗门", isPlayerSect = true)
                )
            )
        )
    }

    /** 妖兽防守回退臂结算面断言：击败标记/战报/弹窗/HP 写回/奖励入卡。 */
    private fun assertBeastDefenseSettled(service: ExplorationService) = runBlocking {
        val handled = service.resolveBeastAttackFight("b1")
        assertTrue("防守生产必须完成", handled)
        val gd = stateStore.latestGameData
        assertTrue("妖兽必须标记击败", gd.worldLevels.single().defeated)
        val log = stateStore.battleLogs.value.single()
        assertEquals(BattleType.PVE, log.type)
        assertEquals("妖兽甲", log.attackerName)
        assertEquals("玩家宗门", log.defenderName)
        assertEquals("结果记录与结算胜负一致", BattleResult.WIN, log.result)
        assertEquals(2, log.beastsDefeated)
        // 防守弹窗（isBeastDefense）始终入队，携带奖励与幸存成员
        val popup = gd.pendingPatrolBattleResults.single()
        assertTrue(popup.isBeastDefense)
        assertTrue(popup.victory)
        assertTrue("灵石奖励必须入卡", popup.rewards.any { it.type == "spiritStones" })
        assertTrue(popup.teamMembers.isNotEmpty())
        // 幸存者 HP 表级写回（战斗终态 hp=50，钳制上限为含装备最终口径）
        assertEquals(50, stateStore.discipleTables.currentHps[1])
        // 阵亡计数不虚增
        assertEquals(0, gd.annualDeceasedDisciples)
    }

    @Test
    fun `beast defense - bridge absent falls back to Kotlin and settles`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val service = buildExplorationService()
            setupBeastDefenseState()
            // AUTHORITATIVE + 桥未加载：路由器返回 null → Kotlin 妖兽防守臂完成生产
            assertBeastDefenseSettled(service)
        }
    }

    @Test
    fun `beast defense - flag OFF keeps Kotlin fallback arm`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val service = buildExplorationService()
            setupBeastDefenseState()
            // 灰度旗标 OFF：回退臂与生产前行为一致（不删臂契约）
            assertBeastDefenseSettled(service)
        }
    }

    // ── Part B：巡逻生产（PatrolBattleSystem 三段路由） ──────────────────

    private fun buildPatrolSystem(): Pair<PatrolBattleSystem, BattleSystem> {
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        val inventorySystem = mockSmart(InventorySystem::class.java)
        stubInventoryAddMaterial(inventorySystem)
        val buildingConfigService = mockSmart(BuildingConfigService::class.java)
        whenever(buildingConfigService.getSlotCountByDisplayName("巡视楼")).thenReturn(2)
        val battleSystem = mockSmart(BattleSystem::class.java)
        val system = PatrolBattleSystem(
            battleSystem, rngManager, inventorySystem,
            buildingConfigService, DiscipleDeathHandler()
        )
        return system to battleSystem
    }

    private fun patrolBeastLevel(id: String, name: String) = WorldLevel(
        id = id, type = LevelType.BEAST, beastName = name,
        beastType = 0, realm = 5, count = 1,
        beastMaxHp = 200, beastMaxMp = 100,
        beastPhysicalAttack = 40, beastMagicAttack = 40,
        beastPhysicalDefense = 25, beastMagicDefense = 25,
        beastSpeed = 12, realmLayer = 1,
        expiryYear = 99, expiryMonth = 12
    )

    /** 巡逻生产事务态：巡视楼 1 号塔驻弟子 1，未击败妖兽关卡（弹窗开启以断言奖励卡）。 */
    private fun buildPatrolState(
        aiDirectTargets: Map<String, List<String>> = emptyMap(),
        aiSectDisciples: Map<String, List<Disciple>> = emptyMap()
    ): MutableGameState {
        val tables = DiscipleTables()
        insertDisciple(tables, 1, realm = 5)
        return MutableGameState(
            gameData = GameData(
                gameYear = 5, gameMonth = 6,
                patrolConfigs = listOf(PatrolConfig(requireFullStatus = false)),
                patrolBattleResultPopup = true,
                patrolSlots = listOf(
                    PatrolSlot(index = 0, discipleId = "1", buildingInstanceId = "t1")
                ),
                placedBuildings = listOf(
                    GridBuildingData(buildingId = "patrol", displayName = "巡视楼", instanceId = "t1")
                ),
                worldLevels = listOf(patrolBeastLevel("b1", "妖兽乙")),
                aiSectBeastDirectTargets = aiDirectTargets,
                aiSectDisciples = aiSectDisciples,
                worldMapSects = listOf(WorldSect(id = "sect1", name = "青云宗"))
            ),
            discipleTables = tables,
            equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
            manualStacks = EntityStore(), manualInstances = EntityStore(),
            pills = EntityStore(), materials = EntityStore(),
            herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
    }

    private fun stubPatrolVictoryBattles(battleSystem: BattleSystem) {
        whenever(
            battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        ).thenReturn(
            Battle(
                team = listOf(teamCombatant("1", hp = 50)),
                beasts = listOf(beastCombatant("beast_0", hp = 0))
            )
        )
        whenever(battleSystem.executeBattle(any(), any())).thenReturn(
            BattleSystemResult(
                battle = Battle(
                    team = listOf(teamCombatant("1", hp = 50)),
                    beasts = listOf(beastCombatant("beast_0", hp = 0))
                ),
                victory = true, rewards = mapOf("spiritStones" to 100), turnCount = 3
            )
        )
    }

    /** 巡逻胜利回退臂结算面断言：击败/引导计数/战报/奖励卡/HP 写回/灵石入账。 */
    private fun assertPatrolVictorySettled(system: PatrolBattleSystem, state: MutableGameState) {
        val gd = state.gameData
        assertTrue("妖兽必须标记击败", gd.worldLevels.single().defeated)
        assertEquals(
            "引导计数必须累加", 1L,
            gd.guideCounters[GuideCounterKeys.PATROL_BEAST_DEFEATED]
        )
        // b05 意图断言（根治恢复）：灵石奖励必须真实入账（基线 1000 + 奖励 100），
        // 不得被 applyResults 终局的旧快照链覆盖——修复前恒 1000（弹窗显示与实际不符）
        assertEquals("灵石奖励必须入账（b05）", 1100L, gd.spiritStones)
        val log = state.battleLogs.single()
        assertEquals(BattleType.PVE, log.type)
        assertEquals("巡视队伍", log.attackerName)
        assertEquals(BattleResult.WIN, log.result)
        // 战斗结果弹窗（奖励卡）必须入队且携带奖励段
        val popup = system.consumePendingPatrolResults().single()
        assertTrue(popup.victory)
        assertTrue("奖励卡必须非空", popup.rewards.isNotEmpty())
        assertEquals(50, state.discipleTables.currentHps[1])
    }

    @Test
    fun `patrol pve - bridge absent falls back to Kotlin and settles`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val (system, battleSystem) = buildPatrolSystem()
            stubPatrolVictoryBattles(battleSystem)
            val state = buildPatrolState()
            // AUTHORITATIVE + 桥未加载：普通 PvE 段走 Kotlin 臂完成生产
            system.executePatrolRound(state)
            assertPatrolVictorySettled(system, state)
        }
    }

    @Test
    fun `patrol pve - flag OFF keeps Kotlin fallback arm`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val (system, battleSystem) = buildPatrolSystem()
            stubPatrolVictoryBattles(battleSystem)
            val state = buildPatrolState()
            system.executePatrolRound(state)
            assertPatrolVictorySettled(system, state)
        }
    }

    @Test
    fun `patrol conflict - bridge absent falls back to Kotlin through both phases`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val (system, battleSystem) = buildPatrolSystem()
            val aiDisciple = Disciple(
                id = "ai1", name = "剑尘", realm = 5, realmLayer = 1,
                age = 30, lifespan = 90,
                skills = SkillStats(comprehension = 100),
                combat = CombatAttributes(currentHp = -1)
            ).copy(isAlive = true)
            val state = buildPatrolState(
                aiDirectTargets = mapOf("b1" to listOf("sect1")),
                aiSectDisciples = mapOf("sect1" to listOf(aiDisciple))
            )
            // Phase 1 PvP：巡逻队胜（AI 阵亡）→ Phase 2 PvE：胜者再胜妖兽
            whenever(battleSystem.executeBattle(any(), any())).thenReturn(
                BattleSystemResult(
                    battle = Battle(
                        team = listOf(teamCombatant("1", hp = 90)),
                        beasts = listOf(beastCombatant("ai1", hp = 0))
                    ),
                    victory = true, rewards = emptyMap(), turnCount = 2
                ),
                BattleSystemResult(
                    battle = Battle(
                        team = listOf(teamCombatant("1", hp = 50)),
                        beasts = listOf(beastCombatant("beast_0", hp = 0))
                    ),
                    victory = true, rewards = mapOf("spiritStones" to 100), turnCount = 3
                )
            )
            // 冲突战参战单位组装：按 side 分流巡逻队/AI 参战单位
            val patrolCombatant = teamCombatant("1", hp = 90)
            val aiCombatant = beastCombatant("ai1", hp = 0)
            whenever(
                battleSystem.convertDiscipleToCombatant(any(), any(), any(), any(), any(), any(), any())
            ).thenAnswer { inv ->
                if (inv.getArgument<CombatantSide>(4) == CombatantSide.ATTACKER) aiCombatant
                else patrolCombatant
            }
            // Phase 2 PvE 战斗构建（巡逻队胜 → 真实 createBattle 组装）
            whenever(
                battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            ).thenReturn(
                Battle(
                    team = listOf(teamCombatant("1", hp = 90)),
                    beasts = listOf(beastCombatant("beast_0", hp = 0))
                )
            )

            system.executePatrolRound(state)

            // 两阶段均经回退臂完成：Phase 2 终态（hp=50/灵石奖励卡/击败标记）落地面
            assertPatrolVictorySettled(system, state)

            // b05 意图断言（根治恢复）：冲突段两笔直写必须保留——
            // ① 已处理的冲突妖兽从 AI 直攻目标表移除（修复前残留 b1 键）
            assertTrue(
                "冲突目标必须从 AI 直攻表移除（b05）",
                state.gameData.aiSectBeastDirectTargets.isEmpty()
            )
            // ② Phase 1 战死的 AI 弟子阵亡标记不被终局覆盖复活（修复前 isAlive 回 true）
            assertFalse(
                "AI 阵亡标记必须保留（b05）",
                state.gameData.aiSectDisciples.getValue("sect1").single().isAlive
            )
        }
    }
}
