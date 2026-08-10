package com.xianxia.sect.core.render

/**
 * 渲染特性开关聚合 — 配置级开关（由 [NativeRenderConfig] 携带，运行时只读）。
 *
 * 每个开关对应一个渲染特性，双后端（Vulkan/Canvas）统一消费：
 * - [buildingShadows]：建筑投影阴影（WP3）
 * - [selectionHighlight]：普通点击选中高亮描边（WP3）
 * - [vsyncPacing]：渲染线程 vsync 帧节奏对齐（WP5，软件路径主收益）
 * - [decorLod]：装饰层按缩放档位 LOD（WP5）
 * - [textureCompression]：Vulkan 图集 ASTC 压缩（WP7）
 *
 * ## 关闭语义
 * 开关关闭时对应特性双端同时消失（行为 = 特性未实现前的现状），
 * 用于极端低端设备兜底与渲染问题排查二分定位。
 */
data class RenderFlags(
    val buildingShadows: Boolean = true,
    val selectionHighlight: Boolean = true,
    val vsyncPacing: Boolean = true,
    val decorLod: Boolean = true,
    val textureCompression: Boolean = true
)
