package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HP/MP 列直读 vs 对象式等价性守卫（每旬热点路径）。
 *
 * 列版 [HpMpRecoveryService.recoverHpMpSingleColumn] 消除每旬热点路径的
 * 全量 assemble + getFinalStats（~90 列读取 + 10 个嵌套对象）。
 * 本测试守卫列版与对象版 [recoverHpMpSingle] 恢复后的 currentHps/currentMps
 * 完全相等（Int 精确相等）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class HpMpRecoveryEquivalenceTest {

    private lateinit var service: HpMpRecoveryService

    @Before
    fun setUp() {
        service = HpMpRecoveryService()
    }

    @Suppress("LongParameterList") // 等价性 fixture 参数聚合（15+ 参数，测试辅助）
    private fun buildState(
        realm: Int = 5, realmLayer: Int = 1,
        hpVariance: Int = 0, mpVariance: Int = 0,
        equipment: List<EquipmentInstance> = emptyList(),
        manuals: List<ManualInstance> = emptyList(),
        proficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
        pillDuration: Int = 0, pillHp: Int = 0, pillMp: Int = 0,
        curHp: Int = 500, curMp: Int = 500
    ): MutableGameState {
        val base = Disciple(
            id = "1", name = "测试弟子",
            realm = realm, realmLayer = realmLayer,
            pillEffects = PillEffects(
                pillHpBonus = pillHp, pillMpBonus = pillMp,
                pillEffectDuration = pillDuration
            )
        )
        val disciple = base.copy(
            combat = base.combat.copy(
                hpVariance = hpVariance, mpVariance = mpVariance,
                currentHp = curHp, currentMp = curMp
            ),
            equipment = base.equipment.copy(
                headId = equipment.getOrNull(0)?.id ?: "",
                bodyId = equipment.getOrNull(1)?.id ?: "",
                handsId = equipment.getOrNull(2)?.id ?: "",
                feetId = equipment.getOrNull(3)?.id ?: "",
                weaponId = equipment.getOrNull(4)?.id ?: "",
                legsId = equipment.getOrNull(5)?.id ?: ""
            ),
            manualIds = manuals.map { it.id }
        )
        val tables = DiscipleTables()
        tables.writeAllowed = true
        tables.insert(disciple)
        // 保持 writeAllowed=true：恢复函数会写 currentHps/currentMps（测试直调不走 stateStore.update）
        tables.changedIdTracker.consumeChangedIds()
        val gameData = GameData(
            manualProficiencies = proficiencies
        )
        return MutableGameState(
            gameData = gameData,
            discipleTables = tables,
            equipmentInstances = EntityStore(equipment),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(manuals),
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
    }

    /** 对同一状态分别跑对象版与列版，断言恢复后 currentHps/currentMps 完全相等 */
    @Suppress("LongParameterList") // 等价性 fixture 参数聚合（15+ 参数，测试辅助）
    private fun assertEquivalence(
        realm: Int = 5, realmLayer: Int = 1,
        hpVariance: Int = 0, mpVariance: Int = 0,
        equipment: List<EquipmentInstance> = emptyList(),
        manuals: List<ManualInstance> = emptyList(),
        proficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
        pillDuration: Int = 0, pillHp: Int = 0, pillMp: Int = 0,
        curHp: Int = 500, curMp: Int = 500,
        phasesToSettle: Int = 1
    ) {
        val stateA = buildState(realm, realmLayer, hpVariance, mpVariance,
            equipment, manuals, proficiencies, pillDuration, pillHp, pillMp, curHp, curMp)
        val stateB = buildState(realm, realmLayer, hpVariance, mpVariance,
            equipment, manuals, proficiencies, pillDuration, pillHp, pillMp, curHp, curMp)

        val eqMap = stateA.equipmentInstances.items.associateBy { it.id }
        val mMap = stateA.manualInstances.items.associateBy { it.id }

        service.recoverHpMpSingle(stateA, 1, phasesToSettle, equipmentMap = eqMap, manualMap = mMap)
        service.recoverHpMpSingleColumn(stateB, 1, phasesToSettle, equipmentMap = eqMap, manualMap = mMap)

        val msg = "realm=$realm/$realmLayer var=$hpVariance/$mpVariance " +
            "eq=${equipment.size} manual=${manuals.size} pill=$pillDuration phases=$phasesToSettle"
        assertEquals("HP 不一致: $msg", stateA.discipleTables.currentHps[1], stateB.discipleTables.currentHps[1])
        assertEquals("MP 不一致: $msg", stateA.discipleTables.currentMps[1], stateB.discipleTables.currentMps[1])
    }

    @Test
    fun `等价性 - 基础境界组合`() {
        for (realm in listOf(2, 5, 9)) {
            for (layer in listOf(1, 3, 9)) {
                assertEquivalence(realm = realm, realmLayer = layer, curHp = 100, curMp = 100)
            }
        }
    }

    @Test
    fun `等价性 - 方差组合`() {
        for (hpVar in listOf(-30, 0, 30)) {
            for (mpVar in listOf(-30, 0, 30)) {
                assertEquivalence(hpVariance = hpVar, mpVariance = mpVar, curHp = 200, curMp = 200)
            }
        }
    }

    @Test
    fun `等价性 - 满血状态列版不写入`() {
        // 先算一次 maxHp 作为满血基准（curHp 恰好等于 maxHp 的合法状态）
        val probe = buildState(realm = 5, curHp = 500, curMp = 500)
        val probeEq = probe.equipmentInstances.items.associateBy { it.id }
        val probeM = probe.manualInstances.items.associateBy { it.id }
        service.recoverHpMpSingleColumn(probe, 1, 10, equipmentMap = probeEq, manualMap = probeM)
        val maxHp = probe.discipleTables.currentHps[1]  // 恢复 10 旬到上限
        val maxMp = probe.discipleTables.currentMps[1]

        val state = buildState(realm = 5, curHp = maxHp, curMp = maxMp)
        val eqMap = state.equipmentInstances.items.associateBy { it.id }
        val mMap = state.manualInstances.items.associateBy { it.id }
        val wrote = service.recoverHpMpSingleColumn(state, 1, 1, equipmentMap = eqMap, manualMap = mMap)
        assertFalse("满血列版不应写入", wrote)
        assertEquals(maxHp, state.discipleTables.currentHps[1])
        assertEquals(maxMp, state.discipleTables.currentMps[1])
    }

    /** B3 实例轨夹具：六部位实例，HP 加成经副词条承载（EquipStatResolver 消费面；
     *  词条族无 MP 维度——MP 恢复仅由功法/丹药驱动，本夹具以 HP 单维覆盖装备分支） */
    private fun eq(
        id: String, part: EquipmentSlot,
        hp: Double = 0.0
    ): EquipmentInstance {
        val subs = buildList {
            if (hp != 0.0) add(EquipStatValue(EquipStat.HP, hp))
        }
        return EquipmentInstance(
            id = id,
            name = id,
            part = part,
            growth = EquipGrowth(
                affix = EquipAffixSet(
                    mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
                    subStats = subs
                )
            )
        )
    }

    @Test
    fun `等价性 - 装备四部位全配与空`() {
        val eq = listOf(
            eq("h1", EquipmentSlot.HEAD, hp = 120.0),
            eq("b1", EquipmentSlot.BODY, hp = 80.0),
            eq("g1", EquipmentSlot.HANDS, hp = 20.0),
            eq("f1", EquipmentSlot.FEET, hp = 50.0)
        )
        assertEquivalence(equipment = eq, curHp = 100, curMp = 100)
        assertEquivalence(equipment = emptyList(), curHp = 100, curMp = 100)
    }

    @Test
    fun `等价性 - 功法熟练度 0-50-100`() {
        val manual = ManualInstance(id = "m1", name = "御剑诀", rarity = 3, type = ManualType.ATTACK,
            stats = mapOf("hp" to 200, "mp" to 100))
        for (mastery in listOf(0, 50, 100)) {
            val prof = ManualProficiencyData(manualId = "m1", proficiency = mastery.toDouble(),
                masteryLevel = mastery / 20)
            assertEquivalence(
                manuals = listOf(manual),
                proficiencies = mapOf("1" to listOf(prof)),
                curHp = 100, curMp = 100
            )
        }
    }

    @Test
    fun `等价性 - 丹药有效与过期`() {
        assertEquivalence(pillDuration = 3, pillHp = 200, pillMp = 100, curHp = 100, curMp = 100)
        assertEquivalence(pillDuration = 0, pillHp = 200, pillMp = 100, curHp = 100, curMp = 100)
    }

    @Test
    fun `等价性 - 多旬结算`() {
        assertEquivalence(curHp = 50, curMp = 50, phasesToSettle = 3)
    }

    @Test
    fun `等价性 - 负值特殊态跳过`() {
        val stateA = buildState(curHp = -1, curMp = -1)
        val stateB = buildState(curHp = -1, curMp = -1)
        val eqMap = stateA.equipmentInstances.items.associateBy { it.id }
        val mMap = stateA.manualInstances.items.associateBy { it.id }
        val wroteA = service.recoverHpMpSingleColumn(stateA, 1, 1, equipmentMap = eqMap, manualMap = mMap)
        service.recoverHpMpSingle(stateB, 1, 1, equipmentMap = eqMap, manualMap = mMap)
        assertFalse("负值特殊态不应写入", wroteA)
        assertEquals(-1, stateA.discipleTables.currentHps[1])
        assertEquals(-1, stateB.discipleTables.currentHps[1])
    }
}
