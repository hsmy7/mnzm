package com.xianxia.sect.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscipleComponentsTest {

    @Test
    fun formatDiscipleAge_appendsYearUnit() {
        assertEquals("0岁", formatDiscipleAge(0))
        assertEquals("1岁", formatDiscipleAge(1))
        assertEquals("18岁", formatDiscipleAge(18))
        assertEquals("999岁", formatDiscipleAge(999))
    }
}
