package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.ZoneCalculator
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** ProductionProcessorTest 拆分（LC>800 行）：14 个用例随 fixture 迁出，行为零变更。 */
class ProductionProcessorSettlementTest : ProductionProcessorTestBase() {

    // ═══════════════════════════════════════════════════════════════
    // isDiscipleFollowed — Disciple 字段访问验证
    // ═══════════════════════════════════════════════════════════════

    /** 与生产代码 ProductionProcessor.isDiscipleFollowed 逻辑一致 */

    @Test
    fun `processSpiritFieldHarvest - 收获后引导计数与年度统计正确累计不覆盖`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plants = (1..3).map { i ->
            SpiritFieldPlant(
                buildingInstanceId = "field$i", seedId = "p$i",
                seedName = "聚灵草种", growTime = 36, expectedYield = 5,
                plantYear = 1, plantMonth = 1
            )
        }
        val seeds = listOf(Seed(id = "s1", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 3))
        val state = createState(plants = plants, seeds = seeds, gameYear = 4, gameMonth = 1)
        // 非零基线：预置已有收获记录（验证统计不被旧 data 引用覆盖清零）
        state.gameData = state.gameData.copy(
            guideCounters = mapOf(GuideCounterKeys.HERBS_HARVESTED to 5L),
            annualHerbCount = 7,
            annualHerbBySource = mapOf("spirit_field" to 10)
        )
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        assertEquals("引导计数应累计 +3 而非覆盖",
            8L, state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED])
        assertEquals("年度收获数应累计 +3 而非覆盖", 10, state.gameData.annualHerbCount)
        assertEquals("年度来源统计应累计实际入库 15", 25, state.gameData.annualHerbBySource["spirit_field"])
        assertEquals("3 块田同种灵草合并为 1 条记录", 1, state.herbs.all().size)
        assertEquals("总产量 15", 15, state.herbs.all().first().quantity)
        assertTrue("3 颗种子应全部消耗", state.seeds.all().isEmpty())
        assertEquals("3 块田全部续种", 3,
            state.gameData.spiritFieldPlants.count { it.plantYear == 4 && it.seedId.isNotEmpty() })
    }
    @Test
    fun `processSpiritFieldHarvest - 300块灵田批量收获合并续种统计正确`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plants = (1..300).map { i ->
            SpiritFieldPlant(
                buildingInstanceId = "field$i", seedId = "p$i",
                seedName = "聚灵草种", growTime = 36, expectedYield = 5,
                plantYear = 1, plantMonth = 1
            )
        }
        // 单堆叠 quantity=300（真实大额种子场景）：避免 seeds.size 占槽位导致 maxSlots 溢出
        val seeds = listOf(Seed(id = "s1", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 300))
        val state = createState(plants = plants, seeds = seeds, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        val processor = createProcessor(inventorySystem = inventorySystem)
        processor.processSpiritFieldHarvest(state)
        val herbs = state.herbs.all()
        assertEquals("300 块田同种灵草合并为 1 条记录", 1, herbs.size)
        assertEquals("总产量 1500", 1500, herbs.first().quantity)
        assertTrue("种子应被全部消耗", state.seeds.all().isEmpty())
        assertEquals("地块数不变", 300, state.gameData.spiritFieldPlants.size)
        assertEquals("300 块全部续种", 300,
            state.gameData.spiritFieldPlants.count { it.plantYear == 4 && it.seedId.isNotEmpty() })
        assertEquals("引导计数累计 +300", 300L,
            state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED])
        assertEquals("年度收获数累计 +300", 300, state.gameData.annualHerbCount)
        assertEquals("年度来源统计累计 1500", 1500, state.gameData.annualHerbBySource["spirit_field"])
        verify(inventorySystem, never()).sendOverflowMail(any(), any(), any(), any(), any(), any())
    }
    @Test
    fun `processSpiritFieldHarvest - 光环内灵田提前成熟收获，光环外不受影响`() = runTest {
        // 灵植阁(0,0,4x3) 中心(2,1.5)：光环内田(2,1,1x1) 距离 0 ≤ 6 命中；
        // 光环外田(20,20,1x1) 最近距离约 25.8 > 6 不命中
        val placedBuildings = listOf(
            GridBuildingData(displayName = "灵植阁", gridX = 0, gridY = 0,
                width = 4, height = 3, instanceId = "garden1", sectId = "sectA"),
            GridBuildingData(displayName = "灵田", gridX = 2, gridY = 1,
                width = 1, height = 1, instanceId = "field_in", sectId = "sectA"),
            GridBuildingData(displayName = "灵田", gridX = 20, gridY = 20,
                width = 1, height = 1, instanceId = "field_out", sectId = "sectA")
        )
        // 灵植属性取到加成上限 0.20（sp = base + 20×step，无天赋/词条职务加成）
        val elderSp = GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE +
            20 * GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_STEP
        val auraSp = GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_BASE +
            20 * GameConfig.PolicyConfig.HERB_GARDEN_DISCIPLE_SPIRIT_STEP
        val tables = DiscipleTables()
        tables.addId(100)
        tables.names[100] = "灵植长老"
        tables.isAlive[100] = 1
        tables.spiritPlantings[100] = elderSp
        tables.addId(101)
        tables.names[101] = "光环弟子"
        tables.isAlive[101] = 1
        tables.spiritPlantings[101] = auraSp
        val elderSlots = ElderSlots(
            herbGardenElder = "100",
            herbGardenDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "101"))
        )
        val innerPlant = SpiritFieldPlant(buildingInstanceId = "field_in", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val outerPlant = SpiritFieldPlant(buildingInstanceId = "field_out", seedId = "p2",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(
            plants = listOf(innerPlant, outerPlant),
            gameYear = 3, gameMonth = 5,  // elapsed = 28 月
            env = HarvestEnv(
                placedBuildings = placedBuildings,
                elderSlots = elderSlots,
                discipleTables = tables
            )
        )
        // 期望有效生长时间（用与生产代码同一 API 推导——测试光环索引+地块门控集成，
        // 加成公式算术本身由 HerbGardenAuraServiceTest 覆盖）
        val innerEff = HerbGardenAuraService.calculateEffectiveGrowTime(
            36, ZoneCalculator.calculate(1.0, 0.2, 0.2, 0.0) - 1.0)
        val outerEff = HerbGardenAuraService.calculateEffectiveGrowTime(
            36, ZoneCalculator.calculate(1.0, 0.2, 0.0, 0.0) - 1.0)
        assertEquals("光环内有效生长时间 25", 25, innerEff)
        assertEquals("光环外有效生长时间 30", 30, outerEff)
        val processor = createProcessor()
        processor.processSpiritFieldHarvest(state)
        val herbs = state.herbs.all()
        assertEquals("仅光环内灵田收获", 1, herbs.size)
        assertEquals("收获聚灵草", "聚灵草", herbs.first().name)
        assertEquals("产量 5", 5, herbs.first().quantity)
        val inField = state.gameData.spiritFieldPlants.first { it.buildingInstanceId == "field_in" }
        val outField = state.gameData.spiritFieldPlants.first { it.buildingInstanceId == "field_out" }
        assertEquals("光环内田已收获清空（无种子续种）", "", inField.seedId)
        assertEquals("光环外田未成熟保持不变", "p2", outField.seedId)
    }
    @Test
    fun `processSpiritFieldHarvest - 仓库满时溢出转邮件且统计按实际入库`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val dbHerb = HerbDatabase.getHerbFromSeedName("聚灵草种") ?: return@runTest
        // maxSlots = computeMaxSlots(无仓库=基础容量 50) - 其他类型 0 - 种子 0 = 50；
        // 50 个满堆叠（herb maxStack=9999）占满全部槽位 → 收获的 5 株零合并且无空槽 → Failure 分支整批转邮件
        val maxStack = InventoryConfig().getMaxStackSize("herb")
        val fullStacks = (1..50).map { i ->
            Herb(id = "h$i", name = dbHerb.name, rarity = dbHerb.rarity,
                description = dbHerb.description, category = dbHerb.category, quantity = maxStack)
        }
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), herbs = fullStacks, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        val processor = createProcessor(inventorySystem = inventorySystem)
        processor.processSpiritFieldHarvest(state)
        // 溢出全量 5 株转邮件（满堆叠零合并且无空槽 → Failure 分支，与 Partial 同为溢出转邮件路径）
        verify(inventorySystem).sendOverflowMail("spirit_field", "herb", dbHerb.name, dbHerb.rarity, 5, dbHerb.id)
        assertEquals("年度来源统计按实际入库 0", 0, state.gameData.annualHerbBySource["spirit_field"])
        assertEquals("仓库记录数不变（无新堆叠）", 50, state.herbs.all().size)
        assertEquals("引导计数仍累计（收获行为本身成功）", 1L,
            state.gameData.guideCounters[GuideCounterKeys.HERBS_HARVESTED])
        assertEquals("灵田已收获清空", "", state.gameData.spiritFieldPlants.first().seedId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 锁定种子续种豁免 + 邮件异常防御
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun `processSpiritFieldHarvest - 锁定种子不用于自动续种，田收获后清空且种子数量不变`() = runTest {
        // 全系统"锁定=不可消耗"语义，自动续种不得绕过锁定保护
        val lockedSeed = Seed(
            id = "s1", slotId = 1, name = "聚灵草种", rarity = 1,
            growTime = 36, yield = 5, quantity = 3, isLocked = true
        )
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), seeds = listOf(lockedSeed), gameYear = 4, gameMonth = 1)
        createProcessor().processSpiritFieldHarvest(state)

        assertEquals("草药正常收获", 1, state.herbs.all().size)
        assertEquals("锁定种子不被消耗", 3, state.seeds.all().first().quantity)
        assertEquals("田收获后清空（无种子可续种）", "", state.gameData.spiritFieldPlants.first().seedId)
    }
    @Test
    fun `processSpiritFieldHarvest - 溢出邮件异常时收获不丢草药`() = runTest {
        // 循环中途异常时已完成地块的草药仍随事务提交、
        // 未处理地块保持成熟待下月再收（防御 try-catch，替代旧"整轮丢失"语义退化）
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val dbHerb = HerbDatabase.getHerbFromSeedName("聚灵草种") ?: return@runTest
        val maxStack = InventoryConfig().getMaxStackSize("herb")
        val fullStacks = (1..50).map { i ->
            Herb(id = "h$i", name = dbHerb.name, rarity = dbHerb.rarity,
                description = dbHerb.description, category = dbHerb.category, quantity = maxStack)
        }
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), herbs = fullStacks, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        // 仓库满 → 溢出转邮件 → 邮件系统异常（模拟故障）
        whenever(inventorySystem.sendOverflowMail(any(), any(), any(), any(), any(), any()))
            .thenThrow(RuntimeException("邮件系统故障"))
        createProcessor(inventorySystem = inventorySystem).processSpiritFieldHarvest(state)

        assertEquals("草药未丢失（堆叠数不变）", 50, state.herbs.all().size)
        assertEquals("田保持成熟未清空（异常地块下月再收）", "p1",
            state.gameData.spiritFieldPlants.first().seedId)
        assertEquals("统计未虚增（add 未完成）", 0,
            state.gameData.annualHerbBySource["spirit_field"] ?: 0)
    }
    @Test
    fun `processSpiritFieldHarvest - 续种后 seedId 更新为实际消耗的种子堆叠`() = runTest {
        // 田 seedId 必须指向实际消耗的堆叠——悬空 seedId（其堆叠已被扣尽移除）会让
        // UI 按 seedId 查库存失败误显示存量 0、同种种子分组分裂
        val seed = Seed(id = "s2", slotId = 1, name = "聚灵草种", rarity = 1,
            growTime = 36, yield = 5, quantity = 2, isLocked = false)
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "stale1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), seeds = listOf(seed), gameYear = 4, gameMonth = 1)
        createProcessor().processSpiritFieldHarvest(state)

        assertEquals("续种消耗新堆叠 1 颗", 1, state.seeds.all().first().quantity)
        assertEquals("田 seedId 指向实际消耗的堆叠", "s2",
            state.gameData.spiritFieldPlants.first().seedId)
    }
    @Test
    fun `processSpiritFieldHarvest - 跨宗门地块不收获不扣种子`() = runTest {
        // sectId 非本宗的田不收获（防扣本宗种子续种到异常田）
        val seed = Seed(id = "s1", slotId = 1, name = "聚灵草种", rarity = 1,
            growTime = 36, yield = 5, quantity = 3, isLocked = false)
        val foreignPlant = SpiritFieldPlant(buildingInstanceId = "field_foreign", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1,
            sectId = "sectB")
        val state = createState(plants = listOf(foreignPlant), seeds = listOf(seed),
            gameYear = 4, gameMonth = 1, env = HarvestEnv(activeSectId = "sectA"))
        createProcessor().processSpiritFieldHarvest(state)

        assertEquals("跨宗门田不收获", 0, state.herbs.all().size)
        assertEquals("种子不被消耗", 3, state.seeds.all().first().quantity)
        assertEquals("田保持原样", "p1", state.gameData.spiritFieldPlants.first().seedId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 灵田收获附带同种种子（0~4 各 20%）— 入库合并 + 溢出转邮件 + 续种交互
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun `processSpiritFieldHarvest - 收获附带同种种子入仓合并并参与续种`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val existing = Seed(id = "s0", slotId = 1, name = "聚灵草种",
            rarity = dbSeed.rarity, growTime = 36, yield = 5, quantity = 3)
        val state = createState(plants = listOf(plant), seeds = listOf(existing), gameYear = 4, gameMonth = 1)
        createProcessor(seedRoll = 2).processSpiritFieldHarvest(state)

        // 3（原有）+ 2（收获入仓）= 5 → 续种消耗 1 = 4；同种合并为单堆叠
        assertEquals("收获种子应与同种堆叠合并", 1, state.seeds.all().size)
        assertEquals("种子名称为同种", "聚灵草种", state.seeds.all().first().name)
        assertEquals("3+2-1=4", 4, state.seeds.all().first().quantity)
        assertEquals("续种消耗后 seedId 指向仓库堆叠", "s0",
            state.gameData.spiritFieldPlants.first().seedId)
    }
    @Test
    fun `processSpiritFieldHarvest - 种子 roll=0 不获得种子且无种清田`() = runTest {
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), gameYear = 4, gameMonth = 1)
        createProcessor(seedRoll = 0).processSpiritFieldHarvest(state)

        assertEquals("roll=0 不获得种子", 0, state.seeds.all().size)
        assertEquals("无种可续 → 田清空", "", state.gameData.spiritFieldPlants.first().seedId)
    }
    @Test
    fun `processSpiritFieldHarvest - roll=4 收获种子自给自足续种仓净存3`() = runTest {
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), gameYear = 4, gameMonth = 1)
        createProcessor(seedRoll = 4).processSpiritFieldHarvest(state)

        assertEquals("4 收获入仓 - 1 续种消耗 = 3", 3, state.seeds.all().first().quantity)
        val updated = state.gameData.spiritFieldPlants.first()
        assertEquals("田续种", "聚灵草种", updated.seedName)
        assertTrue("seedId 指向新收获的种子堆叠", updated.seedId.isNotEmpty())
    }
    @Test
    fun `processSpiritFieldHarvest - 多块田各自独立 roll 每株恰一次`() = runTest {
        val plants = (1..3).map { i ->
            SpiritFieldPlant(buildingInstanceId = "field$i", seedId = "p$i",
                seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        }
        val state = createState(plants = plants, gameYear = 4, gameMonth = 1)
        val rng = mock<DeterministicRng>()
        whenever(rng.nextInt(5)).thenReturn(2)
        val rngManager = mock<GameRngManager>()
        whenever(rngManager.getRng(any())).thenReturn(rng)
        val processor = ProductionProcessor(
            stateStore = mock(), inventorySystem = mock(),
            productionCoordinator = mock(), productionSlotRepository = mock(),
            formulaService = mock(), rngManager = rngManager,
            scopeProvider = mock(), ioDispatcher = IoDispatcher(),
            inventoryConfig = com.xianxia.sect.core.config.InventoryConfig()
        )
        processor.processSpiritFieldHarvest(state)

        // 每株成熟田恰 roll 一次（均匀 0~4 概率实现的来源）；3 株各 2 颗入仓、各续种消耗 1
        verify(rng, times(3)).nextInt(5)
        assertEquals("同种合并单堆叠 = 3×2 - 3×1", 3, state.seeds.all().first().quantity)
        assertEquals("3 块田全部续种", 3,
            state.gameData.spiritFieldPlants.count { it.seedId.isNotEmpty() })
    }
    @Test
    fun `processSpiritFieldHarvest - 种子仓库满溢出转邮件且邮件部分不续种`() = runTest {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: return@runTest
        // 用满 50 个"云雾花种"堆叠占满种子槽位（与收获田非同种 → 无仓库种可续；
        // 溢出转邮件的聚灵草种也不在 seedStore 中 → 田应清空）
        val maxStack = InventoryConfig().getMaxStackSize("seed")
        val fullStacks = (1..50).map { i ->
            Seed(id = "fs$i", slotId = 1, name = "云雾花种", rarity = 1,
                growTime = 36, yield = 4, quantity = maxStack)
        }
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "p1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), seeds = fullStacks, gameYear = 4, gameMonth = 1)
        val inventorySystem = mock<InventorySystem>()
        createProcessor(inventorySystem = inventorySystem, seedRoll = 2).processSpiritFieldHarvest(state)

        verify(inventorySystem).sendOverflowMail("spirit_field", "seed", "聚灵草种", dbSeed.rarity, 2, dbSeed.id)
        assertEquals("溢出的种子不入仓", 50, state.seeds.all().size)
        assertTrue("仓库无聚灵草种", state.seeds.all().none { it.name == "聚灵草种" })
        assertEquals("邮件部分不参与续种 → 田清空", "",
            state.gameData.spiritFieldPlants.first().seedId)
    }
    @Test
    fun `batchSpiritFieldHarvest - 影子读档路径同样收获种子`() = runTest {
        val plant = SpiritFieldPlant(buildingInstanceId = "field1", seedId = "s1",
            seedName = "聚灵草种", growTime = 36, expectedYield = 5, plantYear = 1, plantMonth = 1)
        val state = createState(plants = listOf(plant), gameYear = 4, gameMonth = 1)
        createProcessor(seedRoll = 2).batchSpiritFieldHarvest(mutableListOf(), state)

        assertEquals("2 入仓 - 1 续种消耗 = 1", 1, state.seeds.all().first().quantity)
        assertEquals("田续种", "聚灵草种", state.gameData.spiritFieldPlants.first().seedName)
    }

    // ═══════════════════════════════════════════════════════════════
    // 住所自动入住无视空闲状态 — takeCandidate 支持非 IDLE 候选池
    // ═══════════════════════════════════════════════════════════════
}
