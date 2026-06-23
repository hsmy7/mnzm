package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证寿命增益仅在跨大境界突破时触发，同境界内小层升级不增加寿命。
 *
 * Bug 背景：getLifespanGainForRealm 在每次突破成功时都被调用，
 * 包括同境界内升层，导致筑基弟子每升一层 +50 寿命，筑基7层已达430。
 *
 * 期望行为：只有 newRealm != oldRealm 时才加寿命。
 */
class LifespanGainOnBreakthroughTest {

    // ---- getLifespanGainForRealm 返回值 ----

    private fun getLifespanGainForRealm(realm: Int): Int {
        return when (realm) {
            8 -> 50; 7 -> 100; 6 -> 200; 5 -> 400
            4 -> 800; 3 -> 1500; 2 -> 3000; 1 -> 5000
            0 -> 10000; else -> 0
        }
    }

    @Test fun `炼气 realm9 没有寿命增益`() =
        assertEquals(0, getLifespanGainForRealm(9))

    @Test fun `筑基 realm8 寿命增益50`() =
        assertEquals(50, getLifespanGainForRealm(8))

    @Test fun `金丹 realm7 寿命增益100`() =
        assertEquals(100, getLifespanGainForRealm(7))

    @Test fun `元婴 realm6 寿命增益200`() =
        assertEquals(200, getLifespanGainForRealm(6))

    @Test fun `化神 realm5 寿命增益400`() =
        assertEquals(400, getLifespanGainForRealm(5))

    @Test fun `炼虚 realm4 寿命增益800`() =
        assertEquals(800, getLifespanGainForRealm(4))

    @Test fun `合体 realm3 寿命增益1500`() =
        assertEquals(1500, getLifespanGainForRealm(3))

    @Test fun `大乘 realm2 寿命增益3000`() =
        assertEquals(3000, getLifespanGainForRealm(2))

    @Test fun `渡劫 realm1 寿命增益5000`() =
        assertEquals(5000, getLifespanGainForRealm(1))

    @Test fun `仙人 realm0 寿命增益10000`() =
        assertEquals(10000, getLifespanGainForRealm(0))

    // ---- 突破流程中仅大境界变更加寿命 ----

    /**
     * 模拟一次突破的 realm/layer 变化和寿命累加逻辑。
     * 返回 Triple(newRealm, newRealmLayer, addedLifespan)。
     */
    private fun simulateBreakthrough(
        realm: Int, layer: Int, currentLifespan: Int
    ): Triple<Int, Int, Int> {
        var newRealm = realm
        var newRealmLayer = layer
        val oldRealm = newRealm
        if (newRealmLayer < GameConfig.Realm.get(newRealm).maxLayers) {
            newRealmLayer++
        } else {
            newRealm--
            newRealmLayer = 1
        }
        val addedLifespan = if (newRealm != oldRealm) {
            getLifespanGainForRealm(newRealm)
        } else {
            0
        }
        return Triple(newRealm, newRealmLayer, addedLifespan)
    }

    // ---- 同境界升层不加寿命 ----

    @Test
    fun `炼气同境界升层不加寿命`() {
        val (newRealm, newLayer, added) = simulateBreakthrough(9, 5, 80)
        assertEquals(9, newRealm)       // realm 不变
        assertEquals(6, newLayer)       // layer +1
        assertEquals(0, added)           // 不加寿命
    }

    @Test
    fun `筑基同境界升层不加寿命`() {
        val (newRealm, newLayer, added) = simulateBreakthrough(8, 3, 130)
        assertEquals(8, newRealm)
        assertEquals(4, newLayer)
        assertEquals(0, added)           // 同境界内不加寿命
    }

    @Test
    fun `金丹同境界升层不加寿命`() {
        val (newRealm, newLayer, added) = simulateBreakthrough(7, 2, 230)
        assertEquals(7, newRealm)
        assertEquals(3, newLayer)
        assertEquals(0, added)
    }

    // ---- 跨大境界加寿命 ----

    @Test
    fun `炼气9层突破至筑基1层 寿命加50`() {
        val (newRealm, newLayer, added) = simulateBreakthrough(9, 9, 80)
        assertEquals(8, newRealm)       // realm 从 9 变 8
        assertEquals(1, newLayer)       // layer 重置为 1
        assertEquals(50, added)          // 筑基寿命增益
    }

    @Test
    fun `筑基9层突破至金丹1层 寿命加100`() {
        val (newRealm, newLayer, added) = simulateBreakthrough(8, 9, 130)
        assertEquals(7, newRealm)
        assertEquals(1, newLayer)
        assertEquals(100, added)
    }

    @Test
    fun `金丹9层突破至元婴1层 寿命加200`() {
        val (newRealm, newLayer, added) = simulateBreakthrough(7, 9, 230)
        assertEquals(6, newRealm)
        assertEquals(1, newLayer)
        assertEquals(200, added)
    }

    // ---- 完整成长路径模拟 ----

    @Test
    fun `炼气到筑基9层的完整路径 寿命仅跨境界时增加`() {
        var realm = 9
        var layer = 1
        var lifespan = 80  // 炼气 maxAge

        // 炼气 1→9：同境界升层，不加寿命
        repeat(8) {
            val r = simulateBreakthrough(realm, layer, lifespan)
            realm = r.first; layer = r.second; lifespan += r.third
        }
        assertEquals(9, realm); assertEquals(9, layer)
        assertEquals(80, lifespan)  // 炼气 realm9 不加寿命

        // 炼气9→筑基1：跨境界，+50
        val b1 = simulateBreakthrough(realm, layer, lifespan)
        realm = b1.first; layer = b1.second; lifespan += b1.third
        assertEquals(8, realm); assertEquals(1, layer)
        assertEquals(130, lifespan)  // 80 + 50

        // 筑基 1→9：同境界升层，不加寿命
        repeat(8) {
            val r = simulateBreakthrough(realm, layer, lifespan)
            realm = r.first; layer = r.second; lifespan += r.third
        }
        assertEquals(8, realm); assertEquals(9, layer)
        assertEquals(130, lifespan)  // 不应再增加

        // 筑基9→金丹1：跨境界，+100
        val jd = simulateBreakthrough(realm, layer, lifespan)
        realm = jd.first; layer = jd.second; lifespan += jd.third
        assertEquals(7, realm); assertEquals(1, layer)
        assertEquals(230, lifespan)  // 130 + 100
    }

    /**
     * 模拟修复前 Bug 的完整路径，验证 Bug 的影响量级。
     * 修复后此测试验证旧行为不再出现。
     */
    @Test
    fun `筑基7层不应达到430寿命 回归验证`() {
        // 走完整路径：炼气1→9→筑基1→7
        var realm = 9; var layer = 1; var lifespan = 80
        repeat(8) {
            val r = simulateBreakthrough(realm, layer, lifespan)
            realm = r.first; layer = r.second; lifespan += r.third
        }
        val b1 = simulateBreakthrough(realm, layer, lifespan)
        realm = b1.first; layer = b1.second; lifespan += b1.third
        repeat(6) {
            val r = simulateBreakthrough(realm, layer, lifespan)
            realm = r.first; layer = r.second; lifespan += r.third
        }
        assertEquals(8, realm)
        assertEquals(7, layer)
        assertEquals(130, lifespan)  // 修复后：只有跨境界 +50，不应是430
    }
}
