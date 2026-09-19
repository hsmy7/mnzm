package com.xianxia.sect.ui.game.sect

import android.os.Build

/**
 * 渲染设备标识（R3.5 远景观看容量路径的黑名单键）。
 *
 * ## 职责
 * 把设备信息收敛成**一个稳定字符串键**，供
 * [com.xianxia.sect.core.render.FarViewGroundPolicy] 的白名单做表驱动命中判定。
 * 设备知识（`Build.*` 读取 + API 级别守卫）留在本对象，策略侧保持零平台依赖 ⇒
 * 守卫可直测策略的命中/不命中两侧。
 *
 * ## 为什么单独成类
 * [Build.SOC_MANUFACTURER]/[Build.SOC_MODEL] 是 **API 31+** 字段：旧设备直接访问
 * 会抛 `NoSuchFieldError`（仓库规范 13.3 明列此坑）。集中一处守卫，
 * 避免判定逻辑散落到渲染热路径各处。
 *
 * ## 键格式
 * `"{socManufacturer}/{socModel}"`，各段小写去空白；任一段缺失时退化为可用部分；
 * 两段全缺 = 空串（**必然不在白名单** ⇒ 未识别设备恒走逐格地面，安全默认）。
 */
object RenderDeviceKey {

    /**
     * 当前设备键（进程内稳定——`Build` 字段不会变化，惰性求值一次）。
     *
     * 白名单条目须与本格式逐字符一致（登记时以真机实测打印值为准）。
     */
    val current: String by lazy {
        val (manufacturer, model) = socFields()
        buildKey(manufacturer, model)
    }

    /**
     * 组装设备键（纯函数，供守卫直测而不依赖真实设备）。
     *
     * @param socManufacturer `Build.SOC_MANUFACTURER`（API 31+；缺失传 null）
     * @param socModel `Build.SOC_MODEL`（API 31+；缺失传 null）
     * @return 小写去空白的 `"manufacturer/model"`；任一段缺失时只保留可用段；
     *         两段皆空返回空串
     */
    fun buildKey(socManufacturer: String?, socModel: String?): String {
        val manufacturer = socManufacturer?.trim()?.lowercase().orEmpty()
        val model = socModel?.trim()?.lowercase().orEmpty()
        if (manufacturer.isEmpty()) return model
        if (model.isEmpty()) return manufacturer
        return "$manufacturer/$model"
    }

    /**
     * 安全读取 API 31+ 的 SoC 字段（旧 API 返回 null，不抛异常）。
     *
     * `@Suppress("NewApi")`：字段访问已由 [Build.VERSION.SDK_INT] 守卫包住——
     * 若把读取提到守卫外，旧设备会触发 `NoSuchFieldError` 闪退。
     */
    @Suppress("NewApi")
    private fun socFields(): Pair<String?, String?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER to Build.SOC_MODEL
        } else {
            null to null
        }
}
