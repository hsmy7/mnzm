package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SecretRealmEventGeneratorTest {

    private val rng = DeterministicRng.fromSeed(99L)

    companion object {
        /** rollNextEvent 空地分支种子：首次 nextDouble() ≈ 6.5e-9（< REST_AREA_CHANCE 0.30） */
        private const val REST_AREA_SEED = 1L

        /** rollNextEvent 妖兽分支种子：首次 nextDouble() ≈ 0.5000000009（>= REST_AREA_CHANCE 0.30） */
        private const val BEAST_SEED = 4L
    }

    @Test
    fun `generateBeastEvent - 描述含妖兽名数量境界且选项 3 个`() {
        repeat(20) {
            val event = SecretRealmEventGenerator.generateBeastEvent(rng, playerAvgRealm = 5)
            assertEquals(SecretRealmEventType.BEAST_ENCOUNTER.name, event.eventType)
            assertTrue(event.description.contains("途中遭遇妖兽"))
            assertTrue(event.description.contains("×"))
            assertTrue(event.description.contains("境界"))
            assertEquals(3, event.options.size)
            val count = event.params.beastCount
            assertTrue(
                count >= GameConfig.SecretRealm.BEAST_COUNT_MIN &&
                    count <= GameConfig.SecretRealm.BEAST_COUNT_MAX
            )
            assertTrue(event.params.beastRealm in 0..9)
            assertTrue(event.params.beastTypeName.isNotEmpty())
            // 妖兽层数 1..9（境界显示如"炼气三层"）
            assertTrue(event.params.beastLayer in 1..9)
            // 层数与战斗倍率一致：预生成属性的 realmLayer 必须等于显示层数
            // （防止"显示九层实际最弱"的显示/战力脱钩）
            val stats = SecretRealmEventGenerator.buildBeastPreGenStats(
                rng, event.params.beastRealm, event.params.beastTypeName,
                event.params.ambushSucceeded, beastLayer = event.params.beastLayer
            )
            assertEquals(event.params.beastLayer, stats.realmLayer)
        }
    }

    @Test
    fun `rollBeastRealm - 境界限制在 avg 减 1 到 avg 加 2 且 clamp 零至九`() {
        repeat(50) {
            val realm = SecretRealmEventGenerator.rollBeastRealm(rng, playerAvgRealm = 3)
            assertTrue(realm in 2..5)
        }
        repeat(50) {
            val realm = SecretRealmEventGenerator.rollBeastRealm(rng, playerAvgRealm = 1)
            assertTrue(realm in 0..3)
        }
        repeat(50) {
            val realm = SecretRealmEventGenerator.rollBeastRealm(rng, playerAvgRealm = 9)
            assertTrue(realm in 8..9)
        }
    }

    @Test
    fun `buildBeastPreGenStats - 偷袭成功时妖兽初始血量削减一成`() {
        // 相同种子 → 相同随机序列 → 基础属性一致，仅 maxHp 应用 0.9 修正
        val normal = SecretRealmEventGenerator.buildBeastPreGenStats(
            DeterministicRng.fromSeed(55L), realm = 5, beastTypeName = "虎妖",
            ambushSucceeded = false
        )
        val ambushed = SecretRealmEventGenerator.buildBeastPreGenStats(
            DeterministicRng.fromSeed(55L), realm = 5, beastTypeName = "虎妖",
            ambushSucceeded = true
        )
        assertEquals(normal.physicalAttack, ambushed.physicalAttack)
        assertEquals(normal.speed, ambushed.speed)
        // maxHp 约 0.9 倍（整数截断容差 1）
        assertTrue(
            "expected ~${normal.maxHp * 0.9}, actual ${ambushed.maxHp}",
            Math.abs(ambushed.maxHp - normal.maxHp * 0.9) <= 1.5
        )
        assertTrue(ambushed.maxHp > 0)
    }

    @Test
    fun `generateRestAreaEvent - 事件类型标题描述与两个选项正确`() {
        val event = SecretRealmEventGenerator.generateRestAreaEvent()
        assertEquals(SecretRealmEventType.REST_AREA.name, event.eventType)
        assertEquals("发现空地", event.title)
        assertEquals("发现一处平坦空地", event.description)
        assertEquals(listOf("原地休整", "继续前进"), event.options.map { it.label })
        assertTrue(event.options.all { it.description.isNotEmpty() })
        // params 妖兽字段为空 → UI 走描述分支而非妖兽精灵图分支
        assertTrue(event.params.beastTypeName.isEmpty())
    }

    @Test
    fun `rollNextEvent - 首次随机值低于三成时生成空地事件`() {
        val event = SecretRealmEventGenerator.rollNextEvent(
            DeterministicRng.fromSeed(REST_AREA_SEED), playerAvgRealm = 5
        )
        assertEquals(SecretRealmEventType.REST_AREA.name, event.eventType)
        assertEquals("发现空地", event.title)
    }

    @Test
    fun `rollNextEvent - 首次随机值不低于三成时生成妖兽事件`() {
        val event = SecretRealmEventGenerator.rollNextEvent(
            DeterministicRng.fromSeed(BEAST_SEED), playerAvgRealm = 5
        )
        assertEquals(SecretRealmEventType.BEAST_ENCOUNTER.name, event.eventType)
    }

    @Test
    fun `rollNextEvent - 生成的事件必为三类真实事件之一`() {
        val event = SecretRealmEventGenerator.rollNextEvent(
            DeterministicRng.fromSeed(BEAST_SEED), playerAvgRealm = 5
        )
        // 方向选择后的 rollNextEvent 只产出真实事件（方向事件不经此处生成）
        assertTrue(
            event.eventType == SecretRealmEventType.BEAST_ENCOUNTER.name ||
                event.eventType == SecretRealmEventType.REST_AREA.name ||
                event.eventType == SecretRealmEventType.RUIN_EXPLORE.name
        )
    }

    @Test
    fun `playerAvgRealm - 存活成员平均境界且全灭取上限`() {
        assertEquals(4, SecretRealmEventGenerator.playerAvgRealm(
            listOf(
                com.xianxia.sect.core.model.SecretRealmMemberState(
                    discipleId = "1", realm = 5, currentHp = -1, maxHp = 100
                ),
                com.xianxia.sect.core.model.SecretRealmMemberState(
                    discipleId = "2", realm = 4, currentHp = -1, maxHp = 100
                )
            )
        ))
        assertEquals(
            com.xianxia.sect.core.GameConfig.SecretRealm.REALM_MAX,
            SecretRealmEventGenerator.playerAvgRealm(emptyList())
        )
    }

    @Test
    fun `rollBeastLoot - 每只妖兽固定 2 个材料`() {
        val loot = SecretRealmEventGenerator.rollBeastLoot(rng, "虎妖", beastRealm = 5, beastCount = 3)
        assertEquals(6, loot.size)
        assertTrue(loot.all { it.type == "material" })
        assertTrue(loot.all { it.quantity == 1 })
        assertTrue(loot.all { it.name.isNotEmpty() })
    }

    // ── 发现遗迹事件 ──────────────────────────────────────────────────

    @Test
    fun `generateRuinsEvent - 事件类型标题描述与三个选项正确`() {
        val event = SecretRealmEventGenerator.generateRuinsEvent()
        assertEquals(SecretRealmEventType.RUIN_EXPLORE.name, event.eventType)
        assertEquals("发现遗迹", event.title)
        assertEquals("发现未知遗迹可能存在未知宝物", event.description)
        assertEquals(listOf("直接离开", "简单搜寻", "仔细搜寻"), event.options.map { it.label })
        // 体力消耗：前两项默认 1，仔细搜寻 2
        assertEquals(1, event.options[0].staminaCost)
        assertEquals(1, event.options[1].staminaCost)
        assertEquals(GameConfig.SecretRealm.CAREFUL_SEARCH_STAMINA_COST, event.options[2].staminaCost)
        // params 妖兽字段为空 → UI 走描述分支而非妖兽精灵图分支
        assertTrue(event.params.beastTypeName.isEmpty())
    }

    @Test
    fun `generateDirectionEvent - 标题描述选项与体力消耗正确`() {
        val event = SecretRealmEventGenerator.generateDirectionEvent("战斗结束！你方击退了1只虎妖")
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, event.eventType)
        assertEquals("探索方向", event.title)
        assertEquals("战斗结束！你方击退了1只虎妖，请选择探索方向", event.description)
        assertEquals(listOf("向左走", "走中间", "向右走"), event.options.map { it.label })
        // 三个方向选项均消耗 1 体力（与事件选项一致）
        assertTrue(event.options.all { it.staminaCost == 1 })
        // params 妖兽字段为空 → UI 走描述分支而非妖兽精灵图分支
        assertTrue(event.params.beastTypeName.isEmpty())
        assertTrue(event.params.itemRewards.isEmpty())
    }

    @Test
    fun `generateDirectionEvent - 空结果文本不产生前导逗号`() {
        val event = SecretRealmEventGenerator.generateDirectionEvent("")
        assertEquals("请选择探索方向", event.description)
        // 空白串同样防御
        val blank = SecretRealmEventGenerator.generateDirectionEvent("   ")
        assertEquals("请选择探索方向", blank.description)
    }

    @Test
    fun `generateRuinsTreasure - 数量与品阶范围正确`() {
        repeat(50) {
            val simple = SecretRealmEventGenerator.generateRuinsTreasure(
                rng,
                GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MIN,
                GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MAX,
                GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MIN,
                GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MAX
            )
            assertTrue(
                "简单搜寻数量 ${simple.size} 超出 1..5",
                simple.size in GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MIN..
                    GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MAX
            )
            assertTrue(
                "简单搜寻品阶越界",
                simple.all { it.rarity in GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MIN..
                    GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MAX }
            )
            val careful = SecretRealmEventGenerator.generateRuinsTreasure(
                rng,
                GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MIN,
                GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MAX,
                GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MIN,
                GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MAX
            )
            assertTrue(
                "仔细搜寻数量 ${careful.size} 超出 2..7",
                careful.size in GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MIN..
                    GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MAX
            )
            assertTrue(
                "仔细搜寻品阶越界",
                careful.all { it.rarity in GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MIN..
                    GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MAX }
            )
        }
        // 通用约束：数量为 1、名称非空、类型为六类之一
        val sample = SecretRealmEventGenerator.generateRuinsTreasure(rng, 2, 7, 2, 4)
        assertTrue(sample.all { it.quantity == 1 })
        assertTrue(sample.all { it.name.isNotEmpty() })
        assertTrue(
            sample.all { it.type in listOf("equipment", "manual", "pill", "material", "herb", "seed") }
        )
    }

    @Test
    fun `generateRuinsTreasure - 相同种子产生相同结果`() {
        val first = SecretRealmEventGenerator.generateRuinsTreasure(
            DeterministicRng.fromSeed(20260803L), 2, 7, 2, 4
        )
        val second = SecretRealmEventGenerator.generateRuinsTreasure(
            DeterministicRng.fromSeed(20260803L), 2, 7, 2, 4
        )
        assertEquals(first, second)
    }

    @Test
    fun `ruins 配置不变量 - 数量品阶范围合法且概率分段无重叠`() {
        // 数量范围 min<=max（守卫：未来配置改坏会导致 rng.nextInt(bound<=0) 抛异常）
        assertTrue(GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MIN <=
            GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MAX)
        assertTrue(GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MIN <=
            GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MAX)
        // 品阶范围在合法区间 1..6 且 min<=max
        assertTrue(GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MIN in 1..6)
        assertTrue(GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MAX in 1..6)
        assertTrue(GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MIN <=
            GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MAX)
        assertTrue(GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MIN in 1..6)
        assertTrue(GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MAX in 1..6)
        assertTrue(GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MIN <=
            GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MAX)
        // 事件分段概率和无重叠且不超过 1（nextDouble 单次分段判定依赖）
        assertTrue(GameConfig.SecretRealm.REST_AREA_CHANCE +
            GameConfig.SecretRealm.RUINS_CHANCE <= 1.0)
        // 仔细搜寻体力消耗在合法扣费范围内（calculateNewStamina clamp 依赖）
        assertTrue(GameConfig.SecretRealm.CAREFUL_SEARCH_STAMINA_COST in
            1..GameConfig.SecretRealm.STAMINA_MAX)
        // 秘宝概率在开区间 (0,1) 内
        assertTrue(GameConfig.SecretRealm.RUINS_TREASURE_CHANCE > 0.0)
        assertTrue(GameConfig.SecretRealm.RUINS_TREASURE_CHANCE < 1.0)
    }

    @Test
    fun `rollNextEvent - 三分段 mock 分支判定`() {
        // 0.2999 < 0.30 → 空地事件
        var mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.2999)
        assertEquals(
            SecretRealmEventType.REST_AREA.name,
            SecretRealmEventGenerator.rollNextEvent(mockRng, 5).eventType
        )
        // 0.30 <= roll < 0.50 → 发现遗迹（含恰值 0.30 边界）
        mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.30)
        assertEquals(
            SecretRealmEventType.RUIN_EXPLORE.name,
            SecretRealmEventGenerator.rollNextEvent(mockRng, 5).eventType
        )
        // 中段 → 发现遗迹
        mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.4)
        assertEquals(
            SecretRealmEventType.RUIN_EXPLORE.name,
            SecretRealmEventGenerator.rollNextEvent(mockRng, 5).eventType
        )
        // 0.50 <= roll → 妖兽事件（含恰值 0.50 边界）
        mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.50)
        assertEquals(
            SecretRealmEventType.BEAST_ENCOUNTER.name,
            SecretRealmEventGenerator.rollNextEvent(mockRng, 5).eventType
        )
        mockRng = mock(DeterministicRng::class.java)
        `when`(mockRng.nextDouble()).thenReturn(0.9)
        assertEquals(
            SecretRealmEventType.BEAST_ENCOUNTER.name,
            SecretRealmEventGenerator.rollNextEvent(mockRng, 5).eventType
        )
    }
}
