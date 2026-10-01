package com.xianxia.sect.core.engine

import com.xianxia.sect.core.state.CriticalSaveEventBus
import com.xianxia.sect.core.state.CriticalSaveKind
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.core.util.AnalyticsTracker
import javax.inject.Inject
import javax.inject.Singleton

/** 空实现（测试直构 GameEngineCore / 无埋点基建场景的默认值） */
object NoopAnalyticsTracker : AnalyticsTracker {
    override fun trackEvent(eventName: String, properties: Map<String, Any>) = Unit
}

/**
 * BreakthroughAnalyticsObserver — 突破成功埋点。
 *
 * 每旬突破判定在 C++ 侧执行（runPhaseSettlementCore 步骤 7），
 * 本观察器以"旬前后 breakthroughCounts 列差分"重建突破成功事件，
 * 保留 TapDB 埋点语义（含 TapDBAnalyticsTracker 的 FTUE 首次突破派生）：
 * 每次成功恰好一条，属性取镜像后（突破后）境界/层数/姓名——与
 * DiscipleBreakthroughHandler performBreakthrough 成功分支的取值时机一致。
 *
 * 基线每旬由生产 tick 在 nativeSettlePhase **之前**捕获：旬间战斗前结算等
 * Kotlin 域突破（自带埋点）落在下一基线内，不会重复上报；读档/新游戏/
 * 重启的计数跳变同样被下一条基线吸收，无需额外重锚。
 */
@Singleton
class BreakthroughAnalyticsObserver @Inject constructor(
    private val analyticsTracker: AnalyticsTracker,
    // SS6：关键事件自动存档总线——突破（渡劫）成功的 Kotlin 镜像差分消费点经此
    // 请求落盘；默认值仅供测试直构（无人订阅 = 事件零副作用），生产由 Hilt 注入单例。
    private val criticalSaveEvents: CriticalSaveEventBus = CriticalSaveEventBus()
) {
    private var baseline: Map<Int, Int> = emptyMap()

    /** 捕获基线（每旬 settle 前调用） */
    fun captureBaseline(tables: DiscipleTables) {
        baseline = snapshotOf(tables)
    }

    /** 镜像后对拍增量：新增成功次数逐条上报（属性 = 突破后快照）；有新增即请求关键事件落盘 */
    fun reportNewBreakthroughs(tables: DiscipleTables) {
        var hasNewBreakthrough = false
        for (id in tables.ids) {
            val before = baseline[id] ?: 0
            val after = tables.breakthroughCounts.getOrDefault(id, 0)
            val delta = after - before
            if (delta <= 0) continue
            hasNewBreakthrough = true
            repeat(delta) {
                analyticsTracker.trackEvent(
                    AnalyticsEvents.BREAKTHROUGH_SUCCESS,
                    mapOf(
                        AnalyticsEvents.PROP_REALM to tables.realms.getOrDefault(id, 9),
                        AnalyticsEvents.PROP_REALM_LAYER to tables.realmLayers.getOrDefault(id, 1),
                        AnalyticsEvents.PROP_DISCIPLE_NAME to (tables.names.getOrNull(id) ?: "")
                    )
                )
            }
        }
        if (hasNewBreakthrough) {
            // 突破成功 = 唯一性里程碑已入镜像（方案 §2.5），请求关键事件落盘
            criticalSaveEvents.notify(CriticalSaveKind.MILESTONE)
        }
        baseline = snapshotOf(tables)
    }

    private fun snapshotOf(tables: DiscipleTables): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        for (id in tables.ids) {
            val count = tables.breakthroughCounts.getOrDefault(id, 0)
            if (count > 0) out[id] = count
        }
        return out
    }
}
