package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiffDirtyDisciplesTest — 变更集弟子集合增量应用测试。
 *
 * 守护目标：[StateSyncService.applyDirty] 对 `disciples` 集合的按 id
 * upsert/remove 语义（DiscipleTables 列式存储合并回写）。
 *
 * 平台约束：DiscipleTables 底层为 android.util.SparseArray——普通 JVM 的
 * android.jar stub 在 returnDefaultValues=true 下静默 no-op，必须在
 * Robolectric 环境运行。本类**不经 native**（协议 JSON 直测），因此不会
 * 触发桌面 .so 的跨 ClassLoader 加载冲突（native 端到端由 DiffDirtyTest
 * 普通 JUnit 类守护）。
 *
 * 弟子 id 为数字串——DiscipleTables 以 Int 内部索引（生产不变量）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class DiffDirtyDisciplesTest {

    @Test
    fun `disciple upsert updates existing by id`() {
        val store = FakeGameStateStore()
        store.disciplesValue = listOf(
            Disciple().apply { id = "101"; name = "张三" },
            Disciple().apply { id = "102"; name = "李四" },
        )
        val service = StateSyncService(store)

        val result = service.applyDirty(
            """{"version":3,"changed":{"disciples":[
               {"id":"101","name":"张三改"}]}}""".trimIndent()
        )

        assertEquals(1, result!!.upsertCount)
        assertEquals(2, store.disciplesValue.size)
        val zhang = store.disciplesValue.first { it.id == "101" }
        assertEquals("张三改", zhang.name)
        assertEquals("李四", store.disciplesValue.first { it.id == "102" }.name)
    }

    @Test
    fun `disciple removal deletes by id and keeps others`() {
        val store = FakeGameStateStore()
        store.disciplesValue = listOf(
            Disciple().apply { id = "201"; name = "王五" },
            Disciple().apply { id = "202"; name = "赵六" },
        )
        val service = StateSyncService(store)

        val result = service.applyDirty(
            """{"version":4,"removed":{"disciples":["201"]}}"""
        )

        assertEquals(1, result!!.removedCount)
        assertEquals(1, store.disciplesValue.size)
        assertEquals("202", store.disciplesValue[0].id)
        assertEquals("赵六", store.disciplesValue[0].name)
    }

    @Test
    fun `non numeric disciple id degrades to rejection not crash`() {
        // 生产弟子 id 恒为数字串；防御性验证非数字 id 不会被半写入
        //（replaceAll 的 toInt 抛出经事务边界向上传播，状态不被污染）
        val store = FakeGameStateStore()
        store.disciplesValue = listOf(Disciple().apply { id = "301"; name = "孙七" })
        val service = StateSyncService(store)

        var thrown = false
        try {
            service.applyDirty(
                """{"version":5,"changed":{"disciples":[
                   {"id":"abc","name":"坏数据"}]}}""".trimIndent()
            )
        } catch (expected: Exception) {
            thrown = true
        }
        assertTrue("非数字 id 应显式失败（编程/协议错误）", thrown)
        // 失败事务不污染既有状态（update 原子性：异常时 Fake 不持久化）
        assertEquals(1, store.disciplesValue.size)
        assertEquals("孙七", store.disciplesValue[0].name)
    }
}
