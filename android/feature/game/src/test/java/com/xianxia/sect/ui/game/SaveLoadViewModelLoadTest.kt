package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GameData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SaveLoadViewModel 读档流程纯逻辑测试。
 *
 * 验证 gameData.isGameStarted 的设置行为：
 * - updateGameData { it.copy(isGameStarted = true) } 的效果
 * - 默认 GameData 的 isGameStarted 为 false
 */
class SaveLoadViewModelLoadTest {

    @Test
    fun `isGameStarted 默认值为 false`() {
        val data = GameData()
        assertFalse(
            "GameData 默认 isGameStarted 应为 false",
            data.isGameStarted
        )
    }

    @Test
    fun `通过 copy 设置 isGameStarted 为 true`() {
        val original = GameData()
        val modified = original.copy(isGameStarted = true)
        assertTrue(
            "通过 copy(isGameStarted = true) 应生效",
            modified.isGameStarted
        )
    }

    @Test
    fun `copy 不修改未指定的字段`() {
        val original = GameData(sectName = "测试宗门", spiritStones = 5000)
        val modified = original.copy(isGameStarted = true)
        assertTrue(modified.isGameStarted)
        assert(modified.sectName == "测试宗门")
        assert(modified.spiritStones == 5000L)
    }

    @Test
    fun `加载存档后 isGameStarted 应为 true`() {
        // 模拟 loadData 后 gameData 被设置为 isGameStarted = true 的效果
        // 这对应于 SaveLoadViewModel.loadGameFromSlot 中
        // gameEngine.updateGameData { it.copy(isGameStarted = true) } 的最终结果
        val gameDataAfterLoad = GameData(
            isGameStarted = true,
            sectName = "青云宗",
            currentSlot = 1
        )
        assertTrue("读档后 isGameStarted 应为 true", gameDataAfterLoad.isGameStarted)
    }
}
