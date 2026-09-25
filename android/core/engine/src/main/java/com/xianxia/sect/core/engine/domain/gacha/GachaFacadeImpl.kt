package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.nativebridge.GachaNativeTx
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GachaFacade] 实现。
 *
 * 碎片入账走「native 臂优先 → Kotlin 回退臂」双臂：native 臂经
 * `GachaNativeTx`（AUTHORITATIVE 门控 + 既有 tryExecuteNative 通道，零新增
 * `external fun`）把 C++ `addFragment` 的结果镜像回来；回退臂是
 * `GachaFragmentLedger` 的逐字同式实现（与 SectPolicyToggleUseCase 的
 * 「native 臂 + Kotlin 等价回退臂 + 文案由 Kotlin 产出」同构，故回退臂必须存在
 * 且与权威臂等价——flag 关闭、native 桥不可用、降级信封三种场景下入账不能丢）。
 *
 * 抽卡 roll/保底仍占位（G09 接 `GACHA_PULL_ONCE` / `GACHA_PULL_TEN` 后改为
 * native 事务），当前恒返回 [GachaPullResult.NotReady]。
 */
@Singleton
class GachaFacadeImpl @Inject constructor(
    private val gachaService: GachaService,
    private val gameEngineCore: GameEngineCore,
) : GachaFacade {

    companion object {
        private const val TAG = "GachaFacadeImpl"
        /** 入账入参无效：模板 id 为空串（玩家可见文案由调用方按码拼装） */
        internal const val REASON_EMPTY_TEMPLATE_ID = "INVALID_TEMPLATE_ID"
        /** 入账入参无效：碎片数非正 */
        internal const val REASON_NON_POSITIVE_COUNT = "INVALID_COUNT"
        /** 两条臂均未落账（回退臂被账本拒绝——预校验后不可达，仅兜底） */
        internal const val REASON_LEDGER_REJECTED = "LEDGER_REJECTED"
    }

    override val pityCounters: StateFlow<Map<String, Int>> = gachaService.pityCounters
    override val fragmentCounts: StateFlow<Map<String, Int>> = gachaService.fragmentCounts
    override val starMap: StateFlow<Map<String, Int>> = gachaService.starMap
    override val history: StateFlow<List<GachaHistoryEntry>> = gachaService.history

    private val nativeTx by lazy { GachaNativeTx(gameEngineCore) }

    override suspend fun pullOnce(poolId: String): GachaPullResult =
        GachaPullResult.NotReady

    override suspend fun pullTen(poolId: String): GachaPullResult =
        GachaPullResult.NotReady

    override suspend fun grantFragments(templateId: String, count: Int): GachaGrantResult {
        // 预闸判据与两侧账本臂一致（C++ std::string::empty() / Kotlin isEmpty()）：
        // 用 isBlank() 会让门面拒绝、而账本臂接受同一入参并写入键，双臂行为分叉
        if (templateId.isEmpty()) return GachaGrantResult.Invalid(REASON_EMPTY_TEMPLATE_ID)
        if (count <= 0) return GachaGrantResult.Invalid(REASON_NON_POSITIVE_COUNT)
        nativeTx.tryGrantFragments(templateId, count)?.let { grant ->
            return GachaGrantResult.Granted(
                starBefore = grant.starBefore,
                starAfter = grant.starAfter,
                fragmentsAfter = grant.fragmentsAfter,
            )
        }
        return grantFragmentsKotlinArm(templateId, count)
    }

    /**
     * Kotlin 回退臂：账本落账经 [GachaService.grantFragmentsLocally]
     * （native 臂未转发/降级时唯一落账路径，与 C++ `addFragment` 同式）。
     */
    private fun grantFragmentsKotlinArm(templateId: String, count: Int): GachaGrantResult {
        val outcome = gachaService.grantFragmentsLocally(templateId, count)
        if (outcome == null) {
            DomainLog.w(TAG, "grantFragments rejected by ledger: tid=$templateId count=$count")
            return GachaGrantResult.Failure(REASON_LEDGER_REJECTED)
        }
        DomainLog.i(
            TAG,
            "grantFragments kotlin arm: tid=$templateId star=${outcome.starBefore}->" +
                "${outcome.starAfter} progress=${outcome.fragmentsAfter}"
        )
        return GachaGrantResult.Granted(
            starBefore = outcome.starBefore,
            starAfter = outcome.starAfter,
            fragmentsAfter = outcome.fragmentsAfter,
        )
    }
}
