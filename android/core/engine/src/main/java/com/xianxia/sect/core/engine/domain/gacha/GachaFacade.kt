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
     * 单抽（一次寻访）。
     *
     * 权威臂经 ActionId `GACHA_PULL_ONCE` 落 C++ `gacha_tx.h`；native 未转发时回退到
     * 逐字同式的 Kotlin 臂（[GachaPullLedger]），两条臂消费**同一抽卡分区**
     * （`RngPartition.GACHA`）的同一条流。
     *
     * 线程要求：必须在引擎线程上下文内调用（同 [grantFragments]）。
     *
     * @param poolId 卡池 id（当前只有一张常驻池 `standard`）
     * @return [GachaPullResult.Success] 出货（带每格结果与解锁角色）；
     *         [GachaPullResult.Failure] 校验未过或两条臂均未落账——账本与灵石零改动
     */
    suspend fun pullOnce(poolId: String = "standard"): GachaPullResult

    /**
     * 十连（一笔事务内顺序执行 10 次单抽语义）。
     *
     * 与 10 次 [pullOnce] 的区别在于**原子性**：余额一次性校验、一次性扣费，
     * 中途不存在失败分支（不做差额部分抽取），保底计数跨十连连续。
     */
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

/**
 * 寻访结果（按抽记条；[GachaPullRow] 的数组下标即结果页格序）。
 *
 * 失败以类型承载，不抛异常：灵石不足 / 池未开放 / 池配置不自洽都是可预期的业务失败，
 * 且**账本与灵石零改动**（校验全部先于扣费与掷点）。
 */
sealed interface GachaPullResult {
    /**
     * 出货成功。
     *
     * @property pricePaid 本次实际扣减的下品灵石（十连为 10 × 单抽价）
     * @property spiritStonesAfter 扣费后的下品灵石余额
     * @property pityAfter 保底计数（触发保底后为归零值）
     * @property rows 每格结果（顺序即抽取序，十连第 10 格在末位）
     * @property unlockedTemplateIds 本次首次解锁（0 星 → 1 星）的角色模板 id；
     *   对应弟子已入册（入册唯一口 `DiscipleService.instantiateTemplate`，
     *   已持有实例时按幂等处理，不重复入册）
     */
    data class Success(
        val poolId: String,
        val pricePaid: Long,
        val spiritStonesAfter: Long,
        val pityAfter: Int,
        val rows: List<GachaPullRow>,
        val unlockedTemplateIds: List<String>,
    ) : GachaPullResult

    /** 未出货（[reason] 为结果码，玩家可见文案由 UI 侧按码拼装） */
    data class Failure(val reason: String) : GachaPullResult
}

/**
 * 寻访结果的一格。
 *
 * 只带 id 不带资源键：头像/立绘键由 UI 经 `CharacterTemplateDb.byId(templateId)` 查，
 * 结果 DTO 携带资源键会造成第二真源。**档位口径**：小头像位读 `avatarKey`
 * （512 档 `avatar_<id>`），**不读** `portraitKey`（1024 档全身像）。
 *
 * @property category `character` / `item` / `pity`（保底抽本身即角色碎片）
 * @property templateId 角色类与保底格非空；物品格为空
 * @property itemId 物品格的模板 id；角色/保底格为空
 * @property rarity 物品品阶；角色/保底格为 0（星级由 `starMap` 呈现，不混用本字段）
 * @property count 本次入账数量（碎片数或物品件数）
 */
data class GachaPullRow(
    val category: String,
    val templateId: String,
    val itemId: String,
    val rarity: Int,
    val count: Int,
    val isPity: Boolean,
)

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
