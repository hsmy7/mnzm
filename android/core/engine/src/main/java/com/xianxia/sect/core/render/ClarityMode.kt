package com.xianxia.sect.core.render

/**
 * 各向异性过滤等级（用于地图纹理采样）。
 *
 * 主流手游画质档位对纹理采样分级（关 / 2x / 4x / 8x），此处作为 [ClarityMode] 的
 * 纹理采样维度。高清晰度档位启用更高各向异性以锐化高倍放大 / 斜视角。
 */
enum class AnisotropyMode(val level: Float) {
    OFF(1.0f),
    X2(2.0f),
    X4(4.0f),
    X8(8.0f)
}

/**
 * 自选清晰度 - 玩家可选的五档渲染质量策略（设置界面"性能模式"下方，独立于帧率）。
 *
 * 与 [com.xianxia.sect.core.engine.PerformanceMode]（帧率）正交：
 * - 性能模式管帧率（节能/均衡/性能）
 * - 清晰度管渲染分辨率 + 纹理采样 + 装饰 LOD
 *
 * 各档参数（对标主流手游 低/中/高/极致）：
 * - [renderScaleCap]：目标渲染缩放上限（主清晰度旋钮；实际值 = min(自动档, 本上限)）
 * - [qualityFactor]：清晰度质量因子（参与装饰 LOD 阈值/关闭判断，多因子取 min）
 * - [anisotropy]：各向异性过滤等级（依赖 Vulkan，Compose 通用精灵不受影响）
 * - [mipmap]：是否启用 mipmap（依赖地图图集带 mip，见资产管线 B.1）
 *
 * @param displayName 设置界面显示名
 * @param description 设置界面说明
 * @param renderScaleCap 目标渲染缩放上限（0.5~1.0）
 * @param qualityFactor 清晰度质量因子（0.4~1.0，参与装饰 LOD）
 * @param anisotropy 各向异性等级
 * @param mipmap 是否启用 mipmap
 */
enum class ClarityMode(
    val displayName: String,
    val description: String,
    val renderScaleCap: Float,
    val qualityFactor: Float,
    val anisotropy: AnisotropyMode,
    val mipmap: Boolean
) {
    /** 极低：最低渲染 + 关纹理过滤，最省电发热最低 */
    VERY_LOW(
        "极低",
        "最低渲染 + 关纹理过滤，最省电发热最低",
        0.5f, 0.40f, AnisotropyMode.OFF, false
    ),

    /** 低：低渲染 + 基础纹理，流畅优先 */
    LOW(
        "低",
        "低渲染 + 基础纹理，流畅优先",
        0.6f, 0.55f, AnisotropyMode.OFF, false
    ),

    /** 中（默认）：渲染缩放 0.8，质量因子不劣化基准（主杠杆是 renderScaleCap） */
    MEDIUM(
        "中",
        "均衡（默认）",
        0.8f, 1.00f, AnisotropyMode.X2, true
    ),

    /** 高：高渲染 + 高过滤 */
    HIGH(
        "高",
        "高渲染 + 高过滤，画面更锐利",
        1.0f, 1.00f, AnisotropyMode.X4, true
    ),

    /** 极高：原生渲染 + 顶级过滤 */
    VERY_HIGH(
        "极高",
        "原生渲染 + 顶级过滤，最清晰",
        1.0f, 1.00f, AnisotropyMode.X8, true
    );

    companion object {
        /** 从持久化字符串解析（null/非法值回退默认档 = 中） */
        fun fromStorage(name: String?): ClarityMode =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
