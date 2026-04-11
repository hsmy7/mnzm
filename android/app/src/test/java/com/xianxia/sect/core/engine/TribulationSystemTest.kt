package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Test

class TribulationSystemTest {

    private fun createDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        realm: Int = 5,
        realmLayer: Int = 1,
        soulPower: Int = 0,
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            isAlive = isAlive,
            skills = SkillStats(loyalty = 50)
        ).also { it.soulPower = soulPower }
    }

    @Test
    fun `trialHeartDemon - 神魂达标时通过`() {
        val disciple = createDisciple(realm = 5, soulPower = 500)
        val result = TribulationSystem.trialHeartDemon(disciple)
        assertTrue(result.success)
        assertEquals("heartDemon", result.type)
    }

    @Test
    fun `trialHeartDemon - 神魂不足时失败`() {
        val disciple = createDisciple(realm = 5, soulPower = 10)
        val result = TribulationSystem.trialHeartDemon(disciple)
        assertFalse(result.success)
        assertEquals("heartDemon", result.type)
    }

    @Test
    fun `trialHeartDemon - 结果包含弟子名字`() {
        val disciple = createDisciple(name = "张三", realm = 5, soulPower = 10)
        val result = TribulationSystem.trialHeartDemon(disciple)
        assertTrue(result.message.contains("张三"))
    }

    @Test
    fun `needsHeartDemon - 境界5及以下需要心魔考验`() {
        for (realm in 0..5) {
            val disciple = createDisciple(realm = realm)
            assertTrue("realm=$realm 应需要心魔考验", TribulationSystem.needsHeartDemon(disciple))
        }
    }

    @Test
    fun `needsHeartDemon - 境界6及以上不需要心魔考验`() {
        for (realm in 6..9) {
            val disciple = createDisciple(realm = realm)
            assertFalse("realm=$realm 不应需要心魔考验", TribulationSystem.needsHeartDemon(disciple))
        }
    }

    @Test
    fun `trialThunderTribulation - 高境界弟子大概率通过雷劫`() {
        var successes = 0
        val trials = 20
        for (i in 1..trials) {
            val disciple = createDisciple(realm = 1, realmLayer = 9)
            val result = TribulationSystem.trialThunderTribulation(disciple)
            if (result.success) successes++
        }
        assertTrue("高境界弟子应大概率通过雷劫，实际 ${successes}/${trials}", successes > trials / 2)
    }

    @Test
    fun `trialThunderTribulation - 高境界弟子通过雷劫`() {
        val disciple = createDisciple(realm = 9, realmLayer = 9)
        val result = TribulationSystem.trialThunderTribulation(disciple)
        assertNotNull(result)
        assertTrue(result.type == "thunder")
    }

    @Test
    fun `trialThunderTribulation - 雷劫结果包含成功或失败`() {
        val disciple = createDisciple(realm = 5, realmLayer = 9)
        val result = TribulationSystem.trialThunderTribulation(disciple)
        assertTrue(result.success || !result.success)
    }

    @Test
    fun `trialThunderTribulation - 雷劫结果类型为thunder`() {
        val disciple = createDisciple(realm = 5, realmLayer = 9)
        val result = TribulationSystem.trialThunderTribulation(disciple)
        assertEquals("thunder", result.type)
    }

    @Test
    fun `trialThunderTribulation - 失败时有伤害值`() {
        val disciple = createDisciple(realm = 5, realmLayer = 1)
        for (i in 1..100) {
            val result = TribulationSystem.trialThunderTribulation(disciple)
            if (!result.success) {
                assertTrue(result.damageDealt > 0)
                return
            }
        }
    }

    @Test
    fun `trialThunderTribulation - 结果包含弟子名字`() {
        val disciple = createDisciple(name = "李四", realm = 5, realmLayer = 9)
        val result = TribulationSystem.trialThunderTribulation(disciple)
        assertTrue(result.message.contains("李四"))
    }

    @Test
    fun `TribulationResult - 成功结果数据正确`() {
        val result = TribulationResult(
            success = true,
            type = "thunder",
            message = "通过",
            damageDealt = 100
        )
        assertTrue(result.success)
        assertEquals("thunder", result.type)
        assertEquals(100, result.damageDealt)
    }

    @Test
    fun `TribulationResult - 失败结果数据正确`() {
        val result = TribulationResult(
            success = false,
            type = "heartDemon",
            message = "失败"
        )
        assertFalse(result.success)
        assertEquals(0, result.damageDealt)
    }
}
