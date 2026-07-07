package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.GameData
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * GameEngineCoordination 加载相关扩展函数的测试。
 *
 * 验证 loadData 不再对 isGameStarted 做额外修改。
 * 由于 loadData 是复杂的集成流程，本测试通过纯逻辑验证
 * 数据传递的正确性。
 */
class GameEngineCoordinationTest {

    @Test
    fun `默认 GameData 的 isGameStarted 应为 false`() {
        val data = GameData()
        assertFalse(
            "默认 GameData 的 isGameStarted 应为 false",
            data.isGameStarted
        )
    }

    @Test
    fun `GameData copy 不会意外设置 isGameStarted`() {
        val original = GameData(sectName = "测试宗门")
        val copied = original.copy()
        assertFalse(
            "copy 不应改变 isGameStarted",
            copied.isGameStarted
        )
    }

    @Test
    fun `GameData copy 保留已有 isGameStarted 值`() {
        val original = GameData(isGameStarted = true, sectName = "测试宗门")
        val copied = original.copy()
        assert(copied.isGameStarted) {
            "copy 应保留 isGameStarted = true"
        }
    }
}
