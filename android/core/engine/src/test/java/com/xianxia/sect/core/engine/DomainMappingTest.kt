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
    fun `tab OVERVIEW — OVERVIEW（宗门地图显示灵石+弟子数+建筑）`() {
        val domains = resolve(tab = "OVERVIEW")
        assertDomains(domains, FocusDomain.OVERVIEW)
    }

    @Test
    fun `tab DISCIPLES — DISCIPLE_LIST（弟子卡片显示境界）`() {
        val domains = resolve(tab = "DISCIPLES")
        assertDomains(domains, FocusDomain.DISCIPLE_LIST)
    }

    @Test
    fun `tab BUILDINGS — BUILDING_LIST（建筑管理）`() {
        val domains = resolve(tab = "BUILDINGS")
        assertDomains(domains, FocusDomain.BUILDING_LIST)
    }

    @Test
    fun `tab WAREHOUSE — WAREHOUSE_TAB（显示灵石+物品数量）`() {
        val domains = resolve(tab = "WAREHOUSE")
        assertDomains(domains, FocusDomain.WAREHOUSE_TAB)
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
    fun `dialog Alchemy — ALCHEMY（显示炼制进度条）`() {
        assertDomains(resolve(dialog = "Alchemy"), FocusDomain.ALCHEMY)
    }

    @Test
    fun `dialog Forge — FORGE（显示锻造进度条）`() {
        assertDomains(resolve(dialog = "Forge"), FocusDomain.FORGE)
    }

    @Test
    fun `dialog HerbGarden — HERB_GARDEN（灵植阁生产）`() {
        assertDomains(resolve(dialog = "HerbGarden"), FocusDomain.HERB_GARDEN)
    }

    @Test
    fun `dialog SpiritMine — SPIRIT_MINE（灵矿界面映射，产出走月度结算）`() {
        assertDomains(resolve(dialog = "SpiritMine"), FocusDomain.SPIRIT_MINE)
    }

    @Test
    fun `dialog Planting — PLANTING（灵田种植生产）`() {
        assertDomains(resolve(dialog = "Planting"), FocusDomain.PLANTING)
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：仓库/商人/交易
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog Warehouse — WAREHOUSE_DIALOG（显示灵石+物品）`() {
        assertDomains(
            resolve(dialog = "Warehouse"),
            FocusDomain.WAREHOUSE_DIALOG
        )
    }

    @Test
    fun `dialog Merchant — MERCHANT（显示灵石余额+仓库存量）`() {
        assertDomains(
            resolve(dialog = "Merchant"),
            FocusDomain.MERCHANT
        )
    }

    @Test
    fun `dialog SectTrade — SECT_TRADE（显示灵石+交易物品）`() {
        assertDomains(
            resolve(dialog = "SectTrade"),
            FocusDomain.SECT_TRADE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：任务阁
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog MissionHall — MISSION_HALL（显示任务进度+弟子HP）`() {
        assertDomains(
            resolve(dialog = "MissionHall"),
            FocusDomain.MISSION_HALL
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框 — 焦点域：血炼池
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dialog BloodRefiningPool — BLOOD_REFINING（显示血炼进度）`() {
        assertDomains(
            resolve(dialog = "BloodRefiningPool"),
            FocusDomain.BLOOD_REFINING
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
    fun `组合：OVERVIEW tab + Merchant dialog — 两域叠加`() {
        val domains = resolve(tab = "OVERVIEW", dialog = "Merchant")
        assertDomains(
            domains,
            FocusDomain.OVERVIEW, FocusDomain.MERCHANT
        )
    }

    @Test
    fun `组合：SETTINGS tab + Alchemy dialog — ALCHEMY 单独`() {
        val domains = resolve(tab = "SETTINGS", dialog = "Alchemy")
        assertDomains(domains, FocusDomain.ALCHEMY)
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

    // ═══════════════════════════════════════════════════════════════
    // FocusDomain 反向声明：activeSystemsFor / assignedDomainFor
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `activeSystemsFor — OVERVIEW 域返回完整系统集合`() {
        val systems = FocusDomain.activeSystemsFor(setOf(FocusDomain.OVERVIEW))
        assertTrue(
            "OVERVIEW 应激活 CultivationTickSystem",
            systems.any { it.simpleName == "CultivationTickSystem" }
        )
        assertTrue(
            "OVERVIEW 应激活 EconomySubsystem",
            systems.any { it.simpleName == "EconomySubsystem" }
        )
    }

    @Test
    fun `activeSystemsFor — 空域集返回空系统集`() {
        val systems = FocusDomain.activeSystemsFor(emptySet())
        assertTrue("空域集应返回空系统集", systems.isEmpty())
    }

    @Test
    fun `activeSystemsFor — 多域叠加去重`() {
        // DISCIPLE_LIST 和 MISSION_HALL 都声明 CultivationTickSystem
        val systems = FocusDomain.activeSystemsFor(
            setOf(FocusDomain.DISCIPLE_LIST, FocusDomain.MISSION_HALL)
        )
        val cultivationCount = systems.count { it.simpleName == "CultivationTickSystem" }
        assertEquals("重复系统应去重", 1, cultivationCount)
    }

    @Test
    fun `assignedDomainFor — 活跃域优先`() {
        val domain = FocusDomain.assignedDomainFor(
            com.xianxia.sect.core.engine.system.building.AlchemySystem::class,
            setOf(FocusDomain.ALCHEMY)
        )
        assertEquals("应返回活跃域 ALCHEMY", FocusDomain.ALCHEMY, domain)
    }

    @Test
    fun `assignedDomainFor — 系统不在活跃域时返回其首个域`() {
        // CultivationTickSystem 声明在 OVERVIEW, DISCIPLE_LIST, MISSION_HALL
        val domain = FocusDomain.assignedDomainFor(
            com.xianxia.sect.core.engine.system.CultivationTickSystem::class,
            setOf(FocusDomain.ALCHEMY) // ALCHEMY 不包含 CultivationTickSystem
        )
        // 应回退到该系统的第一个声明域（OVERVIEW）
        assertEquals("应返回系统的首个声明域", FocusDomain.OVERVIEW, domain)
    }

    @Test
    fun `assignedDomainFor — 未声明系统回退 BACKGROUND`() {
        // TimeSystem 只声明在 ALWAYS 域
        val domain = FocusDomain.assignedDomainFor(
            com.xianxia.sect.core.engine.system.TimeSystem::class,
            setOf(FocusDomain.ALCHEMY) // ALCHEMY 不包含 TimeSystem
        )
        // TimeSystem 的首个域是 ALWAYS（永远在活跃集中），所以返回 ALWAYS
        assertEquals("TimeSystem 的 assignedDomain 应为 ALWAYS", FocusDomain.ALWAYS, domain)
    }

    // ═══════════════════════════════════════════════════════════════
    // DialogRoute → InterfaceDomainMap 一致性验证
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `DialogRoute 焦点 dialog 的 toString 匹配 InterfaceDomainMap`() {
        // 所有应有独立域的 DialogRoute 对象
        val focusDialogs = listOf(
            "Alchemy", "Forge", "HerbGarden", "SpiritMine", "Planting",
            "Warehouse", "Merchant", "MissionHall",
            "BloodRefiningPool", "WorldMap", "Diplomacy",
            "Disciples", "Buildings"
        )
        for (name in focusDialogs) {
            assertNotNull(
                "DialogRoute $name 应在 InterfaceDomainMap 中有映射",
                resolve(dialog = name).find { it != FocusDomain.ALWAYS }
            )
        }
    }

    @Test
    fun `DialogRoute 静态 dialog 的 toString 不匹配任何焦点域`() {
        val staticDialogs = listOf(
            "None", "Settings", "Recruit", "SalaryConfig",
            "BattleLog", "Mail", "Activity", "Library",
            "WenDaoPeak", "QingyunPeak", "TianshuHall",
            "LawEnforcementHall", "ReflectionCliff",
            "PatrolTower", "Residence", "WarehouseBuilding",
            "GameOver", "SectLevelDetail"
        )
        for (name in staticDialogs) {
            val domains = resolve(dialog = name)
            assertEquals(
                "DialogRoute $name 应仅含 ALWAYS（无非焦点域映射）",
                setOf(FocusDomain.ALWAYS), domains
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 域→系统→轨道 端到端验证
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `端到端 — 炼丹 dialog 应激活 ALCHEMY 域及其 AlchemySystem`() {
        val domains = resolve(dialog = "Alchemy")
        assertTrue("应包含 ALCHEMY", FocusDomain.ALCHEMY in domains)
        assertTrue("始终包含 ALWAYS", FocusDomain.ALWAYS in domains)

        // ALWAYS(TimeSystem) + ALCHEMY(AlchemySystem) = 2 个系统
        val activeSystems = FocusDomain.activeSystemsFor(domains)
        val names = activeSystems.map { it.simpleName }.toSet()
        assertTrue("应激活 AlchemySystem", "AlchemySystem" in names)
        assertTrue("应激活 TimeSystem（来自 ALWAYS）", "TimeSystem" in names)
        assertEquals("应激活 2 个系统（TimeSystem + AlchemySystem）", 2, activeSystems.size)
    }

    @Test
    fun `端到端 — 宗门地图 Tab 应激活三个系统`() {
        val domains = resolve(tab = "OVERVIEW")
        assertTrue("应包含 OVERVIEW", FocusDomain.OVERVIEW in domains)

        // ALWAYS(TimeSystem) + OVERVIEW(2个系统) = 3 个系统
        val activeSystems = FocusDomain.activeSystemsFor(domains)
        val names = activeSystems.map { it.simpleName }.toSet()
        assertTrue("应包含 TimeSystem", "TimeSystem" in names)
        assertTrue("应包含 CultivationTickSystem", "CultivationTickSystem" in names)
        assertTrue("应包含 EconomySubsystem", "EconomySubsystem" in names)
        assertEquals("应激活 3 个系统", 3, activeSystems.size)
    }

    @Test
    fun `端到端 — 切换域后系统应正确变更`() {
        // 模拟：从宗门地图切换到建筑 Tab
        val overviewSystems = FocusDomain.activeSystemsFor(
            resolve(tab = "OVERVIEW")
        )
        val buildingSystems = FocusDomain.activeSystemsFor(
            resolve(tab = "BUILDINGS")
        )

        // 宗门地图激活 3 个系统（TimeSystem + CultivationTickSystem + EconomySubsystem）
        // 建筑 Tab 激活 2 个系统（TimeSystem + ProductionSubsystem）
        assertEquals(3, overviewSystems.size)
        assertEquals(2, buildingSystems.size)

        val overviewNames = overviewSystems.map { it.simpleName }.toSet()
        val buildingNames = buildingSystems.map { it.simpleName }.toSet()
        assertTrue("宗门地图应激活 TimeSystem", "TimeSystem" in overviewNames)
        assertTrue("宗门地图应激活 CultivationTickSystem", "CultivationTickSystem" in overviewNames)
        assertTrue("宗门地图应激活 EconomySubsystem", "EconomySubsystem" in overviewNames)
        assertTrue("建筑 Tab 应激活 TimeSystem", "TimeSystem" in buildingNames)
        assertTrue("建筑 Tab 应激活 ProductionSubsystem", "ProductionSubsystem" in buildingNames)

        // 两域叠加合并去重
        val combinedSystems = FocusDomain.activeSystemsFor(
            setOf(FocusDomain.OVERVIEW, FocusDomain.BUILDING_LIST)
        )
        // CultivationTickSystem + EconomySubsystem + ProductionSubsystem = 3
        assertEquals(3, combinedSystems.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 子对话框（Sub-dialog）域
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `subDialog DiscipleSelector — DISCIPLE_SELECTOR（显示弟子卡片）`() {
        val domains = resolveDomainsFromView(
            tab = null, dialog = null, subDialogs = setOf("DiscipleSelector")
        )
        assertDomains(domains, FocusDomain.DISCIPLE_SELECTOR)
    }

    @Test
    fun `subDialog 与父 dialog 域叠加 — Alchemy + DiscipleSelector`() {
        val domains = resolveDomainsFromView(
            tab = null, dialog = "Alchemy",
            subDialogs = setOf("DiscipleSelector")
        )
        assertDomains(domains, FocusDomain.ALCHEMY, FocusDomain.DISCIPLE_SELECTOR)
    }

    @Test
    fun `subDialog 与 tab + dialog 三域叠加`() {
        val domains = resolveDomainsFromView(
            tab = "OVERVIEW", dialog = "Alchemy",
            subDialogs = setOf("DiscipleSelector")
        )
        assertDomains(
            domains, FocusDomain.OVERVIEW,
            FocusDomain.ALCHEMY, FocusDomain.DISCIPLE_SELECTOR
        )
    }

    @Test
    fun `subDialog 空集合 — 不影响结果`() {
        val domains = resolveDomainsFromView(
            tab = "OVERVIEW", dialog = null, subDialogs = emptySet()
        )
        assertDomains(domains, FocusDomain.OVERVIEW)
    }

    @Test
    fun `subDialog 未知值 — 仅 ALWAYS`() {
        val domains = resolveDomainsFromView(
            tab = null, dialog = null,
            subDialogs = setOf("UnknownSubDialog")
        )
        assertDomains(domains)
    }

    @Test
    fun `activeSystemsFor — DISCIPLE_SELECTOR 包含 CultivationTickSystem`() {
        val systems = FocusDomain.activeSystemsFor(
            setOf(FocusDomain.DISCIPLE_SELECTOR)
        )
        assertTrue(
            "DISCIPLE_SELECTOR 应激活 CultivationTickSystem",
            systems.any { it.simpleName == "CultivationTickSystem" }
        )
    }
}
