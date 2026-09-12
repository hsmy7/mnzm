package com.xianxia.sect.core.engine

/**
 * 性能模式 — 玩家可选的帧率/画质策略（设置界面三档）。
 *
 * 帧率最终值为「场景 × 性能模式 × 热控/电量」三者取 min：
 * `effectiveFps = minOf(thermal.recommendedTargetFps, sceneFps(场景, 模式), batteryAware.fpsCap)`
 * 质量因子同理取 min，装饰层在节能档强制关闭。
 *
 * @param displayName 设置界面显示名
 * @param qualityFactor 该档质量因子（与热控质量因子取 min）
 * @param dynamic 是否启用动态帧率（均衡模式：活跃 60 → 静止 30 → 深闲置 10）
 */
enum class PerformanceMode(
    val displayName: String,
    val description: String,
    val qualityFactor: Float,
    val dynamic: Boolean
) {
    /** 节能：锁 30fps + 低画质 + 关装饰层（深闲置 30s 后仍降 10fps 保电） */
    ENERGY_SAVING(
        "节能",
        "锁 30 帧 + 低画质，最省电发热最低",
        0.8f,
        false
    ),

    /** 均衡（默认）：动态帧率 — 活跃 60fps，静止 5s 降 30fps，深闲置 30s 降 10fps */
    BALANCED(
        "均衡",
        "动态帧率：操作 60 帧，挂机静止自动降 30 帧（默认）",
        1.0f,
        true
    ),

    /** 性能：活跃场景恒 60fps（深闲置 30s 后仍降 10fps 保电） */
    PERFORMANCE(
        "性能",
        "恒 60 帧，挂机 30 秒后仍会降至 10 帧保电",
        1.0f,
        false
    );

    companion object {
        /**
         * 从持久化字符串解析性能模式。
         *
         * @param name 存储值（null 或非法值回退默认档）
         * @return 解析结果，非法输入返回 [BALANCED]
         */
        fun fromStorage(name: String?): PerformanceMode =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
