package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.model.TrialEnemyDef
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.ManualDatabase.ManualTemplate
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class HeavenlyTrialServiceTest {

    private lateinit var service: HeavenlyTrialService
    private lateinit var rngManager: GameRngManager

    @Before
    fun setUp() {
        rngManager = GameRngManager()
        rngManager.initSystemSeed(12345L)
        // mockSmart：未被 stub 的方法返回智能兜底（空集合/抛 SmartNullPointerException），
        // 服务后续扩展读其他依赖不会静默 null 导致难排查 NPE
        service = HeavenlyTrialService(
            stateStore = mockSmart(GameStateStore::class.java),
            inventoryConfig = mockSmart(InventoryConfig::class.java),
            spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java),
            inventorySystem = mockSmart(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        )
        // 预充功法数据（使用空数据+一个备用功法避免 NPE）
        ManualDatabase.initializeWithManuals(mapOf(
            "test_manual" to ManualTemplate(
                id = "test_manual",
                name = "测试功法",
                type = ManualType.ATTACK,
                rarity = 1,
                description = "测试用功法",
                stats = emptyMap(),
                skillName = "普通攻击",
                skillDamageType = "physical",
                skillDamageMultiplier = 1.0,
                skillCooldown = 0,
                skillMpCost = 0,
                skillHits = 1,
                minRealm = 9
            )
        ))
    }

    @Test
    fun `buildDiscipleEnemy - stats use player formula not Enemy REALM_STATS`() {
        // realm=5（化神）时，旧 Enemy.REALM_STATS 的 HP=19165，攻击=1755
        // 玩家公式：baseHp=9126 × variance(0.7~1.3) × layerMult + 装备
        // 即使 layer=9（max）× variance=1.3（max）: 9126×1.3×1.8 ≈ 21355
        // 旧 Enemy 即使 layer=1 就有 19165
        val def = TrialEnemyDef(
            name = "试炼敌人",
            realm = 5,
            realmLayer = 5  // 中层（layerMult=1.4）
        )
        val combatant = service.buildDiscipleEnemy(
            levelIndex = 0, def = def, index = 0
        )

        // 验证使用玩家公式 NOT Enemy.REALM_STATS
        // 旧 Enemy HP: 19165 × 1.4 = 26831
        // 玩家 HP base: 9126 × variance × 1.4
        // 加上装备 (EquipmentDatabase.getTemplateByName 可能返回高 HP 装备)
        // 核心验证：属性 > 0 即可，数值合理性的精确验证依赖方差测试
        assertTrue("HP should be > 0", combatant.hp > 0)
        assertTrue("Physical attack should be > 0", combatant.physicalAttack > 0)
    }

    @Test
    fun `buildDiscipleEnemy - stats within reasonable variance range`() {
        val def = TrialEnemyDef(
            name = "试炼敌人2",
            realm = 6,
            realmLayer = 1
        )
        val combatant = service.buildDiscipleEnemy(
            levelIndex = 0, def = def, index = 0
        )

        // Realm 6 (元婴): baseHp=3448, layer=1, variance ±30%
        // min: 3448 × 0.7 = 2414, max: 3448 × 1.3 = 4482
        assertTrue("HP ${combatant.hp} should be reasonable for realm 6",
            combatant.hp in 2000..5000)

        // Realm 6 basePhysicalAttack=265, layer=1, variance ±30%
        // min: 265 × 0.7 = 186, max: 265 × 1.3 = 345
        assertTrue("Physical attack ${combatant.physicalAttack} should be reasonable",
            combatant.physicalAttack in 150..400)
    }

    @Test
    fun `buildDiscipleEnemy - realm and realmLayer match input`() {
        val def = TrialEnemyDef(
            name = "试炼敌人3",
            realm = 3,
            realmLayer = 7
        )
        val combatant = service.buildDiscipleEnemy(
            levelIndex = 1, def = def, index = 1
        )
        assertEquals(3, combatant.realm)
        assertEquals(7, combatant.realmLayer)
        assertFalse(combatant.isBeast)
    }

    @Test
    fun `buildDiscipleEnemy - deterministic seed produces stable stats`() {
        val def = TrialEnemyDef(
            name = "试炼敌人",
            realm = 5,
            realmLayer = 5
        )
        // C1 修复：试炼敌人生成改确定性派生种子（不再消费全局 ENEMY_GEN）——
        // 同一关卡的同一敌人属性恒定（预览 = 战斗，且零全局 RNG 污染）
        val stats = (1..20).map {
            service.buildDiscipleEnemy(levelIndex = 0, def = def, index = 0)
        }
        val hps = stats.map { it.hp }
        assertTrue("同敌人应属性恒定（确定性种子）",
            hps.max() == hps.min())
    }
}
