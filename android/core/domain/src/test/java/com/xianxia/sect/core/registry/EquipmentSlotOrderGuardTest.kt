package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 四部位枚举守卫。
 *
 * `EquipmentSlot` 四值（HEAD/BODY/HANDS/FEET，编号 10..13）是全链的部位锚点：
 * UI 部位顺序、Disciple.EquipmentSet 四列、proto 列号、C++ 四列全部挂在其上。
 * 本守卫钉死：
 * 1. `displayOrder` 覆盖全部枚举值且与声明序一致（头/身/手/脚）；
 * 2. `@ProtoNumber` 编号 10..13 与常量一一对应、无重复（BINARY 注解不可反射，
 *    走源码扫描——Gradle 工作目录 = android/core/domain）；
 * 3. 退役部位（旧四槽 WEAPON/ARMOR/BOOTS/ACCESSORY 与四部位化前的
 *    WEAPON/LEGS）不在枚举内、退役编号 14/15 未被复用。
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
            "displayOrder 与枚举声明序不一致（头/身/手/脚，重排会改写 UI 部位顺序与消费面顺序）",
            declared, EquipmentSlot.displayOrder
        )
    }

    @Test
    fun `四常量ProtoNumber编号钉死且无重复`() {
        val expected = linkedMapOf(
            "HEAD" to 10,
            "BODY" to 11,
            "HANDS" to 12,
            "FEET" to 13
        )
        val regex = Regex("@ProtoNumber\\((\\d+)\\)\\s+(HEAD|BODY|HANDS|FEET)\\b")
        val found = regex.findAll(enumBlock).map { it.groupValues[2] to it.groupValues[1].toInt() }.toMap()
        assertEquals(
            "四常量的 @ProtoNumber 编号面与预期不符（编号退役禁复用，改动须走迁移）",
            expected, found
        )
        // 编号无重复（10..13 各出现一次由上断言保证，此处双保险扫整个枚举块）
        val allNumbers = Regex("@ProtoNumber\\((\\d+)\\)").findAll(enumBlock).map { it.groupValues[1].toInt() }
        assertEquals("枚举块内 @ProtoNumber 编号应无重复", allNumbers.toList().size, allNumbers.toSet().size)
    }

    @Test
    fun `枚举恰好四值且退役部位名不回潮`() {
        assertEquals(
            "EquipmentSlot 应恰为四值（头/身/手/脚）",
            4, EquipmentSlot.entries.size
        )
        assertTrue(
            "退役部位枚举名不得回潮（旧四槽与四部位化前的 WEAPON/LEGS 均为退役段）",
            EquipmentSlot.entries.none {
                it.name == "ARMOR" || it.name == "BOOTS" || it.name == "ACCESSORY" ||
                    it.name == "WEAPON" || it.name == "LEGS"
            }
        )
    }

    @Test
    fun `退役编号14与15未复用`() {
        val claimed = Regex("@ProtoNumber\\((\\d+)\\)").findAll(enumBlock).map { it.groupValues[1].toInt() }.toSet()
        assertTrue(
            "退役编号 14/15（原 WEAPON/LEGS）不得在 EquipmentSlot 枚举内复用——" +
                "旧档字节会按新部位语义解码；reserved 声明见 Items.kt 枚举注释",
            claimed.intersect(setOf(14, 15)).isEmpty()
        )
    }

    @Test
    fun `displayName与四部位一一对应`() {
        val expected = linkedMapOf(
            EquipmentSlot.HEAD to "头部",
            EquipmentSlot.BODY to "身体",
            EquipmentSlot.HANDS to "手部",
            EquipmentSlot.FEET to "脚部"
        )
        expected.forEach { (slot, name) ->
            assertEquals("部位 ${slot.name} 的 displayName", name, slot.displayName)
        }
    }
}
