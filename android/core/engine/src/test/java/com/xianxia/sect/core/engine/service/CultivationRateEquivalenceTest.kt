package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.pillCultivationSpeedBonus
import com.xianxia.sect.core.model.pillEffectDuration
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner



/**
 * 速率等价性金标准测试：列式直读版 [CultivationRateCalculator.calculateCultivationPerPhaseById]
 * 与对象式版 [CultivationRateCalculator.calculateDiscipleCultivationPerPhase] 在全部
 * 乘区组合下必须输出一致（1e-9 精度）。
 *
 * 覆盖维度：境界/弟子类型/灵根数量/政策津贴/师徒/丹药临时加速/功法熟练度。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class CultivationRateEquivalenceTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var calculator: CultivationRateCalculator

    @Before
    fun setUp() {
        // 初始化功法数据库（fixture 含 manualIds 时走 ManualDatabase 兜底路径）
        ManualDatabase.initializeWithManuals(mapOf(
            "m1" to ManualDatabase.ManualTemplate(
                id = "m1", name = "基础吐纳术", type = ManualType.MIND,
                rarity = 1, description = "测试功法",
                stats = mapOf("cultivationSpeedPercent" to 10)
            )
        ))
        // Fake 默认 manualInstances/disciples flow 即空列表——等价 mock 时代 stub，
        // 且后续服务扩展读其他 store 状态不会静默 null
        calculator = CultivationRateCalculator(FakeAtomicStateStore())
    }

    private data class Fixture(
        val name: String,
        val disciple: Disciple,
        val data: GameData,
        val extraDisciples: List<Disciple> = emptyList()
    )

    private fun makeDisciple(
        id: String = "1",
        name: String = "弟子$id",
        realm: Int = 9,
        discipleType: String = "outer",
        spiritRootType: String = "metal",
        social: SocialData = SocialData(),
        cultivationSpeedBonus: Double = 0.0,
        cultivationSpeedDuration: Int = 0,
        pillEffects: PillEffects = PillEffects(),
        manualIds: List<String> = emptyList(),
        cultivation: Double = 100.0,
        skills: SkillStats = SkillStats()
    ): Disciple = Disciple(
        id = id, name = name, realm = realm, cultivation = cultivation,
        discipleType = discipleType, spiritRootType = spiritRootType,
        social = social, cultivationSpeedBonus = cultivationSpeedBonus,
        cultivationSpeedDuration = cultivationSpeedDuration,
        pillEffects = pillEffects, manualIds = manualIds,
        skills = skills
    )

    /** 20+ 固定 fixtures：覆盖全部速率乘区组合 */
    private fun fixtures(): List<Fixture> {
        val base = GameData(gameYear = 5, gameMonth = 3)
        val result = mutableListOf<Fixture>()

        // 1. 基础组合：境界 × 弟子类型 × 灵根数量（4×2×3 = 24 个）
        result.addAll(basicComboFixtures(base = base))
        // 2. 政策津贴：cultivationSubsidy 仅 realm>5 生效
        result.addAll(policySubsidyFixtures(base = base))
        // 3. 师徒加成：师父低境界（弟子 realm >= 师父 realm 且有 teaching）
        result.addAll(masterFixtures(base = base))
        // 4. 丹药临时加速
        result.addAll(pillFixtures(base = base))
        // 6. 功法熟练度（走 ManualDatabase 兜底路径）
        result.addAll(manualProficiencyFixtures(base = base))
        // 7-8. 讲道长老加成（含 teachingFlat 跨阈值回归）
        result.addAll(preachingElderFixtures(base = base))

        return result
    }

    /** 基础组合：境界 × 弟子类型 × 灵根数量（4×2×3 = 24 个） */
    private fun basicComboFixtures(base: GameData): List<Fixture> = buildList {
        for (realm in listOf(9, 5, 1, 0)) {
            for (type in listOf("outer", "inner")) {
                for (root in listOf("metal", "metal,fire", "metal,fire,wood,water,earth")) {
                    add(
                        Fixture(
                            "basic realm=$realm type=$type root=$root",
                            makeDisciple(realm = realm, discipleType = type, spiritRootType = root),
                            base
                        )
                    )
                }
            }
        }
    }

    /** 政策津贴：cultivationSubsidy 仅 realm>5 生效 + 苦修/宽松组合 */
    private fun policySubsidyFixtures(base: GameData): List<Fixture> = buildList {
        add(
            Fixture(
                "policy subsidy realm=8",
                makeDisciple(realm = 8),
                base.copy(sectPolicies = SectPolicies(cultivationSubsidy = true))
            )
        )
        add(
            Fixture(
                "policy subsidy realm=5 (not applicable)",
                makeDisciple(realm = 5),
                base.copy(sectPolicies = SectPolicies(cultivationSubsidy = true))
            )
        )
        add(
            Fixture(
                "policy ascetic + relaxed",
                makeDisciple(realm = 9),
                base.copy(sectPolicies = SectPolicies(
                    asceticTraining = true, relaxedMgmt = true
                ))
            )
        )
    }

    /** 师徒加成：师父存活 / 已死 */
    private fun masterFixtures(base: GameData): List<Fixture> = buildList {
        val master = makeDisciple(
            id = "200", name = "师父", realm = 3,
            skills = SkillStats(teaching = 90)
        )
        add(
            Fixture(
                "with living master teaching=90",
                makeDisciple(realm = 3, social = SocialData(masterId = "200")),
                base,
                listOf(master)
            )
        )
        add(
            Fixture(
                "with dead master",
                makeDisciple(social = SocialData(masterId = "200")),
                base,
                listOf(master.copy(isAlive = false))
            )
        )
    }

    /** 丹药临时加速：生效中 / 已过期 */
    private fun pillFixtures(base: GameData): List<Fixture> = buildList {
        add(
            Fixture(
                "pill speed bonus active",
                makeDisciple(pillEffects = PillEffects(
                    pillEffectDuration = 5, pillCultivationSpeedBonus = 0.5
                )),
                base
            )
        )
        add(
            Fixture(
                "pill speed bonus expired",
                makeDisciple(pillEffects = PillEffects(
                    pillEffectDuration = 0, pillCultivationSpeedBonus = 0.5
                )),
                base
            )
        )
    }

    /** 功法熟练度：走 ManualDatabase 兜底路径 */
    private fun manualProficiencyFixtures(base: GameData): List<Fixture> = buildList {
        add(
            Fixture(
                "with manual proficiency",
                makeDisciple(manualIds = listOf("m1")),
                base.copy(manualProficiencies = mapOf(
                    "1" to listOf(ManualProficiencyData(
                        manualId = "m1", manualName = "基础吐纳术",
                        proficiency = 50.0, maxProficiency = 100,
                        masteryLevel = 1
                    ))
                ))
            )
        )
    }

    /** 讲道长老加成：elderSlots 配置 + 长老 teaching */
    private fun preachingElderFixtures(base: GameData): List<Fixture> = buildList {
        val preachingElder = makeDisciple(
            id = "300", name = "讲道长老", realm = 2,
            discipleType = "elder", skills = SkillStats(teaching = 95)
        )
        val elderSlots = ElderSlots(
            preachingElder = "300", preachingMasters = emptyList(),
            qingyunPreachingElder = "", qingyunPreachingMasters = emptyList()
        )
        add(
            Fixture(
                "with preaching elder outer disciple",
                makeDisciple(realm = 3, discipleType = "outer"),
                base.copy(elderSlots = elderSlots),
                listOf(preachingElder)
            )
        )
    }

    @Test
    fun `column rate equals object rate across all fixtures`() {
        val fixtures = fixtures()
        assertTrue("fixtures 数应 >= 20，实际 ${fixtures.size}", fixtures.size >= 20)

        for (f in fixtures) {
            val tables = DiscipleTables()
            (f.extraDisciples + f.disciple).forEach { tables.insert(it) }

            val objectRate = calculator.calculateDiscipleCultivationPerPhase(
                f.disciple, f.data, tables
            )
            val columnRate = calculator.calculateCultivationPerPhaseById(
                f.disciple.id.toInt(), f.data, tables
            )
            assertEquals(
                "fixture [${f.name}]: object=$objectRate column=$columnRate",
                objectRate, columnRate, 1e-9
            )
        }
    }

    @Test
    fun `legacy cultivationSpeedBonus field no longer affects rate`() {
        // 丹药修炼速度加成统一收敛于 pillEffects 体系，
        // 旧 cultivationSpeedBonus 顶层字段（双写时代产物）写入后不应再产生任何加成
        val tables = DiscipleTables()
        tables.insert(makeDisciple(id = "1", cultivationSpeedBonus = 0.3, cultivationSpeedDuration = 4))
        val baselineTables = DiscipleTables()
        baselineTables.insert(makeDisciple(id = "1"))
        val data = GameData(gameYear = 5, gameMonth = 3)

        val withLegacy = calculator.calculateCultivationPerPhaseById(1, data, tables)
        val baseline = calculator.calculateCultivationPerPhaseById(1, data, baselineTables)
        assertEquals("旧 cultivationSpeedBonus 字段不应再影响速率", baseline, withLegacy, 1e-9)
    }

    @Test
    fun `pill speed bonus applies once not doubled`() {
        // 同一颗修炼速度丹只应生效一份加成（单倍），
        // 防止双字段（cultivationSpeedBonus + pillCultivationSpeedBonus）累加造成双倍
        val tables = DiscipleTables()
        tables.insert(makeDisciple(
            id = "1",
            pillEffects = PillEffects(pillEffectDuration = 9, pillCultivationSpeedBonus = 0.5)
        ))
        val data = GameData(gameYear = 5, gameMonth = 3)
        val rate = calculator.calculateCultivationPerPhaseById(1, data, tables)

        // 单灵根炼气基准 19 × (1 + 0.5) = 28.5（单倍；双倍应为 19 × 2.0 = 38）
        assertEquals("丹药加成应只生效一份（28.5）", 19.0 * 1.5, rate, 1e-9)
    }
}
