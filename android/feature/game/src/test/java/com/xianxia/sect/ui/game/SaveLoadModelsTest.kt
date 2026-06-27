package com.xianxia.sect.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveLoadModelsTest {

    @Test
    fun `progress constants - have monotonically increasing values`() {
        val values = listOf(
            SaveLoadViewModelConstants.PROGRESS_START,
            SaveLoadViewModelConstants.PROGRESS_ENGINE_INIT,
            SaveLoadViewModelConstants.PROGRESS_DATA_LOAD,
            SaveLoadViewModelConstants.PROGRESS_SAVE_COMPLETE,
            SaveLoadViewModelConstants.PROGRESS_RESTART_DATA_LOAD,
            SaveLoadViewModelConstants.PROGRESS_DATA_PRELOAD,
            SaveLoadViewModelConstants.PROGRESS_SPRITE_PRELOAD,
            SaveLoadViewModelConstants.PROGRESS_GAME_LOOP_START,
            SaveLoadViewModelConstants.PROGRESS_MAP_PRELOAD,
            SaveLoadViewModelConstants.PROGRESS_COMPLETE
        )
        for (i in 1 until values.size) {
            assertTrue(
                "PROGRESS at index $i (${values[i]}) should be >= index ${i - 1} (${values[i - 1]})",
                values[i] >= values[i - 1]
            )
        }
    }

    @Test
    fun `progress constants - start is 0`() {
        assertEquals(0f, SaveLoadViewModelConstants.PROGRESS_START)
    }

    @Test
    fun `progress constants - complete is 1`() {
        assertEquals(1f, SaveLoadViewModelConstants.PROGRESS_COMPLETE)
    }

    @Test
    fun `phase constants - are distinct and non-empty`() {
        val phases = setOf(
            SaveLoadViewModelConstants.PHASE_INIT,
            SaveLoadViewModelConstants.PHASE_DATA_PRELOAD,
            SaveLoadViewModelConstants.PHASE_SPRITE_PRELOAD,
            SaveLoadViewModelConstants.PHASE_READY
        )
        assertEquals(4, phases.size)
        phases.forEach { phase ->
            assertTrue("Phase '$phase' should not be empty", phase.isNotEmpty())
        }
    }

    @Test
    fun `SaveLoadState - isBusy when saving`() {
        val state = SaveLoadState(isSaving = true, isLoading = false)
        assertTrue(state.isBusy)
    }

    @Test
    fun `SaveLoadState - isBusy when loading`() {
        val state = SaveLoadState(isSaving = false, isLoading = true)
        assertTrue(state.isBusy)
    }

    @Test
    fun `SaveLoadState - isNotBusy when idle`() {
        val state = SaveLoadState(isSaving = false, isLoading = false)
        assertTrue(!state.isBusy)
    }
}
