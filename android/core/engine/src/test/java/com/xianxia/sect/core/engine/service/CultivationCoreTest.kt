package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * CultivationCore 直接单元测试。
 *
 * 覆盖范围：
 * - [CultivationCore.getLifespanGainForRealm]：不同境界寿命增益
 * - [CultivationCore.isDiscipleFullHpMp]：满 HP/MP 判定（Disciple 与 Tables 两个重载）
 * - [CultivationCore.recoverHpMpForAllDisciples]：HP/MP 恢复逻辑
 * - [CultivationCore.calculateDiscipleCultivationPerPhase]：修炼计算（含建筑加成间接验证）
 * - 突破条件：cultivation >= maxCultivation && full health/mana
 *
 * 测试框架：JUnit 4 + Mockito（与项目现有测试一致）。
 *
 * 注意：calculateBuildingCultivationBonus 为 private 方法，通过 calculateDiscipleCultivationPerPhase
 * 间接验证不同建筑 displayName 对应的加成系数（1.40/1.20/1.10/1.0）。
 *
 * 使用 Robolectric 以获得真实的 SparseArray/SparseIntArray 实现
 * （DiscipleTables 的底层存储依赖 Android 的 SparseArray）。
 */
@RunWith(RobolectricTestRunner::class)
class CultivationCoreTest {

    private lateinit var core: CultivationCore
    private lateinit var mockStateStore: GameStateStore

    @Before
    fun setUp() {
        // 注入 DiscipleStatCalculator 实现到 domain 模块（与 XianxiaApplication 一致），
        // 使 disciple.maxHp / disciple.maxMp 等计算属性在测试中可用。
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
                griefCultivationSpeedPenalty: Double
            ) = DiscipleStatCalculator.calculateCultivationSpeed(
                disciple, manuals, manualProficiencies, buildingBonus, additionalBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double
            ) = DiscipleStatCalculator.calculateCultivationSpeed(
                aggregate, manuals, manualProficiencies, buildingBonus, additionalBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun getBreakthroughChance(
                disciple: Disciple, innerElderComprehension: Int,
                outerElderComprehensionBonus: Double, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehensionBonus,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate, innerElderComprehension: Int,
                outerElderComprehensionBonus: Double, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehensionBonus,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
        }

        mockStateStore = Mockito.mock(GameStateStore::class.java)
        // calculateDiscipleCultivationPerPhase 访问 stateStore.manualInstances.value，
        // 需 stub 为空 StateFlow 避免空指针。
        Mockito.`when`(mockStateStore.manualInstances)
            .thenReturn(MutableStateFlow(emptyList()))

        core = CultivationCore(
            stateStore = mockStateStore,
            inventoryConfig = Mockito.mock(InventoryConfig::class.java),
            thermalMonitor = Mockito.mock(ThermalMonitor::class.java),
            gameClock = Mockito.mock(GameTimeClock::class.java),
            scopeProvider = Mockito.mock(CoroutineScopeProvider::class.java),
            pillManager = Mockito.mock(DisciplePillManager::class.java),
            equipmentManager = Mockito.mock(DiscipleEquipmentManager::class.java),
            manualManager = Mockito.mock(DiscipleManualManager::class.java)
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

    // ==================== getLifespanGainForRealm ====================

    @Test
    fun `getLifespanGainForRealm - 仙人 realm0 寿命增益10000`() {
        assertEquals(10000, core.getLifespanGainForRealm(0))
    }

    @Test
    fun `getLifespanGainForRealm - 渡劫 realm1 寿命增益5000`() {
        assertEquals(5000, core.getLifespanGainForRealm(1))
    }

    @Test
    fun `getLifespanGainForRealm - 大乘 realm2 寿命增益3000`() {
        assertEquals(3000, core.getLifespanGainForRealm(2))
    }

    @Test
    fun `getLifespanGainForRealm - 合体 realm3 寿命增益1500`() {
        assertEquals(1500, core.getLifespanGainForRealm(3))
    }

    @Test
    fun `getLifespanGainForRealm - 炼虚 realm4 寿命增益800`() {
        assertEquals(800, core.getLifespanGainForRealm(4))
    }

    @Test
    fun `getLifespanGainForRealm - 化神 realm5 寿命增益400`() {
        assertEquals(400, core.getLifespanGainForRealm(5))
    }

    @Test
    fun `getLifespanGainForRealm - 元婴 realm6 寿命增益200`() {
        assertEquals(200, core.getLifespanGainForRealm(6))
    }

    @Test
    fun `getLifespanGainForRealm - 金丹 realm7 寿命增益100`() {
        assertEquals(100, core.getLifespanGainForRealm(7))
    }

    @Test
    fun `getLifespanGainForRealm - 筑基 realm8 寿命增益50`() {
        assertEquals(50, core.getLifespanGainForRealm(8))
    }

    @Test
    fun `getLifespanGainForRealm - 炼气 realm9 未知境界返回0`() {
        assertEquals(0, core.getLifespanGainForRealm(9))
    }

    @Test
    fun `getLifespanGainForRealm - 负数境界返回0`() {
        assertEquals(0, core.getLifespanGainForRealm(-1))
    }

    @Test
    fun `getLifespanGainForRealm - 境界越低寿命增益越大`() {
        val gain0 = core.getLifespanGainForRealm(0)
        val gain4 = core.getLifespanGainForRealm(4)
        val gain8 = core.getLifespanGainForRealm(8)
        assertTrue("仙人增益应大于炼虚", gain0 > gain4)
        assertTrue("炼虚增益应大于筑基", gain4 > gain8)
    }

    // ==================== isDiscipleFullHpMp(disciple) ====================

    @Test
    fun `isDiscipleFullHpMp - 满HP满MP返回true`() {
        val disciple = createDisciple(currentHp = -1, currentMp = -1)
        // currentHp/currentMp 为 -1（负数）时视为满值
        assertTrue(core.isDiscipleFullHpMp(disciple))
    }

    @Test
    fun `isDiscipleFullHpMp - HP和MP均等于maxHp返回true`() {
        val disciple = createDisciple(currentHp = 9999, currentMp = 9999)
        // currentHp/currentMp 均大于等于 maxHp/maxMp
        assertTrue(core.isDiscipleFullHpMp(disciple))
    }

    @Test
    fun `isDiscipleFullHpMp - HP未满返回false`() {
        val maxHp = DiscipleStatCalculator.getBaseStats(createDisciple()).maxHp
        val disciple = createDisciple(currentHp = maxHp / 2, currentMp = -1)
        assertFalse(core.isDiscipleFullHpMp(disciple))
    }

    @Test
    fun `isDiscipleFullHpMp - MP未满返回false`() {
        val maxMp = DiscipleStatCalculator.getBaseStats(createDisciple()).maxMp
        val disciple = createDisciple(currentHp = -1, currentMp = maxMp / 2)
        assertFalse(core.isDiscipleFullHpMp(disciple))
    }

    @Test
    fun `isDiscipleFullHpMp - HP和MP均未满返回false`() {
        val stats = DiscipleStatCalculator.getBaseStats(createDisciple())
        val disciple = createDisciple(currentHp = stats.maxHp / 2, currentMp = stats.maxMp / 2)
        assertFalse(core.isDiscipleFullHpMp(disciple))
    }

    @Test
    fun `isDiscipleFullHpMp - currentHp为负数视为满值`() {
        // currentHp < 0 → hp = maxHp；currentMp 也满 → true
        val disciple = createDisciple(currentHp = -5, currentMp = -1)
        assertTrue(core.isDiscipleFullHpMp(disciple))
    }

    @Test
    fun `isDiscipleFullHpMp - currentMp为负数视为满值`() {
        val maxHp = DiscipleStatCalculator.getBaseStats(createDisciple()).maxHp
        val disciple = createDisciple(currentHp = maxHp, currentMp = -5)
        assertTrue(core.isDiscipleFullHpMp(disciple))
    }

    // ==================== isDiscipleFullHpMp(id, tables) ====================

    @Test
    fun `isDiscipleFullHpMp tables - 满HP满MP返回true`() {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = "1", currentHp = -1, currentMp = -1))
        assertTrue(core.isDiscipleFullHpMp(1, tables))
    }

    @Test
    fun `isDiscipleFullHpMp tables - HP等于baseHp返回true`() {
        val tables = DiscipleTables()
        val disciple = createDisciple(id = "1", currentHp = 120, currentMp = 60)
        tables.insert(disciple)
        // currentHp >= baseHp(120) && currentMp >= baseMp(60)
        assertTrue(core.isDiscipleFullHpMp(1, tables))
    }

    @Test
    fun `isDiscipleFullHpMp tables - HP未满返回false`() {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = "1", currentHp = 50, currentMp = -1))
        assertFalse(core.isDiscipleFullHpMp(1, tables))
    }

    @Test
    fun `isDiscipleFullHpMp tables - MP未满返回false`() {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = "1", currentHp = -1, currentMp = 10))
        assertFalse(core.isDiscipleFullHpMp(1, tables))
    }

    @Test
    fun `isDiscipleFullHpMp tables - currentHp为负数视为满值`() {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = "1", currentHp = -5, currentMp = -1))
        assertTrue(core.isDiscipleFullHpMp(1, tables))
    }

    @Test
    fun `isDiscipleFullHpMp tables - currentHp和currentMp均为负数跳过视为满值`() {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = "1", currentHp = -1, currentMp = -1))
        assertTrue(core.isDiscipleFullHpMp(1, tables))
    }

    // ==================== recoverHpMpForAllDisciples ====================

    @Test
    fun `recoverHpMpForAllDisciples - 恢复HP且不超过上限`() {
        val disciple = createDisciple(id = "1", currentHp = 10, currentMp = -1)
        val state = createMutableGameState(listOf(disciple))

        val maxHp = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap()).maxHp
        core.recoverHpMpForAllDisciples(state)

        val recoveredHp = state.discipleTables.currentHps[1]
        assertTrue("恢复后HP应大于初始值10", recoveredHp > 10)
        assertTrue("恢复后HP不应超过上限 $maxHp", recoveredHp <= maxHp)
    }

    @Test
    fun `recoverHpMpForAllDisciples - 恢复MP且不超过上限`() {
        val disciple = createDisciple(id = "1", currentHp = -1, currentMp = 5)
        val state = createMutableGameState(listOf(disciple))

        val maxMp = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap()).maxMp
        core.recoverHpMpForAllDisciples(state)

        val recoveredMp = state.discipleTables.currentMps[1]
        assertTrue("恢复后MP应大于初始值5", recoveredMp > 5)
        assertTrue("恢复后MP不应超过上限 $maxMp", recoveredMp <= maxMp)
    }

    @Test
    fun `recoverHpMpForAllDisciples - 已满HP和MP不恢复`() {
        val disciple = createDisciple(id = "1", currentHp = -1, currentMp = -1)
        val state = createMutableGameState(listOf(disciple))

        core.recoverHpMpForAllDisciples(state)

        // currentHp/currentMp 均为负数 → 特殊状态跳过恢复
        assertEquals(-1, state.discipleTables.currentHps[1])
        assertEquals(-1, state.discipleTables.currentMps[1])
    }

    @Test
    fun `recoverHpMpForAllDisciples - 死亡弟子不恢复`() {
        val disciple = createDisciple(id = "1", currentHp = 10, currentMp = 10)
        disciple.isAlive = false
        val state = createMutableGameState(listOf(disciple))

        core.recoverHpMpForAllDisciples(state)

        // isAlive != 1 → 跳过
        assertEquals(10, state.discipleTables.currentHps[1])
        assertEquals(10, state.discipleTables.currentMps[1])
    }

    @Test
    fun `recoverHpMpForAllDisciples - HP和MP均为负数跳过恢复`() {
        val disciple = createDisciple(id = "1", currentHp = -1, currentMp = -1)
        val state = createMutableGameState(listOf(disciple))

        core.recoverHpMpForAllDisciples(state)

        assertEquals(-1, state.discipleTables.currentHps[1])
        assertEquals(-1, state.discipleTables.currentMps[1])
    }

    @Test
    fun `recoverHpMpForAllDisciples - 恢复量至少为1`() {
        // 使用极低 maxHp 的弟子验证恢复量至少为 1
        val disciple = createDisciple(id = "1", currentHp = 0, currentMp = 0)
        val state = createMutableGameState(listOf(disciple))

        core.recoverHpMpForAllDisciples(state)

        val recoveredHp = state.discipleTables.currentHps[1]
        val recoveredMp = state.discipleTables.currentMps[1]
        assertTrue("HP恢复量应至少为1", recoveredHp >= 1)
        assertTrue("MP恢复量应至少为1", recoveredMp >= 1)
    }

    @Test
    fun `recoverHpMpForAllDisciples - 多弟子同时恢复`() {
        val d1 = createDisciple(id = "1", currentHp = 10, currentMp = 10)
        val d2 = createDisciple(id = "2", currentHp = 20, currentMp = 20)
        val state = createMutableGameState(listOf(d1, d2))

        core.recoverHpMpForAllDisciples(state)

        assertTrue("弟子1 HP应恢复", state.discipleTables.currentHps[1] > 10)
        assertTrue("弟子2 HP应恢复", state.discipleTables.currentHps[2] > 20)
    }

    @Test
    fun `recoverHpMpForAllDisciples - 恢复量等于maxHp乘以0点05乘以10`() {
        val disciple = createDisciple(id = "1", currentHp = 0, currentMp = 0)
        val state = createMutableGameState(listOf(disciple))

        val maxHp = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap()).maxHp
        val maxMp = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap()).maxMp
        val expectedHpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * 10)
            .toInt().coerceAtLeast(1)
        val expectedMpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * 10)
            .toInt().coerceAtLeast(1)

        core.recoverHpMpForAllDisciples(state)

        assertEquals(expectedHpRecovery, state.discipleTables.currentHps[1])
        assertEquals(expectedMpRecovery, state.discipleTables.currentMps[1])
    }

    // ==================== calculateBuildingCultivationBonus（间接验证） ====================
    // calculateBuildingCultivationBonus 为 private，通过 calculateDiscipleCultivationPerPhase
    // 间接验证不同建筑 displayName 的加成系数。
    // 建筑加成是 calculateCultivationSpeed 的乘数，因此不同建筑下修炼速度比值
    // 应等于加成系数比值（1.40/1.20/1.10/1.0）。

    private fun gameDataWithBuilding(discipleId: String, displayName: String): GameData {
        val buildingInstanceId = "building-1"
        return GameData(
            residenceSlots = listOf(
                ResidenceSlot(buildingInstanceId = buildingInstanceId, discipleId = discipleId)
            ),
            placedBuildings = listOf(
                GridBuildingData(instanceId = buildingInstanceId, displayName = displayName)
            )
        )
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 中级单人住所加成1点40`() {
        val disciple = createDisciple(id = "1", spiritRootType = "metal")
        val tables = DiscipleTables()

        val noBuildingSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, GameData(), tables
        )
        val midResidenceSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "中级单人住所"), tables
        )

        assertTrue("中级单人住所修炼速度应高于无建筑", midResidenceSpeed > noBuildingSpeed)
        assertEquals("加成系数应为1.40", 1.40, midResidenceSpeed / noBuildingSpeed, 0.01)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 单人住所加成1点20`() {
        val disciple = createDisciple(id = "1", spiritRootType = "metal")
        val tables = DiscipleTables()

        val noBuildingSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, GameData(), tables
        )
        val singleResidenceSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "单人住所"), tables
        )

        assertTrue("单人住所修炼速度应高于无建筑", singleResidenceSpeed > noBuildingSpeed)
        assertEquals("加成系数应为1.20", 1.20, singleResidenceSpeed / noBuildingSpeed, 0.01)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 多人住所加成1点10`() {
        val disciple = createDisciple(id = "1", spiritRootType = "metal")
        val tables = DiscipleTables()

        val noBuildingSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, GameData(), tables
        )
        val multiResidenceSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "多人住所"), tables
        )

        assertTrue("多人住所修炼速度应高于无建筑", multiResidenceSpeed > noBuildingSpeed)
        assertEquals("加成系数应为1.10", 1.10, multiResidenceSpeed / noBuildingSpeed, 0.01)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 无建筑加成1点0`() {
        val disciple = createDisciple(id = "1", spiritRootType = "metal")
        val tables = DiscipleTables()

        val noBuildingSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, GameData(), tables
        )
        // 未识别建筑名 → 加成 1.0
        val unknownBuildingSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "未知建筑"), tables
        )

        assertEquals("未识别建筑加成应为1.0", noBuildingSpeed, unknownBuildingSpeed, 0.001)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 无住所槽位加成1点0`() {
        val disciple = createDisciple(id = "1", spiritRootType = "metal")
        val tables = DiscipleTables()

        val speed = core.calculateDiscipleCultivationPerPhase(
            disciple, GameData(), tables
        )

        // 无 residenceSlots → 加成 1.0，速度应 > 0
        assertTrue("无建筑时修炼速度应为正", speed > 0)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 建筑加成排序 中级大于单人大于多人`() {
        val disciple = createDisciple(id = "1", spiritRootType = "metal")
        val tables = DiscipleTables()

        val midSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "中级单人住所"), tables
        )
        val singleSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "单人住所"), tables
        )
        val multiSpeed = core.calculateDiscipleCultivationPerPhase(
            disciple, gameDataWithBuilding("1", "多人住所"), tables
        )

        assertTrue("中级单人住所应快于单人住所", midSpeed > singleSpeed)
        assertTrue("单人住所应快于多人住所", singleSpeed > multiSpeed)
    }

    // ==================== calculateDiscipleCultivationPerPhase 基础验证 ====================

    @Test
    fun `calculateDiscipleCultivationPerPhase - 境界越高修炼越快`() {
        val tables = DiscipleTables()
        val lianqi = createDisciple(id = "1", realm = 9, spiritRootType = "metal")
        val zhuji = createDisciple(id = "2", realm = 8, spiritRootType = "metal")

        val sL = core.calculateDiscipleCultivationPerPhase(lianqi, GameData(), tables)
        val sZ = core.calculateDiscipleCultivationPerPhase(zhuji, GameData(), tables)

        assertTrue("筑基应快于炼气", sZ > sL)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 灵根越少修炼越快`() {
        val tables = DiscipleTables()
        val single = createDisciple(id = "1", spiritRootType = "metal")
        val triple = createDisciple(id = "2", spiritRootType = "metal,wood,water")

        val s1 = core.calculateDiscipleCultivationPerPhase(single, GameData(), tables)
        val s3 = core.calculateDiscipleCultivationPerPhase(triple, GameData(), tables)

        assertTrue("单灵根应快于三灵根", s1 > s3)
    }

    @Test
    fun `calculateDiscipleCultivationPerPhase - 修炼速度最低为1`() {
        val tables = DiscipleTables()
        val disciple = createDisciple(spiritRootType = "metal")
        val speed = core.calculateDiscipleCultivationPerPhase(disciple, GameData(), tables)
        assertTrue("修炼速度最低为1", speed >= 1.0)
    }

    // ==================== 突破条件 ====================
    // 突破条件：cultivation >= maxCultivation && isDiscipleFullHpMp(disciple)
    // 不依赖 BREAKTHROUGH flag，仅由修炼值满 + 满血满蓝决定。

    @Test
    fun `突破条件 - 修炼满且满血满蓝可突破`() {
        val disciple = createDisciple(currentHp = -1, currentMp = -1)
        // 修炼值设为 maxCultivation
        val fullCultivationDisciple = disciple.copy(cultivation = disciple.maxCultivation)

        val canBreakthrough = fullCultivationDisciple.cultivation >= fullCultivationDisciple.maxCultivation
            && core.isDiscipleFullHpMp(fullCultivationDisciple)

        assertTrue("修炼满且满血满蓝应可突破", canBreakthrough)
    }

    @Test
    fun `突破条件 - 修炼未满不可突破`() {
        val disciple = createDisciple(currentHp = -1, currentMp = -1, cultivation = 0.0)

        val canBreakthrough = disciple.cultivation >= disciple.maxCultivation
            && core.isDiscipleFullHpMp(disciple)

        assertFalse("修炼未满不可突破", canBreakthrough)
    }

    @Test
    fun `突破条件 - 修炼满但HP未满不可突破`() {
        val disciple = createDisciple(currentHp = 1, currentMp = -1)
        val fullCultivationDisciple = disciple.copy(cultivation = disciple.maxCultivation)
        val maxHp = DiscipleStatCalculator.getBaseStats(fullCultivationDisciple).maxHp

        // currentHp=1 < maxHp → HP 未满
        val canBreakthrough = fullCultivationDisciple.cultivation >= fullCultivationDisciple.maxCultivation
            && core.isDiscipleFullHpMp(fullCultivationDisciple)

        assertFalse("修炼满但HP未满不可突破 (currentHp=1, maxHp=$maxHp)", canBreakthrough)
    }

    @Test
    fun `突破条件 - 修炼满但MP未满不可突破`() {
        val disciple = createDisciple(currentHp = -1, currentMp = 1)
        val fullCultivationDisciple = disciple.copy(cultivation = disciple.maxCultivation)

        val canBreakthrough = fullCultivationDisciple.cultivation >= fullCultivationDisciple.maxCultivation
            && core.isDiscipleFullHpMp(fullCultivationDisciple)

        assertFalse("修炼满但MP未满不可突破", canBreakthrough)
    }

    @Test
    fun `突破条件 - 修炼满且HP和MP恰好等于上限可突破`() {
        val disciple = createDisciple()
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val fullDisciple = disciple.copy(
            cultivation = disciple.maxCultivation,
            combat = disciple.combat.copy(
                currentHp = stats.maxHp,
                currentMp = stats.maxMp
            )
        )

        val canBreakthrough = fullDisciple.cultivation >= fullDisciple.maxCultivation
            && core.isDiscipleFullHpMp(fullDisciple)

        assertTrue("修炼满且HP/MP恰好等于上限应可突破", canBreakthrough)
    }

    @Test
    fun `突破条件 - 仙人境界maxCultivation等于当前cultivation`() {
        // realm=0 (仙人) 时 maxCultivation 直接返回 cultivation
        val immortal = createDisciple(realm = 0, cultivation = 100.0, currentHp = -1, currentMp = -1)

        assertEquals(100.0, immortal.maxCultivation, 0.001)

        val canBreakthrough = immortal.cultivation >= immortal.maxCultivation
            && core.isDiscipleFullHpMp(immortal)

        assertTrue("仙人境界修炼值恒满，满血满蓝即可突破", canBreakthrough)
    }
}
