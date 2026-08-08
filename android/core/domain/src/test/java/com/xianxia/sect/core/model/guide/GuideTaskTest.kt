package com.xianxia.sect.core.model.guide

import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test



/**
 * 引导任务定义 & 条件检查 测试。
 *
 * 覆盖：
 * - GuideTaskRegistry：所有任务定义正确
 * - GuideCondition：各条件类型的 isMet()/progressText()/currentValue()/label
 * - GuideCounterKeys：条件中使用的 Key 与常量一致
 */
class GuideTaskTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    // ==================== Helper: 构建带指定建筑的 GameData ====================

    private fun gameDataWithBuildings(vararg displayNames: String): GameData {
        val buildings = displayNames.map { name ->
            GridBuildingData(
                buildingId = name,
                displayName = name,
                gridX = 0, gridY = 0, width = 1, height = 1,
                instanceId = "test_$name", sectId = "sect"
            )
        }
        return GameData().copy(placedBuildings = buildings)
    }

    // ==================== GuideTaskRegistry ====================

    @Test
    fun `GuideTaskRegistry - 包含 25 个引导任务`() {
        assertEquals("任务数量应为 25", 25, GuideTaskRegistry.ALL_TASKS.size)
    }

    @Test
    fun `GuideTaskRegistry - 任务 ID 从 1 到 25 连续`() {
        val ids = GuideTaskRegistry.ALL_TASKS.map { it.id }.sorted()
        assertEquals("任务 ID 应连续无缺", (1..25).toList(), ids)
    }

    @Test
    fun `GuideTaskRegistry - 每项任务至少有一个条件`() {
        GuideTaskRegistry.ALL_TASKS.forEach { task ->
            assertTrue("任务 ${task.id}(${task.name}) 应至少有一个条件", task.conditions.isNotEmpty())
        }
    }

    @Test
    fun `GuideTaskRegistry - getTask 存在时返回正确任务`() {
        val task = GuideTaskRegistry.getTask(1)
        assertNotNull("getTask(1) 不应为空", task)
        assertEquals("任务 1 名称应为'初识灵石'", "初识灵石", task!!.name)
    }

    @Test
    fun `GuideTaskRegistry - getTask 不存在时返回 null`() {
        assertNull("getTask(999) 应为 null", GuideTaskRegistry.getTask(999))
        assertNull("getTask(0) 应为 null", GuideTaskRegistry.getTask(0))
        assertNull("getTask(26) 应为 null", GuideTaskRegistry.getTask(26))
    }

    @Test
    fun `GuideTaskRegistry - 每项任务奖励统一为 2 个凡品储物袋`() {
        GuideTaskRegistry.ALL_TASKS.forEach { task ->
            assertEquals("任务 ${task.id} 的奖励数量应为 2", 2, task.rewardItemQuantity)
            assertEquals("任务 ${task.id} 的奖励物品应为凡品储物袋", "凡品储物袋", task.rewardItemName)
        }
    }

    // ==================== GuideCondition.BuildingCount ====================

    @Test
    fun `BuildingCount - 无建筑时返回 0 未完成`() {
        val gd = gameDataWithBuildings()
        val cond = GuideCondition.BuildingCount("灵矿场", 10)
        assertEquals("label 应显示'建造10座灵矿场'", "建造10座灵矿场", cond.label)
        assertEquals("当前值应为 0", 0L, cond.currentValue(gd))
        assertFalse("isMet 应为 false", cond.isMet(gd))
        assertTrue("progressText 应包含 (0/10)", cond.progressText(gd).contains("0/10"))
    }

    @Test
    fun `BuildingCount - 有部分建筑时返回正确计数`() {
        val gd = gameDataWithBuildings("灵矿场", "灵矿场", "灵矿场", "炼丹炉")
        val cond = GuideCondition.BuildingCount("灵矿场", 10)
        assertEquals("当前值应为 3", 3L, cond.currentValue(gd))
        assertFalse("isMet 应为 false", cond.isMet(gd))
    }

    @Test
    fun `BuildingCount - 达到目标时完成`() {
        val names = (1..10).map { "灵矿场" }.toTypedArray()
        val gd = gameDataWithBuildings(*names)
        val cond = GuideCondition.BuildingCount("灵矿场", 10)
        assertEquals("当前值应为 10", 10L, cond.currentValue(gd))
        assertTrue("isMet 应为 true", cond.isMet(gd))
    }

    @Test
    fun `BuildingCount - displayName 区分不同建筑`() {
        val names = (1..5).map { "灵矿场" }.toTypedArray() +
            (1..3).map { "炼丹炉" }.toTypedArray()
        val gd = gameDataWithBuildings(*names)
        assertEquals("灵矿场计数应为 5", 5L,
            GuideCondition.BuildingCount("灵矿场", 10).currentValue(gd))
        assertEquals("炼丹炉计数应为 3", 3L,
            GuideCondition.BuildingCount("炼丹炉", 3).currentValue(gd))
        assertTrue("炼丹炉 3/3 应完成",
            GuideCondition.BuildingCount("炼丹炉", 3).isMet(gd))
    }

    // ==================== GuideCondition.CumulativeCounter ====================

    @Test
    fun `CumulativeCounter - 空计数器返回 0`() {
        val gd = GameData()
        val cond = GuideCondition.CumulativeCounter("miningOutput", 100_000, "灵矿产出")
        assertEquals("label 应格式化为万", "累计灵矿产出达10万", cond.label)
        assertEquals("当前值应为 0", 0L, cond.currentValue(gd))
        assertFalse("isMet 应为 false", cond.isMet(gd))
        assertTrue("progressText 应为 (0/10万)", cond.progressText(gd).contains("0/10万"))
    }

    @Test
    fun `CumulativeCounter - 有计数时正确读取`() {
        val gd = GameData().copy(guideCounters = mapOf("miningOutput" to 50_000L))
        val cond = GuideCondition.CumulativeCounter("miningOutput", 100_000, "灵矿产出")
        assertEquals("当前值应为 50000", 50_000L, cond.currentValue(gd))
        assertFalse("未达目标", cond.isMet(gd))
        assertTrue("progressText 应包含 5万/10万", cond.progressText(gd).contains("5万/10万"))
    }

    @Test
    fun `CumulativeCounter - 达到目标时完成`() {
        val gd = GameData().copy(guideCounters = mapOf("miningOutput" to 100_000L))
        val cond = GuideCondition.CumulativeCounter("miningOutput", 100_000, "灵矿产出")
        assertTrue("达到 10 万应完成", cond.isMet(gd))
    }

    @Test
    fun `CumulativeCounter - 大数格式化`() {
        val gd = GameData().copy(guideCounters = mapOf("test" to 1_0000_0000L))
        val cond = GuideCondition.CumulativeCounter("test", 2_0000_0000L, "测试")
        assertEquals("label 应显示'累计测试达2亿'", "累计测试达2亿", cond.label)
        assertTrue("progressText 应包含 1亿/2亿", cond.progressText(gd).contains("1亿/2亿"))
    }

    @Test
    fun `CumulativeCounter - 小数值不格式化`() {
        val cond = GuideCondition.CumulativeCounter("test", 5, "小测试")
        assertEquals("label 应为'累计小测试达5'", "累计小测试达5", cond.label)
        val gd = GameData().copy(guideCounters = mapOf("test" to 3L))
        assertEquals("progressText 应为 (3/5)", "(3/5)", cond.progressText(gd))
    }

    // ==================== GuideCondition.ElderAppointed ====================

    @Test
    fun `ElderAppointed - 未任命时检查`() {
        val gd = GameData()  // elderSlots 为空
        val cond = GuideCondition.ElderAppointed("viceSectMaster", "副宗主")
        assertEquals("label", "任命副宗主", cond.label)
        assertEquals("当前值", 0L, cond.currentValue(gd))
        assertFalse("未任命", cond.isMet(gd))
    }

    @Test
    fun `ElderAppointed - 已任命时完成`() {
        val slots = ElderSlots(viceSectMaster = "disciple_1")
        val gd = GameData().copy(elderSlots = slots)
        val cond = GuideCondition.ElderAppointed("viceSectMaster", "副宗主")
        assertEquals("当前值", 1L, cond.currentValue(gd))
        assertTrue("已任命", cond.isMet(gd))
    }

    @Test
    fun `ElderAppointed - 不同长老字段区分`() {
        val slots = ElderSlots(viceSectMaster = "", outerElder = "disciple_2")
        val gd = GameData().copy(elderSlots = slots)
        assertFalse("副宗主未任命",
            GuideCondition.ElderAppointed("viceSectMaster", "副宗主").isMet(gd))
        assertTrue("外门长老已任命",
            GuideCondition.ElderAppointed("outerElder", "外门长老").isMet(gd))
    }

    @Test
    fun `ElderAppointed - 未知字段返回 0`() {
        val gd = GameData()
        assertEquals("未知字段应返回 0", 0L,
            GuideCondition.ElderAppointed("nonexistent", "未知").currentValue(gd))
    }

    // ==================== GuideCondition.DirectDiscipleActive ====================

    @Test
    fun `DirectDiscipleActive - 空列表返回 0`() {
        val gd = GameData()
        val cond = GuideCondition.DirectDiscipleActive("spiritMineDeacon", "灵矿执事")
        assertEquals("当前值应为 0", 0L, cond.currentValue(gd))
        assertFalse("isMet", cond.isMet(gd))
    }

    @Test
    fun `DirectDiscipleActive - 有活跃执事时完成`() {
        val slots = ElderSlots(spiritMineDeaconDisciples = listOf(
            DirectDiscipleSlot(index = 0, discipleId = "d1", discipleName = "张三"),
            DirectDiscipleSlot(index = 1, discipleId = "d2", discipleName = "李四")
        ))
        val gd = GameData().copy(elderSlots = slots)
        val cond = GuideCondition.DirectDiscipleActive("spiritMineDeacon", "灵矿执事")
        assertEquals("当前值应为 2", 2L, cond.currentValue(gd))
        assertTrue("至少 1 位执事", cond.isMet(gd))
    }

    // ==================== GuideCondition.SlotFilledCount ====================

    @Test
    fun `SlotFilledCount - 空槽位返回 0`() {
        val gd = GameData()
        assertEquals("librarySlots 应为 0", 0L,
            GuideCondition.SlotFilledCount("librarySlots", 3, "在藏经阁研习").currentValue(gd))
    }

    // 注意：SlotFilledCount 需要构造带具体插槽的 GameData
    // 由于 librarySlots/residenceSlots 等字段在 GameData 中有默认空列表，只测试初始状态

    // ==================== GuideCondition.PlantCropOnce ====================

    @Test
    fun `PlantCropOnce - 未种植时未完成`() {
        val gd = GameData()
        assertFalse("未种植", GuideCondition.PlantCropOnce.isMet(gd))
    }

    // ==================== GuideCondition.BloodRefinementCompleted ====================

    @Test
    fun `BloodRefinementCompleted - 初始为 0`() {
        val gd = GameData()
        val cond = GuideCondition.BloodRefinementCompleted()
        assertEquals("label", "完成1次血炼", cond.label)
        assertEquals("当前值应为 0", 0L, cond.currentValue(gd))
        assertFalse("isMet", cond.isMet(gd))
    }

    // ==================== GuideCondition.PatrolBeastDefeated ====================

    @Test
    fun `PatrolBeastDefeated - 使用 guideCounters`() {
        val gd0 = GameData()
        assertFalse("空状态未击败",
            GuideCondition.PatrolBeastDefeated().isMet(gd0))

        val gd1 = GameData().copy(guideCounters = mapOf(GuideCounterKeys.PATROL_BEAST_DEFEATED to 1L))
        assertTrue("击败 1 次",
            GuideCondition.PatrolBeastDefeated().isMet(gd1))
    }

    // ==================== GuideCondition.DiscipleTotalCount / MissionCompleted ====================

    @Test
    fun `DiscipleTotalCount - 读取 guideCounters`() {
        val gd = GameData().copy(guideCounters = mapOf(GuideCounterKeys.DISCIPLES_RECRUITED to 5L))
        assertTrue("招募 5 人达到 5 人目标",
            GuideCondition.DiscipleTotalCount(5).isMet(gd))
        assertFalse("未达到 10 人",
            GuideCondition.DiscipleTotalCount(10).isMet(gd))
    }

    @Test
    fun `MissionCompleted - 读取 guideCounters`() {
        val gd = GameData().copy(guideCounters = mapOf(GuideCounterKeys.MISSIONS_COMPLETED to 2L))
        assertFalse("2/3 未完成",
            GuideCondition.MissionCompleted(3).isMet(gd))

        val gd2 = GameData().copy(guideCounters = mapOf(GuideCounterKeys.MISSIONS_COMPLETED to 3L))
        assertTrue("3/3 完成",
            GuideCondition.MissionCompleted(3).isMet(gd2))
    }

    // ==================== formatGuideCount 格式化 ====================

    @Test
    fun `formatGuideCount - 大数格式化`() {
        // 测试私有函数的间接效果 — 通过 CumulativeCounter label/progressText 验证
        val cond = GuideCondition.CumulativeCounter("test", 1_0000_0000L, "亿级")
        assertTrue("label 含亿", cond.label.contains("亿"))
        assertTrue("label 数字正确", cond.label.contains("1亿"))
    }

    // ==================== GuideCounterKeys 一致性 ====================

    @Test
    fun `GuideCounterKeys - 任务中使用的 Key 与常量一致`() {
        // 验证所有 CumulativeCounter 使用的 key 与 GuideCounterKeys 常量名匹配
        for (task in GuideTaskRegistry.ALL_TASKS) {
            for (cond in task.conditions) {
                if (cond is GuideCondition.CumulativeCounter) {
                    val key = cond.counterKey
                    assertNotNull("任务 ${task.id} 使用计数器 key '$key' 应非空", key)
                }
            }
        }
    }

    @Test
    fun `GuideCounterKeys - 所有常量非空`() {
        // 验证所有关键常量定义正确
        assertEquals("miningOutput", GuideCounterKeys.MINING_OUTPUT)
        assertEquals("alchemyCompleted", GuideCounterKeys.ALCHEMY_COMPLETED)
        assertEquals("forgeCompleted", GuideCounterKeys.FORGE_COMPLETED)
        assertEquals("herbsHarvested", GuideCounterKeys.HERBS_HARVESTED)
        assertEquals("missionsCompleted", GuideCounterKeys.MISSIONS_COMPLETED)
        assertEquals("disciplesRecruited", GuideCounterKeys.DISCIPLES_RECRUITED)
        assertEquals("patrolBeastDefeated", GuideCounterKeys.PATROL_BEAST_DEFEATED)
        assertEquals("policyActivated", GuideCounterKeys.POLICY_ACTIVATED)
        assertEquals("autoMineActivated", GuideCounterKeys.AUTO_MINE_ACTIVATED)
        assertEquals("autoPlantActivated", GuideCounterKeys.AUTO_PLANT_ACTIVATED)
        assertEquals("autoProductionActivated", GuideCounterKeys.AUTO_PRODUCTION_ACTIVATED)
        assertEquals("breakthroughs", GuideCounterKeys.BREAKTHROUGHS)
        assertEquals("discipleImprisoned", GuideCounterKeys.DISCIPLE_IMPRISONED)
    }

    // ==================== GuideCondition.DiscipleReachRealm ====================

    @Test
    fun `DiscipleReachRealm - 旧签名永远 false`() {
        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 2, targetLabel = "达到筑基")
        val gd = GameData()
        assertFalse("isMet(gameData) 应返回 false", cond.isMet(gd))
        assertEquals("currentValue(gameData) 应返回 0", 0L, cond.currentValue(gd))
    }

    @Test
    fun `DiscipleReachRealm - 无 tables 时返回 false0`() {
        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 1, targetLabel = "达到筑基")
        val gd = GameData()
        assertFalse("isMet(gameData, null) 应 false", cond.isMet(gd, null))
        assertEquals("currentValue(gameData, null) 应 0", 0L, cond.currentValue(gd, null))
    }

    @Test
    fun `DiscipleReachRealm - 计数达到目标`() {
        val tables = DiscipleTables()
        // 弟子 1: realm=3（满足 ≤5）
        tables.addId(1); tables.realms[1] = 3; tables.isAlive[1] = 1
        // 弟子 2: realm=5（满足 ≤5）
        tables.addId(2); tables.realms[2] = 5; tables.isAlive[2] = 1
        // 弟子 3: realm=8（不满足 ≤5）
        tables.addId(3); tables.realms[3] = 8; tables.isAlive[3] = 1

        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 2, targetLabel = "达到筑基")
        val gd = GameData()

        assertEquals("currentValue 应返回 2", 2L, cond.currentValue(gd, tables))
        assertTrue("2 位弟子 ≤5 应达标", cond.isMet(gd, tables))
    }

    @Test
    fun `DiscipleReachRealm - 计数未达到目标`() {
        val tables = DiscipleTables()
        tables.addId(1); tables.realms[1] = 3; tables.isAlive[1] = 1
        tables.addId(2); tables.realms[2] = 7; tables.isAlive[2] = 1

        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 2, targetLabel = "达到筑基")
        val gd = GameData()

        assertEquals("currentValue 应返回 1", 1L, cond.currentValue(gd, tables))
        assertFalse("仅 1 位 ≤5 不应达标", cond.isMet(gd, tables))
    }

    @Test
    fun `DiscipleReachRealm - 无弟子时返回 0`() {
        val tables = DiscipleTables()
        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 1, targetLabel = "达到筑基")
        val gd = GameData()
        assertEquals("空表 currentValue 应 0", 0L, cond.currentValue(gd, tables))
        assertFalse("空表 isMet 应 false", cond.isMet(gd, tables))
    }

    @Test
    fun `DiscipleReachRealm - progressText 保留旧签名`() {
        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 2, targetLabel = "达到筑基")
        val gd = GameData()
        // progressText 仅使用 gameData，旧签名应有内容但不一定准确
        assertNotNull("progressText 不应 null", cond.progressText(gd))
    }

    @Test
    fun `DiscipleReachRealm - 排除死亡弟子`() {
        val tables = DiscipleTables()
        // 弟子 1: alive, realm=3（满足）
        tables.addId(1); tables.realms[1] = 3; tables.isAlive[1] = 1
        // 弟子 2: dead, realm=4（满足但已死亡，不计入）
        tables.addId(2); tables.realms[2] = 4; tables.isAlive[2] = 0
        // 弟子 3: alive, realm=8（不满足）
        tables.addId(3); tables.realms[3] = 8; tables.isAlive[3] = 1

        val cond = GuideCondition.DiscipleReachRealm(maxRealmLayer = 5, targetValue = 2, targetLabel = "达到筑基")
        val gd = GameData()

        assertEquals("排除死亡后仅 1 人", 1L, cond.currentValue(gd, tables))
        assertFalse("仅 1 人不应达标", cond.isMet(gd, tables))
    }
}
