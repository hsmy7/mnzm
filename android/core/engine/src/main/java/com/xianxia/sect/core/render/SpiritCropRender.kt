package com.xianxia.sect.core.render

/**
 * 灵田作物生长动画纯函数 — 双渲染后端（Vulkan/Canvas）共享的数学来源。
 *
 * 输入为作物生长进度 progress01（0=刚播种，1=成熟），输出：
 * - [computeStage]：三阶段精灵索引（0=幼苗 / 1=成长期 / 2=成熟）
 * - [crossfade]：阶段内交叉淡化 alpha（0..1，随进度线性淡入）
 *
 * 绘制端（Vulkan drawAllTiles 作物段 / Canvas chunk 合成后逐帧绘制）
 * 均消费本纯函数输出，保证双端动画节奏一致。
 */
object SpiritCropRender {

    /** 作物生长阶段数（图集 CropStage 枚举项数） */
    const val CROP_STAGES = 3

    /** 阶段边界（1/3、2/3——与 [CROP_STAGES] 联动） */
    private const val STAGE_BOUNDARY = 1f / CROP_STAGES

    /**
     * 计算生长阶段索引（0..2）。
     *
     * @param progress01 生长进度 0..1（NaN/Inf/越界钳制为合法值）
     * @return 阶段索引 [0, CROP_STAGES)
     */
    fun computeStage(progress01: Float): Int {
        if (progress01.isNaN() || progress01.isInfinite()) return 0
        val p = progress01.coerceIn(0f, 1f)
        return when {
            p < STAGE_BOUNDARY -> 0
            p < STAGE_BOUNDARY * 2 -> 1
            else -> 2
        }
    }

    /**
     * 阶段内交叉淡化系数（0..1）。
     *
     * 本阶段精灵从 0 淡入到 1：阶段起点为 0，阶段终点（下一阶段边界）为 1——
     * 阶段切换时新精灵刚好完全不透明，无跳变。
     *
     * @param progress01 生长进度 0..1（NaN/Inf 防御返回 0）
     * @return 淡化 alpha [0, 1]
     */
    fun crossfade(progress01: Float): Float {
        if (progress01.isNaN() || progress01.isInfinite()) return 0f
        val p = progress01.coerceIn(0f, 1f)
        val stageStart = computeStage(p) / CROP_STAGES.toFloat()
        val local = (p - stageStart) * CROP_STAGES.toFloat()
        return local.coerceIn(0f, 1f)
    }

    /**
     * 逻辑帧间平滑进度（2026-08-13 批次 3 插值消费链）：
     * `prev + (cur - prev) × alpha`——渲染端在相邻两帧逻辑进度间插值，
     * 消除 10Hz 逻辑步下的生长跳变（对标 Godot 物理插值消费
     * get_physics_interpolation_fraction）。alpha=1 时等于当前值（无平滑）。
     *
     * @param previous 上一逻辑帧进度（无历史/非法时为 null → 返回当前值）
     * @param current 当前逻辑帧进度（NaN/Inf 防御原样返回）
     * @param alpha 插值因子 0..1（越界钳制）
     * @return 平滑后进度 [0, 1]
     */
    fun smoothedProgress(previous: Float?, current: Float, alpha: Float): Float {
        val safePrev = if (previous != null && !previous.isNaN() && !previous.isInfinite()) previous else null
        if (current.isNaN() || current.isInfinite() || safePrev == null) return current
        // alpha NaN 防御（对抗性审查 2026-08-13 边界#2）：Float.coerceIn 对 NaN
        // 返回自身会穿透——显式拦截为 0（无插值 = 直接用当前进度）
        val a = if (alpha.isNaN()) 0f else alpha.coerceIn(0f, 1f)
        return (safePrev + (current - safePrev) * a).coerceIn(0f, 1f)
    }

    /**
     * 作物进度插值键：gx/gy 网格坐标编码为 Long（与 C++ 作物段 key 编码
     * 同语义——网格坐标为整数浮点，取整编码无精度损失）。双后端插值
     * 状态表共用同一键语义。
     */
    fun cropProgressKey(gx: Float, gy: Float): Long =
        (gx.toInt().toLong() shl 32) or (gy.toInt().toLong() and 0xFFFFFFFFL)
}
