package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.*
import org.junit.Test

class TimeSystemPureLogicTest {

    // --- 常量 ---

    @Test
    fun phasesPerMonth_is3() {
        assertEquals(3, TimeSystem.PHASES_PER_MONTH)
    }

    @Test
    fun monthsPerYear_is12() {
        assertEquals(12, TimeSystem.MONTHS_PER_YEAR)
    }

    // --- advancePhase 纯逻辑（驱动冻结基准 advancePhaseBaseline，不再内联复刻） ---

    private fun advancePhase(currentPhase: Int, currentMonth: Int, currentYear: Int): Triple<Int, Int, Int> {
        val state = MutableGameState(
            gameData = GameData().apply {
                gamePhase = currentPhase; gameMonth = currentMonth; gameYear = currentYear
            },
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
            manualStacks = EntityStore(), manualInstances = EntityStore(),
            pills = EntityStore(), materials = EntityStore(),
            herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        state.advancePhaseBaseline(1)
        return Triple(state.gameData.gameYear, state.gameData.gameMonth, state.gameData.gamePhase)
    }

    @Test
    fun advancePhase_phaseIncrements_from0() {
        val (year, month, phase) = advancePhase(0, 1, 1)
        assertEquals(1, year)
        assertEquals(1, month)
        assertEquals(1, phase)
    }

    @Test
    fun advancePhase_phaseIncrements_from1() {
        val (year, month, phase) = advancePhase(1, 1, 1)
        assertEquals(1, year)
        assertEquals(1, month)
        assertEquals(2, phase)
    }

    @Test
    fun advancePhase_monthRollsOver() {
        val (year, month, phase) = advancePhase(2, 1, 1)
        assertEquals(1, year)
        assertEquals(2, month)
        assertEquals(0, phase)
    }

    @Test
    fun advancePhase_yearRollsOver() {
        val (year, month, phase) = advancePhase(2, 12, 1)
        assertEquals(2, year)
        assertEquals(1, month)
        assertEquals(0, phase)
    }

    @Test
    fun advancePhase_midYearMonthRoll() {
        val (year, month, phase) = advancePhase(2, 6, 5)
        assertEquals(5, year)
        assertEquals(7, month)
        assertEquals(0, phase)
    }

    // --- 总月数 / 总旬数计算公式 ---

    @Test
    fun totalMonthsCalculation() {
        val year = 3
        val month = 5
        val totalMonths = year * 12 + month
        assertEquals(41, totalMonths)
    }

    @Test
    fun totalPhasesCalculation() {
        val year = 3
        val month = 5
        val phase = 2
        val totalPhases = year * 12 * 3 + (month - 1) * 3 + phase
        assertEquals(122, totalPhases)
    }
}
