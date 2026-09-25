package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.model.GachaHistoryEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 寻访（角色卡池）域门面——第 8 个领域 Facade。
 *
 * 权威逻辑在 C++（入账 `gacha_fragment.h`、roll/保底 `gacha_tx` G09），
 * Kotlin 侧只做转发与降级回退臂，不反向写镜像。
 * 禁止塞进 DiscipleFacade。
 */
interface GachaFacade {
    /** 保底进度 map：poolId → 已抽次数（0..pityThreshold-1） */
    val pityCounters: StateFlow<Map<String, Int>>

    /** 碎片进度：templateId → x（当前星级内进度；满星后继续累加，不截断、不折算） */
    val fragmentCounts: StateFlow<Map<String, Int>>

    /**
     * 星级：templateId → star（1..maxStar）。
     *
     * 账本稀疏：0 星不落键，故「已获得碎片但未满一星」的模板不在此表内；
     * 未解锁角色同样无键。
     */
    val starMap: StateFlow<Map<String, Int>>

    /** 最近寻访历史（环缓冲，按抽记条，新在前） */
    val history: StateFlow<List<GachaHistoryEntry>>

    /**
     * 单抽占位。G09 前返回 [GachaPullResult.NotReady]；
     * G09 起经 ActionId `GACHA_PULL_ONCE` 走 C++ 事务。
     */
    suspend fun pullOnce(poolId: String = "standard"): GachaPullResult

    /** 十连占位（一笔事务内原子 10 次单抽语义）。 */
    suspend fun pullTen(poolId: String = "standard"): GachaPullResult

    /**
     * 角色碎片入账——**全渠道唯一入口**（抽卡 / 兑换码 / 邮件 / 活动共用）。
     *
     * 零 RNG、零时间依赖：只做「碎片累加 + 满每星门槛
     * （`GameConfig.Gacha.FRAGMENTS_PER_STAR`）升星」，
     * 满星后进度继续累加（不截断、不折算成其它货币）。权威写者是 C++
     * `gacha_fragment.h::addFragment`（经 ActionId `GACHA_FRAGMENT_GRANT_TX`
     * native 事务），native 不可用时降级到逐字同式的 Kotlin 回退臂
     * （见 `GachaFragmentLedger`），两条臂共用同一套换算口径。
     *
     * 调用方**禁止**自行 `fragmentCounts[key]++` 或直写 `gachaStarMap`。
     *
     * 线程要求：必须在引擎线程上下文内调用（`GameEngine.launchOnEngine { }` /
     * `withEngineContext`）——回退臂要写 GameStateStore，主线程直调会被状态存储的
     * 架构监护判错。
     *
     * @param templateId 角色模板 id（空白视为无效授予，账本零改动）
     * @param count 本次入账的碎片数（非正视为无效授予，账本零改动）
     * @return [GachaGrantResult.Granted] 已入账（带前后星级与星内进度）；
     *         [GachaGrantResult.Invalid] 入参无效；
     *         [GachaGrantResult.Failure] 两条臂均未落账
     */
    suspend fun grantFragments(templateId: String, count: Int): GachaGrantResult
}

/** 寻访历史条目（按抽；保底格 [isPity]=true）。 */
sealed interface GachaPullResult {
    data object NotReady : GachaPullResult
    data class Failure(val reason: String) : GachaPullResult
}

/** 碎片入账结果（业务失败以类型承载，不抛异常）。 */
sealed interface GachaGrantResult {
    /**
     * 入账成功。
     *
     * @property starBefore 入账前星级（无记录为 0）
     * @property starAfter 入账后星级（可连升多星）
     * @property fragmentsAfter 入账后当前星级内进度
     */
    data class Granted(
        val starBefore: Int,
        val starAfter: Int,
        val fragmentsAfter: Int,
    ) : GachaGrantResult

    /** 入参无效（空白模板 id / 非正碎片数）——账本零改动，调用方无需重试 */
    data class Invalid(val reason: String) : GachaGrantResult

    /** 入账失败（native 臂与回退臂均未落账）——账本零改动 */
    data class Failure(val reason: String) : GachaGrantResult
}
