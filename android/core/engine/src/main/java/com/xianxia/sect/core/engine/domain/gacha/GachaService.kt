package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 寻访域服务（G01 立骨架）。
 *
 * 状态面：聚合 C++ 镜像过来的卡池字段供 UI 订阅（权威逻辑在
 * `gacha_fragment.h` / `gacha_tx`，本类只读派生）。
 *
 * 写入面：**Kotlin 侧碎片账本的唯一服务级入口** [grantFragmentsLocally]——
 * 它是 native 臂降级后的等价回退臂，落账一律经 [GachaFragmentLedger]，
 * 本类之外不得再出现对 `gachaFragmentCounts` / `gachaStarMap` 的写入。
 */
@Singleton
@GameService(name = "GachaService")
class GachaService @Inject constructor(
    private val stateStore: GameStateStore,
    scopeProvider: CoroutineScopeProvider,
) {
    private val scope = scopeProvider.scope

    val pityCounters: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaPityCounters }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val fragmentCounts: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaFragmentCounts }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val starMap: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaStarMap }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val history: StateFlow<List<GachaHistoryEntry>> = stateStore.gameData
        .map { it.gachaHistory }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 碎片入账的 Kotlin 回退臂（与 C++ `gacha_fragment.h::addFragment` 逐字同式）。
     *
     * 单次 `updateAndReturn` 事务内原子完成「读两张账本 → 账本累加升星 → 写回」，
     * 禁止拆成多次孤立 update。入参无效（空白模板 id / 非正数量）时账本零改动。
     *
     * @param templateId 角色模板 id
     * @param count 本次入账的碎片数
     * @return 入账结果（含入账前后星级与入账后星内进度）；入参无效时为 null
     */
    internal fun grantFragmentsLocally(
        templateId: String,
        count: Int,
    ): GachaFragmentLedger.GrantOutcome? = stateStore.updateAndReturn {
        val outcome = GachaFragmentLedger.grant(
            fragmentCounts = gameData.gachaFragmentCounts,
            starMap = gameData.gachaStarMap,
            templateId = templateId,
            count = count,
        )
        if (outcome != null) {
            gameData.gachaFragmentCounts = outcome.fragmentCounts
            gameData.gachaStarMap = outcome.starMap
        }
        outcome
    }
}
