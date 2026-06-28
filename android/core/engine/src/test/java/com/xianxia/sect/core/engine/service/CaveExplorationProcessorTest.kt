package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.*
import org.junit.Test

class CaveExplorationProcessorTest {

    // ── buildDefenseBattleEnemies 测试 ──

    @Test
    fun `buildDefenseBattleEnemies - 全宗门200弟子但仅10人参战 敌人列表仅含10人`() {
        // 模拟：攻击方宗门有200名弟子
        val sectPool = (0 until 200).map { i ->
            makeDisciple(id = "attacker_$i", realm = (i % 10))
        }
        // 仅前10人参战，3人阵亡
        val survivingAttackers = sectPool.take(7).map {
            it.copy(combat = it.combat.copy(currentHp = 300))
        }
        val deadAttackerIds = sectPool.slice(7 until 10).map { it.id }

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivingAttackers,
            deadAttackerIds = deadAttackerIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        // 核心断言：敌人列表应仅为10名参战弟子，而非200名
        assertEquals(10, enemies.size)
    }

    @Test
    fun `buildDefenseBattleEnemies - 幸存者 isAlive=true hp为实际值`() {
        val sectPool = listOf(
            makeDisciple(id = "a1", realm = 5),
            makeDisciple(id = "a2", realm = 5)
        )
        val survivors = listOf(
            sectPool[0].copy(combat = sectPool[0].combat.copy(currentHp = 450))
        )
        val deadIds = listOf("a2")

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = deadIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        assertEquals(2, enemies.size)
        val survivor = checkNotNull(enemies.find { it.id == "a1" })
        assertTrue(survivor.isAlive)
        assertEquals(450, survivor.hp)

        val dead = checkNotNull(enemies.find { it.id == "a2" })
        assertFalse(dead.isAlive)
        assertEquals(0, dead.hp)
    }

    @Test
    fun `buildDefenseBattleEnemies - 全部幸存 敌人列表仅含幸存者`() {
        val sectPool = (0 until 100).map { i ->
            makeDisciple(id = "attacker_$i", realm = (i % 10))
        }
        val survivors = sectPool.take(10).map {
            it.copy(combat = it.combat.copy(currentHp = 500))
        }
        val deadIds = emptyList<String>()

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = deadIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        assertEquals(10, enemies.size)
        assertTrue(enemies.all { it.isAlive })
        assertTrue(enemies.all { it.hp > 0 })
    }

    @Test
    fun `buildDefenseBattleEnemies - 全部阵亡 敌人列表仅含阵亡者`() {
        val sectPool = (0 until 150).map { i ->
            makeDisciple(id = "attacker_$i", realm = (i % 10))
        }
        val survivors = emptyList<Disciple>()
        val deadIds = sectPool.take(10).map { it.id }

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = deadIds,
            sectDisciplePool = sectPool,
            attackerSectName = "测试宗门"
        )

        assertEquals(10, enemies.size)
        assertTrue(enemies.none { it.isAlive })
        assertTrue(enemies.all { it.hp == 0 })
    }

    @Test
    fun `buildDefenseBattleEnemies - name字段包含宗门名`() {
        val sectPool = listOf(makeDisciple(id = "a1", realm = 3))
        val survivors = listOf(sectPool[0])

        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = survivors,
            deadAttackerIds = emptyList(),
            sectDisciplePool = sectPool,
            attackerSectName = "天剑宗"
        )

        assertEquals("天剑宗弟子", enemies[0].name)
    }

    @Test
    fun `buildDefenseBattleEnemies - 空宗门池+空参战者 返回空列表`() {
        val enemies = CaveExplorationProcessor.buildDefenseBattleEnemies(
            survivingAttackers = emptyList(),
            deadAttackerIds = emptyList(),
            sectDisciplePool = emptyList(),
            attackerSectName = "空宗门"
        )

        assertTrue(enemies.isEmpty())
    }

    // ── 辅助方法 ──

    private fun makeDisciple(
        id: String,
        realm: Int = 9,
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            realm = realm,
            isAlive = isAlive
        )
    }
}
