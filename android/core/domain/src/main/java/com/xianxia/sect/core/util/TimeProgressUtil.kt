package com.xianxia.sect.core.util

object TimeProgressUtil {

    // ── 年月整数族（B5 前旧判据；ProductionSlot 已切毫秒孪生，其余模型
    //    （AlchemySlot/ForgeSlot/CultivatorCave/灵田）仍消费——随各自毫秒化退役，
    //    债表 D2，勿新增消费点） ──

    fun calculateElapsedMonths(startYear: Int, startMonth: Int, currentYear: Int, currentMonth: Int): Int {
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        return (yearDiff * 12 + monthDiff).toInt()
    }

    fun calculateRemainingMonths(startYear: Int, startMonth: Int, duration: Int, currentYear: Int,
        currentMonth: Int): Int {
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return (duration - elapsed).coerceAtLeast(0)
    }

    fun calculateProgressPercent(startYear: Int, startMonth: Int, duration: Int, currentYear: Int,
        currentMonth: Int): Int {
        if (duration <= 0) return 0
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun calculateProgressFraction(startYear: Int, startMonth: Int, duration: Int, currentYear: Int,
        currentMonth: Int): Float {
        if (duration <= 0) return 0f
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return (elapsed.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    }

    fun isTimeElapsed(startYear: Int, startMonth: Int, duration: Int, currentYear: Int, currentMonth: Int): Boolean {
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return elapsed >= duration
    }

    // ── 游戏毫秒族（结算改造 2026-09-27 B5：槽位毫秒孪生判据，方案 §3.4.4）──
    // 输入均为 ProductionSlot.startedAtGameMs/completeAtGameMs 孪生与权威轴
    // GameData.elapsedGameMs；负差值（时钟异常/半写态）按 0 收敛。

    /** 已进行时长（游戏毫秒）：now − startedAt */
    fun calculateElapsedGameMs(startedAtGameMs: Long, nowGameMs: Long): Long =
        (nowGameMs - startedAtGameMs).coerceAtLeast(0L)

    /** 剩余时长（游戏毫秒）：completeAt − now，下限 0 */
    fun calculateRemainingGameMs(completeAtGameMs: Long, nowGameMs: Long): Long =
        (completeAtGameMs - nowGameMs).coerceAtLeast(0L)

    /** 进度比例 [0,1]：按 startedAt/completeAt 区间线性插值 */
    fun calculateProgressFractionByGameMs(
        startedAtGameMs: Long,
        completeAtGameMs: Long,
        nowGameMs: Long
    ): Float {
        val total = completeAtGameMs - startedAtGameMs
        if (total <= 0L) return 0f
        val elapsed = (nowGameMs - startedAtGameMs).coerceIn(0L, total)
        return elapsed.toFloat() / total.toFloat()
    }

    /** 完成判定：completeAt ≤ now（与 C++ isSlotCompleteDynamic 毫秒臂同式） */
    fun isElapsedByGameMs(completeAtGameMs: Long, nowGameMs: Long): Boolean =
        completeAtGameMs in 1..nowGameMs

    // ── 时间进度投影族（结算改造 2026-09-27 B8，方案 §10 B8「旬进度→时间进度」）──
    // INV-1 派生投影：进度条不再按旬三档量化（旧 gamePhase/3f），改为由
    // 权威轴旬内连续进度（GameTimeClock.phaseProgressFlow，INV-2 帧计划的
    // 旬内分量）与日历旬序合成的连续值。纯函数，可单元测试。

    /**
     * 月内时间进度 [0,1]：(已完成旬序 + 旬内连续进度)/3。
     *
     * 替代旧 `gamePhase/3f` 三档量化：旧口径月内进度一旬才走一步（2s@1x），
     * 进度条长期停在 1/3、2/3 刻度，读作「差一点不结算」；本投影随旬内
     * 时间连续逼近月界，收获判据（月界收割，B5 口径）不变。
     *
     * @param gamePhase 日历旬序 0..2（镜像投影）
     * @param phaseProgress 旬内连续进度 [0,1]（phaseProgressFlow 现值）
     */
    fun monthProgressFraction(gamePhase: Int, phaseProgress: Float): Float =
        ((gamePhase.coerceIn(0, 2) + phaseProgress.coerceIn(0f, 1f)) / 3f)
            .coerceIn(0f, 1f)

    /**
     * 月计时长工作进度 [0,1]：(已完成整月 + 月内时间进度)/总月数。
     *
     * 槽位类（炼丹/锻造等月计模型）进度条目标值唯一口径：整月部分来自
     * remainingMonths 差值（月界收割判据），月内小数部分来自
     * [monthProgressFraction] 连续投影。
     */
    fun slotProgressFraction(
        completedMonths: Int,
        monthProgressFraction: Float,
        totalDuration: Int
    ): Float {
        if (totalDuration <= 0) return 0f
        val elapsed = completedMonths + monthProgressFraction
        return (elapsed / totalDuration).coerceIn(0f, 1f)
    }
}
