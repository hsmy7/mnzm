package com.xianxia.sect.core.dialog

import com.xianxia.sect.core.domain.dialog.DialogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DialogManagerImpl] 核心行为单元测试。
 *
 * 覆盖：
 * - open/close/closeAll 基本操作
 * - open 替换已有对话框行为
 * - 无对话框时 close 幂等
 * - params 传递
 * - params 防御性拷贝
 * - DialogType.None 守卫
 */
class DialogManagerImplTest {

    private lateinit var manager: DialogManagerImpl

    @Before
    fun setUp() {
        manager = DialogManagerImpl()
    }

    @Test
    fun `open - sets currentDialog to the opened entry`() {
        manager.open(DialogType.Settings)
        assertEquals(DialogType.Settings, manager.currentDialog.value?.type)
    }

    @Test
    fun `close - sets currentDialog to null`() {
        manager.open(DialogType.Buildings)
        manager.close()
        assertNull(manager.currentDialog.value)
    }

    @Test
    fun `open - replaces previous dialog`() {
        manager.open(DialogType.Disciples)
        manager.open(DialogType.Warehouse)
        assertEquals(DialogType.Warehouse, manager.currentDialog.value?.type)
    }

    @Test
    fun `close - when no dialog open does not throw`() {
        // Should not throw
        manager.close()
    }

    @Test
    fun `closeAll - clears dialog`() {
        manager.open(DialogType.Settings)
        manager.closeAll()
        assertNull(manager.currentDialog.value)
    }

    @Test
    fun `open - stores params correctly`() {
        manager.open(DialogType.Alchemy("building_001"), mapOf("slot" to "A"))
        val entry = manager.currentDialog.value
        assertEquals("building_001", (entry?.type as? DialogType.Alchemy)?.buildingInstanceId)
        assertEquals("A", entry?.params?.get("slot"))
    }

    @Test
    fun `open - multiple close calls are idempotent`() {
        manager.open(DialogType.Disciples)
        manager.close()
        manager.close() // second close should be no-op
        assertNull(manager.currentDialog.value)
    }

    @Test
    fun `open - rapid open close open shows last dialog`() {
        manager.open(DialogType.Disciples)
        manager.close()
        manager.open(DialogType.Warehouse)
        assertEquals(DialogType.Warehouse, manager.currentDialog.value?.type)
    }

    @Test
    fun `currentDialog - initial state is null`() {
        assertNull(manager.currentDialog.value)
    }

    @Test
    fun `open - with empty params map`() {
        manager.open(DialogType.Settings, emptyMap())
        assertEquals(emptyMap<String, Any?>(), manager.currentDialog.value?.params)
    }

    @Test
    fun `open - with null params value`() {
        manager.open(DialogType.Settings, mapOf("key" to null))
        assertEquals(null, manager.currentDialog.value?.params?.get("key"))
    }

    @Test
    fun `open None - treats as close and sets null`() {
        manager.open(DialogType.Settings)
        manager.open(DialogType.None)
        assertNull("open(None) should set currentDialog to null", manager.currentDialog.value)
    }

    @Test
    fun `open None then open real - shows the real dialog`() {
        manager.open(DialogType.None)
        manager.open(DialogType.Disciples)
        assertEquals(DialogType.Disciples, manager.currentDialog.value?.type)
    }

    @Test
    fun `open - params defensive copy prevents external mutation`() {
        val mutableParams = mutableMapOf("count" to 1)
        manager.open(DialogType.Settings, mutableParams)
        mutableParams["count"] = 999  // external mutation
        assertEquals(1, manager.currentDialog.value?.params?.get("count"))
    }

    @Test
    fun `open - emptyMap passed as params is valid`() {
        manager.open(DialogType.Settings, mapOf())
        assertEquals(0, manager.currentDialog.value?.params?.size ?: -1)
    }

    @Test
    fun `open - with buildingInstanceId params`() {
        manager.open(DialogType.Alchemy("bld_1"))
        val type = manager.currentDialog.value?.type
        assertTrue(type is DialogType.Alchemy)
        assertEquals("bld_1", (type as DialogType.Alchemy).buildingInstanceId)
    }

    @Test
    fun `closeAll - already null stays null`() {
        assertNull(manager.currentDialog.value)
        manager.closeAll()
        assertNull(manager.currentDialog.value)
    }
}
