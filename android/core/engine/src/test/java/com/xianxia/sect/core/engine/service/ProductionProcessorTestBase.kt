package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.config.InventoryConfig
import org.junit.Rule
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ProductionProcessor 测试族共享 fixture。
 *
 * 单文件测试类按 LargeClass 阈值拆分后，三个测试类
 * （[ProductionProcessorTest] / [ProductionProcessorHarvestTest] /
 * [ProductionProcessorSettlementTest]）共用同一组字段与构造助手，
 * 抽到基类避免夹具重复拷贝漂移。
 */
abstract class ProductionProcessorTestBase {

    /** 写守卫规则：测试期间关闭 DiscipleTables 写入守卫（T3 需直接组装弟子表） */
    @get:Rule
    val writeGuardRule = WriteGuardRule()

    internal lateinit var procStore: FakeAtomicStateStore
    internal lateinit var procRepo: ProductionSlotRepository
    internal lateinit var processor: ProductionProcessor

    /** 与生产代码 ProductionProcessor.isDiscipleFollowed 逻辑一致 */
    protected fun isDiscipleFollowed(d: Disciple): Boolean {
        return d.statusData["followed"] == "true"
    }

    /**
     * 模拟 processAutoAssign 中的 takeCandidate 逻辑。
     *
     * @param pool 可变候选弟子列表（会被修改）
     * @param focused 是否仅分配已关注弟子
     * @param rootCounts 允许的灵根数列表
     * @param threshold 属性门槛
     * @param attr 属性提取函数
     * @return 选中的弟子，或 null
     */
    protected fun takeCandidate(
        pool: MutableList<Disciple>,
        focused: Boolean,
        rootCounts: List<Int>,
        threshold: Int,
        attr: (Disciple) -> Int
    ): Disciple? {
        val enabled = focused || rootCounts.isNotEmpty()
        if (!enabled || pool.isEmpty()) return null
        val candidate = pool
            .filter { d ->
                val matchesFilter = (focused && isDiscipleFollowed(d)) ||
                    d.spiritRoot.types.size in rootCounts
                matchesFilter && attr(d) >= threshold
            }
            .sortedWith(
                compareByDescending<Disciple> { if (focused) isDiscipleFollowed(it) else false }
                    .thenBy { it.spiritRoot.types.size }
                    .thenByDescending { attr(it) }
            )
            .firstOrNull()
        if (candidate != null) pool.remove(candidate)
        return candidate
    }

    protected fun createProcessor(
        inventorySystem: InventorySystem = mock(),
        seedRoll: Int = 0
    ): ProductionProcessor {
        // 灵田收获种子奖励 roll（nextInt(5)）：默认 0（不获得种子，保持既有收获断言）；
        // 专项测试传入指定值验证 0~4 各档行为
        val rng = mock<DeterministicRng>()
        whenever(rng.nextInt(5)).thenReturn(seedRoll)
        val rngManager = mock<GameRngManager>()
        whenever(rngManager.getRng(any())).thenReturn(rng)
        return ProductionProcessor(
            stateStore = mock(),
            inventorySystem = inventorySystem,
            productionCoordinator = mock(),
            productionSlotRepository = mock(),
            formulaService = mock(),
            rngManager = rngManager,
            scopeProvider = mock(),
            ioDispatcher = IoDispatcher(),
            inventoryConfig = InventoryConfig()
        )
    }

    /** 收获/种植测试的可选环境配置（默认空，仅光环/长老/跨宗门场景使用） */
    protected data class HarvestEnv(
        val placedBuildings: List<GridBuildingData> = emptyList(),
        val elderSlots: ElderSlots = ElderSlots(),
        val discipleTables: DiscipleTables = DiscipleTables(),
        val activeSectId: String = ""
    )

    protected fun createState(
        plants: List<SpiritFieldPlant> = emptyList(),
        seeds: List<Seed> = emptyList(),
        herbs: List<Herb> = emptyList(),
        gameYear: Int = 1,
        gameMonth: Int = 1,
        env: HarvestEnv = HarvestEnv()
    ): MutableGameState {
        return MutableGameState(
            gameData = GameData(
                gameYear = gameYear,
                gameMonth = gameMonth,
                spiritFieldPlants = plants,
                placedBuildings = env.placedBuildings,
                elderSlots = env.elderSlots,
                activeSectId = env.activeSectId
            ),
            discipleTables = env.discipleTables,
            equipmentStacks = EntityStore(emptyList()),
            equipmentInstances = EntityStore(emptyList()),
            manualStacks = EntityStore(emptyList()),
            manualInstances = EntityStore(emptyList()),
            pills = EntityStore(emptyList()),
            materials = EntityStore(emptyList()),
            herbs = EntityStore(herbs),
            seeds = EntityStore(seeds),
            storageBags = EntityStore(emptyList()),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    /** 住所分配轮规格（simulateTwoPassResidence 参数分组）：过滤开关 + 灵根数白名单 + 悟性阈值 */
    protected data class ResidencePassSpec(
        val focused: Boolean,
        val rootCounts: List<Int>,
        val threshold: Int
    )
}
