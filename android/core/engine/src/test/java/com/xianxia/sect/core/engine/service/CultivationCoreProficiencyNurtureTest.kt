package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * 功法熟练度 + 装备孕养每旬增长测试。
 *
 * 覆盖范围：
 * - [CultivationCore.processManualProficiencyPerPhase]：每旬功法熟练度增长
 * - [CultivationCore.processEquipmentNurturePerPhase]：每旬装备孕养经验增长
 * - 边界条件：无功法/无装备弟子、死亡弟子、满熟练度
 */
@RunWith(RobolectricTestRunner::class)
class CultivationCoreProficiencyNurtureTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var core: CultivationCore
    private lateinit var mockStateStore: GameStateStore

    @Before
    fun setUp() {
        // 注入 DiscipleStatCalculator 实现（与 XianxiaApplication 一致）
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(disciple, equipments, manuals, manualProficiencies)
            override fun getFinalStats(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(aggregate, equipments, manuals, manualProficiencies)
            override fun calculateCultivationSpeed(
                disciple: Disciple, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun getBreakthroughChance(
                disciple: Disciple, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
        }

        mockStateStore = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(mockStateStore.manualInstances)
            .thenReturn(MutableStateFlow(emptyList()))

        val mockPillManager = Mockito.mock(DisciplePillManager::class.java)
        val realHpMpRecoveryService = HpMpRecoveryService()

        core = CultivationCore(
            hpMpRecoveryService = realHpMpRecoveryService,
            autoPillService = AutoPillService(mockPillManager, Mockito.mock()),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(mockStateStore),
            battleSettlementService = BattleSettlementService(realHpMpRecoveryService)
        )
    }

    // ==================== 辅助构造函数 ====================

    private fun createDisciple(
        id: String = "1",
        realm: Int = 9,
        realmLayer: Int = 1,
        currentHp: Int = -1,
        currentMp: Int = -1,
        cultivation: Double = 0.0,
        spiritRootType: String = "metal",
        discipleType: String = "outer",
        comprehension: Int = 50
    ): Disciple {
        return Disciple(
            id = id,
            realm = realm,
            realmLayer = realmLayer,
            cultivation = cultivation,
            spiritRootType = spiritRootType,
            discipleType = discipleType,
            combat = CombatAttributes(currentHp = currentHp, currentMp = currentMp),
            skills = SkillStats(comprehension = comprehension)
        )
    }

    private fun createMutableGameState(
        disciples: List<Disciple> = emptyList(),
        gameData: GameData = GameData()
    ): MutableGameState {
        val tables = DiscipleTables()
        disciples.forEach { tables.insert(it) }
        return MutableGameState(
            gameData = gameData,
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
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    // ==================== 功法熟练度每旬增长 ====================

    @Test
    fun `processManualProficiencyPerPhase - 有功法弟子熟练度增长`() {
        val disciple = createDisciple(id = "1", comprehension = 100)
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        // 给弟子一本功法
        state.discipleTables.manualIds[1] = listOf("manual_1")
        val manual = ManualInstance(
            id = "manual_1", name = "测试功法", rarity = 1
        )
        state.manualInstances = EntityStore(listOf(manual))

        core.processManualProficiencyPerPhase(state)

        val proficiencies = checkNotNull(state.gameData.manualProficiencies["1"]) {
            "应创建熟练度条目"
        }
        assertTrue("熟练度应大于0", proficiencies.isNotEmpty())
        assertTrue("熟练度值应大于0", proficiencies[0].proficiency > 0.0)
        // 悟性=100 时，comprehensionBonus = 1.0 + (100-70)*0.1 = 4.0
        // 每旬增长 = 6.0 * 4.0 * 1.0 * 2000/1000 = 48.0
        assertEquals("每旬增长应等于48.0", 48.0, proficiencies[0].proficiency, 0.01)
    }

    @Test
    fun `processManualProficiencyPerPhase - 无功法弟子不创建条目`() {
        val disciple = createDisciple(id = "1")
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )

        core.processManualProficiencyPerPhase(state)

        assertTrue("无功法弟子不应有熟练度条目",
            state.gameData.manualProficiencies.isEmpty())
    }

    @Test
    fun `processManualProficiencyPerPhase - 死亡弟子跳过`() {
        val disciple = createDisciple(id = "1", comprehension = 100)
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        state.discipleTables.manualIds[1] = listOf("manual_1")
        state.discipleTables.isAlive[1] = 0  // 标记死亡
        val manual = ManualInstance(
            id = "manual_1", name = "测试功法", rarity = 1
        )
        state.manualInstances = EntityStore(listOf(manual))

        core.processManualProficiencyPerPhase(state)

        assertTrue("死亡弟子不应有熟练度增长",
            state.gameData.manualProficiencies.isEmpty())
    }

    @Test
    fun `processManualProficiencyPerPhase - 已满熟练度不超上限`() {
        val disciple = createDisciple(id = "1", comprehension = 100)
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        state.discipleTables.manualIds[1] = listOf("manual_1")
        val manual = ManualInstance(
            id = "manual_1", name = "测试功法", rarity = 1
        )
        state.manualInstances = EntityStore(listOf(manual))

        // 预填满熟练度
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        val existingProfs = mapOf(
            "1" to listOf(
                ManualProficiencyData(
                    manualId = "manual_1", manualName = "测试功法",
                    proficiency = maxProf.toDouble(), maxProficiency = maxProf,
                    level = 1, masteryLevel = 3  // 圆满
                )
            )
        )
        state.gameData = state.gameData.copy(
            manualProficiencies = existingProfs
        )

        core.processManualProficiencyPerPhase(state)

        val proficiencies = checkNotNull(state.gameData.manualProficiencies["1"]) {
            "熟练度应为非空"
        }
        assertEquals("熟练度不应超过上限",
            maxProf.toDouble(), proficiencies[0].proficiency, 0.01)
    }

    @Test
    fun `processManualProficiencyPerPhase - 多个弟子各自增长`() {
        val d1 = createDisciple(id = "1", comprehension = 100)
        val d2 = createDisciple(id = "2", comprehension = 70)
        val state = createMutableGameState(
            listOf(d1, d2),
            gameData = GameData()
        )
        state.discipleTables.manualIds[1] = listOf("manual_1")
        state.discipleTables.manualIds[2] = listOf("manual_2")
        val man1 = ManualInstance(id = "manual_1", name = "功法A", rarity = 1)
        val man2 = ManualInstance(id = "manual_2", name = "功法B", rarity = 1)
        state.manualInstances = EntityStore(listOf(man1, man2))

        core.processManualProficiencyPerPhase(state)

        val p1 = checkNotNull(state.gameData.manualProficiencies["1"]) {
            "弟子1应有熟练度"
        }
        val p2 = checkNotNull(state.gameData.manualProficiencies["2"]) {
            "弟子2应有熟练度"
        }
        assertTrue("弟子1熟练度应大于弟子2（悟性更高）",
            p1[0].proficiency > p2[0].proficiency)
    }

    // ==================== 装备孕养每旬增长 ====================

    @Test
    fun `processEquipmentNurturePerPhase - 有装备弟子孕养经验增长`() {
        val disciple = createDisciple(id = "1")
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        // 给弟子装备武器
        val weapon = EquipmentInstance(
            id = "eq_1", name = "测试剑", rarity = 1,
            nurtureProgress = 0.0, nurtureLevel = 0
        )
        state.equipmentInstances = EntityStore(listOf(weapon))
        state.discipleTables.weaponIds[1] = "eq_1"

        core.processEquipmentNurturePerPhase(state)

        val updated = checkNotNull(state.equipmentInstances.find { it.id == "eq_1" }) {
            "装备应存在"
        }
        assertTrue("孕养经验应大于0", updated.nurtureProgress > 0.0)
        // NURTURE_GAIN_PER_PHASE = 5.0 * 2000 / 1000 = 10.0
        assertEquals("孕养经验应等于10.0", 10.0, updated.nurtureProgress, 0.01)
    }

    @Test
    fun `processEquipmentNurturePerPhase - 无装备弟子不影响`() {
        val disciple = createDisciple(id = "1")
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )

        core.processEquipmentNurturePerPhase(state)

        assertTrue("无装备时不应有更新",
            state.equipmentInstances.isEmpty())
    }

    @Test
    fun `processEquipmentNurturePerPhase - 死亡弟子跳过`() {
        val disciple = createDisciple(id = "1")
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        val weapon = EquipmentInstance(
            id = "eq_1", name = "测试剑", rarity = 1
        )
        state.equipmentInstances = EntityStore(listOf(weapon))
        state.discipleTables.weaponIds[1] = "eq_1"
        state.discipleTables.isAlive[1] = 0  // 标记死亡

        core.processEquipmentNurturePerPhase(state)

        val updated = checkNotNull(state.equipmentInstances.find { it.id == "eq_1" }) {
            "装备应存在"
        }
        assertEquals("死亡弟子装备不应有孕养增长",
            0.0, updated.nurtureProgress, 0.01)
    }

    @Test
    fun `processEquipmentNurturePerPhase - 满级装备不增长`() {
        val disciple = createDisciple(id = "1")
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        // 凡品(稀有度1)最高5级，设为满级
        val weapon = EquipmentInstance(
            id = "eq_1", name = "测试剑", rarity = 1,
            nurtureLevel = 5, nurtureProgress = 0.0
        )
        state.equipmentInstances = EntityStore(listOf(weapon))
        state.discipleTables.weaponIds[1] = "eq_1"

        core.processEquipmentNurturePerPhase(state)

        val updated = checkNotNull(state.equipmentInstances.find { it.id == "eq_1" }) {
            "装备应存在"
        }
        assertEquals("满级装备孕养经验不应增长",
            0.0, updated.nurtureProgress, 0.01)
        assertEquals("满级装备等级不应变化",
            5, updated.nurtureLevel)
    }

    @Test
    fun `processEquipmentNurturePerPhase - 多件装备各自增长`() {
        val disciple = createDisciple(id = "1")
        val state = createMutableGameState(
            listOf(disciple),
            gameData = GameData()
        )
        val weapon = EquipmentInstance(
            id = "eq_1", name = "测试剑", rarity = 1,
            nurtureProgress = 0.0, nurtureLevel = 0
        )
        val armor = EquipmentInstance(
            id = "eq_2", name = "测试甲", rarity = 1,
            nurtureProgress = 0.0, nurtureLevel = 0
        )
        state.equipmentInstances = EntityStore(listOf(weapon, armor))
        state.discipleTables.weaponIds[1] = "eq_1"
        state.discipleTables.armorIds[1] = "eq_2"

        core.processEquipmentNurturePerPhase(state)

        val updatedWeapon = checkNotNull(state.equipmentInstances.find { it.id == "eq_1" }) {
            "武器应增长"
        }
        val updatedArmor = checkNotNull(state.equipmentInstances.find { it.id == "eq_2" }) {
            "护甲应增长"
        }
        assertEquals("武器孕养增长应为10.0", 10.0, updatedWeapon.nurtureProgress, 0.01)
        assertEquals("护甲孕养增长应为10.0", 10.0, updatedArmor.nurtureProgress, 0.01)
    }

    // ==================== 综合验证 ====================

    @Test
    fun `processManualProficiencyPerPhase - 无功法弟子不影响其他弟子`() {
        val d1 = createDisciple(id = "1", comprehension = 100)
        val d2 = createDisciple(id = "2")
        val state = createMutableGameState(
            listOf(d1, d2),
            gameData = GameData()
        )
        state.discipleTables.manualIds[1] = listOf("manual_1")
        val manual = ManualInstance(id = "manual_1", name = "测试功法", rarity = 1)
        state.manualInstances = EntityStore(listOf(manual))

        core.processManualProficiencyPerPhase(state)

        assertTrue("弟子1应有熟练度",
            state.gameData.manualProficiencies.containsKey("1"))
        assertFalse("弟子2不应有熟练度条目",
            state.gameData.manualProficiencies.containsKey("2"))
    }
}
