package com.xianxia.sect.core.util

/**
 * RNG 分区枚举 — 不同游戏子系统使用独立 PRNG，防止跨域污染。
 *
 * 各分区独立序列化到 GameData.rngStates，确保读档后随机序列一致。
 *
 * ## id 是持久化键，永不改义
 * `id` 即 `rngStates`（`Map<Int, Long>`）的键，写入存档二进制。**禁止改动/复用
 * 任何既有 id**；新增分区只能追加新 id，且必须同步
 * `gamecore/rng/rng_manager.h` 的枚举 + `initSystemSeed` 播种 + 本文档登记
 *（`RngSourceGuardTest` 的 `registeredPartitionIds` 会拦截未登记的追加）。
 *
 * @property id 持久化键（`rngStates` 的 Map key）
 * @property inSnapshot 是否参与 `rngStates` 序列化——**通道型分区**（如
 *   [AI_SECT_MIRROR]）只提供"访问另一个真相源的句柄"，其状态由该真相源自己
 *   保管与落盘，若留在 `rngStates` 里会与**同 id 的宿主侧字段**互相覆盖
 *   （§阶段 1② 实测：Kotlin 侧 9 号 = `AI_SECT` 流态，宿主侧 9 号 =
 *   `aiRng_` 流态，两侧序列不同 ⇒ 对拍恒红）
 */
enum class RngPartition(val id: Int, val inSnapshot: Boolean = true) {
    /** 战斗系统：暴击/闪避/技能随机/命中判定 */
    BATTLE(0),
    /** 突破系统：突破成功/失败 */
    BREAKTHROUGH(1),
    /** 探索系统：妖兽移动/关卡生成/掠夺物品 */
    EXPLORATION(2),
    /** 系统级：UI 随机/非关键随机化 */
    SYSTEM(3),
    /** 敌人属性生成：AI 敌人属性方差（与战斗 RNG 隔离，避免跨线程污染） */
    ENEMY_GEN(4),
    /** 邮件/兑换码奖励随机生成：弟子属性/装备/丹药/草药等 */
    MAIL(5),
    /** AI 宗门：弟子生成/装备分配/修炼演化（与主游戏 RNG 隔离，避免跨线程污染） */
    AI_SECT(6),
    /** 远古秘境：秘境刷新/事件生成/妖兽属性/分支判定 */
    SECRET_REALM(7),
    /** 任务系统：任务刷新/任务奖励随机生成（存档 rngStates 8 号键；旧档缺失时按 systemSeed+8 播种） */
    MISSION(8),

    /**
     * **AI 流访问句柄**（委托模式下 `NativeBackedRng(9)` 直达 C++ `GameCore::aiRng_` 本体）。
     *
     * 与 [AI_SECT] 的关系：两者是**不同种子、不同序列**的独立流
     *（`AI_SECT` = `seed + 6`，`aiRng_` = `seed + 6×31337`）——合并会改变 AI
     * 演化行为基线，故不合并；[AI_SECT] 保留供非委托模式的本地等价实现。
     *
     * **`inSnapshot = false`（通道型分区）**：`aiRng_` 的状态由 C++ 自己保管并
     * 随 `gameData.rngStates` 的 **9 号键**落盘/恢复，本枚举项只是 Kotlin 侧取用
     * 该流的句柄——若它也进出 `rngStates`，Kotlin 会把 `AI_SECT` 的流态写到同一个
     * 键上、与 `aiRng_` 的流态互相覆盖（两侧序列不同 ⇒ 跨语言对拍恒红）。
     * 本分区不在 [GameRngManager.exportStates]/`restoreStates` 遍历面内；
     * `GameCore::rngNextInt(9)` 等三入口与 C++ `syncRngStates` 单独处理该键。
     */
    AI_SECT_MIRROR(9, inSnapshot = false),
    /**
     * 弟子交谈（W4-A·A5 新增）：`DiscipleChatDialog` 决策类随机（交谈树/结果
     * 分支/效果增量抽取）。用户时序驱动的独立流——**不与既有分区共用**：
     * 交谈抽取的插入时机由玩家行为决定，混入任何结算分区都会扰动该分区的
     * 既有抽取序（红线 1）。`rngStates` 10 号键；旧档缺失时按
     * `systemSeed + 10` 播种（MISSION 同款恢复语义）。
     */
    CHAT(10),

    /**
     * 残留执行器本地随机域（R4.4/B14 新增）。
     *
     * ## 存在理由：消除 per-roll JNI
     * R4.4 之前，AUTHORITATIVE 下 `GameRngManager.rebuildPartitions()` 把
     * **全部** `inSnapshot` 分区实例化为 [NativeBackedRng]——于是残留执行器
     * （月/年变编排、亲属赠送/突破…）的每一次 `nextInt()` 都要跨 JNI 回到
     * C++ 取一个标量。委托对"结算与大世界推演同源"是必要的，但对**只花在
     * Kotlin 平台效应面上的随机**是纯开销（B14 实测：月/年结界点单批
     * 数百次 cross-line，见 `ResidualRngLocalityGuardTest` 计数替身实证）。
     *
     * 本分区把该域**独立出来**：Kotlin 侧持有**本地 PCG 实例**
     *（[DeterministicRng] 本地实现，`state` 字段真实使用），播种与快照全部
     * 走本地 state——**不再经 [NativeBackedRng] / [NativeRngChannel] 逐 roll 跨线**。
     *
     * ## 与既有分区的关系（零扰动）
     * - 本分区**只承接残留执行器本地消费面**；既有分区（BATTLE / SYSTEM /
     *   EXPLORATION / MAIL / MISSION / BREAKTHROUGH / ENEMY_GEN / SECRET_REALM /
     *   AI_SECT / CHAT）的委托关系与抽取序**逐位不变**（红线 1）；
     * - 通道型分区 [AI_SECT_MIRROR] 的通道语义（`inSnapshot = false`、
     *   C++ 保管流态）不受波及——本分区与之无关。
     *
     * ## 老档兼容（无键重播语义）
     * 生产 `rngStates` 若**无 11 号键**（R4.4 前的旧档），按 `systemSeed + 11`
     * 确定性重种（与 MISSION(8) / CHAT(10) 同款恢复语义，见
     * `GameRngManager.restoreStates` 的缺失键重播路径）——不崩溃、不漂移。
     *
     * ## 存档版本说明
     * 序列基线变化属**设计内行为变更**：同一 seed 下本分区的抽取序列与
     * R4.4 前"借用委托分区"的序列不同。变更已登记于
     * `CHANGELOG.md` 4.01.15 段与 `docs/native-engine-refactor-plan-2026-09-17.md`
     * §7.2 B14 段。
     */
    RESIDUAL(11);

    /**
     * 是否**本地 PCG 分区**（不参与 native 委托）。
     *
     * `true` ⇒ [GameRngManager] 为其持有 [DeterministicRng] 本地实例
     *（`state` 字段真实使用），抽取/快照/恢复全在 Kotlin 侧完成，
     * **零 per-roll 跨线**；`false` ⇒ AUTHORITATIVE 下实例化为
     * [NativeBackedRng]（逐 roll 委托 C++ 单一真相源）。
     *
     * 判定为**纯声明式**（枚举自带，无平台依赖）——桌面 GTest 与 JVM 单测
     * 可直接断言，无需加载 native 库。
     */
    val isLocal: Boolean
        get() = this == RESIDUAL
}
