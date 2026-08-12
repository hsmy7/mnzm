package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 招募列表净化规则测试 — 覆盖 [RecruitListCleanupRule]。
 */
class RecruitListCleanupRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(RecruitListCleanupRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `validate - 损坏招募条目 Repaired并净化`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(createRecruit(name = ""))
        )
        val result = SaveValidator.validate(saveData(gd))

        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue(result.data.gameData.recruitList.isEmpty())
        assertTrue(result.details.isNotEmpty())
    }

    @Test
    fun `validate - 同id重复招募 Repaired去重`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(
                createRecruit(id = "dup", name = "张三"),
                createRecruit(id = "dup", name = "李四")
            )
        )
        val result = SaveValidator.validate(saveData(gd))

        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertEquals(1, result.data.gameData.recruitList.size)
    }

    @Test
    fun `validate - 已入宗门残留招募 Repaired移除`() {
        val recruit = createRecruit(name = "张三", age = 20)
        val inSect = createRecruit(name = "张三", age = 20, id = "999")
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(recruit)
        )
        val result = SaveValidator.validate(saveData(gd, disciples = listOf(inSect)))

        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue(result.data.gameData.recruitList.isEmpty())
    }

    @Test
    fun `validate - 宗门侧已死亡残留 仍移除（非对称容差）`() {
        // 死者年龄冻结 30 岁，幽灵老化到 33 岁——非对称容差下应移除
        val ghost = createRecruit(name = "张三", age = 33)
        val deadInSect = createRecruit(name = "张三", age = 30, id = "999")
            .copy(isAlive = false)
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(ghost)
        )
        val result = SaveValidator.validate(saveData(gd, disciples = listOf(deadInSect)))

        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue(result.data.gameData.recruitList.isEmpty())
    }

    @Test
    fun `validate - 死亡弟子不误删合法新条目`() {
        val recruit = createRecruit(name = "张三", age = 20)
        val deadInSect = createRecruit(name = "张三", age = 30, id = "999")
            .copy(isAlive = false)
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(recruit)
        )
        val result = SaveValidator.validate(saveData(gd, disciples = listOf(deadInSect)))

        assertEquals(IntegrityResult.Passed, result)
    }

    @Test
    fun `validate - 序列化不对称 仍匹配残留（列表侧无体质）`() {
        // 模拟真实数据：recruitList 条目经 DiscipleSerializer 后体质/词条恒空，
        // 宗门弟子侧有真实值——签名不应包含这两字段
        val recruit = createRecruit(name = "张三", age = 20)
        val inSect = createRecruit(name = "张三", age = 20, id = "999")
            .copy(physiqueIds = listOf("p1"), affixIds = listOf("a1"))
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(recruit)
        )
        val result = SaveValidator.validate(saveData(gd, disciples = listOf(inSect)))

        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue(result.data.gameData.recruitList.isEmpty())
    }

    @Test
    fun `validate - 正常招募列表 Passed`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(createRecruit(name = "张三"))
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    @Test
    fun `validate - 怪异极端数据 不抛异常不Corrupted`() {
        // 防阻断读档：异常数据必须可净化而非报损坏
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(
                createRecruit(name = "", age = 0, realm = -1),
                createRecruit(name = "x", age = 999999, realm = 42)
            )
        )
        val result = SaveValidator.validate(saveData(gd))

        assertTrue("不应判定 Corrupted", result !is IntegrityResult.Corrupted)
        assertTrue(result is IntegrityResult.Repaired)
    }

    @Test
    fun `validate - 幂等 二次校验Passed`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(
                createRecruit(name = ""),
                createRecruit(name = "张三")
            )
        )
        val first = SaveValidator.validate(saveData(gd))
        assertTrue(first is IntegrityResult.Repaired)
        val cleaned = (first as IntegrityResult.Repaired).data

        assertEquals(IntegrityResult.Passed, SaveValidator.validate(cleaned))
    }

    @Test
    fun `validate - 38岁炼虚 保留`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(createRecruit(name = "天才", age = 38, realm = 4))
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(saveData(gd)))
    }

    private fun saveData(
        gd: GameData,
        disciples: List<Disciple> = emptyList()
    ): SaveData = SaveData(
        gameData = gd, disciples = disciples, pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
            )

    private fun createRecruit(
        name: String = "弟子",
        age: Int = 20,
        realm: Int = 9,
        id: String = UUID.randomUUID().toString()
    ): Disciple = Disciple(
        id = id,
        name = name,
        age = age,
        realm = realm,
        spiritRootType = "金"
    )
}
