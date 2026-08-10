package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 装备孕养批量版性能基准测试（P-2，2026-08-02）。
 *
 * 修复前：每旬热点循环对每个弟子执行 `equipmentInstances.map{}` 全量列表重建
 * （O(E) × D 弟子 = O(D×E)），D=300、E=1200 时每旬 ~360K 元素拷贝 + 300 个新 List。
 * 修复后：所有弟子的更新累积到共享 Map，循环后单次列表重建（O(E)）。
 *
 * 断言：批量版耗时 ≤ 单弟子版 50%（Robolectric 噪声较大，阈值保守化——结构性退化
 * （批量版误回每弟子重建）时比值必然接近 1.0）。
 */
@RunWith(RobolectricTestRunner::class)
class EquipmentNurtureBatchBenchmarkTest {

    private lateinit var core: CultivationCore

    private val DISCIPLE_COUNT = 300

    @Before
    fun setUp() {
        // Fake 默认 manualInstances/disciples flow 即空列表——等价 mock 时代逐条 stub，
        // 且后续服务扩展读其他 store 状态不会静默 null
        val stateStore = FakeAtomicStateStore()

        val realHpMpRecoveryService = HpMpRecoveryService()
        core = CultivationCore(
            hpMpRecoveryService = realHpMpRecoveryService,
            autoPillService = AutoPillService(mockSmart(DisciplePillManager::class.java), mockSmart()),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(stateStore),
            battleSettlementService = BattleSettlementService(realHpMpRecoveryService)
        )
    }

    /**
     * 构建 300 弟子 × 4 装备（全装备）的测试状态。
     * rarity=5 + 大 nurtureProgress 保证每旬每件装备必跨级产出更新。
     */
    private fun buildState(): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        val equipment = mutableListOf<EquipmentInstance>()
        for (i in 1..DISCIPLE_COUNT) {
            tables.insert(Disciple(id = i.toString(), name = "弟子$i", realm = 5, realmLayer = 1))
            tables.weaponIds[i] = "w$i"
            tables.armorIds[i] = "a$i"
            tables.bootsIds[i] = "b$i"
            tables.accessoryIds[i] = "c$i"
            equipment += EquipmentInstance(
                id = "w$i", name = "剑", rarity = 5, slot = EquipmentSlot.WEAPON,
                nurtureLevel = 0, nurtureProgress = 99999.0
            )
            equipment += EquipmentInstance(
                id = "a$i", name = "甲", rarity = 5, slot = EquipmentSlot.ARMOR,
                nurtureLevel = 0, nurtureProgress = 99999.0
            )
            equipment += EquipmentInstance(
                id = "b$i", name = "靴", rarity = 5, slot = EquipmentSlot.BOOTS,
                nurtureLevel = 0, nurtureProgress = 99999.0
            )
            equipment += EquipmentInstance(
                id = "c$i", name = "饰", rarity = 5, slot = EquipmentSlot.ACCESSORY,
                nurtureLevel = 0, nurtureProgress = 99999.0
            )
        }
        tables.writeAllowed = false
        tables.changedIdTracker.consumeChangedIds()
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(equipment),
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

    private fun measure(iterations: Int, block: () -> Unit): Long {
        repeat(3) { block() }  // warmup
        val times = (1..iterations).map {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun `批量版耗时不超过单弟子版 50%`() {
        val ids = buildState().discipleTables.ids.toList()

        val singleState = buildState()
        val singleTime = measure(5) {
            val eqMap = singleState.equipmentInstances.items.associateBy { it.id }
            for (id in ids) {
                core.processEquipmentNurtureSingle(singleState, id, eqMap)
            }
        }

        val batchState = buildState()
        val batchTime = measure(5) {
            val eqMap = batchState.equipmentInstances.items.associateBy { it.id }
            val updates = mutableMapOf<String, EquipmentInstance>()
            for (id in ids) {
                core.processEquipmentNurtureSingle(batchState, id, eqMap, updates)
            }
            core.applyEquipmentUpdates(batchState, updates)
        }

        val ratio = batchTime.toDouble() / singleTime
        assertTrue(
            "批量版(${batchTime / 1000}μs) 应显著快于单弟子版(${singleTime / 1000}μs)，" +
                "实际比值 $ratio > 0.50——批量路径可能误用每弟子重建，请检查" +
                "processEquipmentNurtureSingle 的 sharedUpdates 分支",
            ratio <= 0.50
        )
    }

    /** 批量版与单弟子版最终装备列表逐元素等价（行为不变性回归） */
    @Test
    fun `批量版与单弟子版最终 equipmentInstances 逐元素等价`() {
        val ids = buildState().discipleTables.ids.toList()

        // 单弟子版
        val stateSingle = buildState()
        val eqMapSingle = stateSingle.equipmentInstances.items.associateBy { it.id }
        for (id in ids) {
            core.processEquipmentNurtureSingle(stateSingle, id, eqMapSingle)
        }

        // 批量版
        val stateBatch = buildState()
        val eqMapBatch = stateBatch.equipmentInstances.items.associateBy { it.id }
        val updates = mutableMapOf<String, EquipmentInstance>()
        for (id in ids) {
            core.processEquipmentNurtureSingle(stateBatch, id, eqMapBatch, updates)
        }
        core.applyEquipmentUpdates(stateBatch, updates)

        assertTrue(
            "批量版与单弟子版装备列表不一致：single=${stateSingle.equipmentInstances.items.size} " +
                "batch=${stateBatch.equipmentInstances.items.size}",
            stateSingle.equipmentInstances.items == stateBatch.equipmentInstances.items
        )
        // 确保孕养确实产出更新（构造有效性的自检——若无更新则本测试无意义）
        assertTrue(
            "构造无效：单旬孕养未产出任何更新（nurtureProgress 可能已满级），" +
                "请调整测试装备构造",
            stateSingle.equipmentInstances.items.any { it.nurtureLevel > 0 }
        )
    }
}
