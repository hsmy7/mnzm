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
 * 种子 = **存档 `mapSeed` × 场景键** 派生（`BootSequenceController` 在
 * `generateMapPreloadData` 处接线播种——新档/读档的唯一汇合点，一处接线
 * 覆盖两端），**不入存档**——`R3` 明确定义该类不落盘。
 * ⇒ 同一存档 + 同一场景实例 ⇒ 每次进入结果恒定（**跨会话一致**）；
 *   不同场景实例之间保留多样性；零协议面、不污染决策流。
 *
 * ## 两种取用方式（新增消费点前必读）
 * 1. **共享根流**（DI 单例直用）：适合"持续变化更好"的装饰/动画
 *    （云层 `CloudLayerAnimator` 等刻意不用本类、走 config 维度固定种子）；
 *    缺点：抽到什么取决于"玩家点过哪些界面"（90+ 消费点共流的时序耦合）。
 * 2. **场景流 `scene(key)`（推荐）**：返回**独立实例**，序列只由
 *    `(worldSeed, key)` 决定——同键恒定、异键无关、与根流零竞争。
 *    键**必须含场景实例身份**（如弟子 id / 参战者 id / 宗门 id）：
 *    全部调用共用同一常量键 ⇒ 每次同一结果、**表现多样性归零**
 *    （比"重进会变"更糟；常量键仅限"本就该固定轮播"的场景，如加载提示）。
 *    哈希用 [fnv1a64]（FNV-1a 64，跨平台契约）——**禁用 `String.hashCode()`**
 *    （JVM 实现约定，KMP/Native 侧取值可能不同）。
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

    /** 世界种子（场景派生基底；播种前为 [DEFAULT_SEED] 兜底） */
    @Volatile
    private var worldSeed: Long = DEFAULT_SEED

    /** 场景流专用：种子即派生结果（委托主构造完成 DI 面初始化后覆盖流） */
    private constructor(rngSeed: Long) : this() {
        rng = DeterministicRng.fromSeed(rngSeed)
    }

    /**
     * 以世界种子播种表现流（`BootSequenceController.generateMapPreloadData`
     * 在新档/读档时调用——全仓唯一接线点，守卫测试锁死其存在）。
     *
     * 派生式 `mapSeed xor SALT`：与决策分区同源但**不同序列**——即使
     * `mapSeed` 与某分区种子巧合相同，salt 保证两条流不重合。
     *
     * @param mapSeed 世界种子（`GameData.mapSeed`，可为负数）
     */
    fun seedFromWorld(mapSeed: Long) {
        worldSeed = mapSeed
        rng = DeterministicRng.fromSeed(mapSeed xor PRESENTATION_SALT)
    }

    /**
     * 派生**场景流**：独立实例，序列由 `(worldSeed, key)` 唯一决定。
     *
     * - 同键 ⇒ 两次取用序列逐位相同（跨会话一致）
     * - 异键 ⇒ 序列不同（键参与哈希）
     * - 与根实例互不影响（各抽各的，零时序耦合）
     *
     * 键纪律见类 KDoc"两种取用方式"。
     */
    fun scene(key: String): PresentationRandom =
        PresentationRandom(worldSeed xor PRESENTATION_SALT xor fnv1a64(key))

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

    /**
     * 适配为 [kotlin.random.Random]。
     *
     * 供 `:core:domain` 中仍以 `kotlin.random.Random` 为形参的表现类 API
     *（如 [com.xianxia.sect.core.config.SectResponseTexts.getAcceptResponse]）
     * 消费——这些 API 的形参已改为**必传**（消除"默认值静默回落
     * `Random.Default`"的陷阱），调用方传本适配器即可。
     */
    fun asKotlinRandom(): kotlin.random.Random = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = rng.nextInt() ushr (32 - bitCount)
        override fun nextInt(bound: Int): Int = this@PresentationRandom.nextInt(bound)
        override fun nextInt(from: Int, until: Int): Int = this@PresentationRandom.nextInt(from, until)
        override fun nextDouble(): Double = this@PresentationRandom.nextDouble()
    }

    private companion object {
        /** 表现流派生 salt（改此值会改变全部表现文案的选择序列，属表现面变更） */
        const val PRESENTATION_SALT = -0x5EED_5EED_5EED_5EEDL

        /** 引擎播种前的兜底种子（固定值——场景键派生同样要求确定性可测） */
        const val DEFAULT_SEED = -7046029254386353131L
    }
}

/**
 * FNV-1a 64 位哈希（场景键派生专用，`internal` 以便锚点测试直测）。
 *
 * 跨平台契约：字节序取 `encodeToByteArray()`（UTF-8）、basis `0xcbf29ce484222325`、
 * prime `0x100000001b3`——iOS（KMP/Native）侧实现必须复现同一取值，
 * `PresentationRandomSceneTest` 以字面量期望值锁死（跨平台回归锚点）。
 * **禁用 `String.hashCode()`**：JVM 实现约定而非跨平台契约。
 */
internal fun fnv1a64(key: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis (0xcbf29ce484222325)
    for (b in key.encodeToByteArray()) {
        hash = hash xor (b.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash
}
