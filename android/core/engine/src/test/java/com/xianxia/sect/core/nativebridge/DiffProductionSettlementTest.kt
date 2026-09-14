package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffProductionSettlementTest — 炼丹/锻造完成结算跨语言差分对拍。
 *
 * 守护目标：C++ `production.h`（month_settlement 步骤 4a/4b 完成结算 + 末尾
 * 自动排班）与 Kotlin `MonthSettlementExecutor`（真实 AlchemySystem/
 * ForgeSystem → 真实 ProductionProcessor 全链：FormulaService/Coordinator/
 * Repository(InMemory port)）的生产月变语义**逐位一致**。
 *
 * 场景（buildProductionSceneSnapshot）：双到期槽——锻造成功率 0.0（失败臂：
 * 恰抽 1 次 SYSTEM、无产出、计数累加）+ 炼丹成功率 1.0（成功臂：恰抽 2 次
 * SYSTEM（成功 roll + grade roll）、产丹、弟子晋升 level 1 + 晋升事件）。
 * autoRestart 全关（规避 Kotlin 编排"完成结算前续炼启动"与 C++ 末尾续炼的
 * 编排时点差异——C++ 当月续炼为登记过的改进基线，黄金测试锁定）；全 male
 * 弟子 + 无灵田/秘境/侦察/附庸/任务/政策 → 其余步骤零抽取，SYSTEM 序 =
 * F1 roll → A1 roll → A1 grade（双端逐位一致，rngStates[3] 终态锁定）。
 *
 * Kotlin 臂月结后以 repo 为真源写回镜像（完成结算的槽位重置走 Room 通道，
 * keepDisciple 分支镜像不写为 B5 既有口径；与生产管线 settleMonthNative 的
 * 窗口对齐互为同构）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffProductionSettlementTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private companion object {
        const val PHASES = 3
        const val SEED = 20260901L
    }



    // ── S4 场景定义（从 DiffMonthSettlementTest 拆出，自足） ──────────

    /** S4 场景：炼丹工/锻造工 id（性别 male——规避伴侣配对组合抽取） */
    private val alchemyWorkerId = "31"
    private val forgeWorkerId = "32"

    /** 初始 RNG 分区状态：seed+partitionId 播种后各抽取 3 次（非平凡状态） */
    private fun initialRngStates(seed: Long): MutableMap<Int, Long> {
        val states = mutableMapOf<Int, Long>()
        RngPartition.values().forEach { partition ->
            val rng = DeterministicRng.fromSeed(seed + partition.id)
            repeat(3) { rng.nextInt() }
            states[partition.id] = rng.snapshot()
        }
        return states
    }

    /** 最小弟子（male/成年/默认属性——生产场景零配对零偷盗） */
    private fun prodDisciple(id: String, name: String) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1,
        cultivation = 10.0, spiritRootType = "metal",
        age = 20, gender = "male",
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    internal fun buildProductionSceneSnapshot(): Pair<NativeGameState, InMemoryProductionSlotDataPort> {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            worldLevelLastRefreshMonth = 1 * 12 + 1
            productionSlots = listOf(
                ProductionSlot(
                    id = "slot-f1", slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = "forge",
                    status = ProductionSlotStatus.WORKING,
                    recipeId = "ironSword", recipeName = "精铁剑",
                    startYear = 1, startMonth = 1, duration = 1, baseDuration = 1,
                    successRate = 0.0,
                    assignedDiscipleId = forgeWorkerId,
                    assignedDiscipleName = "丙一"
                ),
                ProductionSlot(
                    id = "slot-a1", slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = "alchemy",
                    status = ProductionSlotStatus.WORKING,
                    recipeId = "cultivationSpeed_1_low", recipeName = "引灵丹",
                    startYear = 1, startMonth = 1, duration = 1, baseDuration = 1,
                    successRate = 1.0,
                    assignedDiscipleId = alchemyWorkerId,
                    assignedDiscipleName = "甲一"
                )
            )
        }
        // aiSectDisciples 置一条空池：C++ 导出侧空容器为 null 的既有口径与
        // Kotlin {} 结构不匹配——非空键 + 空列表双端同构（且不触发任何 AI 域
        // 消费：worldLevels 空 → 兽袭预计算早退；无秘境 → AI 派遣早退）
        gameData.aiSectDisciples = mapOf("ai-3" to emptyList<Disciple>())
        val port = InMemoryProductionSlotDataPort().also {
            it.seed(gameData.productionSlots)
        }
        return Pair(
            NativeGameState(
                gameData = gameData,
                aiSectDisciples = gameData.aiSectDisciples,
                disciples = listOf(
                    prodDisciple(alchemyWorkerId, "甲一"),
                    prodDisciple(forgeWorkerId, "丙一")
                )
            ),
            port
        )
    }

    @Test
    fun `production completion matches Kotlin bit-for-bit across one boundary`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val (snapshot, port) = buildProductionSceneSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val harness = buildMonthDiffHarness(
            FakeGameStateStore().also {
                it.gameDataValue = snapshot.gameData
                it.disciplesValue = snapshot.disciples
                it.equipmentStacksValue = snapshot.equipmentStacks
                it.equipmentInstancesValue = snapshot.equipmentInstances
                it.manualStacksValue = snapshot.manualStacks
                it.manualInstancesValue = snapshot.manualInstances
                it.pillsValue = snapshot.pills
                it.materialsValue = snapshot.materials
                it.herbsValue = snapshot.herbs
                it.seedsValue = snapshot.seeds
                it.storageBagsValue = snapshot.storageBags
            },
            snapshot.gameData.rngStates
        )
        harness.productionSlotPort.seed(port.getAllSync())
        harness.productionSlotRepository.initialize()  // 同步灌缓存（repo 读走内存缓存）
        val expected = advanceKotlinMonthSide(harness, PHASES)

        // ── C++ 被测侧 ──
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 场景显式断言（全量结构对拍兜底）
        val gd = actual.gameData
        assertEquals("锻造完成计数", 1L, gd.guideCounters["forgeCompleted"])
        assertEquals("炼丹完成计数", 1L, gd.guideCounters["alchemyCompleted"])
        assertEquals("年度锻造计数", 1, gd.annualForgeCount)
        assertEquals("年度炼丹计数", 1, gd.annualAlchemyCount)
        assertEquals("锻造失败不应产出装备", 0, actual.equipmentStacks.size)
        assertEquals("炼丹成功应产出 1 枚丹药", 1, actual.pills.size)
        assertEquals("丹药品类", PillCategory.CULTIVATION, actual.pills[0].category)
        val alchemistPromoted =
            gd.gameEventRecords.count { it.eventType == "alchemist_promoted" }
        assertEquals("炼丹师晋升事件", 1, alchemistPromoted)
        val worker = actual.disciples.first { it.id == alchemyWorkerId }
        assertEquals("炼丹工晋升 level 1", 1, worker.skills.alchemyLevel)
        assertEquals("晋升计数清零", 0, worker.skills.alchemyPromotionCount)
        assertEquals("双槽位均重置 IDLE", "IDLE,IDLE",
            gd.productionSlots.joinToString(",") { it.status.name })

        diffAssertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }
}
