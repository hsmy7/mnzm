package com.xianxia.sect.ui.game.saveload

/**
 * 保存链反馈口径（SR-4）——同一条保存链服务三种触发，提示语义必须分流。
 *
 * 出处：`SaveLoadViewModelSaveOps` 原注释登记的技术债（"如需严格静默需给保存链加
 * `silent` 形参，属后续项"）在 SR-4 兑现——自动存档触发（月变每 6 秒一次 / `onStop`）
 * 若沿用手动保存的 snackbar 文案，会把消息刷没完；但**失败一律仍走告警通道**
 * （方案 §4 SR-4"失败走告警通道，不再静默"，三种口径共享）。
 *
 * 默认值 [Manual] ⇒ 手动保存路径逐行零变化（LEGACY/手动硬红线）。
 */
sealed interface SaveFeedback {

    /** 设置页/存档面板"保存"：成功 `"游戏保存成功"`，失败如实 snackbar（现状语义） */
    data object Manual : SaveFeedback

    /** 月变自动存档：成功给消息栏**常驻一行**（非常驻弹窗），失败 snackbar */
    data object AutoNotice : SaveFeedback

    /**
     * `onStop` 后台保存：成功零提示（玩家已离场），失败仍投递告警通道——
     * 退到后台后提示不可见是既有已登记限制（见 `saveOnBackground` KDoc），不粉饰为"静默成功"。
     */
    data object Silent : SaveFeedback
}
