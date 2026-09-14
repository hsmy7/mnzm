package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.StackKey
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.util.AppError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffSpiritFieldTest — 灵田收获跨语言差分对拍。
 *
 * 守护目标：C++ processSpiritFieldHarvest（成熟判定/灵草入库/种子奖励/续种/
 * 年度报告）与 Kotlin ProductionProcessor.processSpiritFieldHarvest 语义一致。
 *
 * Kotlin 基准：内联复刻（HerbDatabase 真实注册表 + StackableItemStore 真实实现）。
 * RNG 对齐：Kotlin 复刻注入固定 roll 值，C++ 侧选定 seed（seed=2 → SYSTEM 分区
 * nextInt(5)=2；seed=8 → 0）与之匹配。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffSpiritFieldTest {

    private val json = Json { encodeDefaults = true }

    private fun notFound(id: String) = AppError.Domain.Inventory.NotFound(id)

    // ── Kotlin 基准：processSpiritFieldHarvest 内联复刻 ─────────────────

    /** 复刻单地块收获（roll 由调用方注入以对齐 C++ RNG） */
    private data class KotlinHarvestResult(
        val herbs: List<Herb>,
        val seeds: List<Seed>,
        val plants: List<SpiritFieldPlant>,
        val annualHerbCount: Int,
        val annualHerbBySource: Map<String, Int>,
        val herbsHarvested: Int,
    )

    /** Kotlin 端灵田收获复刻（对拍专用内联实现，禁止重构拆分——漂移即对拍失败）。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun kotlinHarvest(
        gd: GameData, herbs: List<Herb>, seeds: List<Seed>, roll: Int,
    ): KotlinHarvestResult {
        val currentYear = gd.gameYear
        val currentMonth = gd.gameMonth
        val newPlants = gd.spiritFieldPlants.toMutableList()
        val herbStore = StackableItemStore(
            initialItems = herbs,
            stackKeyOf = { StackKey.of(it.name, it.rarity, it.category) },
            maxStack = 9999,
            maxSlots = { 50 },
            notFound = ::notFound
        )
        val seedStore = StackableItemStore(
            initialItems = seeds,
            stackKeyOf = { StackKey.of(it.name, it.rarity, it.growTime) },
            maxStack = 9999,
            maxSlots = { 50 },
            notFound = ::notFound
        )
        var annualHerbCount = gd.annualHerbCount
        val annualHerbBySource = gd.annualHerbBySource.toMutableMap()
        var herbsHarvested = 0

        gd.spiritFieldPlants.forEachIndexed { index, plant ->
            if (plant.seedId.isEmpty() || plant.growTime <= 0) return@forEachIndexed
            if (plant.sectId.isNotEmpty() && plant.sectId != gd.activeSectId) return@forEachIndexed
            val elapsedMonths = ((currentYear - plant.plantYear) * 12 +
                (currentMonth - plant.plantMonth)).coerceAtLeast(0)
            if (elapsedMonths < plant.growTime) return@forEachIndexed

            val dbHerb = HerbDatabase.getHerbFromSeedName(plant.seedName) ?: return@forEachIndexed
            val finalYield = plant.expectedYield.coerceAtLeast(1)
            val newHerb = Herb(
                id = "kotlin-herb-$index",
                name = dbHerb.name, rarity = dbHerb.rarity,
                description = dbHerb.description, category = dbHerb.category,
                quantity = finalYield
            )
            val herbResult = herbStore.add(newHerb)
            val actualAdded = when (herbResult) {
                is com.xianxia.sect.core.util.DomainResult.Success -> finalYield
                is com.xianxia.sect.core.util.DomainResult.Partial -> finalYield - herbResult.overflow
                else -> 0
            }
            herbsHarvested += actualAdded
            annualHerbBySource["spirit_field"] =
                (annualHerbBySource["spirit_field"] ?: 0) + actualAdded
            annualHerbCount++

            // 种子奖励（roll 注入对齐 C++ RNG）
            if (roll > 0) {
                val seedTemplate = HerbDatabase.getSeedByName(plant.seedName)
                if (seedTemplate != null) {
                    val newSeed = Seed(
                        id = "kotlin-seed-$index",
                        name = seedTemplate.name, rarity = seedTemplate.rarity,
                        description = seedTemplate.description,
                        growTime = seedTemplate.growTime, yield = seedTemplate.yield,
                        quantity = roll
                    )
                    seedStore.add(newSeed)
                }
            }

            // 续种（updateSlotAfterHarvest 语义）
            val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
            val existingSeed = seedStore.all().find {
                it.name == plant.seedName &&
                    it.rarity == (matchingSeed?.rarity ?: 1) &&
                    it.growTime == plant.growTime && it.quantity > 0 && !it.isLocked
            }
            if (existingSeed != null) {
                seedStore.remove(existingSeed.id, 1)
                newPlants[index] = plant.copy(
                    seedId = existingSeed.id,
                    plantYear = currentYear, plantMonth = currentMonth,
                    completionMonth = currentYear * 12 + currentMonth +
                        plant.growTime.coerceAtLeast(1),
                    completionPhase = 3
                )
            } else {
                newPlants[index] = plant.copy(
                    seedId = "", seedName = "", growTime = 0, expectedYield = 0,
                    plantYear = 0, plantMonth = 0,
                    completionMonth = 0, completionPhase = 1
                )
            }
        }
        return KotlinHarvestResult(
            herbs = herbStore.all(), seeds = seedStore.all(), plants = newPlants,
            annualHerbCount = annualHerbCount, annualHerbBySource = annualHerbBySource,
            herbsHarvested = herbsHarvested,
        )
    }

    // ── 对拍执行 ─────────────────────────────────────────────────────

    private fun execCppHarvest(initial: NativeGameState, year: Int, month: Int): NativeGameState {
        val encoded = json.encodeToString(NativeGameState.serializer(), initial)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        val ops = buildJsonArray {
            add(
                buildJsonObject {
                    put("op", "spiritFieldHarvest"); put("year", year); put("month", month)
                }
            )
        }
        val resultJson = DiffRngBridge.nativeCoreExecOps(
            json.encodeToString(JsonArray.serializer(), ops).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $resultJson", !resultJson.contains("\"error\""))
        return json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
    }

    /** 断言 Kotlin 复刻与 C++ 收获结果一致（roll 对齐） */
    private fun assertHarvestParity(
        initial: NativeGameState, year: Int, month: Int, roll: Int,
    ) {
        val kotlin = kotlinHarvest(initial.gameData, initial.herbs, initial.seeds, roll)
        val cpp = execCppHarvest(initial, year, month)

        // 灵草聚合
        val kotlinHerbs = kotlin.herbs.groupingBy { it.name }
            .fold(0) { acc, h -> acc + h.quantity }
        val cppHerbs = cpp.herbs.groupingBy { it.name }
            .fold(0) { acc, h -> acc + h.quantity }
        assertEquals("灵草入库不一致", kotlinHerbs, cppHerbs)

        // 种子聚合（新 id 不同，按名称聚合）
        val kotlinSeeds = kotlin.seeds.groupingBy { it.name }
            .fold(0) { acc, s -> acc + s.quantity }
        val cppSeeds = cpp.seeds.groupingBy { it.name }
            .fold(0) { acc, s -> acc + s.quantity }
        assertEquals("种子不一致", kotlinSeeds, cppSeeds)

        // 地块状态（seedId 可能不同——比较关键字段）
        assertEquals("地块数量不一致", kotlin.plants.size, cpp.gameData.spiritFieldPlants.size)
        for (i in kotlin.plants.indices) {
            val kp = kotlin.plants[i]
            val cp = cpp.gameData.spiritFieldPlants[i]
            assertEquals("地块 growTime 不一致", kp.growTime, cp.growTime)
            assertEquals("地块 plantYear 不一致", kp.plantYear, cp.plantYear)
            assertEquals("地块 plantMonth 不一致", kp.plantMonth, cp.plantMonth)
            assertEquals("地块 seedId 空状态不一致", kp.seedId.isEmpty(), cp.seedId.isEmpty())
        }

        // 年度报告
        assertEquals("annualHerbCount 不一致", kotlin.annualHerbCount, cpp.gameData.annualHerbCount)
        assertEquals("annualHerbBySource 不一致", kotlin.annualHerbBySource, cpp.gameData.annualHerbBySource)
    }

    // ── 测试用例 ─────────────────────────────────────────────────────

    @Test
    fun `mature plant harvest matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field-0", seedId = "seed-0", seedName = "聚灵草种",
            growTime = 1, expectedYield = 5, plantYear = 1, plantMonth = 1,
            completionMonth = 13, completionPhase = 3
        )
        val initial = NativeGameState(
            gameData = GameData().apply { gameYear = 2; gameMonth = 3 },
        )
        initial.gameData.spiritFieldPlants = listOf(plant)
        // seed=2 → SYSTEM 分区 nextInt(5)=2
        DiffRngBridge.nativeManagerInit(2)
        assertHarvestParity(initial, 2, 3, roll = 2)
    }

    @Test
    fun `no seed clears plot matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field-0", seedId = "seed-0", seedName = "聚灵草种",
            growTime = 1, expectedYield = 5, plantYear = 1, plantMonth = 1,
            completionMonth = 13, completionPhase = 3
        )
        val initial = NativeGameState(gameData = GameData().apply { gameYear = 2; gameMonth = 3 })
        initial.gameData.spiritFieldPlants = listOf(plant)
        // seed=8 → SYSTEM 分区 nextInt(5)=0 → 无种子奖励 → 无库存 → 清空
        DiffRngBridge.nativeManagerInit(8)
        assertHarvestParity(initial, 2, 3, roll = 0)
    }

    @Test
    fun `reseed consumes existing seed matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field-0", seedId = "seed-0", seedName = "聚灵草种",
            growTime = 1, expectedYield = 5, plantYear = 1, plantMonth = 1,
            completionMonth = 13, completionPhase = 3
        )
        val seed = Seed(
            id = "seed-stack-1", name = "聚灵草种", rarity = 1,
            description = "种植后可收获聚灵草", growTime = 1, yield = 5, quantity = 2
        )
        val initial = NativeGameState(
            gameData = GameData().apply { gameYear = 2; gameMonth = 3 },
            seeds = listOf(seed)
        )
        initial.gameData.spiritFieldPlants = listOf(plant)
        DiffRngBridge.nativeManagerInit(2)
        assertHarvestParity(initial, 2, 3, roll = 2)
    }

    @Test
    fun `immature plant not harvested matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val plant = SpiritFieldPlant(
            buildingInstanceId = "field-0", seedId = "seed-0", seedName = "聚灵草种",
            growTime = 100, expectedYield = 5, plantYear = 1, plantMonth = 1,
            completionMonth = 112, completionPhase = 3
        )
        val initial = NativeGameState(gameData = GameData().apply { gameYear = 2; gameMonth = 3 })
        initial.gameData.spiritFieldPlants = listOf(plant)
        DiffRngBridge.nativeManagerInit(2)
        assertHarvestParity(initial, 2, 3, roll = 2)
    }
}
