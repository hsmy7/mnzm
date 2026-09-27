package com.xianxia.sect.data

/**
 * 自动保存触发开关（审计 §16 #6 方案 A `onStop` 触发 + SR-4 月变触发）。
 *
 * ## 与产品历史的关系
 * 产品 2026-07-25 **主动移除**了自动存档（见 `docs/report-移除自动存档-接入云存档.md`），
 * 改为纯手动存档（设置页保存按钮 / 新游戏首存 / 重开游戏三个入口）。
 * `saveOnBackground` 那次回摆仍以旗标默认关入库（`870be9771`）；SR-4 的**打开**由
 * 存档重构方案 §0 D6 明确拍板（"自动存档 = 游戏月月变钩子 + onStop … 本方案打开并接入云上传"，
 * 用户 2026-09-21），两个默认值即该拍板的落库形态。
 *
 * ## 频率口径（用户 2026-09-27 拍板：现实墙钟每 10 秒一存）
 * 触发源只保留两条：**现实墙钟节拍**（[realtimeTick]，每 10 现实秒）与 `onStop` 后台保存。
 * 历史上的"游戏月月变触发"（游戏月 = 3 旬 × 2000ms = 6 秒真实时间，2x 下 3 秒）
 * 已随 [autoSaveOnMonthChange] 停用：结算改现实时间连续化后，月界不再是进度完整点。
 * 现实节拍与游戏速度解耦 ⇒ 2x 下不会变成每 3 秒一存。
 *
 * ## 纪律
 * - 各方旗标各自保留关闭态 = 回滚臂（关掉即逐行回到"仅手动保存"的历史行为）；
 * - 关闭时不得有任何副作用（触发点第一行短路，`onStop` 仅多一次 volatile 读）。
 */
object SaveTriggerFlag {

    /**
     * 游戏月月变自动存档（D6/SR-4）。
     *
     * **已停用**：月变不再是自动存档触发源（用户 2026-09-27 拍板——结算改现实时间连续化后，
     * 月界不再是有意义的"进度完整点"，自动存档统一由现实墙钟节拍承担，见 [realtimeTick]）。
     * 字段保留仅为部署期兼容（旧版本写入的 `Settings` 值不因读不到键而崩溃），
     * 生产代码**不得**再消费本字段；删除条件 = 下一大版本确认无旧包回滚需求。
     */
    @Deprecated("月变触发已由现实墙钟节拍取代，改用 realtimeTick", ReplaceWith("realtimeTick"))
    @Volatile
    var autoSaveOnMonthChange: Boolean = true

    /** 后台保存触发（D6/SR-4 打开）。**默认开**；关 = 回滚臂（`870be9771` 入库态）。 */
    @Volatile
    var saveOnBackground: Boolean = true

    /**
     * 现实墙钟自动存档节拍（用户 2026-09-27 拍板：每 10 现实秒一存）。
     *
     * 与 [autoSaveOnMonthChange] 的区别是**时间基不同**：此处按现实单调时钟计时，
     * 与游戏速度（1x/2x）、暂停、游戏日历全部解耦——游戏暂停期间同样按真实时间落盘，
     * 因此"玩家多久没存档"由现实时间单一决定。节拍间隔见
     * `SaveLoadViewModelAutoSaveOps.REALTIME_AUTO_SAVE_INTERVAL_MS`。
     */
    @Volatile
    var realtimeTick: Boolean = true
}

/**
 * 自动保存是否该落盘——**纯函数**，桌面/JVM 可直测（`onStop` 与月变两个触发点共用）。
 *
 * 三个前置全部满足才保存：旗标开（灰度/回滚门控）、有有效槽位（无槽位无从落盘）、
 * 引擎已加载（未加载时快照无意义）。
 */
fun shouldAutoSave(flagOn: Boolean, hasActiveSlot: Boolean, engineLoaded: Boolean): Boolean =
    flagOn && hasActiveSlot && engineLoaded
