package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.domain.production.EconomySubsystem
import com.xianxia.sect.core.engine.domain.production.ProductionSubsystem
import com.xianxia.sect.core.engine.system.building.AlchemySystem
import com.xianxia.sect.core.engine.system.building.ForgeSystem
import com.xianxia.sect.core.engine.system.building.HerbGardenSystem
import com.xianxia.sect.core.engine.system.building.PlantingSystem

import kotlin.reflect.KClass

/**
 * 玩家关注域 — 两档制 tick 分频，纯视角驱动 + 域声明系统。
 *
 * 每个包含可变数据的 UI 界面对应一个独立的域。
 * 域通过 [systemClasses] 反向声明「本 UI 展示的数据由哪些系统生产」，
 * 激活时这些系统走 100ms 实时轨；未激活时走 30s 批量轨。
 *
 * - 视角驱动：当前界面所在的域即为焦点域 → 100ms 实时结算
 * - 域声明系统：域决定哪些系统实时，而非系统自声明所属域
 * - 界面→域映射定义在 [InterfaceDomainMap] 中，新增界面只需在那里加一行
 */
enum class FocusDomain(
    /** 该域激活时需实时 tick 的系统集合（反向声明）。 */
    val systemClasses: Set<KClass<out GameSystem>> = emptySet()
) {
    /** 时间推进 — 始终高频，每 tick 必执行 */
    ALWAYS(setOf(TimeSystem::class)),

    // ── Tab 级域 ──
    /** 总览 Tab（宗门地图）— 弟子状态摘要 + 灵石 */
    OVERVIEW(setOf(
        CultivationTickSystem::class, EconomySubsystem::class
    )),
    /** 弟子列表 Tab — 修炼进度、HP/MP */
    DISCIPLE_LIST(setOf(CultivationTickSystem::class)),
    /** 建筑管理 Tab — 生产槽位进度 */
    BUILDING_LIST(setOf(ProductionSubsystem::class)),
    /** 仓库 Tab — 物品数量 + 灵石 */
    WAREHOUSE_TAB(setOf(InventorySystem::class, EconomySubsystem::class)),

    // ── 生产建筑 Dialog（每个独立域）──
    /** 炼丹 — 炼制进度/完成检测 */
    ALCHEMY(setOf(AlchemySystem::class)),
    /** 锻器 — 锻造进度/完成检测 */
    FORGE(setOf(ForgeSystem::class)),
    /** 药园 — 灵草生长检测 */
    HERB_GARDEN(setOf(HerbGardenSystem::class)),
    /** 灵矿 — 产出已移至月度结算，域保留用于界面映射 */
    SPIRIT_MINE(emptySet()),
    /** 灵田 — 种植成熟检测 */
    PLANTING(setOf(PlantingSystem::class)),

    // ── 仓库/交易 Dialog ──
    /** 仓库 Dialog — 物品存储 + 灵石 */
    WAREHOUSE_DIALOG(setOf(InventorySystem::class, EconomySubsystem::class)),
    /** 商人 — 物品交易 + 灵石余额 */
    MERCHANT(setOf(InventorySystem::class, EconomySubsystem::class)),
    /** 宗门交易 — 物品交易 + 灵石 */
    SECT_TRADE(setOf(InventorySystem::class, EconomySubsystem::class)),

    // ── 其他可变数据 UI ──
    /** 任务堂 — 任务进度 + 派遣弟子状态 */
    MISSION_HALL(setOf(ExplorationTickSystem::class, CultivationTickSystem::class)),
    /** 血炼池 — 血炼进度（实时进度在 SettlementCoordinator 中处理） */
    BLOOD_REFINING(emptySet()),
    /** 世界地图 — 地图标记/探索状态 */
    WORLD_MAP(emptySet()),

    // ── 子界面域 ──
    /** 弟子选择器子界面 — 需实时刷新弟子修炼进度/HP/MP */
    DISCIPLE_SELECTOR(setOf(CultivationTickSystem::class)),
    /** 外交 — 好感度/关系变化（月度事件中处理） */
    DIPLOMACY(emptySet()),

    /** 后台 — AI 宗门、生育、邮件等玩家不可见的系统（30 秒一次） */
    BACKGROUND(setOf(
        ProductionSubsystem::class,
        PartnerSystem::class,
        ChildBirthSystem::class,
        MailSystem::class
    ));

    companion object {
        /**
         * 域→系统的反向索引，用于根据系统查找其所属域。
         * 延迟构建以避免枚举初始化时的循环依赖。
         */
        private val systemToDomains: Map<KClass<out GameSystem>, List<FocusDomain>> by lazy {
            val map = mutableMapOf<KClass<out GameSystem>, MutableList<FocusDomain>>()
            for (domain in values()) {
                for (cls in domain.systemClasses) {
                    map.getOrPut(cls) { mutableListOf() }.add(domain)
                }
            }
            map
        }

        /**
         * 从一组活跃域计算所有应实时执行的系统集合。
         * 用于 [SystemManager.onPhaseTickWithDomainFilter] 判断系统是否处于焦点域。
         */
        fun activeSystemsFor(domains: Set<FocusDomain>): Set<KClass<out GameSystem>> =
            domains.flatMapTo(mutableSetOf()) { it.systemClasses }

        /**
         * 为系统从活跃域集合中查找其「生效域」。
         * 优先选择同时在活跃域中的域，否则返回系统声明的第一个域。
         * 用于域级时间追踪（[GameEngineCore.shouldExecuteDomain] 等）。
         */
        fun assignedDomainFor(
            systemClass: KClass<out GameSystem>,
            activeDomains: Set<FocusDomain>
        ): FocusDomain {
            val domains = systemToDomains[systemClass] ?: return BACKGROUND
            return domains.firstOrNull { it in activeDomains } ?: domains.first()
        }
    }
}

/**
 * 界面→域映射表（1:1） — 焦点域判定的唯一数据源。
 *
 * 每个 UI 界面名映射到唯一对应的 [FocusDomain]。
 * [GameEngineCore.resolveDomainsFromView] 从此表读取。
 *
 * 判定原则：**界面显示随时间变化的数据（进度条、倒计时、数量增减），
 * 就应映射到对应的域。仅静态信息（历史记录、配置面板）的界面不在此表。**
 */
internal val InterfaceDomainMap: Map<String, FocusDomain> = mapOf(
    // ═══ Tab ═══
    "OVERVIEW"  to FocusDomain.OVERVIEW,
    "DISCIPLES" to FocusDomain.DISCIPLE_LIST,
    "BUILDINGS" to FocusDomain.BUILDING_LIST,
    "WAREHOUSE" to FocusDomain.WAREHOUSE_TAB,

    // ═══ 生产建筑 Dialog ═══
    "Alchemy"    to FocusDomain.ALCHEMY,
    "Forge"      to FocusDomain.FORGE,
    "HerbGarden" to FocusDomain.HERB_GARDEN,
    "SpiritMine" to FocusDomain.SPIRIT_MINE,
    "Planting"   to FocusDomain.PLANTING,

    // ═══ 仓库/交易 Dialog ═══
    "Warehouse"  to FocusDomain.WAREHOUSE_DIALOG,
    "Merchant"   to FocusDomain.MERCHANT,
    "SectTrade"  to FocusDomain.SECT_TRADE,

    // ═══ 任务/探索 ═══
    "MissionHall" to FocusDomain.MISSION_HALL,

    // ═══ 血炼 ═══
    "BloodRefiningPool" to FocusDomain.BLOOD_REFINING,

    // ═══ 地图/外交 ═══
    "WorldMap"  to FocusDomain.WORLD_MAP,
    "Diplomacy" to FocusDomain.DIPLOMACY,

    // ═══ 子界面 ═══
    "DiscipleSelector" to FocusDomain.DISCIPLE_SELECTOR,

    // ═══ 兼容旧调用方（GameRoute.DialogRoute.Disciples/Buildings.toString()）═══
    "Disciples" to FocusDomain.DISCIPLE_LIST,
    "Buildings" to FocusDomain.BUILDING_LIST,
)
