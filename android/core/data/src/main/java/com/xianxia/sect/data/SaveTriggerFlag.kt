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
 * ## 频率口径（月月必存，用户 2026-09-22 在三选中拍板）
 * 游戏月 = 3 旬 × 2000ms = **6 秒真实时间**（2x 下 3 秒），⇒ 月变自动存档是每 6 秒一次
 * 全量快照 + 本地事务，**不是**墙钟"每月一次"。性能/限频后果见 `docs/parallel-batches-w5/`
 * `batch-SR4.md` §1 与 SR-4 完成报告。
 *
 * ## 纪律
 * - 两旗标各自保留关闭态 = 回滚臂（关掉即逐行回到"仅手动保存"的历史行为）；
 * - 关闭时不得有任何副作用（触发点第一行短路，`onStop` 仅多一次 volatile 读）。
 */
object SaveTriggerFlag {

    /** 游戏月月变自动存档（D6/SR-4）。**默认开**；关 = 回滚到"仅手动保存"。 */
    @Volatile
    var autoSaveOnMonthChange: Boolean = true

    /** 后台保存触发（D6/SR-4 打开）。**默认开**；关 = 回滚臂（`870be9771` 入库态）。 */
    @Volatile
    var saveOnBackground: Boolean = true
}

/**
 * 自动保存是否该落盘——**纯函数**，桌面/JVM 可直测（`onStop` 与月变两个触发点共用）。
 *
 * 三个前置全部满足才保存：旗标开（灰度/回滚门控）、有有效槽位（无槽位无从落盘）、
 * 引擎已加载（未加载时快照无意义）。
 */
fun shouldAutoSave(flagOn: Boolean, hasActiveSlot: Boolean, engineLoaded: Boolean): Boolean =
    flagOn && hasActiveSlot && engineLoaded
