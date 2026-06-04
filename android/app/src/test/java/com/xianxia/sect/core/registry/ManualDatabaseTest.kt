package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.registry.ManualDatabase.BuffInfo
import com.xianxia.sect.core.registry.ManualDatabase.ManualTemplate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManualDatabaseTest {

    @Before
    fun setUp() {
        // 使用 initializeWithManuals 注入测试数据，避免依赖 Android Context
        ManualDatabase.initializeWithManuals(buildTestManuals())
    }

    // ============================================================
    // 所有功法数据完整性
    // ============================================================

    @Test
    fun `所有功法id非空`() {
        ManualDatabase.allManuals.values.forEach { m ->
            assertTrue("功法id不应为空: $m", m.id.isNotEmpty())
        }
    }

    @Test
    fun `所有功法name非空`() {
        ManualDatabase.allManuals.values.forEach { m ->
            assertTrue("功法name不应为空: ${m.id}", m.name.isNotEmpty())
        }
    }

    @Test
    fun `所有功法type为有效枚举值`() {
        ManualDatabase.allManuals.values.forEach { m ->
            assertTrue("功法type应为有效枚举: ${m.id}", m.type in ManualType.entries)
        }
    }

    @Test
    fun `所有功法rarity在1到6范围`() {
        ManualDatabase.allManuals.values.forEach { m ->
            assertTrue("功法rarity应在1-6范围: ${m.id} rarity=${m.rarity}",
                m.rarity in 1..6)
        }
    }

    @Test
    fun `所有功法description非空`() {
        ManualDatabase.allManuals.values.forEach { m ->
            assertTrue("功法description不应为空: ${m.id}", m.description.isNotEmpty())
        }
    }

    @Test
    fun `所有功法price非负`() {
        ManualDatabase.allManuals.values.forEach { m ->
            assertTrue("功法price不应为负: ${m.id} price=${m.price}",
                m.price >= 0)
        }
    }

    // ============================================================
    // ID 唯一性
    // ============================================================

    @Test
    fun `所有功法ID唯一`() {
        val ids = ManualDatabase.allManuals.values.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("功法ID应唯一", ids.size, uniqueIds.size)
    }

    // ============================================================
    // isInitialized
    // ============================================================

    @Test
    fun `初始化后isInitialized为true`() {
        assertTrue(ManualDatabase.isInitialized)
    }

    // ============================================================
    // getById
    // ============================================================

    @Test
    fun `getById已知id返回对应功法`() {
        val result = ManualDatabase.getById("attack_1")
        assertNotNull("已知id应返回功法", result)
        assertEquals("attack_1", result!!.id)
    }

    @Test
    fun `getById未知id返回null`() {
        val result = ManualDatabase.getById("nonexistent_id")
        assertNull("未知id应返回null", result)
    }

    // ============================================================
    // getByName
    // ============================================================

    @Test
    fun `getByName已知名称返回对应功法`() {
        val result = ManualDatabase.getByName("烈焰诀")
        assertNotNull("已知名称应返回功法", result)
        assertEquals("烈焰诀", result!!.name)
    }

    @Test
    fun `getByName未知名称返回null`() {
        val result = ManualDatabase.getByName("不存在的功法")
        assertNull("未知名称应返回null", result)
    }

    // ============================================================
    // getByType
    // ============================================================

    @Test
    fun `getByType返回对应类型的功法`() {
        val attacks = ManualDatabase.getByType(ManualType.ATTACK)
        assertTrue("攻击型功法应存在", attacks.isNotEmpty())
        attacks.forEach {
            assertEquals(ManualType.ATTACK, it.type)
        }
    }

    @Test
    fun `getByType各类型都有功法`() {
        ManualType.entries.forEach { type ->
            val result = ManualDatabase.getByType(type)
            assertTrue("类型 ${type.name} 应有功法", result.isNotEmpty())
        }
    }

    // ============================================================
    // getByRarity
    // ============================================================

    @Test
    fun `getByRarity返回对应稀有度的功法`() {
        val rarity1 = ManualDatabase.getByRarity(1)
        assertTrue("稀有度1应有功法", rarity1.isNotEmpty())
        rarity1.forEach {
            assertEquals(1, it.rarity)
        }
    }

    @Test
    fun `getByRarity每个稀有度都有功法`() {
        for (rarity in 1..6) {
            val result = ManualDatabase.getByRarity(rarity)
            assertTrue("稀有度 $rarity 应有功法", result.isNotEmpty())
        }
    }

    @Test
    fun `getByRarity无效稀有度返回空列表`() {
        val result = ManualDatabase.getByRarity(99)
        assertTrue(result.isEmpty())
    }

    // ============================================================
    // generateRandom
    // ============================================================

    @Test
    fun `generateRandom返回有效ManualStack`() {
        val stack = ManualDatabase.generateRandom()
        assertNotNull(stack)
        assertTrue(stack.name.isNotEmpty())
        assertTrue(stack.rarity in 1..6)
    }

    @Test
    fun `generateRandom指定类型`() {
        val stack = ManualDatabase.generateRandom(type = ManualType.ATTACK)
        assertEquals(ManualType.ATTACK, stack.type)
    }

    @Test
    fun `generateRandom指定稀有度范围`() {
        repeat(20) {
            val stack = ManualDatabase.generateRandom(minRarity = 3, maxRarity = 5)
            assertTrue("稀有度应在3-5范围: ${stack.rarity}", stack.rarity in 3..5)
        }
    }

    @Test(expected = NoSuchElementException::class)
    fun `generateRandom无匹配模板时抛出异常`() {
        // 稀有度99没有对应模板
        ManualDatabase.generateRandom(minRarity = 99, maxRarity = 99)
    }

    // ============================================================
    // createFromTemplate
    // ============================================================

    @Test
    fun `createFromTemplate生成有效ManualStack`() {
        val template = ManualDatabase.getById("attack_1")!!
        val stack = ManualDatabase.createFromTemplate(template)
        assertEquals(template.name, stack.name)
        assertEquals(template.type, stack.type)
        assertEquals(template.rarity, stack.rarity)
        assertTrue(stack.id.isNotEmpty())
        assertNotEquals(template.id, stack.id) // 新实例应有新id
    }

    // ============================================================
    // getByNameAndRarity
    // ============================================================

    @Test
    fun `getByNameAndRarity已知名称和稀有度返回对应功法`() {
        val result = ManualDatabase.getByNameAndRarity("烈焰诀", 1)
        assertNotNull(result)
        assertEquals("烈焰诀", result!!.name)
        assertEquals(1, result.rarity)
    }

    @Test
    fun `getByNameAndRarity错误稀有度返回null`() {
        val result = ManualDatabase.getByNameAndRarity("烈焰诀", 6)
        assertNull(result)
    }

    // ============================================================
    // getLastValidationResult（未启用proto校验时应为null）
    // ============================================================

    @Test
    fun `getLastValidationResult通过initializeWithManuals时为null`() {
        // initializeWithManuals 不执行proto校验
        assertNull(ManualDatabase.getLastValidationResult())
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private fun buildTestManuals(): Map<String, ManualTemplate> {
        return mapOf(
            "attack_1" to ManualTemplate(
                id = "attack_1",
                name = "烈焰诀",
                type = ManualType.ATTACK,
                rarity = 1,
                description = "基础攻击功法",
                stats = mapOf("physicalAttack" to 10),
                skillName = "烈焰斩",
                skillDescription = "释放烈焰攻击",
                skillDamageMultiplier = 1.5
            ),
            "attack_2" to ManualTemplate(
                id = "attack_2",
                name = "寒冰掌",
                type = ManualType.ATTACK,
                rarity = 2,
                description = "中级攻击功法",
                stats = mapOf("magicAttack" to 15),
                skillName = "寒冰掌",
                skillDamageMultiplier = 2.0
            ),
            "defense_1" to ManualTemplate(
                id = "defense_1",
                name = "铁壁功",
                type = ManualType.DEFENSE,
                rarity = 1,
                description = "基础防御功法",
                stats = mapOf("physicalDefense" to 10)
            ),
            "defense_2" to ManualTemplate(
                id = "defense_2",
                name = "金钟罩",
                type = ManualType.DEFENSE,
                rarity = 3,
                description = "高级防御功法",
                stats = mapOf("physicalDefense" to 30, "magicDefense" to 20)
            ),
            "support_1" to ManualTemplate(
                id = "support_1",
                name = "回春术",
                type = ManualType.SUPPORT,
                rarity = 2,
                description = "治疗功法",
                skillName = "回春",
                skillHealPercent = 0.3
            ),
            "support_2" to ManualTemplate(
                id = "support_2",
                name = "清心咒",
                type = ManualType.SUPPORT,
                rarity = 4,
                description = "高级辅助功法",
                skillBuffs = listOf(BuffInfo(type = "speed", value = 0.2, duration = 3))
            ),
            "mind_1" to ManualTemplate(
                id = "mind_1",
                name = "太虚心经",
                type = ManualType.MIND,
                rarity = 5,
                description = "顶级心法",
                stats = mapOf("cultivationSpeedPercent" to 50)
            ),
            "mind_2" to ManualTemplate(
                id = "mind_2",
                name = "无极真经",
                type = ManualType.MIND,
                rarity = 6,
                description = "至高心法",
                stats = mapOf("cultivationSpeedPercent" to 100)
            ),
            // 补充更多稀有度覆盖
            "attack_3" to ManualTemplate(
                id = "attack_3",
                name = "天雷诀",
                type = ManualType.ATTACK,
                rarity = 3,
                description = "雷系攻击功法"
            ),
            "attack_4" to ManualTemplate(
                id = "attack_4",
                name = "灭世火莲",
                type = ManualType.ATTACK,
                rarity = 4,
                description = "火系高级攻击功法"
            ),
            "attack_5" to ManualTemplate(
                id = "attack_5",
                name = "九天玄雷",
                type = ManualType.ATTACK,
                rarity = 5,
                description = "顶级攻击功法"
            ),
            "attack_6" to ManualTemplate(
                id = "attack_6",
                name = "混沌神雷",
                type = ManualType.ATTACK,
                rarity = 6,
                description = "至高攻击功法"
            ),
            "defense_3" to ManualTemplate(
                id = "defense_3",
                name = "玄武盾",
                type = ManualType.DEFENSE,
                rarity = 2,
                description = "中级防御功法"
            ),
            "defense_4" to ManualTemplate(
                id = "defense_4",
                name = "不灭金身",
                type = ManualType.DEFENSE,
                rarity = 4,
                description = "高级防御功法"
            ),
            "defense_5" to ManualTemplate(
                id = "defense_5",
                name = "万法不侵",
                type = ManualType.DEFENSE,
                rarity = 5,
                description = "顶级防御功法"
            ),
            "defense_6" to ManualTemplate(
                id = "defense_6",
                name = "天地护体",
                type = ManualType.DEFENSE,
                rarity = 6,
                description = "至高防御功法"
            ),
            "support_3" to ManualTemplate(
                id = "support_3",
                name = "灵泉术",
                type = ManualType.SUPPORT,
                rarity = 1,
                description = "基础辅助功法"
            ),
            "support_4" to ManualTemplate(
                id = "support_4",
                name = "天音咒",
                type = ManualType.SUPPORT,
                rarity = 3,
                description = "中级辅助功法"
            ),
            "support_5" to ManualTemplate(
                id = "support_5",
                name = "大梵圣音",
                type = ManualType.SUPPORT,
                rarity = 5,
                description = "顶级辅助功法"
            ),
            "support_6" to ManualTemplate(
                id = "support_6",
                name = "万灵归宗",
                type = ManualType.SUPPORT,
                rarity = 6,
                description = "至高辅助功法"
            ),
            "mind_3" to ManualTemplate(
                id = "mind_3",
                name = "清心诀",
                type = ManualType.MIND,
                rarity = 1,
                description = "基础心法"
            ),
            "mind_4" to ManualTemplate(
                id = "mind_4",
                name = "紫气东来",
                type = ManualType.MIND,
                rarity = 2,
                description = "中级心法"
            ),
            "mind_5" to ManualTemplate(
                id = "mind_5",
                name = "混元功",
                type = ManualType.MIND,
                rarity = 3,
                description = "高级心法"
            ),
            "mind_6" to ManualTemplate(
                id = "mind_6",
                name = "太极心经",
                type = ManualType.MIND,
                rarity = 4,
                description = "超级心法"
            )
        )
    }
}
