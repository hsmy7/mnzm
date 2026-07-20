package com.xianxia.sect.ui.game.saveload

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.config.BuildingConfigModel
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.core.engine.domain.save.SavePipeline
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 建筑占地×2 旧存档溢出迁移测试。
 *
 * 覆盖 [SaveLoadLoadDelegate.computeBuildingOverflowMigration] 的核心逻辑：
 * - 边界越界 → 拆除 + 全额退款
 * - 重叠 → 造价低的被拆除
 * - 灵田优先保留（尺寸不变，最高优先级）
 * - 造价高的优先保留
 * - 无溢出时全部保留
 */
class BuildingOverflowMigrationTest {

    private lateinit var buildingConfigService: BuildingConfigService
    private lateinit var delegate: SaveLoadLoadDelegate

    @Before
    fun setup() {
        buildingConfigService = mockk(relaxed = true)

        // 建筑造价 stub
        every { buildingConfigService.getBuildingConfigByDisplayName("灵田") } returns
            BuildingConfigModel(id = "spirit_field", displayName = "灵田",
                buildingType = "SPIRIT_FIELD", cost = 200, gridWidth = 1, gridHeight = 1)
        every { buildingConfigService.getBuildingConfigByDisplayName("灵矿场") } returns
            BuildingConfigModel(id = "mining", displayName = "灵矿场",
                buildingType = "MINING", cost = 1500, gridWidth = 4, gridHeight = 4)
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 4000, gridWidth = 4, gridHeight = 4)
        every { buildingConfigService.getBuildingConfigByDisplayName("天枢殿") } returns
            BuildingConfigModel(id = "tianshu_hall", displayName = "天枢殿",
                buildingType = "ADMINISTRATION", cost = 15000, gridWidth = 6, gridHeight = 4)
        every { buildingConfigService.getBuildingConfigByDisplayName("未知建筑") } returns null

        delegate = SaveLoadLoadDelegate(
            gameEngine = mockk(),
            gameEngineCore = mockk(),
            storageFacade = mockk(),
            stateStore = mockk(),
            savePipeline = mockk(),
            buildingConfigService = buildingConfigService,
            spiritStoneWallet = mockk()
        )
    }

    // ── 辅助方法 ──

    private fun b(name: String, gx: Int, gy: Int, w: Int, h: Int,
                  instanceId: String = "id_$name"): GridBuildingData =
        GridBuildingData(displayName = name, gridX = gx, gridY = gy,
            width = w, height = h, instanceId = instanceId)

    private fun migrate(
        buildings: List<GridBuildingData>,
        spiritStones: Long = 0
    ): SaveLoadLoadDelegate.MigrationResult {
        val gd = GameData(placedBuildings = buildings, spiritStones = spiritStones)
        return delegate.computeBuildingOverflowMigration(buildings, gd, buildingConfigService)
    }

    // ================================================================
    // 正常路径
    // ================================================================

    @Test
    fun `no overflow all buildings kept`() {
        // 两个 4×4 建筑平铺放置，48×48 网格绰绰有余
        val buildings = listOf(
            b("灵矿场", 0, 0, 4, 4),
            b("炼丹炉", 4, 0, 4, 4),
        )
        val result = migrate(buildings)
        assertEquals("无溢出时全部保留", 2, result.kept.size)
        assertEquals("无溢出时不移除", 0, result.demolished.size)
        assertEquals("无溢出时无退款", 0L, result.totalRefund)
    }

    @Test
    fun `empty building list returns empty results`() {
        val result = migrate(emptyList())
        assertEquals("空列表", 0, result.kept.size)
        assertEquals("空列表", 0, result.demolished.size)
    }

    // ================================================================
    // 越界检测
    // ================================================================

    @Test
    fun `building out of bounds is demolished with full refund`() {
        // 6×4 建筑放在 (125, 0)，125+6=131>128 → 越界
        val result = migrate(listOf(b("天枢殿", 125, 0, 6, 4)))
        assertEquals("越界建筑被拆除", 0, result.kept.size)
        assertEquals(1, result.demolished.size)
        assertEquals("造价全额返还", 15000L, result.totalRefund)
    }

    @Test
    fun `negative position building is demolished`() {
        val result = migrate(listOf(b("灵矿场", -2, 0, 4, 4)))
        assertEquals("负坐标建筑被拆除", 1, result.demolished.size)
    }

    @Test
    fun `building exceeding bottom edge is demolished`() {
        // 4×4 放在 (0, 125)，125+4=129>128 → 越界
        val result = migrate(listOf(b("灵矿场", 0, 125, 4, 4)))
        assertEquals("下越界", 1, result.demolished.size)
    }

    // ================================================================
    // 重叠检测
    // ================================================================

    @Test
    fun `overlapping building with lower cost is demolished`() {
        // 两个建筑重叠在 4×4 区域
        val buildings = listOf(
            b("灵矿场", 0, 0, 4, 4),         // cost=1500
            b("炼丹炉", 2, 2, 4, 4),         // cost=4000，瓦重叠
        )
        val result = migrate(buildings)
        assertEquals(1, result.kept.size)
        assertEquals(1, result.demolished.size)
        assertEquals("造价低的灵矿场(1500)被拆除",
            "灵矿场", result.demolished[0].displayName)
        assertEquals("返还灵矿场造价", 1500L, result.totalRefund)
    }

    // ================================================================
    // 优先级：灵田 > 高造价
    // ================================================================

    @Test
    fun `spirit field has highest priority regardless of cost`() {
        val buildings = listOf(
            b("灵矿场", 0, 0, 4, 4),        // 位置与灵田重叠（共享 (0,0) 格）
            b("灵田", 0, 0, 1, 1),           // 1×1，不受 ×2 影响
        )
        val result = migrate(buildings)
        assertEquals("灵田优先保留", 1, result.kept.size)
        assertEquals("灵田被保留", "灵田", result.kept[0].displayName)
        assertEquals("灵矿场被拆除", 1, result.demolished.size)
    }

    @Test
    fun `expensive building kept over cheap one`() {
        val buildings = listOf(
            b("灵矿场", 0, 0, 4, 4),
            b("天枢殿", 3, 3, 6, 4),
        )
        val result = migrate(buildings)
        assertEquals(1, result.kept.size)
        assertEquals("天枢殿(15000)造价更高保留", "天枢殿", result.kept[0].displayName)
        assertEquals("灵矿场(1500)被拆除", "灵矿场", result.demolished[0].displayName)
        assertEquals(1500L, result.totalRefund)
    }

    // ================================================================
    // 混合场景
    // ================================================================

    @Test
    fun `mixed overflow and kept buildings`() {
        val buildings = listOf(
            b("灵田", 0, 0, 1, 1),
            b("灵矿场", 2, 0, 4, 4),
            b("炼丹炉", 6, 0, 4, 4),
            b("天枢殿", 0, 5, 6, 4),
            b("灵矿场", 0, 0, 4, 4),         // 与灵田重叠 → 拆除
        )
        val result = migrate(buildings)
        assertEquals(4, result.kept.size)
        assertEquals(1, result.demolished.size)
        assertEquals("拆除的与灵田重叠", "灵矿场", result.demolished[0].displayName)
        assertEquals(1500L, result.totalRefund)
    }

    @Test
    fun `unknown building with default cost`() {
        val result = migrate(listOf(b("未知建筑", -1, 0, 4, 4)))
        assertEquals("默认造价 1000", 1000L, result.totalRefund)
    }

    // ================================================================
    // 精灵图保存
    // ================================================================

    @Test
    fun `existing spirit stones unchanged when no demolition`() {
        val buildings = listOf(b("灵矿场", 0, 0, 4, 4))
        val result = migrate(buildings, spiritStones = 9999)
        assertEquals(0, result.demolished.size)
        assertEquals(0L, result.totalRefund)
        // 注意：totalRefund 是 refund 金额，不是最终灵石数
    }

    // ================================================================
    // 跨宗门 sectId 分组检测
    // computeBuildingOverflowMigration 是纯函数，按给定列表检测。
    // 分组逻辑在 migrateOverflowBuildings() 中实现。
    // 以下测试模拟分组行为：按 sectId 分组后分别调用纯函数，再汇总结果。
    // ================================================================

    /** 模拟 migrateOverflowBuildings 的分组逻辑：按 sectId 分组后分别检测 */
    private fun migrateGrouped(
        buildings: List<GridBuildingData>,
        spiritStones: Long = 0
    ): SaveLoadLoadDelegate.MigrationResult {
        val groups = buildings.groupBy { it.sectId }
        val gd = GameData(placedBuildings = buildings, spiritStones = spiritStones)
        val allKept = mutableListOf<GridBuildingData>()
        val allDemolished = mutableListOf<GridBuildingData>()
        var totalRefund = 0L
        val allFreed = mutableSetOf<String>()
        for ((_, sectBuildings) in groups) {
            val r = delegate.computeBuildingOverflowMigration(sectBuildings, gd, buildingConfigService)
            allKept.addAll(r.kept)
            allDemolished.addAll(r.demolished)
            totalRefund += r.totalRefund
            allFreed.addAll(r.freedDiscipleIds)
        }
        return SaveLoadLoadDelegate.MigrationResult(allKept, allDemolished, totalRefund, allFreed)
    }

    @Test
    fun `buildings from different sectIds do not conflict`() {
        // 两个建筑使用相同坐标但不同 sectId，分组后各自独立检测 → 不冲突
        val buildings = listOf(
            b("灵矿场", 0, 0, 4, 4, instanceId = "id_1").copy(sectId = ""),
            b("炼丹炉", 0, 0, 4, 4, instanceId = "id_2").copy(sectId = "sect_conquered"),
        )
        val result = migrateGrouped(buildings)
        assertEquals("不同宗门的建筑使用同坐标不冲突，全部保留", 2, result.kept.size)
        assertEquals("无拆除", 0, result.demolished.size)
    }

    @Test
    fun `same sectId buildings still conflict detection works`() {
        // 相同 sectId 的建筑仍应被正确检测为重叠
        val buildings = listOf(
            b("灵矿场", 0, 0, 4, 4, instanceId = "id_1").copy(sectId = "sect_a"),
            b("炼丹炉", 2, 2, 4, 4, instanceId = "id_2").copy(sectId = "sect_a"),
        )
        val result = migrateGrouped(buildings)
        assertEquals(1, result.kept.size)
        assertEquals(1, result.demolished.size)
        assertEquals("同宗门内仍造价低的被拆除", "灵矿场", result.demolished[0].displayName)
    }

    @Test
    fun `multiple sects with independent occupied sets`() {
        // 三个宗门各自内部有重叠，但跨宗门坐标相同不应相互影响
        val buildings = listOf(
            // main sect (sectId="")
            b("灵田", 0, 0, 1, 1, instanceId = "id_main_1"),
            b("灵矿场", 0, 0, 4, 4, instanceId = "id_main_2"),  // 与灵田重叠→拆除
            // conquered sect A
            b("炼丹炉", 0, 0, 4, 4, instanceId = "id_a_1").copy(sectId = "sect_a"),
            // conquered sect B
            b("灵矿场", 0, 0, 4, 4, instanceId = "id_b_1").copy(sectId = "sect_b"),
            b("天枢殿", 0, 0, 6, 4, instanceId = "id_b_2").copy(sectId = "sect_b"),  // 重叠→保留高造价
        )
        val result = migrateGrouped(buildings)
        assertEquals("3宗共5栋: 主营保留灵田=1, sect_a保留炼丹炉=1, sect_b保留天枢殿=1 → 3栋保留",
            3, result.kept.size)
        assertEquals("2栋被拆除: 主营灵矿场 + sect_b灵矿场", 2, result.demolished.size)
    }
}
