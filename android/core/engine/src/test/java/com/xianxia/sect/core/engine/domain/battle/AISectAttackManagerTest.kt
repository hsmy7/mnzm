package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import org.junit.Assert.*
import org.junit.Test

class AISectAttackManagerTest {

    // ── 排序方向验证 ──

    @Test
    fun `createAttackTeam - realm升序排列 最强弟子优先`() {
        // realm=0(仙人最强) → realm=9(炼气最弱)
        // 需要 >= MIN_DISCIPLES_FOR_ATTACK(10) 名弟子
        val disciples = listOf(
            makeDisciple("d1", realm = 9),
            makeDisciple("d2", realm = 5),
            makeDisciple("d3", realm = 0),
            makeDisciple("d4", realm = 3),
            makeDisciple("d5", realm = 7),
            makeDisciple("d6", realm = 8),
            makeDisciple("d7", realm = 1),
            makeDisciple("d8", realm = 6),
            makeDisciple("d9", realm = 2),
            makeDisciple("d10", realm = 4)
        )
        val team = AISectAttackManager.createAttackTeam(disciples)
        assertEquals(AISectAttackManager.TEAM_SIZE, team.size)
        // 最强(realm最小)排最前
        assertEquals(0, team[0].realm)
        assertEquals(1, team[1].realm)
        assertEquals(2, team[2].realm)
        assertEquals(3, team[3].realm)
        assertEquals(4, team[4].realm)
    }

    @Test
    fun `createAttackTeam - 仅选存活弟子 top10`() {
        // i=0 dead(realm=0), i=1..14 alive
        val disciples = (0..14).map { i ->
            makeDisciple("d$i", realm = i % 10,
                isAlive = i != 0)
        }
        // 14 alive, should pick top 10 strongest
        val team = AISectAttackManager.createAttackTeam(disciples)
        assertEquals(10, team.size)
        // i=10 alive realm=0, should be first
        assertEquals(0, team[0].realm)
        assertTrue(team.all { it.isAlive })
    }

    @Test
    fun `createDefenseTeam - 仅选IDLE存活弟子`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 0, status = DiscipleStatus.IDLE),
            makeDisciple("d2", realm = 1, status = DiscipleStatus.ON_MISSION),
            makeDisciple("d3", realm = 2, status = DiscipleStatus.IDLE),
            makeDisciple("d4", realm = 3, status = DiscipleStatus.REFLECTING),
            makeDisciple("d5", realm = 4, status = DiscipleStatus.IDLE)
        )
        val team = AISectAttackManager.createDefenseTeam(disciples)
        // 只有3个IDLE弟子入选
        assertEquals(3, team.size)
        assertEquals(listOf(0, 2, 4), team.map { it.realm })
        // ON_MISSION和REFLECTING被排除
        assertTrue(team.none { it.id == "d2" || it.id == "d4" })
    }

    @Test
    fun `createDefenseTeam - 已死亡弟子被排除`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 0, isAlive = false),
            makeDisciple("d2", realm = 1, isAlive = true, status = DiscipleStatus.IDLE)
        )
        val team = AISectAttackManager.createDefenseTeam(disciples)
        assertEquals(1, team.size)
        assertEquals("d2", team[0].id)
    }

    @Test
    fun `createPlayerDefenseTeam - 仅选IDLE弟子`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 0, status = DiscipleStatus.IDLE),
            makeDisciple("d2", realm = 1, status = DiscipleStatus.GARRISONING),
            makeDisciple("d3", realm = 2, status = DiscipleStatus.IDLE)
        )
        val team = AISectAttackManager.createPlayerDefenseTeam(disciples)
        assertEquals(2, team.size)
        assertTrue(team.all { it.status == DiscipleStatus.IDLE })
    }

    // ── 战力计算 ──

    @Test
    fun `calculatePowerScore - 空列表返回0`() {
        assertEquals(0.0, AISectAttackManager.calculatePowerScore(emptyList()), 0.0)
    }

    @Test
    fun `calculatePowerScore - 高境界弟子战力更高`() {
        val weak = listOf(makeDisciple("d1", realm = 9))
        val strong = listOf(makeDisciple("d1", realm = 0))
        assertTrue(
            AISectAttackManager.calculatePowerScore(strong) >
            AISectAttackManager.calculatePowerScore(weak)
        )
    }

    @Test
    fun `calculatePowerScore - 仅计算存活弟子`() {
        val alive = makeDisciple("d1", realm = 0, isAlive = true)
        val dead = makeDisciple("d2", realm = 0, isAlive = false)
        val scoreBoth = AISectAttackManager.calculatePowerScore(listOf(alive, dead))
        val scoreAlive = AISectAttackManager.calculatePowerScore(listOf(alive))
        assertEquals(scoreAlive, scoreBoth, 0.01)
    }

    // ── 辅助方法 ──

    @Test
    fun `supplementDisciples - 从替补池补足到TEAM_SIZE`() {
        val core = listOf(
            makeDisciple("c1", realm = 0),
            makeDisciple("c2", realm = 1)
        )
        val available = (0..12).map { i ->
            makeDisciple("a$i", realm = 2 + i)
        }
        val result = AISectAttackManager.supplementDisciples(
            core, available)
        assertEquals(AISectAttackManager.TEAM_SIZE, result.size)
        // 前2个是核心弟子
        assertTrue(result.take(2).all { it.id.startsWith("c") })
        // 替补按realm升序(最强优先)补入
        val supplements = result.drop(2)
        for (i in 0 until supplements.size - 1) {
            assertTrue(
                supplements[i].realm <= supplements[i + 1].realm
            )
        }
    }

    @Test
    fun `getGarrisonDisciples - 从驻军槽位提取存活弟子`() {
        val allDisciples = listOf(
            makeDisciple("d1", realm = 3, isAlive = true),
            makeDisciple("d2", realm = 5, isAlive = false),
            makeDisciple("d3", realm = 7, isAlive = true)
        )
        val sect = com.xianxia.sect.core.model.WorldSect(
            id = "s1",
            garrisonSlots = listOf(
                com.xianxia.sect.core.model.GarrisonSlot(
                    index = 0, discipleId = "d1"),
                com.xianxia.sect.core.model.GarrisonSlot(
                    index = 1, discipleId = "d2"),
                com.xianxia.sect.core.model.GarrisonSlot(
                    index = 2, discipleId = "")
            )
        )
        val result = AISectAttackManager.getGarrisonDisciples(
            sect, allDisciples)
        // d1存活, d2已死, slot2为空
        assertEquals(1, result.size)
        assertEquals("d1", result[0].id)
    }

    // ── 主宗门防御筛选逻辑 ──

    private val SECT_DEFENSE_EXCLUDED = setOf(
        DiscipleStatus.ON_MISSION,
        DiscipleStatus.IN_TEAM,
        DiscipleStatus.REFLECTING,
        DiscipleStatus.GARRISONING
    )

    private fun isEligibleForSectDefense(d: Disciple): Boolean {
        return d.isAlive &&
            d.status !in SECT_DEFENSE_EXCLUDED &&
            d.statusData["bloodRefining"] != "true"
    }

    @Test
    fun `主宗门防御 - REFLECTING弟子被排除`() {
        val d = makeDisciple("d1", status = DiscipleStatus.REFLECTING)
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - 血炼中弟子被排除`() {
        val d = makeDisciple("d1",
            statusData = mapOf("bloodRefining" to "true"))
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - GARRISONING弟子被排除`() {
        val d = makeDisciple("d1",
            status = DiscipleStatus.GARRISONING)
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - ON_MISSION弟子被排除`() {
        val d = makeDisciple("d1",
            status = DiscipleStatus.ON_MISSION)
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - IN_TEAM弟子被排除`() {
        val d = makeDisciple("d1",
            status = DiscipleStatus.IN_TEAM)
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - 已死亡弟子被排除`() {
        val d = makeDisciple("d1", isAlive = false)
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - IDLE弟子可参战`() {
        val d = makeDisciple("d1", status = DiscipleStatus.IDLE)
        assertTrue(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - 采矿中弟子可参战`() {
        val d = makeDisciple("d1", status = DiscipleStatus.MINING)
        assertTrue(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - realm排序正确 0最强9最弱`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 9),
            makeDisciple("d2", realm = 0),
            makeDisciple("d3", realm = 5)
        ).filter { isEligibleForSectDefense(it) }
            .sortedBy { it.realm }
        assertEquals(0, disciples[0].realm)
        assertEquals(5, disciples[1].realm)
        assertEquals(9, disciples[2].realm)
    }

    // ── 工厂方法 ──

    private fun makeDisciple(
        id: String,
        realm: Int = 9,
        isAlive: Boolean = true,
        status: DiscipleStatus = DiscipleStatus.IDLE,
        statusData: Map<String, String> = emptyMap()
    ): Disciple {
        return Disciple(
            id = id,
            realm = realm,
            isAlive = isAlive,
            status = status,
            statusData = statusData
        )
    }
}
