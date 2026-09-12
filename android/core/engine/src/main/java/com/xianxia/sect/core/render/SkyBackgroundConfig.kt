package com.xianxia.sect.core.render

/**
 * 程序绘制天空渐变背景的颜色（r/g/b ∈ [0,1]，无 Android 依赖）。
 * Vulkan/C++ 路径经 [NativeBridge.setSkyConfig] 推送到 C++；Canvas 路径在
 * [com.xianxia.sect.ui.game.sect.SoftwareCanvasBackend] 内换算为屏幕渐变。
 */
data class SkyColor(val r: Float, val g: Float, val b: Float) {
    companion object {
        /** 顶部：蔚蓝 #4B9FD1 */
        val DEFAULT_TOP = SkyColor(0.294f, 0.624f, 0.820f)
        /** 25% 处：浅蓝青 #62B0D8 */
        val DEFAULT_SECOND = SkyColor(0.384f, 0.690f, 0.847f)
        /** 60% 处：更浅蓝青 #78C0DF */
        val DEFAULT_THIRD = SkyColor(0.471f, 0.753f, 0.875f)
        /** 底部：淡蓝白 #A9DCE8 */
        val DEFAULT_BOTTOM = SkyColor(0.663f, 0.863f, 0.910f)
    }
}

/**
 * SkyBackgroundConfig — 程序绘制天空渐变背景的配置（渲染侧单一真相源）。
 *
 * 采用**四段渐变**（四个停靠色 top→second→third→bottom，位于 0/secondT/thirdT/1）：
 * 默认停靠位置 secondT=0.25、thirdT=0.6。作为 NativeSurfaceView 的 sky 配置；
 * Vulkan/GLES 路径经 [com.xianxia.sect.core.nativebridge.NativeBridge.setSkyConfig]
 * 推送到 C++，Canvas 软件路径由
 * [com.xianxia.sect.ui.game.sect.SoftwareCanvasBackend] 直接读取——两侧同一份配置。
 *
 * ## 未来扩展（天气/时间系统）
 * 晴天/傍晚/夜晚/阴天只需修改各停靠色/位置/强度，无需改动地图/建筑/Camera 渲染。
 */
data class SkyBackgroundConfig(
    val topColor: SkyColor = SkyColor.DEFAULT_TOP,
    /** 第二个停靠色（位于 [secondT]） */
    val secondColor: SkyColor = SkyColor.DEFAULT_SECOND,
    /** 第三个停靠色（位于 [thirdT]） */
    val thirdColor: SkyColor = SkyColor.DEFAULT_THIRD,
    val bottomColor: SkyColor = SkyColor.DEFAULT_BOTTOM,
    /** 第二停靠归一化位置（0=屏幕顶，1=屏幕底；默认 0.33） */
    val secondT: Float = 0.33f,
    /** 第三停靠归一化位置（0=屏幕顶，1=屏幕底；默认 0.66） */
    val thirdT: Float = 0.66f,
    /** 渐变强度（0=整面平铺为顶色，1=全渐变） */
    val strength: Float = 1f
) {
    companion object {
        /** 默认配置（晴朗日间四段天幕：深蔚蓝 → 浅蓝青 → 更浅 → 淡蓝白） */
        val DEFAULT = SkyBackgroundConfig()
    }
}
