package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 DiscipleFactory 统一构造的四段逻辑
 * （variance / comprehension / portrait / skills / baseStats）。
 *
 * 调用方现状：生产侧唯一调用点是 `DiscipleService.instantiateTemplate`
 * （角色模板实例化，传 `templateId` + `portraitResOverride`）；跨语言对拍
 * `DiffDiscipleFactoryTest` 与本类直接构造 [DiscipleFactory.DiscipleSeed]。
 *
 * 断言纪律：本类不写任何随黄金基线重录漂移的具体随机数值——凡涉及随机数的
 * 结论一律用「关系型断言」（消费序对齐、池成员归属、段间前后关系）表达。
 */
class DiscipleFactoryTest {

    private val factory = DiscipleFactory()

    /** 通用肖像池男性段大小（`PortraitPool` 私有清单的公开等价口径） */
    private val malePoolSize: Int = PortraitPool.allPortraitNames().count { it.startsWith("male_") }

    /**
     * 记录每次随机请求 `(from, until)` 的随机源——用于「消费序」断言。
     * 底层与生产同一 PRNG（[DeterministicRng]），同种子两次实例产出同序列。
     */
    private class RecordingRng(seed: Long) {
        private val rng = DeterministicRng.fromSeed(seed)
        val requests = mutableListOf<Pair<Int, Int>>()
        fun nextInt(from: Int, until: Int): Int {
            requests += from to until
            return from + rng.nextInt(until - from)
        }
    }

    // ---- 辅助函数 ----

    private fun newSeed(
        id: String = "test-001",
        gender: String = MALE,
        spiritRootType: String = "火",
        realmLayer: Int = 1,
        templateId: String = "",
        portraitResOverride: String = "",
        nextInt: ((Int, Int) -> Int)? = null
    ): DiscipleFactory.DiscipleSeed {
        return DiscipleFactory.DiscipleSeed(
            id = id,
            gender = gender,
            nameResult = NameService.NameResult("测试", "弟子"),
            spiritRootType = spiritRootType,
            realmLayer = realmLayer,
            nextInt = nextInt ?: { from, _ -> from }, // 确定性：总是取最小值
            templateId = templateId,
            portraitResOverride = portraitResOverride
        )
    }

    // ---- 方差 / 悟性 / 技能 ----

    @Test
    fun `create - variance is deterministic given nextInt`() {
        val d = factory.create(newSeed())
        // nextInt 固定返回 from → Box-Muller z≈4.291 → 截断至 50
        assertEquals(50, d.combat.hpVariance)
        assertEquals(50, d.combat.mpVariance)
        assertEquals(50, d.combat.attackVariance)
        assertEquals(50, d.combat.attackVariance)
        assertEquals(50, d.combat.defenseVariance)
        assertEquals(50, d.combat.defenseVariance)
        assertEquals(50, d.combat.speedVariance)
    }

    @Test
    fun `create - skills are deterministic given nextInt`() {
        val d = factory.create(newSeed())
        // 同 gaussianInt 逻辑：u1=0.0001, u2=0.0, z≈4.291
        // skill = round(4.291*16.5 + 50.5) = round(121.3) = 121
        // 属性上限 200 → 121 保留
        assertEquals(121, d.skills.intelligence)
        assertEquals(121, d.skills.charm)
        assertEquals(121, d.skills.morality)
        assertEquals(121, d.skills.artifactRefining)
        assertEquals(121, d.skills.pillRefining)
        assertEquals(121, d.skills.spiritPlanting)
        assertEquals(121, d.skills.mining)
        assertEquals(121, d.skills.teaching)
    }

    @Test
    fun `create - single spirit root yields high comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火"))
        // nextInt 固定 from=80，单灵根 from=80 → comprehension=80
        assertEquals(80, d.skills.comprehension)
    }

    @Test
    fun `create - two spirit roots yield mid comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水"))
        // nextInt 固定 from=60，双灵根 from=60 → comprehension=60
        assertEquals(60, d.skills.comprehension)
    }

    @Test
    fun `create - three spirit roots yield lower comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水,木"))
        assertEquals(40, d.skills.comprehension)
    }

    @Test
    fun `create - four spirit roots yield minimal comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水,木,金"))
        assertEquals(20, d.skills.comprehension)
    }

    @Test
    fun `create - five spirit roots yield worst comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水,木,金,土"))
        assertEquals(1, d.skills.comprehension)
    }

    // ---- 基础属性（calculateBaseStatsWithVariance） ----

    @Test
    fun `create - baseStats are populated`() {
        val d = factory.create(newSeed())
        assertTrue("baseHp should be > 0", d.combat.baseHp > 0)
        assertTrue("baseMp should be > 0", d.combat.baseMp > 0)
        assertTrue("baseAttack should be > 0", d.combat.baseAttack > 0)
        assertTrue("baseAttack should be > 0", d.combat.baseAttack > 0)
        assertTrue("baseDefense should be > 0", d.combat.baseDefense > 0)
        assertTrue("baseDefense should be > 0", d.combat.baseDefense > 0)
        assertTrue("baseSpeed should be > 0", d.combat.baseSpeed > 0)
    }

    @Test
    fun `create - disciple has valid id`() {
        val d = factory.create(newSeed())
        assertTrue("id should not be blank", d.id.isNotBlank())
        assertEquals(MALE, d.gender)
        assertEquals("非模板弟子 templateId 必须为空串", "", d.templateId)
    }

    // ---- 确定性一致性 ----

    @Test
    fun `create - deterministic fields match given seed`() {
        val seed = newSeed()
        val d1 = factory.create(seed)
        val d2 = factory.create(seed)
        // nextInt 确定性字段
        assertEquals(d1.gender, d2.gender)
        assertEquals(d1.realm, d2.realm)
        assertEquals(d1.realmLayer, d2.realmLayer)
        assertEquals(d1.spiritRootType, d2.spiritRootType)
        assertEquals(d1.skills.comprehension, d2.skills.comprehension)
        assertEquals(d1.combat.hpVariance, d2.combat.hpVariance)
    }

    @Test
    fun `create - same seed produces identical disciple`() {
        // 两个独立构造的相同 seed → 序列一致 → 结果完全一致
        val d1 = factory.create(newSeed())
        val d2 = factory.create(newSeed())
        assertEquals(d1.name, d2.name)
    }

    @Test
    fun `create - different seeds produce different disciples`() {
        val d1 = factory.create(newSeed(id = "a", spiritRootType = "火"))
        val d2 = factory.create(newSeed(id = "b", spiritRootType = "火,水"))
        assertNotEquals(d1.skills.comprehension, d2.skills.comprehension)
    }

    // ---- 边界 ----

    @Test
    fun `create - female gender`() {
        val d = factory.create(newSeed(gender = FEMALE))
        assertEquals(FEMALE, d.gender)
    }

    @Test
    fun `create - realmLayer is preserved`() {
        val d = factory.create(newSeed(realmLayer = 3))
        assertEquals(3, d.realmLayer)
    }

    // ---- 分布验证 ----

    @Test
    fun `create - variance center near zero with real random`() {
        val values = mutableListOf<Int>()
        val kotlinRng = kotlin.random.Random
        repeat(1000) {
            val seed = DiscipleFactory.DiscipleSeed(
                id = "dist-test-$it",
                gender = MALE,
                nameResult = NameService.NameResult("测试", "弟子"),
                spiritRootType = "火",
                realmLayer = 1,
                nextInt = { from, until -> from + kotlinRng.nextInt(until - from) }
            )
            values.add(factory.create(seed).combat.hpVariance)
        }
        val mean = values.average()
        // 均值应接近0（方差[-50,50]的正态分布）
        assertTrue("Variance mean should be near 0: $mean", mean in -10.0..10.0)
        // 至少70%的值落在[-30,30]范围内（约2-sigma）
        val withinMid = values.count { it in -30..30 }
        assertTrue("Less than 70% in [-30,30]: ${withinMid}/1000", withinMid >= 700)
    }

    // ---- 模板分支（G08：portraitResOverride / templateId） ----

    @Test
    fun `create - portraitResOverride hit uses template key and records templateId`() {
        // Given：模板表全量枚举驱动（不钉单条，新增模板自动纳入）
        // When：逐个模板以 portraitResOverride=portraitKey 实例化
        // Then：立绘恒等于模板键、templateId 落模板 id，且键不在 37 张通用像池内
        CharacterTemplateDb.ALL.forEach { template ->
            val d = factory.create(
                newSeed(
                    id = "tpl-${template.id}",
                    gender = template.gender,
                    spiritRootType = template.spiritRootType,
                    templateId = template.id,
                    portraitResOverride = template.portraitKey
                )
            )
            assertEquals(
                "模板弟子立绘必须取模板 portraitKey（D-5：肖像不 roll）。若红查 " +
                    "DiscipleFactory.resolvePortraitRes",
                template.portraitKey, d.portraitRes
            )
            assertEquals("templateId 必须落模板 id", template.id, d.templateId)
            assertFalse(
                "角色立绘键禁止并入 PortraitPool 37 张通用像（D-12）",
                PortraitPool.allPortraitNames().contains(d.portraitRes)
            )
        }
    }

    @Test
    fun `create - empty override still rolls from the 37-key common pool`() {
        // Given：override 为空的两性种子各 roll 一批
        // When / Then：取像恒在通用池内、按性别分段，且确实消耗了随机数（非单点）
        listOf(MALE to "male_", FEMALE to "female_").forEach { (gender, prefix) ->
            val portraits = (0 until POOL_ROLL_SAMPLES).map { i ->
                factory.create(
                    newSeed(
                        id = "pool-$gender-$i",
                        gender = gender,
                        nextInt = RecordingRng(POOL_ROLL_SEED_BASE + i)::nextInt
                    )
                ).portraitRes
            }
            val pool = PortraitPool.allPortraitNames()
            assertTrue(
                "通用臂取像必须落在 PortraitPool 内（37 键语义不变，D-12）。若红查 " +
                    "PortraitPool.getRandomPortrait / DiscipleFactory.resolvePortraitRes",
                portraits.all { pool.contains(it) }
            )
            assertTrue(
                "gender=$gender 不得回退到另一性别池（中文性别值会让这条静默失真）",
                portraits.all { it.startsWith(prefix) }
            )
            assertTrue(
                "通用臂必须真正消费肖像随机数（$POOL_ROLL_SAMPLES 次只出 1 张 = 没 roll）",
                portraits.distinct().size > 1
            )
        }
    }

    @Test
    fun `create - non-empty override consumes exactly one fewer nextInt and shifts skills only`() {
        // Given：同一底层 PRNG、同一 seed 的两条臂（唯一差异是 portraitResOverride）
        val overrideRng = RecordingRng(CONSUMPTION_PROBE_SEED)
        val pooledRng = RecordingRng(CONSUMPTION_PROBE_SEED)
        val template = requireNotNull(
            CharacterTemplateDb.byId(CharacterTemplateDb.STARTUP_TEMPLATE_ID)
        )

        // When
        val withOverride = factory.create(
            newSeed(
                templateId = template.id,
                portraitResOverride = template.portraitKey,
                nextInt = overrideRng::nextInt
            )
        )
        val withPoolRoll = factory.create(
            newSeed(gender = MALE, nextInt = pooledRng::nextInt)
        )

        // Then ①：消费次数恰好少 1（跳过肖像那一次）
        assertEquals(
            "override 非空必须少消耗 1 次 nextInt（D-6：空模板路径消费序逐字节不变）。" +
                "若红查 DiscipleFactory.resolvePortraitRes 的早退分支",
            pooledRng.requests.size - 1, overrideRng.requests.size
        )

        // Then ②：请求序列 = 通用臂删掉那一次肖像请求
        assertRequestsAlignAfterDroppingPortraitCall(pooledRng.requests, overrideRng.requests)

        // Then ③：肖像段之前的输出（六维方差 / 悟性 / 由其派生的基础属性）不受平移影响
        assertPrePortraitSegmentIdentical(withPoolRoll, withOverride)

        // Then ④：技能段在肖像之后，序列平移使其数值**不同**（D-6 预期，禁止改成相等断言）
        assertNotEquals(
            "技能段位于肖像之后——少消耗 1 次随机数必然平移后续序列；若两条臂技能完全相同" +
                "说明肖像请求没被真正跳过。查 DiscipleFactory.rollSkills 的调用位置",
            skillVector(withPoolRoll), skillVector(withOverride)
        )
    }

    /**
     * 消费序对齐断言：模板臂的请求序列必须等于通用臂**去掉唯一一次肖像请求**
     * `(0, 通用池性别段大小)` 后的序列——其余请求逐位、逐界一致。
     */
    private fun assertRequestsAlignAfterDroppingPortraitCall(
        pooled: List<Pair<Int, Int>>,
        override: List<Pair<Int, Int>>
    ) {
        val expected = pooled.toMutableList()
        assertTrue(
            "通用臂必须出现一次 (0, $malePoolSize) 的肖像请求。若红查 PortraitPool 池成员" +
                "（37 键语义变更需同步本断言与 D-12）或 DiscipleFactory 的消费序",
            expected.remove(0 to malePoolSize)
        )
        assertEquals(
            "除肖像那一次外，两条臂的随机请求序列必须逐位一致（消费序不得额外漂移）",
            expected, override
        )
    }

    /** 肖像段之前的确定性输出必须逐字段相同：方差 → 基础属性 → 悟性 */
    private fun assertPrePortraitSegmentIdentical(pooled: Disciple, override: Disciple) {
        assertEquals(pooled.combat.hpVariance, override.combat.hpVariance)
        assertEquals(pooled.combat.mpVariance, override.combat.mpVariance)
        assertEquals(pooled.combat.attackVariance, override.combat.attackVariance)
        assertEquals(pooled.combat.attackVariance, override.combat.attackVariance)
        assertEquals(pooled.combat.defenseVariance, override.combat.defenseVariance)
        assertEquals(pooled.combat.defenseVariance, override.combat.defenseVariance)
        assertEquals(pooled.combat.speedVariance, override.combat.speedVariance)
        assertEquals(pooled.combat.baseHp, override.combat.baseHp)
        assertEquals(pooled.combat.baseMp, override.combat.baseMp)
        assertEquals(pooled.combat.baseAttack, override.combat.baseAttack)
        assertEquals(pooled.combat.baseAttack, override.combat.baseAttack)
        assertEquals(pooled.combat.baseDefense, override.combat.baseDefense)
        assertEquals(pooled.combat.baseDefense, override.combat.baseDefense)
        assertEquals(pooled.combat.baseSpeed, override.combat.baseSpeed)
        assertEquals(pooled.skills.comprehension, override.skills.comprehension)
    }

    /** 8 项 roll 技能（悟性不在其列——它在肖像段之前，数值必须相同） */
    private fun skillVector(disciple: Disciple): List<Int> = with(disciple.skills) {
        listOf(
            intelligence, charm, morality, artifactRefining,
            pillRefining, spiritPlanting, mining, teaching
        )
    }

    private companion object {
        /** `PortraitPool.getRandomPortrait` 与 `Disciple.gender` 的性别取值域 */
        const val MALE = "male"
        const val FEMALE = "female"

        /** 通用臂取像分布样本量（足以证明「真的 roll 过」又不拖慢测试） */
        const val POOL_ROLL_SAMPLES = 200

        /** 消费序探针种子：只用于比较两条臂的**相对**关系，不产生任何绝对黄金值 */
        const val CONSUMPTION_PROBE_SEED = 42L

        /** 分布样本的种子基（逐样本 +1，保证互不相关） */
        const val POOL_ROLL_SEED_BASE = 20260925L
    }
}
