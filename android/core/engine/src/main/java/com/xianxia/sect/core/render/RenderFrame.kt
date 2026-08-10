package com.xianxia.sect.core.render

/**
 * 原生渲染器配置（由 Compose 层创建，渲染线程只读）。
 *
 * 双后端（Vulkan/Canvas）共用同一配置，保证世界尺寸/瓦片尺寸一致。
 *
 * @property tileSize 瓦片像素尺寸（正方形边长）
 * @property worldWidthCells 世界宽度（格数）
 * @property worldHeightCells 世界高度（格数）
 * @property worldPixelWidth 世界像素宽度（tileSize × worldWidthCells）
 * @property worldPixelHeight 世界像素高度（tileSize × worldHeightCells）
 * @property renderFlags 渲染特性开关聚合（默认全开，低端设备兜底关闭）
 */
data class NativeRenderConfig(
    val tileSize: Int,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val worldPixelWidth: Int,
    val worldPixelHeight: Int,
    val renderFlags: RenderFlags = RenderFlags()
)

/**
 * RenderFrame — 渲染管线唯一数据契约。
 *
 * 由 Compose 层批量写入（见宿主 View 的 updateRenderState 通道），
 * Vulkan 和 Canvas 两路径均消费同一份 [RenderFrame]，杜绝数据不同步。
 *
 * ## 设计原则
 * - [tileData] 非 null：编译期强制调用方传入，NullPointerException 将
 *   在写入入口尽早抛出，而非等到渲染线程静默画底色
 * - [cols]/[rows]：瓦片矩阵尺寸，用于 [tileData] 完整性验证
 * - uvMap/buildingUVMap 不在此处传递：Vulkan 和 Canvas 两后端均从
 *   [SpriteAtlasDef] 编译时常量读取，无需帧级数据传递
 *
 * ## 跨平台
 * 纯 Kotlin data class（位于 :core:engine，零 Android 依赖），
 * iOS Metal 渲染后端直接复用同一契约。
 *
 * @property tileData 瓦片类型数据（展平一维，index = row * cols + col）非 null
 * @property cols 地图列数（世界格数）
 * @property rows 地图行数（世界格数）
 * @property buildingData 建筑数据 [gx, gy, w, h, nameIdx] × N（可选，无建筑时为 null）
 * @property buildingCount 建筑数量
 * @property buildingVisible 建筑层是否可见
 * @property selectedBuildingIndex 选中建筑索引（-1=无选中；越界时双后端自动跳过绘制）
 * @property spiritCropData 灵田作物数据 [gx, gy, progress01] × N（可选，无作物时为 null；
 * 低频变化走帧率门控 RenderFrame——不新增命令总线槽位）
 * @property camX 相机 X（世界像素，视口左上角）
 * @property camY 相机 Y（世界像素，视口左上角）
 * @property scale 相机缩放
 * @property showPreview 是否显示建造/移动预览覆盖层
 * @property previewX 预览矩形左上 X（世界像素）
 * @property previewY 预览矩形左上 Y（世界像素）
 * @property previewW 预览矩形宽度（世界像素）
 * @property previewH 预览矩形高度（世界像素）
 * @property previewU0 预览精灵左上 U（图集 0-1）
 * @property previewV0 预览精灵左上 V（图集 0-1）
 * @property previewU1 预览精灵右下 U（图集 0-1）
 * @property previewV1 预览精灵右下 V（图集 0-1）
 * @property previewTintRed 预览调色 R 倍率（0-1，1=原色）
 * @property previewTintGreen 预览调色 G 倍率（0-1，1=原色）
 * @property previewTintBlue 预览调色 B 倍率（0-1，1=原色）
 * @property previewAlpha 预览透明度（0-1）
 */
data class RenderFrame(
    /** 瓦片类型数据（展平一维，index = row * cols + col）非 null */
    val tileData: IntArray,
    /** 地图列数（世界格数） */
    val cols: Int,
    /** 地图行数（世界格数） */
    val rows: Int,

    /** 建筑数据 [gx, gy, w, h, nameIdx] × N（可选，无建筑时为 null） */
    val buildingData: FloatArray? = null,
    val buildingCount: Int = 0,
    val buildingVisible: Boolean = true,

    /** 选中建筑索引（-1=无选中；越界/无建筑时双后端自动跳过高亮绘制） */
    val selectedBuildingIndex: Int = -1,

    /** 灵田作物数据 [gx, gy, progress01] × N（可选，无作物时为 null） */
    val spiritCropData: FloatArray? = null,

    // 相机
    val camX: Float = 0f,
    val camY: Float = 0f,
    val scale: Float = 1f,

    // 预览覆盖层（建造/移动模式）
    val showPreview: Boolean = false,
    val previewX: Float = 0f,
    val previewY: Float = 0f,
    val previewW: Float = 0f,
    val previewH: Float = 0f,
    val previewU0: Float = 0f,
    val previewV0: Float = 0f,
    val previewU1: Float = 0f,
    val previewV1: Float = 0f,
    val previewTintRed: Float = 0.25f,
    val previewTintGreen: Float = 1.0f,
    val previewTintBlue: Float = 0.25f,
    val previewAlpha: Float = 0.5f
)
