package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.InventoryNativeForward
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.long
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.GachaNativeTx
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.onFailure
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
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
 * 抽卡 roll/保底/历史/入库同为双臂：权威臂经 [GachaNativeTx] 走 `GACHA_PULL_ONCE` /
 * `GACHA_PULL_TEN` native 事务，回退臂是 [GachaPullLedger] 的逐字同式实现，两臂由
 * `DiffGachaPullTest` 同种子对拍锁死。
 */
@Singleton
class GachaFacadeImpl @Inject constructor(
    private val gachaService: GachaService,
    private val gameEngineCore: GameEngineCore,
    private val gachaPoolConfig: GachaPoolConfig,
    private val inventorySystem: InventorySystem,
    private val discipleService: DiscipleService,
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

    override suspend fun pullOnce(poolId: String): GachaPullResult = pull(poolId, count = 1)

    override suspend fun pullTen(poolId: String): GachaPullResult = pull(poolId, count = 10)

    /**
     * 寻访双臂：native 事务优先（C++ `gacha_tx.h` 权威），未转发时回退到逐字同式的
     * Kotlin 臂（[GachaService.pullLocally]）。
     *
     * 两条臂都在出货后由本类做两件事：投递 C++ 回传的满仓溢出草稿（邮件不在快照
     * 协议面内，不回读即丢件），以及把解锁描述符落成在册弟子
     * （[DiscipleService.instantiateTemplate] 是具名弟子唯一入册口，限持判定天然幂等）。
     *
     * 失败一律零改动：池自洽性与余额在取用随机数之前校验完毕（与 C++ 同口径），
     * 因此 `Failure` 分支不存在"扣了灵石没出货"的半成品状态。
     */
    private fun pull(poolId: String, count: Int): GachaPullResult {
        val pool = gachaPoolConfig.pool(poolId)
        GachaPullLedger.poolError(pool)?.let { code ->
            DomainLog.w(TAG, "pull rejected: pool=$poolId code=$code")
            return GachaPullResult.Failure(code)
        }
        val native = if (count == 1) nativeTx.tryPullOnce(poolId) else nativeTx.tryPullTen(poolId)
        if (native != null) {
            native.overflowDrafts.forEach { InventoryNativeForward.deliverDraft(inventorySystem, it) }
            return succeed(
                poolId = native.poolId,
                pricePaid = native.pricePaid,
                spiritStonesAfter = native.spiritStonesAfter,
                pityAfter = native.pityAfter,
                rows = native.rows.map { it.toPullRow() },
                unlockedTemplateIds = native.unlockedTemplateIds,
            )
        }
        val local = gachaService.pullLocally(requireNotNull(pool), poolId, count)
            ?: return GachaPullResult.Failure(GachaPullOutcome.CODE_INSUFFICIENT)
        return succeed(
            poolId = poolId,
            pricePaid = local.pricePaid,
            spiritStonesAfter = local.spiritStonesAfter,
            pityAfter = local.pityAfter,
            rows = local.rows.map { GachaPullRow(it.category, it.templateId, it.itemId,
                it.rarity, it.count, it.isPity) },
            unlockedTemplateIds = local.unlockedTemplateIds,
        )
    }

    /** 成功回执 + 解锁入册（弟子实例化失败只记日志：碎片/星级账本已落，读档面幂等补齐） */
    private fun succeed(
        poolId: String,
        pricePaid: Long,
        spiritStonesAfter: Long,
        pityAfter: Int,
        rows: List<GachaPullRow>,
        unlockedTemplateIds: List<String>,
    ): GachaPullResult {
        unlockedTemplateIds.forEach { templateId ->
            discipleService.instantiateTemplate(templateId).onFailure { error ->
                DomainLog.w(
                    TAG,
                    "unlock instantiate failed: tid=$templateId code=${error.code} ${error.message}"
                )
            }
        }
        DomainLog.i(TAG, "pull ok: pool=$poolId count=${rows.size} pity=$pityAfter")
        return GachaPullResult.Success(
            poolId = poolId,
            pricePaid = pricePaid,
            spiritStonesAfter = spiritStonesAfter,
            pityAfter = pityAfter,
            rows = rows,
            unlockedTemplateIds = unlockedTemplateIds,
        )
    }

    /** native 结果行 → DTO（字段名与 `dispatch_gacha.cpp` 的信封编码一一对应） */
    private fun JsonObject.toPullRow(): GachaPullRow = GachaPullRow(
        category = str("category") ?: "",
        templateId = str("templateId") ?: "",
        itemId = str("itemId") ?: "",
        rarity = (long("rarity") ?: 0L).toInt(),
        count = (long("count") ?: 0L).toInt(),
        isPity = this["isPity"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

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
