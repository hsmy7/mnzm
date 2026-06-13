package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

/**
 * 组件表集成调试测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Ignore("需要 MMKV native 库，仅在 androidTest 中运行。核心测试见 core:domain 的 DiscipleTablesTest")
@Config(sdk = [34])
class DiscipleTablesIntegrationTest {

    @Test
    fun `SparseArray put and get works`() {
        val tables = DiscipleTables()
        val d = Disciple(id = "1", name = "测试", discipleType = "outer")
        tables.insert(d)

        // 直接验证 SparseArray 操作
        assertEquals("outer", tables.discipleTypes[1])
        assertEquals("测试", tables.names[1])

        // 修改
        tables.discipleTypes[1] = "inner"
        assertEquals("inner", tables.discipleTypes[1])

        // assemble
        val assembled = tables.assemble(1)
        assertEquals("inner", assembled.discipleType)
    }

    @Test
    fun `assemble remove insert roundtrip`() {
        val tables = DiscipleTables()
        tables.insert(Disciple(id = "1", name = "测试", discipleType = "outer"))

        val d = tables.assemble(1)
        tables.remove(1)
        tables.insert(d.copy(discipleType = "inner"))

        val result = tables.assemble(1)
        assertEquals("inner", result.discipleType)
    }
}
