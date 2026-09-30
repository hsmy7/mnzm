package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 六部位枚举守卫（装备重构 B3，方案 §3.1/§6.1）。
 *
 * `EquipmentSlot` 六值（HEAD/BODY/HANDS/FEET/WEAPON/LEGS，编号 10..15）是
 * 全链的部位锚点：UI 六宫格顺序、Disciple.EquipmentSet 六列、proto 六列号、
 * C++ 六列全部挂在其上。本守卫钉死：
 * 1. `displayOrder` 覆盖全部枚举值且与声明序一致（R1 指定序 头/身/手/脚/武/腿）；
 * 2. `@ProtoNumber` 编号 10..15 与常量一一对应、无重复（BINARY 注解不可反射，
 *    走源码扫描——Gradle 工作目录 = android/core/domain）。
 */
class EquipmentSlotOrderGuardTest {

    /** Items.kt 源文件（Gradle 测试工作目录 = android/core/domain） */
    private val itemsSource: String by lazy {
        val file = File("../../core/domain/src/main/java/com/xianxia/sect/core/model/Items.kt")
        assertTrue("Items.kt 不可达（工作目录应为 android/core/domain）：${file.absolutePath}", file.isFile)
        file.readText()
    }

    /** 枚举声明块（`enum class EquipmentSlot` 起至分号收尾） */
    private val enumBlock: String by lazy {
        val start = itemsSource.indexOf("enum class EquipmentSlot")
        assertTrue("Items.kt 中找不到 EquipmentSlot 枚举", start >= 0)
        val end = itemsSource.indexOf(';', start)
        assertTrue("EquipmentSlot 枚举块未按分号收尾", end > start)
        itemsSource.substring(start, end)
    }

    @Test
    fun `displayOrder覆盖全部枚举值且与声明序一致`() {
        val declared = EquipmentSlot.entries.toList()
        assertEquals("displayOrder 必须覆盖全部枚举值", declared.toSet(), EquipmentSlot.displayOrder.toSet())
        assertEquals(
            "displayOrder 与枚举声明序不一致（R1 指定序 头/身/手/脚/武/腿，重排会改写 UI 六宫格与消费面顺序）",
            declared, EquipmentSlot.displayOrder
        )
    }

    @Test
    fun `六常量ProtoNumber编号钉死且无重复`() {
        val expected = linkedMapOf(
            "HEAD" to 10,
            "BODY" to 11,
            "HANDS" to 12,
            "FEET" to 13,
            "WEAPON" to 14,
            "LEGS" to 15
        )
        val regex = Regex("@ProtoNumber\\((\\d+)\\)\\s+(HEAD|BODY|HANDS|FEET|WEAPON|LEGS)\\b")
        val found = regex.findAll(enumBlock).map { it.groupValues[2] to it.groupValues[1].toInt() }.toMap()
        assertEquals(
            "六常量的 @ProtoNumber 编号面与预期不符（编号退役禁复用，改动须走迁移）",
            expected, found
        )
        // 编号无重复（10..15 各出现一次由上断言保证，此处双保险扫整个枚举块）
        val allNumbers = Regex("@ProtoNumber\\((\\d+)\\)").findAll(enumBlock).map { it.groupValues[1].toInt() }
        assertEquals("枚举块内 @ProtoNumber 编号应无重复", allNumbers.toList().size, allNumbers.toSet().size)
    }

    @Test
    fun `枚举恰好六值且退役编号未复用`() {
        assertEquals(
            "EquipmentSlot 应恰为六值（四部位旧 0..3 已退役，编号禁复用）",
            6, EquipmentSlot.entries.size
        )
        assertTrue(
            "退役枚举名不得回潮",
            EquipmentSlot.entries.none { it.name == "ARMOR" || it.name == "BOOTS" || it.name == "ACCESSORY" }
        )
    }

    @Test
    fun `displayName与六部位一一对应`() {
        val expected = linkedMapOf(
            EquipmentSlot.HEAD to "头部",
            EquipmentSlot.BODY to "身体",
            EquipmentSlot.HANDS to "手部",
            EquipmentSlot.FEET to "脚部",
            EquipmentSlot.WEAPON to "武器",
            EquipmentSlot.LEGS to "腿部"
        )
        expected.forEach { (slot, name) ->
            assertEquals("部位 ${slot.name} 的 displayName", name, slot.displayName)
        }
    }
}
