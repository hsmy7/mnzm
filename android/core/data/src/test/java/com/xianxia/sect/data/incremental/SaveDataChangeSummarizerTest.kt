package com.xianxia.sect.data.incremental

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存变更摘要器单测：真实变更摘要行（表名/主键/变化字段集）的结构语义。
 *
 * 覆盖：首次保存基线行、无变化零行、game_data 字段级对比（戳字段排除）、
 * 集合表三向分类（增/删/改）、超上限溢出聚合行。
 */
class SaveDataChangeSummarizerTest {

    /** 摘要行 newValue 载荷 → 可读文本（UTF-8，与写入侧编码对称） */
    private fun ChangeLogEntry.summaryText(): String = newValue!!.toString(Charsets.UTF_8)

    private fun saveData(
        gameData: GameData = GameData(),
        disciples: List<Disciple> = emptyList(),
        pills: List<Pill> = emptyList()
    ): SaveData = SaveData(
        version = "test",
        timestamp = System.currentTimeMillis(),
        gameData = gameData,
        disciples = disciples,
        pills = pills,
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    @Test
    fun `首次保存（previous 为 null）写一条基线行`() {
        val entries = SaveDataChangeSummarizer.summarize(null, saveData())

        assertEquals(1, entries.size)
        val baseline = entries.single()
        assertEquals("game_data", baseline.tableName)
        assertEquals("game_data", baseline.recordId)
        assertEquals(ChangeLogOperation.INSERT, baseline.operation)
        assertTrue("基线行 newValue 必须携带可读摘要", baseline.summaryText().startsWith("baseline:"))
    }

    @Test
    fun `两次保存数据完全一致时不产生任何摘要行`() {
        val data = saveData(disciples = listOf(Disciple(name = "张三")))
        val entries = SaveDataChangeSummarizer.summarize(previous = data, current = data.copy())

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `game_data 主行字段变化输出变化字段名集且不包含保存戳字段`() {
        val before = saveData(gameData = GameData(spiritStones = 100))
        val after = saveData(gameData = GameData(spiritStones = 200))

        val entries = SaveDataChangeSummarizer.summarize(before, after)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("game_data", entry.tableName)
        assertEquals("game_data", entry.recordId)
        assertEquals(ChangeLogOperation.UPDATE, entry.operation)
        val fields = entry.summaryText().removePrefix("fields=").split(",")
        assertTrue("spiritStones 变化必须进字段集", "spiritStones" in fields)
        assertFalse("保存戳字段不得进字段集", "timestamp" in fields)
        assertFalse("版本戳字段不得进字段集", "saveVersion" in fields)
    }

    @Test
    fun `弟子集合增删改各产出对应操作行`() {
        val kept = Disciple(name = "留下").copy(id = "d-kept")
        val changed = Disciple(name = "改名前").copy(id = "d-changed")
        val removed = Disciple(name = "被删").copy(id = "d-removed")
        val before = saveData(disciples = listOf(kept, changed, removed))

        val changedAfter = changed.copy(name = "改名后")
        val added = Disciple(name = "新弟子").copy(id = "d-added")
        val after = saveData(disciples = listOf(kept, changedAfter, added))

        val entries = SaveDataChangeSummarizer.summarize(before, after)

        assertEquals(3, entries.size)
        val byId = entries.associateBy { it.recordId }
        assertEquals(ChangeLogOperation.INSERT, byId["d-added"]!!.operation)
        assertEquals(ChangeLogOperation.UPDATE, byId["d-changed"]!!.operation)
        assertTrue(
            "变更行字段集必须含实际变化字段",
            byId["d-changed"]!!.summaryText().contains("name")
        )
        assertEquals(ChangeLogOperation.DELETE, byId["d-removed"]!!.operation)
        assertEquals("removed", byId["d-removed"]!!.summaryText())
        assertTrue(
            "全部摘要行表名一致",
            entries.all { it.tableName == "disciples" }
        )
    }

    @Test
    fun `实体字段无变化时不产出行（不同实例同内容）`() {
        val before = saveData(pills = listOf(Pill(name = "回血丹").copy(id = "p-1")))
        val after = saveData(pills = listOf(Pill(name = "回血丹").copy(id = "p-1")))

        val entries = SaveDataChangeSummarizer.summarize(before, after)

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `超过单次上限时截断并产出按表聚合的溢出行`() {
        val disciples = (0 until (SaveDataChangeSummarizer.MAX_ENTRIES_PER_SAVE + 10)).map { index ->
            Disciple(name = "弟子$index").copy(id = "d-$index")
        }
        val before = saveData()
        val after = saveData(disciples = disciples)

        val entries = SaveDataChangeSummarizer.summarize(before, after)

        val overflow = entries.filter { it.recordId == SaveDataChangeSummarizer.OVERFLOW_RECORD_ID }
        assertEquals("溢出行按表聚合为一条", 1, overflow.size)
        assertEquals("disciples", overflow.single().tableName)
        assertEquals(
            "溢出行计数 = 被截断的明细行数",
            "overflow:count=10",
            overflow.single().summaryText()
        )
        assertEquals(
            "明细行 + 溢出行 = 上限 + 聚合行",
            SaveDataChangeSummarizer.MAX_ENTRIES_PER_SAVE + 1,
            entries.size
        )
    }
}
