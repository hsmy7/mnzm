package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.service.EquipmentUpgradeService
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffEquipmentUpgradeTest — 装备升级/分解事务的跨语言双臂对拍（B3，方案 §6.4）。
 *
 * ## 对拍双方
 * - **C++ 权威臂**：`equipment_tx.h`，经既有动作通道
 *   `DiffRngBridge.nativeCoreExecute(ActionIds.EQUIP_UPGRADE = 1486 / EQUIP_DISMANTLE = 1487)`
 *   驱动，事后 `nativeCoreExportState()` 回读实例（等级/词条/强化次数）与灵石；
 * - **Kotlin 回退臂**：[EquipmentUpgradeService.upgradeEquipment/dismantleEquipment]
 *   （CultivationFacade 暴露的生产回退路径），同种子 [GameRngManager]。
 *
 * ## 双臂同起点（与 DiffGachaPullTest 同纪律）
 * 两臂共用**逐位同一个 PCG 状态**：Kotlin `GameRngManager.initSystemSeed(seed)` ⇒
 * EQUIPMENT 分区 = `DeterministicRng.fromSeed(seed + 13)`，取其 snapshot 预置进
 * 导入 C++ 的 `rngStates[13]`；两臂跑完同一升级向量后断言事后 13 号分区状态全等
 * ⇒ 消费次数与顺序逐位钉死（少掷/多掷/换序即分叉变红）。
 *
 * ## 覆盖
 * Lv1→30 全序列（29 次事务，逐级 newLevel 双臂一致）· 强化节点（Lv3/6/…/30 共
 * 10 次 subRolls 演化逐位一致）· 灵石/兽材扣减轨迹一致 · 事后 RNG 分区状态全等 ·
 * 分解返还（灵石+兽材）一致。升级序列同时是强化抽取（kEquipment nextInt）的
 * 跨端消费序锚——任何一侧抽取算法/分区漂移都会在此分叉。
 */
class DiffEquipmentUpgradeTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private companion object {
        const val SEED = 20260930L
        const val INSTANCE_ID = "e1"
        const val RARITY = 2
        const val MAX_LEVEL = 30
    }

    /** 双臂共用的初始装备（Lv1 / r2 / 3 副词条初始 [1,1,1]）。 */
    private fun seedInstance() = EquipmentInstance(
        id = INSTANCE_ID,
        name = "对拍剑",
        setId = "lietian",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(
            level = 1,
            exp = 0,
            affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 18.0),
                subStats = listOf(
                    EquipStatValue(EquipStat.DEFENSE, 6.0),
                    EquipStatValue(EquipStat.HP, 60.0),
                    EquipStatValue(EquipStat.CRIT_RATE, 0.02)
                ),
                subRolls = listOf(1, 1, 1)
            )
        ),
        meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = RARITY, minRealm = 7)
    )

    /** 双臂共用的初始材料（BEAST 全类目两堆叠，(rarity,id) 升序扣）。 */
    private fun seedMaterials() = listOf(
        Material(id = "beast-a", name = "凡兽材", rarity = 1, quantity = 500),
        Material(id = "beast-b", name = "妖兽材", rarity = 2, quantity = 500)
    )

    private fun seedGameData(stones: Long) = GameData().apply {
        spiritStones = stones
        gameYear = 1
        gameMonth = 1
    }

    // ── C++ 权威臂 ───────────────────────────────────────────

    private fun loadNativeScene(stones: Long, rngState: Long) {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        val state = NativeGameState(
            gameData = seedGameData(stones).apply {
                rngStates = mapOf(RngPartition.EQUIPMENT.id to rngState)
            },
            equipmentInstances = listOf(seedInstance()),
            materials = seedMaterials()
        )
        assertTrue(
            "C++ 导入升级场景失败（对拍前提不成立即判红，禁止静默跳过）",
            DiffRngBridge.nativeCoreImportState(
                json.encodeToString(NativeGameState.serializer(), state).encodeToByteArray()
            )
        )
    }

    private fun nativeExecute(actionId: Int, params: JsonObject): JsonObject {
        val envelope = json.parseToJsonElement(
            DiffRngBridge.nativeCoreExecute(
                actionId,
                json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
            ).decodeToString()
        ).jsonObject
        return envelope
    }

    private fun exportNativeState(): NativeGameState = json.decodeFromString(
        NativeGameState.serializer(),
        DiffRngBridge.nativeCoreExportState().decodeToString()
    )

    // ── 对拍主体 ─────────────────────────────────────────────

    @Suppress("LongMethod") // Kotlin/C++ 双臂对拍 30 级全序列逐位平铺，拆分遮蔽逐位一致性
    @Test
    fun `升级30级全序列与分解返还双臂逐位一致`() {
        assumeTrue("需要桌面 .so（-Dgamecore.jni.path）", DiffRngBridge.isAvailable())

        // ── Kotlin 回退臂 ──
        val manager = GameRngManager().apply { initSystemSeed(SEED) }
        val startState = manager.getRng(RngPartition.EQUIPMENT).snapshot()
        val store = FakeGameStateStore().apply {
            gameDataValue = seedGameData(stones = 10_000_000L)
            equipmentInstancesValue = listOf(seedInstance())
            materialsValue = seedMaterials()
        }
        val kotlinService = EquipmentUpgradeService(store, manager)
        val kotlinLevels = mutableListOf<Int>()
        for (step in 1 until MAX_LEVEL) {
            val result = kotlinService.upgradeEquipment(INSTANCE_ID)
            assertTrue("Kotlin 臂第 $step 次升级应成功", result is DomainResult.Success)
            kotlinLevels.add(store.equipmentInstancesValue.single().level)
        }
        val kotlinAfterUpgrades = store.gameDataValue.spiritStones
        val kotlinMaterialsAfter = store.materialsValue.associate { it.id to it.quantity }
        val kotlinSubRolls = store.equipmentInstancesValue.single().growth.affix.subRolls
        val kotlinRngAfter = manager.getRng(RngPartition.EQUIPMENT).snapshot()
        assertTrue("Kotlin 臂分解应成功", kotlinService.dismantleEquipment(INSTANCE_ID) is DomainResult.Success)
        val kotlinStonesAfterDismantle = store.gameDataValue.spiritStones
        val kotlinBeastsAfterDismantle = store.materialsValue.first { it.rarity == 1 }.quantity

        // ── C++ 权威臂（同一起点状态） ──
        loadNativeScene(stones = 10_000_000L, rngState = startState)
        val upgradeParams = buildJsonObject { put("equipmentId", INSTANCE_ID) }
        val nativeLevels = mutableListOf<Int>()
        for (step in 1 until MAX_LEVEL) {
            val envelope = nativeExecute(ActionIds.EQUIP_UPGRADE, upgradeParams)
            val status = envelope["status"]?.jsonPrimitive?.content
            assertEquals("C++ 臂第 $step 次升级应成功", "success", status)
            nativeLevels.add(
                envelope.getValue("data").jsonObject.getValue("newLevel").jsonPrimitive.content.toInt()
            )
        }
        val nativeState = exportNativeState()
        val nativeAfterUpgrades = nativeState.gameData.spiritStones
        val nativeMaterials = nativeState.materials.associate { it.id to it.quantity }
        val nativeSubRolls = nativeState.equipmentInstances.single().growth.affix.subRolls
        val nativeRngAfter = nativeState.gameData.rngStates[RngPartition.EQUIPMENT.id]

        // ── 逐位比对 ──
        assertEquals("双臂等级序列（Lv1→30 全 29 步）", kotlinLevels, nativeLevels)
        assertEquals("双臂升级后灵石", kotlinAfterUpgrades, nativeAfterUpgrades)
        assertEquals("双臂兽材扣减轨迹", kotlinMaterialsAfter, nativeMaterials)
        assertEquals("双臂强化次数演化（10 次节点）", kotlinSubRolls, nativeSubRolls)
        assertEquals(
            "双臂 13 号装备分区事后状态（消费次数与顺序钉死）",
            kotlinRngAfter, nativeRngAfter
        )

        // 分解返还
        val dismantleParams = buildJsonObject { put("equipmentId", INSTANCE_ID) }
        val envelope = nativeExecute(ActionIds.EQUIP_DISMANTLE, dismantleParams)
        assertEquals("C++ 臂分解应成功", "success", envelope["status"]?.jsonPrimitive?.content)
        val nativeAfterDismantle = exportNativeState()
        assertEquals(
            "双臂分解后灵石（含 50% 返还）",
            kotlinStonesAfterDismantle, nativeAfterDismantle.gameData.spiritStones
        )
        assertEquals(
            "双臂分解后兽材（含 floor 返还）",
            kotlinBeastsAfterDismantle,
            nativeAfterDismantle.materials.first { it.rarity == 1 }.quantity
        )
        assertTrue("分解后实例应移除", nativeAfterDismantle.equipmentInstances.isEmpty())
    }

    @Test
    fun `升级材料不足双臂同失败且零消费`() {
        assumeTrue("需要桌面 .so（-Dgamecore.jni.path）", DiffRngBridge.isAvailable())

        // Kotlin 臂：灵石不足 → 失败，RNG 分区不前移
        val manager = GameRngManager().apply { initSystemSeed(SEED) }
        val startState = manager.getRng(RngPartition.EQUIPMENT).snapshot()
        val store = FakeGameStateStore().apply {
            gameDataValue = seedGameData(stones = 1L)
            equipmentInstancesValue = listOf(seedInstance())
            materialsValue = seedMaterials()
        }
        val service = EquipmentUpgradeService(store, manager)
        assertTrue(service.upgradeEquipment(INSTANCE_ID) is DomainResult.Failure)
        val kotlinRngAfter = manager.getRng(RngPartition.EQUIPMENT).snapshot()
        assertEquals("失败臂 Kotlin 零消费", startState, kotlinRngAfter)

        // C++ 臂：同一预置 → 失败信封，13 号分区同样不前移
        loadNativeScene(stones = 1L, rngState = startState)
        val envelope = nativeExecute(
            ActionIds.EQUIP_UPGRADE,
            buildJsonObject { put("equipmentId", INSTANCE_ID) }
        )
        assertEquals("C++ 臂应失败信封", "failure", envelope["status"]?.jsonPrimitive?.content)
        val native = exportNativeState()
        assertEquals(
            "失败臂 C++ 零消费（13 号分区不前移）",
            startState, native.gameData.rngStates[RngPartition.EQUIPMENT.id]
        )
        assertEquals("失败臂实例零改动", 1, native.equipmentInstances.single().level)
    }

}
