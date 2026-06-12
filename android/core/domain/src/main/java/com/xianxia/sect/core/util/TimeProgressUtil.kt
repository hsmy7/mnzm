package com.xianxia.sect.core.util

object TimeProgressUtil {

    fun calculateElapsedMonths(startYear: Int, startMonth: Int, currentYear: Int, currentMonth: Int): Int {
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        return (yearDiff * 12 + monthDiff).toInt()
    }

    fun calculateRemainingMonths(startYear: Int, startMonth: Int, duration: Int, currentYear: Int, currentMonth: Int): Int {
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return (duration - elapsed).coerceAtLeast(0)
    }

    fun calculateProgressPercent(startYear: Int, startMonth: Int, duration: Int, currentYear: Int, currentMonth: Int): Int {
        if (duration <= 0) return 0
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun calculateProgressFraction(startYear: Int, startMonth: Int, duration: Int, currentYear: Int, currentMonth: Int): Float {
        if (duration <= 0) return 0f
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return (elapsed.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    }

    fun isTimeElapsed(startYear: Int, startMonth: Int, duration: Int, currentYear: Int, currentMonth: Int): Boolean {
        val elapsed = calculateElapsedMonths(startYear, startMonth, currentYear, currentMonth)
        return elapsed >= duration
    }
}
