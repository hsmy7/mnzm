package com.xianxia.sect.ui.components

import com.xianxia.sect.core.util.PortraitPool

/**
 * 弟子立绘资源键 → drawable 资源 ID 的统一解析入口。
 *
 * 弟子实例的显示字段只有一个 `portraitRes`，它同时承载两类取值域：
 * 通用弟子像名（`male_disciple_1..20` / `female_disciple_1..17`，由
 * [PortraitPool] 持有）与具名角色立绘键（`portrait_<角色id>`，由
 * [SpriteCategory.CHARACTER] 分类注册在 [SpriteResRegistry]）。本函数按序查这两个域，
 * 使调用方无需关心键属于哪一类。
 *
 * ## 两个查询域互斥
 * [PortraitPool] 的 37 个通用像名是 `PortraitPool` 内部枚举，不出现在
 * `android/scripts/resource-registry.json` 中（该文件的 PORTRAIT 分类只有
 * `disciple_portrait` 一项），因此通用像名在 [SpriteResRegistry.resolve] 域内查不到；
 * 反之角色立绘键也不在 [PortraitPool] 的映射里。显示链上两域的公共键仅
 * `disciple_portrait`，它只由 [SpriteResRegistry] 提供，供调用方作兜底资源。
 * 域互斥 ⇒ 查询顺序不影响解析结果，只影响热点路径开销。
 *
 * ## 顺序理由
 * [PortraitPool.getResourceId] 是启动期预构建的 O(1) map，而
 * [SpriteResRegistry.resolve] 要线性扫全部分类映射。弟子列表滚动等热点场景里
 * 绝大多数键是通用像，故通用像域先查。
 *
 * ## 禁止把角色键并入 [PortraitPool] 或其预载清单
 * - [PortraitPool] 的成员清单是被测试锁定的契约（`PortraitPoolTest` 断言恰为 37 名），
 *   扩充会使其判红；
 * - `ResourcePreloader` 的肖像预载走 `PortraitPool.allPortraitNames()`，且位图上限
 *   `MAX_PORTRAIT_DIMENSION = 256`——角色立绘是 1024 档大图，进该清单会被降质预载，
 *   与「大图类不预载」的既有决策冲突。
 *
 * 角色键经本函数解析出资源 ID 后，`PortraitImage`（`:feature:game`）的
 * 缓存按键必然落空，从而走 `painterResource` 全分辨率按需解码——这是有意路径，
 * 不需要为角色键补缓存。
 *
 * 妖兽展示键（`beast_<index>`）不属于这两个域，由调用方在调用本函数前自行分支处理。
 *
 * @param portraitRes 弟子立绘资源键（可为空串）
 * @return drawable 资源 ID；0 = 无法解析，调用方自行兜底通用像
 */
fun resolvePortraitResId(portraitRes: String): Int {
    if (portraitRes.isBlank()) return 0
    val pooled = PortraitPool.getResourceId(portraitRes)
    if (pooled != 0) return pooled
    return SpriteResRegistry.resolve(portraitRes) ?: 0
}
