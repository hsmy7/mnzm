package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 装备静态数据单源守卫（D1，方案 §3.2/§6.1）。
 *
 * 历史缺陷：EquipmentRegistry 曾持第二份硬编码 72 条装备表，与 EquipmentDatabase
 * 长期漂移（锻造走 Registry、掉落/价格走 Database，同名装备不同数值）。B3 起
 * Registry 降级为纯转发。本守卫钉死三面：
 * 1. **转发一致性**——Registry 装载结果与 Database 全量逐条一致；
 * 2. **分类视图完全划分**——按品阶/按部位的分类视图恰好划分全部 72 条、不多不少；
 * 3. **源码扫描**——Registry 源文件禁止再写任何模板字面量（数据面只允许
 *    转发 [EquipmentDatabase]）。
 */
class EquipmentSingleSourceGuardTest {

    private val registry = EquipmentRegistry()

    @Test
    fun `Registry与Database全量逐条一致`() {
        val fromDb = EquipmentDatabase.entries
        assertEquals("条目总数应为 72（12 部件 × 6 品阶）", 72, fromDb.size)
        assertEquals("Registry 装载条目数与 Database 不一致", fromDb.size, registry.allTemplates.size)
        fromDb.forEach { (id, entry) ->
            val loaded = registry.getById(id)
            assertTrue("Registry 缺失条目 $id", loaded != null)
            assertEquals("条目 $id 转发后与 Database 不一致", entry, loaded)
        }
        assertEquals("Registry 不应有多余条目", fromDb.keys, registry.allTemplates.keys)
    }

    @Test
    fun `品阶分类视图完全划分`() {
        (1..6).forEach { rarity ->
            val fromDb = EquipmentDatabase.entries.values.filter { it.rarity == rarity }
            assertEquals("品阶 $rarity 的 Database 侧条目数", 12, fromDb.size)
            assertEquals(
                "品阶 $rarity 的 Registry.getByRarity 与 Database 不一致",
                fromDb.sortedBy { it.id }, registry.getByRarity(rarity).sortedBy { it.id }
            )
        }
        // 六档并起来恰好 = 全量（完全划分）
        val union = (1..6).flatMap { registry.getByRarity(it) }.map { it.id }.toSet()
        assertEquals("按品阶分类视图未覆盖全量", registry.allTemplates.keys, union)
    }

    @Test
    fun `部位分类视图完全划分`() {
        val union = EquipmentSlot.entries.flatMap { EquipmentDatabase.getBySlot(it) }.map { it.id }.toSet()
        assertEquals("按部位分类视图未覆盖全量", EquipmentDatabase.entries.keys, union)
        EquipmentSlot.entries.forEach { part ->
            assertEquals(
                "部位 $part 应有 2 套 × 6 品阶 = 12 条目",
                12, EquipmentDatabase.getBySlot(part).size
            )
            assertTrue(
                "部位 $part 的分类视图混入其他部位条目",
                EquipmentDatabase.getBySlot(part).all { it.part == part }
            )
        }
    }

    @Test
    fun `getBySlotAndRarity定位两套各一条`() {
        EquipmentSlot.entries.forEach { part ->
            (1..6).forEach { rarity ->
                val hits = EquipmentDatabase.getBySlotAndRarity(part, rarity)
                assertEquals("部位 $part 品阶 $rarity 应定位 2 套各一条", 2, hits.size)
                assertTrue(
                    "定位条目应全部属于请求部位与品阶",
                    hits.all { it.part == part && it.rarity == rarity }
                )
                assertEquals("两套套装各一条", 2, hits.map { it.setId }.distinct().size)
            }
        }
    }

    @Test
    fun `Registry源文件禁止再写模板字面量`() {
        val file = File(
            "../../core/domain/src/main/java/com/xianxia/sect/core/registry/EquipmentRegistry.kt"
        )
        assertTrue("EquipmentRegistry.kt 不可达：${file.absolutePath}", file.isFile)
        val source = file.readText()
        val forbidden = listOf(
            "lietian", "zifu", "裂天", "紫府",
            "listOf(", "mapOf(", "EquipmentTemplate("
        )
        forbidden.forEach { marker ->
            assertTrue(
                "EquipmentRegistry.kt 出现数据面字面量「$marker」——D1 单源收口后本文件只允许转发 EquipmentDatabase",
                !source.contains(marker)
            )
        }
        // 转发面必须在位
        assertTrue(
            "EquipmentRegistry 必须转发 EquipmentDatabase.entries",
            source.contains("EquipmentDatabase.entries")
        )
    }

    @Test
    fun `Registry与Database名称视图语义一致`() {
        // 同名多品阶取最低品阶（展示用）——对齐 Database 侧同名条目集合
        val sampleName = EquipmentDatabase.entries.values
            .groupBy { it.name }
            .filter { it.value.size == 6 }
            .keys.first()
        val viaRegistry = registry.getByName(sampleName)
        val expected = EquipmentDatabase.entries.values
            .filter { it.name == sampleName }
            .minByOrNull { it.rarity }
        assertEquals("同名多品阶应取品阶最低条目", expected, viaRegistry)
        assertEquals("未知名称应返回 null", null, registry.getByName("不存在的装备"))
    }
}
