package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `generateBridgeEvent - 描述为结果文本前缀且三个方向选项`() {
        val event = SecretRealmEventGenerator.generateBridgeEvent("你方悄然绕行，成功避开了妖兽的注意")
        assertEquals(SecretRealmEventType.BRIDGE.name, event.eventType)
        assertTrue(event.description.startsWith("你方悄然绕行，成功避开了妖兽的注意"))
        assertTrue(event.description.contains("请选择探索方向"))
        assertEquals(listOf("走左路", "直线前进", "走右路"), event.options.map { it.label })
    }

    @Test
    fun `rollBeastLoot - 每只妖兽固定 2 个材料`() {
        val loot = SecretRealmEventGenerator.rollBeastLoot(rng, "虎妖", beastRealm = 5, beastCount = 3)
        assertEquals(6, loot.size)
        assertTrue(loot.all { it.type == "material" })
        assertTrue(loot.all { it.quantity == 1 })
        assertTrue(loot.all { it.name.isNotEmpty() })
    }
}
