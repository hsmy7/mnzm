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
}
