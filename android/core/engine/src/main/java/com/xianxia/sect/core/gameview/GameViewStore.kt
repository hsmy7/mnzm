package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.descriptors.elementNames

/** 块①：资源头部（HUD 时间/灵石、仓库页三品阶灵石卡消费面） */
data class ResourcesHeaderView(
    val spiritStones: Long,
    val midGradeSpiritStones: Long,
    val highGradeSpiritStones: Long,
    val gameYear: Int,
    val gameMonth: Int,
    val gamePhase: Int
)

/** 块②：配置回声（三层 StateFlow 架构的 ConfigState 消费面；gameSpeed 属运行态不在镜像面） */
data class ConfigEchoView(
    val sectPolicies: SectPolicies,
    val yearlySalary: Map<Int, Int>,
    val yearlySalaryEnabled: Map<Int, Boolean>,
    val elderSlots: ElderSlots,
    val placedBuildings: List<GridBuildingData>,
    val autoRecruitSpiritRootFilter: Set<Int>
)

/** 块③：事件流当前载体（消息栏；R2.4 由 proto `eventFeed` 承接后转 typed 投影） */
data class EventLogView(val records: List<GameEventRecord>)

/**
 * GameViewStore —— 按 UI 消费块拆分的**投影态**
 * （重构方案 R2.3 第二波：替代 GameStateStore 全量快照的"唯一入口"地位）。
 *
 * ## 定位（方案 §2 目标架构"状态"行）
 * 终态分工：C++ 持权威 GameState（唯一份热状态），Kotlin 侧 UI 只消费 GameView
 * 投影。第一波（B06/B07）之后所有 UI 块仍从 GameStateStore 的全量镜像流取数——
 * 每旬整份 [GameData] 换一次引用，每个消费块都要从这份 137 字段的对象里各取所需。
 * 本 store 把"取所需"显式化为**块契约**：每块声明自己消费哪些 gameData 字段，
 * 镜像提交后只重投"本封真的变了"的块，未触及的块引用不变（消费者零无谓重组），
 * 也不再依赖整份快照的字段面。
 *
 * ## 块清单与迁移顺序（本批交付 ①②③，④ 属 R2.4）
 * | 块 | 视图 | 消费字段 | UI 面 |
 * |---|---|---|---|
 * | ① 资源头部 | [ResourcesHeaderView] | 三阶灵石 + 年/月/旬 | HUD、仓库页灵石卡 |
 * | ② 配置回声 | [ConfigEchoView] | 政策 / 年俸(+开关) / 长老槽 / 已放置建筑 / 自动招募灵根 | ConfigState 消费面 |
 * | ③ 事件流（当前载体） | [EventLogView] | gameEventRecords | 消息栏 |
 * | ④ proto `eventFeed` | — | C++ 本批不产出（R2.4 接线） | 不建消费面，见 [PROTO_EVENT_FEED_BLOCK] |
 *
 * ## 一致性（非镜像写入的对账）
 * 投影只由镜像推进，而 Kotlin 侧仍有稳态写入（回退臂 / 未下沉 UI 事务）。故本 store
 * 注册为 [GameStateStore.TransactionObserver]：**提交世代 ≠ 最近一次镜像世代** ⇒ 该
 * 事务不是 C++ 真相源投影 ⇒ 立即从 store 的 gameData 快照重投全部块（gameData 在
 * 事务内同步发布，读取即新鲜）。AUTHORITATIVE 稳态下该路径每旬命中零次（引擎对拍
 * `nonMirrorWriteCount == 0` 长期看护），命中即自愈、不误显示。
 *
 * ## fail-fast 红线（方案 §5「投影缺失字段 fail-fast 而非静默空」）
 * 块声明的字段必须真实存在于 gameData 镜像协议面（kotlinx 序列化描述符）。字段被
 * 改名 / 转 @Transient / 掉出 C++ 导出面时，投影会退化成"永远取默认值的静默空"——
 * 正是红线要拦的形状，故 [requireInMirrorSurface] 当场抛错而非补默认。行级投影的同
 * 类守卫见 [GameViewDiscipleRows]（C++ emit-always 面缺字段即抛）。
 */
@Singleton
class GameViewStore @Inject constructor() {

    private val _resourcesHeader = MutableStateFlow(GameViewStore.RESOURCES_EMPTY)
    private val _configEcho = MutableStateFlow(GameViewStore.CONFIG_EMPTY)
    private val _eventLog = MutableStateFlow(EventLogView(emptyList()))

    /** 已迁 UI 消费块的只读投影面（块①资源头部） */
    val resourcesHeader: StateFlow<ResourcesHeaderView> = _resourcesHeader.asStateFlow()

    /** 已迁 UI 消费块的只读投影面（块②配置回声） */
    val configEcho: StateFlow<ConfigEchoView> = _configEcho.asStateFlow()

    /** 已迁 UI 消费块的只读投影面（块③事件流当前载体） */
    val eventLog: StateFlow<EventLogView> = _eventLog.asStateFlow()

    /** 最近一次投影的 gameData 实例（引用比对 = 是否需要重投） */
    private var projectedGameData: GameData? = null

    /** 投影代数（观测/守卫用：每次任一块重投 +1） */
    @Volatile
    var projectionGeneration: Long = 0L
        private set

    /** 对账钩子命中次数（非镜像事务触发的重投；稳态恒 0，是"投影未绕过镜像链"的观测面） */
    @Volatile
    var reconciliationCount: Long = 0L
        private set

    @Volatile
    private var attachedStore: GameStateStore? = null

    @Volatile
    private var lastMirrorGeneration = MIRROR_GENERATION_NONE

    private val reconciliationObserver = object : GameStateStore.TransactionObserver {
        override fun onTransactionCommitted(transactionGeneration: Long) {
            if (transactionGeneration == lastMirrorGeneration) return
            reseedFromStore()
        }

        override fun onTransactionRolledBack(transactionGeneration: Long) = Unit
    }

    /**
     * 绑定状态存储并注册对账钩子（同一实例幂等）。
     *
     * 由 [com.xianxia.sect.core.nativebridge.StateSyncService] 构造期调用——投影态
     * 不自行拉取 JNI、也不自行写镜像，只被馈送，保持"镜像链单一入口"审计面。
     */
    fun attach(stateStore: GameStateStore) {
        if (attachedStore === stateStore) return
        attachedStore = stateStore
        stateStore.registerTransactionObserver(reconciliationObserver)
    }

    /** 记录最近一次镜像事务世代（对账钩子据此区分"投影写入"与"Kotlin 游戏写入"）。 */
    fun recordMirrorCommit(transactionGeneration: Long) {
        lastMirrorGeneration = transactionGeneration
    }

    /**
     * 镜像事务提交后按块重投影。
     *
     * @param carriedGameDataFields 本封变更集实际携带的 gameData 字段名（已去 `gameData.` 前缀）
     * @param source 应用后的 gameData（两臂共用同一实例，投影与 store 因此不可能分叉）
     */
    fun project(carriedGameDataFields: Set<String>, source: GameData) {
        projectedGameData = source
        var touched = false
        if (carriedGameDataFields intersects RESOURCES_FIELDS) {
            _resourcesHeader.value = resourcesViewOf(source)
            touched = true
        }
        if (carriedGameDataFields intersects CONFIG_FIELDS) {
            _configEcho.value = configViewOf(source)
            touched = true
        }
        if (carriedGameDataFields intersects EVENT_LOG_FIELDS) {
            _eventLog.value = EventLogView(eventRecordsOf(source))
            touched = true
        }
        if (touched) projectionGeneration++
    }

    /** 全块重投（对账 / 低频全量臂）：不区分本封变了什么，三块一次到位。 */
    fun reprojectAll(source: GameData) {
        projectedGameData = source
        _resourcesHeader.value = resourcesViewOf(source)
        _configEcho.value = configViewOf(source)
        _eventLog.value = EventLogView(eventRecordsOf(source))
        projectionGeneration++
    }

    /** 换档/新档：投影回到空态（与 GameStateStore 未装载同形），等下一封镜像推进。 */
    fun reset() {
        projectedGameData = null
        _resourcesHeader.value = RESOURCES_EMPTY
        _configEcho.value = CONFIG_EMPTY
        _eventLog.value = EventLogView(emptyList())
        projectionGeneration++
    }

    private fun reseedFromStore() {
        val current = attachedStore?.gameDataSnapshot ?: return
        if (current === projectedGameData) return
        reconciliationCount++
        reprojectAll(current)
    }

    private fun eventRecordsOf(gd: GameData): List<GameEventRecord> {
        requireInMirrorSurface("事件流", EVENT_LOG_FIELDS)
        return gd.gameEventRecords
    }

    private infix fun Set<String>.intersects(other: Set<String>): Boolean = any { it in other }

    companion object {
        private const val MIRROR_GENERATION_NONE = -1L

        /** proto 块 3 `eventFeed` 接线状态（R2.4 产出前不得长出消费面，守卫锁定） */
        const val PROTO_EVENT_FEED_BLOCK = "reserved-not-produced"

        /** 块①消费面字段清单（投影契约；与 [resourcesView] 一一对应，守卫锁定） */
        val RESOURCES_FIELDS: Set<String> = setOf(
            "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones",
            "gameYear", "gameMonth", "gamePhase"
        )

        /** 块②消费面字段清单 */
        val CONFIG_FIELDS: Set<String> = setOf(
            "sectPolicies", "yearlySalary", "yearlySalaryEnabled", "elderSlots",
            "placedBuildings", "autoRecruitSpiritRootFilter"
        )

        /** 块③消费面字段清单 */
        val EVENT_LOG_FIELDS: Set<String> = setOf("gameEventRecords")

        /** 全部已建投影块的消费面字段（对账面） */
        val PROJECTED_FIELDS: Set<String> = RESOURCES_FIELDS + CONFIG_FIELDS + EVENT_LOG_FIELDS

        /**
         * 块①视图构造（投影契约 = [RESOURCES_FIELDS]）。
         *
         * 纯函数且两臂共用：[com.xianxia.sect.core.engine.GameEngine] 的回滚臂
         * （旗标关）从整份 gameData 派生同一视图，保证"迁移只换来源、不换值"。
         */
        fun resourcesViewOf(gd: GameData): ResourcesHeaderView {
            requireInMirrorSurface("资源头部", RESOURCES_FIELDS)
            return ResourcesHeaderView(
                spiritStones = gd.spiritStones,
                midGradeSpiritStones = gd.midGradeSpiritStones,
                highGradeSpiritStones = gd.highGradeSpiritStones,
                gameYear = gd.gameYear,
                gameMonth = gd.gameMonth,
                gamePhase = gd.gamePhase
            )
        }

        /** 块②视图构造（投影契约 = [CONFIG_FIELDS]，两臂共用纯函数） */
        fun configViewOf(gd: GameData): ConfigEchoView {
            requireInMirrorSurface("配置回声", CONFIG_FIELDS)
            return ConfigEchoView(
                sectPolicies = gd.sectPolicies,
                yearlySalary = gd.yearlySalary,
                yearlySalaryEnabled = gd.yearlySalaryEnabled,
                elderSlots = gd.elderSlots,
                placedBuildings = gd.placedBuildings,
                autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter
            )
        }

        /** 块①空态（未装载 / 换档复位） */
        val RESOURCES_EMPTY = ResourcesHeaderView(0L, 0L, 0L, 1, 1, 0)

        /** 块②空态 */
        val CONFIG_EMPTY = ConfigEchoView(
            sectPolicies = SectPolicies(),
            yearlySalary = emptyMap(),
            yearlySalaryEnabled = emptyMap(),
            elderSlots = ElderSlots(),
            placedBuildings = emptyList(),
            autoRecruitSpiritRootFilter = emptySet()
        )

        /** gameData 镜像协议面（kotlinx 序列化描述符；@Transient 天然不在其中） */
        private val MIRROR_SURFACE: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            GameData.serializer().descriptor.elementNames.toSet()
        }

        /** 已校验过的块（每块一次，热路径零成本） */
        private val verifiedBlocks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * 投影缺失字段 fail-fast：块声明的字段若已不在 gameData 镜像协议面内
         * （改名 / 转 @Transient / C++ 掉出导出面），立即抛错——以默认值补位等于
         * 让 UI 静默显示恒定初值（方案 §5 明令禁止的形状）。
         */
        internal fun requireInMirrorSurface(block: String, fields: Set<String>) {
            if (!verifiedBlocks.add(block)) return
            val drifted = fields - MIRROR_SURFACE
            require(drifted.isEmpty()) {
                "GameView 投影块「$block」声明的字段已不在 gameData 镜像协议面内" +
                    "（改名 / 转 @Transient / 掉出导出面；禁止以默认值掩盖）：$drifted"
            }
        }
    }
}
