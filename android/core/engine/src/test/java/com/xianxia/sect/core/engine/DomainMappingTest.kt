package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.FocusDomain
import org.junit.Assert.*
import org.junit.Test

/**
 * 焦点域判定映射测试 — 覆盖所有 Tab/Dialog/焦点弟子的组合。
 *
 * 判定标准：视角驱动 — 当前界面所在域即为焦点域。
 * - 焦点域 → 对应 FocusDomain 加入 activeDomains，100ms 高频结算
 * - 非当前视角的域 → 仅 ALWAYS，30 秒批量结算
 */
class DomainMappingTest {

    // ── 辅助方法 ──

    private fun resolve(
        tab: String? = null,
        dialog: String? = null
    ): Set<FocusDomain> = resolveDomainsFromView(tab, dialog)

    private fun assertDomains(
        actual: Set<FocusDomain>,
        vararg expected: FocusDomain
    ) {
        assertEquals(expected.toSet() + FocusDomain.ALWAYS, actual)
    }

    // ═══════════════════════════════════════════════════════════════
    // Tab 映射
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `tab OVERVIEW — DISCIPLES + BUILDINGS + WAREHOUSE（宗门地图显示灵石+弟子数）`() {
        val domains = resolve(tab = "OVERVIEW")
        assertDomains(domains, FocusDomain.DISCIPLES, FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE)
    }

    @Test
    fun `tab DISCIPLES — DISCIPLES（弟子卡片显示境界）`() {
        val domains = resolve(tab = "DISCIPLES")
        assertDomains(domains, FocusDomain.DISCIPLES)
    }

    @Test
    fun `tab BUILDINGS — BUILDINGS（建筑管理）`() {
        val domains = resolve(tab = "BUILDINGS")
        assertDomains(domains, FocusDomain.BUILDINGS)
    }

    @Test
    fun `tab WAREHOUSE — BUILDINGS + WAREHOUSE（显示灵石+物品数量）`() {
        val domains = resolve(tab = "WAREHOUSE")
        assertDomains(domains, FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE)
    }

    @Test
    fun `tab SETTINGS — 仅 ALWAYS（无生产信息）`() {
        val domains = resolve(tab = "SETTINGS")
        assertDomains(domains)
    }

    @Test
    fun `tab null — 仅 ALWAYS`() {
        val domains = resolve(tab = null)
        assertDomains(domains)
    }

    @Test
    fun `tab 未知值 — 仅 ALWAYS`() {
        val domains = resolve(tab = "UNKNOWN_TAB")
        assertDomains(domains)
    }

    // ═══════════════════════════════════════════════════════════════
    // 焦点弟子
    // ═══════════════════════════════════════════════════════════════


    @Test
    fun `SETTINGS tab — 仅 ALWAYS`() {
        val domains = resolve(tab = "SETTINGS")
        assertDomains(domains)
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：生产建筑
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog Alchemy — ALCHEMY + BUILDINGS（显示炼制进度条）`() {
        assertDomains(resolve(dialog = "Alchemy"), FocusDomain.ALCHEMY, FocusDomain.BUILDINGS)
    }

    @Test
    fun `dialog Forge — FORGE + BUILDINGS（显示锻造进度条）`() {
        assertDomains(resolve(dialog = "Forge"), FocusDomain.FORGE, FocusDomain.BUILDINGS)
    }

    @Test
    fun `dialog HerbGarden — HERB_GARDEN + BUILDINGS（灵植阁生产）`() {
        assertDomains(resolve(dialog = "HerbGarden"), FocusDomain.HERB_GARDEN, FocusDomain.BUILDINGS)
    }

    @Test
    fun `dialog SpiritMine — SPIRIT_MINE + BUILDINGS（灵矿场生产）`() {
        assertDomains(resolve(dialog = "SpiritMine"), FocusDomain.SPIRIT_MINE, FocusDomain.BUILDINGS)
    }

    @Test
    fun `dialog Planting — PLANTING + BUILDINGS（灵田种植生产）`() {
        assertDomains(resolve(dialog = "Planting"), FocusDomain.PLANTING, FocusDomain.BUILDINGS)
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：仓库/商人/交易
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog Warehouse — BUILDINGS + WAREHOUSE（显示灵石+物品）`() {
        assertDomains(
            resolve(dialog = "Warehouse"),
            FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE
        )
    }

    @Test
    fun `dialog Merchant — BUILDINGS + WAREHOUSE（显示灵石余额+仓库存量）`() {
        assertDomains(
            resolve(dialog = "Merchant"),
            FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE
        )
    }

    @Test
    fun `dialog SectTrade — BUILDINGS + WAREHOUSE（显示灵石+交易物品）`() {
        assertDomains(
            resolve(dialog = "SectTrade"),
            FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：任务阁
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog MissionHall — EXPLORATION + DISCIPLES（显示任务进度+弟子HP）`() {
        assertDomains(
            resolve(dialog = "MissionHall"),
            FocusDomain.EXPLORATION, FocusDomain.DISCIPLES
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：血炼池
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog BloodRefiningPool — DISCIPLES + BLOOD_REFINING（显示血炼进度）`() {
        assertDomains(
            resolve(dialog = "BloodRefiningPool"),
            FocusDomain.DISCIPLES, FocusDomain.BLOOD_REFINING
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：世界地图
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog WorldMap — WORLD_MAP（地图标记、探索状态）`() {
        assertDomains(resolve(dialog = "WorldMap"), FocusDomain.WORLD_MAP)
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：外交
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog Diplomacy — DIPLOMACY（好感度、关系变化）`() {
        assertDomains(resolve(dialog = "Diplomacy"), FocusDomain.DIPLOMACY)
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 仅 ALWAYS（无实时变化数据）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog Mail — 仅 ALWAYS（一次性领取）`() {
        assertDomains(resolve(dialog = "Mail"))
    }

    @Test
    fun `dialog Activity — 仅 ALWAYS（活动列表）`() {
        assertDomains(resolve(dialog = "Activity"))
    }

    @Test
    fun `dialog TianshuHall — 仅 ALWAYS（长老任命无实时灵石显示）`() {
        assertDomains(resolve(dialog = "TianshuHall"))
    }

    @Test
    fun `dialog SectLevelDetail — 仅 ALWAYS（升级条件无进度条）`() {
        assertDomains(resolve(dialog = "SectLevelDetail"))
    }

    @Test
    fun `dialog PatrolTower — 仅 ALWAYS（护法槽位配置）`() {
        assertDomains(resolve(dialog = "PatrolTower"))
    }

    @Test
    fun `dialog Recruit — 仅 ALWAYS（招募弟子数据固定）`() {
        assertDomains(resolve(dialog = "Recruit"))
    }

    @Test
    fun `dialog Residence — 仅 ALWAYS（住所管理）`() {
        assertDomains(resolve(dialog = "Residence"))
    }

    @Test
    fun `dialog Library — 仅 ALWAYS（藏经阁槽位）`() {
        assertDomains(resolve(dialog = "Library"))
    }

    @Test
    fun `dialog WenDaoPeak — 仅 ALWAYS（外门弟子管理）`() {
        assertDomains(resolve(dialog = "WenDaoPeak"))
    }

    @Test
    fun `dialog QingyunPeak — 仅 ALWAYS（内门弟子管理）`() {
        assertDomains(resolve(dialog = "QingyunPeak"))
    }

    @Test
    fun `dialog LawEnforcementHall — 仅 ALWAYS（纪律管理）`() {
        assertDomains(resolve(dialog = "LawEnforcementHall"))
    }

    @Test
    fun `dialog ReflectionCliff — 仅 ALWAYS（思过崖管理）`() {
        assertDomains(resolve(dialog = "ReflectionCliff"))
    }

    @Test
    fun `dialog BattleLog — 仅 ALWAYS（历史记录）`() {
        assertDomains(resolve(dialog = "BattleLog"))
    }

    @Test
    fun `dialog WarehouseBuilding — 仅 ALWAYS（仓库容量）`() {
        assertDomains(resolve(dialog = "WarehouseBuilding"))
    }

    @Test
    fun `dialog 未知值 — 仅 ALWAYS`() {
        assertDomains(resolve(dialog = "UNKNOWN_DIALOG"))
    }

    @Test
    fun `dialog null — 仅 ALWAYS`() {
        assertDomains(resolve(dialog = null))
    }

    // ═══════════════════════════════════════════════════════════════
    // 组合场景
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `组合：OVERVIEW tab + Merchant dialog — 三域叠加`() {
        val domains = resolve(tab = "OVERVIEW", dialog = "Merchant")
        assertDomains(
            domains,
            FocusDomain.DISCIPLES, FocusDomain.BUILDINGS, FocusDomain.WAREHOUSE
        )
    }

    @Test
    fun `组合：SETTINGS tab + Alchemy dialog — 仅 BUILDINGS`() {
        val domains = resolve(tab = "SETTINGS", dialog = "Alchemy")
        assertDomains(domains, FocusDomain.ALCHEMY, FocusDomain.BUILDINGS)
    }

    @Test
    fun `组合：SETTINGS tab + Diplomacy dialog — DIPLOMACY`() {
        val domains = resolve(tab = "SETTINGS", dialog = "Diplomacy")
        assertDomains(domains, FocusDomain.DIPLOMACY)
    }

    @Test
    fun `组合：SETTINGS tab + Diplomacy dialog — DIPLOMACY（无焦点弟子追加）`() {
        val domains = resolve(tab = "SETTINGS", dialog = "Diplomacy")
        assertDomains(domains, FocusDomain.DIPLOMACY)
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界条件
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `ALWAYS 始终在结果中`() {
        // 任意组合都应包含 ALWAYS
        assertTrue(resolve(tab = null, dialog = null)
            .contains(FocusDomain.ALWAYS))
        assertTrue(resolve(tab = "OVERVIEW", dialog = "Alchemy")
            .contains(FocusDomain.ALWAYS))
    }

    @Test
    fun `焦点域 dialog 全部正确映射`() {
        val focusDialogs = listOf(
            "Alchemy", "Forge", "HerbGarden", "SpiritMine", "Planting",
            "Warehouse", "Merchant", "SectTrade",
            "MissionHall",
            "BloodRefiningPool",
            "WorldMap", "Diplomacy"
        )
        for (d in focusDialogs) {
            val domains = resolve(dialog = d)
            assertTrue(
                "$d 应为焦点域，至少包含一个非 ALWAYS 域",
                domains.size > 1
            )
        }
    }

    @Test
    fun `仅 ALWAYS 的 dialog 正确映射`() {
        val nonFocusDialogs = listOf(
            "Mail", "Activity",
            "TianshuHall", "SectLevelDetail",
            "PatrolTower", "Recruit", "Residence", "Library",
            "WenDaoPeak", "QingyunPeak",
            "LawEnforcementHall", "ReflectionCliff",
            "BattleLog", "WarehouseBuilding"
        )
        for (d in nonFocusDialogs) {
            val domains = resolve(dialog = d)
            assertEquals(
                "$d 应为非焦点域，仅含 ALWAYS",
                setOf(FocusDomain.ALWAYS), domains
            )
        }
    }
}
