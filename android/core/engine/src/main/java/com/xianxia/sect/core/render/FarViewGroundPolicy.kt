package com.xianxia.sect.core.render

/**
 * 远景地面容量策略（重构方案 R3.5）——纯函数：整岛缩小观看时是否启用
 * 「整图 REPEAT 地面 quad」替代逐格地面绘制。
 *
 * ## 背景
 * 逐格地面在整岛可见档（相机缩小、1 屏覆盖全图）会为每个可见格提交一个 sprite：
 * 地图 128×128 时最坏约 16384 sprite/帧（SpriteBatcher 总上限
 * `Rhi.h::MAX_SPRITES_PER_FRAME` = 20480，且与装饰/建筑/作物/云共享配额）
 * ⇒ 逼近容量悬崖，溢出后走 R0.3 有序降级
 * （先跳装饰层再截断），视觉上出现装饰成片消失。整图 REPEAT quad 把地面层压成
 * 1 个 draw call（几何 = 世界可见域与地图矩形求交），容量与画质同时受益。
 *
 * ## 为什么需要黑名单
 * 该路径在部分 **Adreno 驱动**上 REPEAT 采样异常（整图黑屏，`NativeBridge.cpp`
 * 既有编译期恒 false 注释所指）。因此本策略以纯函数形态给出「设备允许 + 缩放到位
 * + 图集就绪 + 用户开关」四重门，**默认不放行任何设备**（白名单为空 = 恒不启用），
 * 待真机验证后逐条登记放行。
 *
 * ## 纯函数契约（可测性红线）
 * 本对象不读取任何 Android 运行时字段（`Build.*` 由调用方解析后传入），
 * 命中判定逐条表驱动 ⇒ 守卫可覆盖「命中即回退逐格路径」「不命中即启用」两侧。
 */
object FarViewGroundPolicy {

    /**
     * 已知 REPEAT 采样异常的设备白名单（当前为空 = 本路径恒不启用）。
     *
     * 登记口径：条目必须来自真机验证（黑屏/花屏复现）或行业报告，注明设备/驱动版本
     * 与验证日期；禁止凭推测添加。空列表是**刻意的安全默认**——方案 R3.5 要求
     * 「带黑名单地验证」，未验证前不得全局打开。
     */
    val ALLOWED_DEVICES: Set<String> = emptySet()

    /**
     * 缩小到该阈值（含）以下视为「整岛观看」档，此时地面层改走整图 quad。
     * 与 [RenderLodPolicy.DECOR_ZOOM_THRESHOLD]（0.6，装饰层跳过）同源常量：
     * 装饰层已在此档跳过，地面层恰好由逐格转整图，两处降级同界不打架。
     */
    const val FAR_VIEW_SCALE_THRESHOLD = RenderLodPolicy.DECOR_ZOOM_THRESHOLD

    /**
     * 判定是否启用整图 REPEAT 地面路径。
     *
     * @param scale 相机缩放（NaN/Inf 视为小于阈值 → 不启用，防御）
     * @param deviceKey 设备标识（由调用方按 `SOC_MANUFACTURER/SOC_MODEL` 组装；
     *        非 API 31+ 或无值时传空串 ⇒ 视为不在白名单）
     * @param groundTextureReady 整图地面纹理是否已上传（id != 0）
     * @param userEnabled 用户侧开关（[com.xianxia.sect.core.nativebridge.NativeEngineFlag]
     *        的远景容量旗标；false = 强制逐格路径回滚臂）
     * @return true = 走整图 quad；false = 逐格地面（现状，含全部未验证设备）
     */
    fun groundQuadEnabled(
        scale: Float,
        deviceKey: String,
        groundTextureReady: Boolean,
        userEnabled: Boolean
    ): Boolean {
        // NaN/Inf 视为不达阈值 → 不启用（防御，同 RenderLodPolicy 口径）
        val withinFarView = !scale.isNaN() && !scale.isInfinite() &&
            scale <= FAR_VIEW_SCALE_THRESHOLD
        return userEnabled &&
            groundTextureReady &&
            withinFarView &&
            ALLOWED_DEVICES.contains(deviceKey)
    }
}
