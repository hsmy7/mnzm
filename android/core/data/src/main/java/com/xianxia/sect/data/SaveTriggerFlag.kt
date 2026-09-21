package com.xianxia.sect.data

/**
 * 后台保存触发开关（审计 §16 #6，推荐方案 A：`onStop` 触发一次保存）。
 *
 * ## 为什么默认关
 * 产品 2026-07-25 **主动移除**了自动存档（见 `docs/report-移除自动存档-接入云存档.md`），
 * 改为纯手动存档（设置页保存按钮 / 新游戏首存 / 重开游戏三个入口）。本旗标是"回摆"
 * 的**灰度开关**：默认关 ⇒ `onStop` 行为与历史逐行一致（**零行为变更**）；打开后
 * 退到后台时额外落一次盘，把进度损失窗口从"无上界"收敛到"最多一次会话"。
 *
 * ## 纪律
 * - 默认值不得改为 `true`——回摆属产品决策，须先拍板；
 * - 关闭时不得有任何副作用（`onStop` 仅多一次 volatile 读后短路）。
 */
object SaveTriggerFlag {

    /** 后台保存触发开关。**默认关 = 回滚臂**（见类 KDoc）。 */
    @Volatile
    var saveOnBackground: Boolean = false
}

/**
 * 是否应在 `onStop`（退到后台）触发一次保存——**纯函数**，桌面/JVM 可直测。
 *
 * 三个前置全部满足才保存：旗标开（灰度门控）、有有效槽位（无槽位无从落盘）、
 * 引擎已加载（未加载时快照无意义）。
 */
fun shouldSaveOnBackground(flagOn: Boolean, hasActiveSlot: Boolean, engineLoaded: Boolean): Boolean =
    flagOn && hasActiveSlot && engineLoaded
