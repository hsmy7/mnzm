package com.xianxia.sect.core.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PresentationRandom — **表现类随机**（文案 / 立绘 / 装饰）专用随机源。
 *
 * ## 存在理由（ADR R3 / R5）
 * 随机源治理的核心不变量是"**表现随机与决策随机物理隔离**"：
 * - 决策类（写入 `GameData` / 实体表 / 影响任何数值）→ 必须走
 *   [GameRngManager.getRng]，其状态随存档导出/恢复（`rngStates`），读档可精确重放
 * - 表现类（战斗描述文案、外交对话文本、加载提示、云层装饰、天劫对手立绘）
 *   → **不得污染决策流**：渲染一帧多抽一次随机不该让全体存档的推演分叉
 *   （行业教训：装饰性 RNG 调用破坏全局状态的灾难案例——渲染粒子多消费一次
 *   随机数导致全部存档失效，见 [RngConsumptionGuardTest] KDoc）
 *
 * ⇒ 本类把表现随机集中到一个**独立、不落盘、不可被决策路径调用**的流上。
 *
 * ## 判据（新增消费点前必须自检）
 * "该随机结果是否写入 `GameData` / 实体表 / 影响数值？"
 * - **写入 = 决策类** → 走 [GameRngManager.getRng]，**不得**用本类
 * - **不写入 = 表现类** → 用本类
 *
 * ## 不落盘（已登记的产品口径）
 * 种子由 `mapSeed` 派生（同会话内可复现），**不入存档**——`R3` 明确定义该类不落盘。
 * 推论：同一存档重进游戏后文案可能不同。若未来要求"跨会话表现完全一致"，
 * 须先拍板改为入档（会新增协议面），见 ADR §11 盲区 5 的登记。
 *
 * ## 线程
 * UI 层（Compose 组合/对话框）与引擎层都会调用——内部
 * [DeterministicRng] 的 `nextInt`/`nextDouble` 为 `@Synchronized`，跨线程安全。
 */
@Singleton
class PresentationRandom @Inject constructor() {

    /** 表现流（`@Volatile` 替换保证可见性；内部抽取 `@Synchronized`） */
    @Volatile
    private var rng: DeterministicRng = DeterministicRng.fromSeed(DEFAULT_SEED)

    /**
     * 以世界种子播种表现流（新档/读档时调用）。
     *
     * 派生式 `mapSeed xor SALT`：与决策分区同源但**不同序列**——即使
     * `mapSeed` 与某分区种子巧合相同，salt 保证两条流不重合。
     *
     * @param mapSeed 世界种子（`GameData.mapSeed`，可为负数）
     */
    fun seedFromWorld(mapSeed: Long) {
        rng = DeterministicRng.fromSeed(mapSeed xor PRESENTATION_SALT)
    }

    /** `[0, bound)` 均匀整数（bound <= 0 时返回 0，防调用方传入空池尺寸） */
    fun nextInt(bound: Int): Int = if (bound <= 0) 0 else rng.nextInt(bound)

    /** `[from, until)` 均匀整数（区间非法时返回 from，语义同 `nextInt` 的防御口径） */
    fun nextInt(from: Int, until: Int): Int =
        if (until <= from) from else from + rng.nextInt(until - from)

    /** `[0.0, 1.0)` 均匀双精度 */
    fun nextDouble(): Double = rng.nextDouble()

    /** 随机布尔 */
    fun nextBoolean(): Boolean = rng.nextInt(2) == 1

    /**
     * 从集合随机取一个元素；**空集合抛 [NoSuchElementException]**（与 stdlib
     * `Collection.random()` 的错误语义逐字一致——存量调用点依赖该语义，
     * 迁移时不得悄悄改成"返回 null 继续"）。
     */
    fun <T> pick(candidates: Collection<T>): T {
        if (candidates.isEmpty()) throw NoSuchElementException("Collection is empty.")
        return candidates.elementAt(rng.nextInt(candidates.size))
    }

    /**
     * 从列表随机取一个元素（空列表返回 null——表现类调用方一律可降级为"不显示"，
     * 不抛异常打断 UI）。
     */
    fun <T> pickOrNull(candidates: List<T>): T? =
        if (candidates.isEmpty()) null else candidates[rng.nextInt(candidates.size)]

    /**
     * 以本流为上界抽取函数（`(bound) -> [0, bound)`）。
     *
     * 供 `PortraitPool.getRandomPortrait(gender) { bound -> … }` 这类
     * "注入抽取函数"的既有 API 复用——调用方无需接触 [DeterministicRng] 本体，
     * 也就不可能误把它接到决策路径上。
     */
    fun boundPicker(): (Int) -> Int = { bound -> nextInt(bound) }

    private companion object {
        /** 表现流派生 salt（改此值会改变全部表现文案的选择序列，属表现面变更） */
        const val PRESENTATION_SALT = -0x5EED_5EED_5EED_5EEDL

        /** 引擎播种前的兜底种子（固定值——表现类不要求跨会话一致，但要求确定性可测） */
        const val DEFAULT_SEED = -7046029254386353131L
    }
}
