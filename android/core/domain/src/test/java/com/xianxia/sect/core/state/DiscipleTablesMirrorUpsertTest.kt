package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 镜像行级 upsert（[DiscipleTables.upsertMirrorRow]）语义测试。
 *
 * 守卫：镜像信封按 id 精确应用弟子行——存在行原位覆盖（保行序）、新行追加末尾、
 * 幽灵行（isAlive 缺键但 _ids 存在）经 insert 路径兜底转 update，语义与
 * update/insert 一致；每次应用记录 changedId（增量组装基建依赖）。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesMirrorUpsertTest {

    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        tables.writeAllowed = true
    }

    private fun disciple(id: String, name: String, cultivation: Double = 10.0) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = cultivation,
        spiritRootType = "metal"
    )

    @Test
    fun `existing row updated in place keeping order`() {
        tables.insert(disciple("1", "甲"))
        tables.insert(disciple("2", "乙"))

        tables.upsertMirrorRow(disciple("1", "甲改", cultivation = 99.0))

        assertEquals(listOf(1, 2), tables.ids)
        assertEquals("甲改", tables.names[1])
        assertEquals(99.0, tables.cultivations[1], 0.0)
        assertEquals("乙", tables.names[2])
        // 行级应用记录 changedId（镜像消费方增量组装依赖）；writeAllFields
        // 连带标记关联列的其他 id 与 update() 同语义——只断言目标 id 必在
        assertTrue(1 in tables.changedIdTracker.consumeChangedIds())
    }

    @Test
    fun `new row appended at end`() {
        tables.insert(disciple("1", "甲"))

        tables.upsertMirrorRow(disciple("3", "丁"))

        assertEquals(listOf(1, 3), tables.ids)
        assertEquals("丁", tables.names[3])
        assertTrue(3 in tables.changedIdTracker.consumeChangedIds())
    }

    @Test
    fun `removed then upserted row is reinserted`() {
        tables.insert(disciple("1", "甲"))
        tables.remove(1)

        tables.upsertMirrorRow(disciple("1", "甲复", cultivation = 7.0))

        assertEquals(listOf(1), tables.ids)
        assertEquals("甲复", tables.names[1])
        assertEquals(7.0, tables.cultivations[1], 0.0)
    }

    @Test
    fun `ghost row falls back to update semantics`() {
        tables.insert(disciple("1", "甲"))
        // 构造幽灵行：isAlive 缺键但 _ids 仍在（异常态，正常路径由一致性断言拦截）
        tables.isAlive.remove(1)

        tables.upsertMirrorRow(disciple("1", "甲兜底"))

        assertEquals(listOf(1), tables.ids)
        assertEquals("甲兜底", tables.names[1])
    }
}
