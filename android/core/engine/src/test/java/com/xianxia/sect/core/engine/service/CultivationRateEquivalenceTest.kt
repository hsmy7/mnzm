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
import com.xianxia.sect.core.model.griefEndYear
import com.xianxia.sect.core.model.parentId1
import com.xianxia.sect.core.model.parentId2
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
 * 覆盖维度：境界/弟子类型/灵根数量/政策津贴/哀悼/父母/师徒/丹药临时加速/功法熟练度。
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
        talentIds: List<String> = emptyList(),
        physiqueIds: List<String> = emptyList(),
        affixIds: List<String> = emptyList(),
        age: Int = 30,
        lifespan: Int = 80,
        cultivation: Double = 100.0,
        skills: SkillStats = SkillStats()
    ): Disciple = Disciple(
        id = id, name = name, realm = realm, cultivation = cultivation,
        discipleType = discipleType, spiritRootType = spiritRootType,
        social = social, cultivationSpeedBonus = cultivationSpeedBonus,
        cultivationSpeedDuration = cultivationSpeedDuration,
        pillEffects = pillEffects, manualIds = manualIds,
        talentIds = talentIds, physiqueIds = physiqueIds, affixIds = affixIds,
        age = age, lifespan = lifespan, skills = skills
    )

    /** 20+ 固定 fixtures：覆盖全部速率乘区组合 */
    private fun fixtures(): List<Fixture> {
        val base = GameData(gameYear = 5, gameMonth = 3)
        val result = mutableListOf<Fixture>()

        // 1. 基础组合：境界 × 弟子类型 × 灵根数量（4×2×3 = 24 个）
        for (realm in listOf(9, 5, 1, 0)) {
            for (type in listOf("outer", "inner")) {
                for (root in listOf("metal", "metal,fire", "metal,fire,wood,water,earth")) {
                    result.add(
                        Fixture(
                            "basic realm=$realm type=$type root=$root",
                            makeDisciple(realm = realm, discipleType = type, spiritRootType = root),
                            base
                        )
                    )
                }
            }
        }

        // 2. 政策津贴：cultivationSubsidy 仅 realm>5 生效
        result.add(
            Fixture(
                "policy subsidy realm=8",
                makeDisciple(realm = 8),
                base.copy(sectPolicies = SectPolicies(cultivationSubsidy = true))
            )
        )
        result.add(
            Fixture(
                "policy subsidy realm=5 (not applicable)",
                makeDisciple(realm = 5),
                base.copy(sectPolicies = SectPolicies(cultivationSubsidy = true))
            )
        )
        result.add(
            Fixture(
                "policy ascetic + relaxed",
                makeDisciple(realm = 9),
                base.copy(sectPolicies = SectPolicies(
                    asceticTraining = true, relaxedMgmt = true
                ))
            )
        )

        // 3. 父母灵根加成（父母存活、双灵根）
        val parent1 = makeDisciple(id = "100", name = "父亲", realm = 5, spiritRootType = "metal,fire")
        val parent2 = makeDisciple(id = "101", name = "母亲", realm = 6, spiritRootType = "metal,wood")
        result.add(
            Fixture(
                "with living parents",
                makeDisciple(social = SocialData(parentId1 = "100", parentId2 = "101")),
                base,
                listOf(parent1, parent2)
            )
        )

        // 4. 父母死亡（无加成）
        result.add(
            Fixture(
                "with dead parent",
                makeDisciple(social = SocialData(parentId1 = "100")),
                base,
                listOf(parent1.copy(isAlive = false))
            )
        )

        // 5. 师徒加成：师父低境界（弟子 realm >= 师父 realm 且有 teaching）
        val master = makeDisciple(
            id = "200", name = "师父", realm = 3,
            skills = SkillStats(teaching = 90)
        )
        result.add(
            Fixture(
                "with living master teaching=90",
                makeDisciple(realm = 3, social = SocialData(masterId = "200")),
                base,
                listOf(master)
            )
        )
        // 师父已死（无加成）
        result.add(
            Fixture(
                "with dead master",
                makeDisciple(social = SocialData(masterId = "200")),
                base,
                listOf(master.copy(isAlive = false))
            )
        )

        // 6. 哀悼期：进行中 / 已结束
        result.add(
            Fixture(
                "grieving (currentYear < griefEndYear)",
                makeDisciple(social = SocialData(griefEndYear = 10)),
                base.copy(gameYear = 5)
            )
        )
        result.add(
            Fixture(
                "grief over (currentYear >= griefEndYear)",
                makeDisciple(social = SocialData(griefEndYear = 3)),
                base.copy(gameYear = 5)
            )
        )

        // 7. 丹药临时加速
        result.add(
            Fixture(
                "pill speed bonus active",
                makeDisciple(pillEffects = PillEffects(
                    pillEffectDuration = 5, pillCultivationSpeedBonus = 0.5
                )),
                base
            )
        )
        // 丹药过期（duration=0）
        result.add(
            Fixture(
                "pill speed bonus expired",
                makeDisciple(pillEffects = PillEffects(
                    pillEffectDuration = 0, pillCultivationSpeedBonus = 0.5
                )),
                base
            )
        )

        // 8. 临时加速（cultivationSpeedBonus）
        result.add(
            Fixture(
                "temporary speed bonus active",
                makeDisciple(cultivationSpeedBonus = 0.3, cultivationSpeedDuration = 4),
                base
            )
        )

        // 9. 功法熟练度（走 ManualDatabase 兜底路径）
        result.add(
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

        // 10. 讲道长老加成（elderSlots 配置 + 长老 teaching）
        val preachingElder = makeDisciple(
            id = "300", name = "讲道长老", realm = 2,
            discipleType = "elder", skills = SkillStats(teaching = 95)
        )
        val elderSlots = ElderSlots(
            preachingElder = "300", preachingMasters = emptyList(),
            qingyunPreachingElder = "", qingyunPreachingMasters = emptyList()
        )
        result.add(
            Fixture(
                "with preaching elder outer disciple",
                makeDisciple(realm = 3, discipleType = "outer"),
                base.copy(elderSlots = elderSlots),
                listOf(preachingElder)
            )
        )

        // 11. teachingFlat 跨阈值：基础 79 + 夫子(teachingFlat) = 有效 ≥80
        // 修复前结算用列基础值（79 < 80 无加成），UI 用 getBaseStats（含 flat）——
        // 两入口必须一致且 teachingFlat 生效
        val teachingFlatElder = makeDisciple(
            id = "400", name = "夫子长老", realm = 2,
            discipleType = "elder", skills = SkillStats(teaching = 79),
            talentIds = listOf("r1_base_teach")
        )
        result.add(
            Fixture(
                "preaching elder with teachingFlat crossing threshold",
                makeDisciple(realm = 3, discipleType = "outer"),
                base.copy(elderSlots = ElderSlots(
                    preachingElder = "400", preachingMasters = emptyList(),
                    qingyunPreachingElder = "", qingyunPreachingMasters = emptyList()
                )),
                listOf(teachingFlatElder)
            )
        )

        return result
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
    fun `teachingFlat talent contributes to preaching bonus`() {
        // F1 回归：结算侧有效教学必须含 teachingFlat（对齐 getBaseStats().teaching），
        // 基础 79 + 夫子(teachingFlat) 跨过 80 阈值应产生讲道加成
        val tables = DiscipleTables()
        tables.insert(makeDisciple(
            id = "400", realm = 2, discipleType = "elder",
            skills = SkillStats(teaching = 79), talentIds = listOf("r1_base_teach")
        ))
        tables.insert(makeDisciple(id = "1", realm = 3, discipleType = "outer"))
        val data = GameData(gameYear = 5, gameMonth = 3, elderSlots = ElderSlots(
            preachingElder = "400", preachingMasters = emptyList(),
            qingyunPreachingElder = "", qingyunPreachingMasters = emptyList()
        ))

        val withTalent = calculator.calculateCultivationPerPhaseById(1, data, tables)

        // 移除天赋后：基础 79 < 80，无讲道加成
        tables.talentIds[400] = emptyList()
        val withoutTalent = calculator.calculateCultivationPerPhaseById(1, data, tables)

        assertTrue(
            "teachingFlat 应贡献讲道加成（有天赋 $withTalent > 无天赋 $withoutTalent）",
            withTalent > withoutTalent
        )
    }

    @Test
    fun `aptitude 120 disciple stays equivalent across both paths`() {
        // 2026-08-12 资质乘区（80 基准每点+1% 最多+40%）：两入口必须一致
        val tables = DiscipleTables()
        val d = makeDisciple(id = "1", skills = SkillStats(aptitude = 120))
        tables.insert(d)
        val data = GameData(gameYear = 5, gameMonth = 3)

        val objectRate = calculator.calculateDiscipleCultivationPerPhase(d, data, tables)
        val columnRate = calculator.calculateCultivationPerPhaseById(1, data, tables)
        assertEquals("资质120：object=$objectRate column=$columnRate", objectRate, columnRate, 1e-9)
        // 资质120 → +40% 封顶：与默认资质 50 相比差 1.40 倍
        val baseTables = DiscipleTables()
        baseTables.insert(makeDisciple(id = "2", skills = SkillStats(aptitude = 50)))
        val baseRate = calculator.calculateCultivationPerPhaseById(2, data, baseTables)
        assertEquals("资质120 应比 50 快 40%", baseRate * 1.40, objectRate, 1e-9)
    }

    @Test
    fun `missing aptitude column defaults to 50 across both paths`() {
        // 守护统一默认值：列缺失（旧档自愈前）时 assemble 与列直读均回退
        // DEFAULT_APTITUDE=50，资质加成 0——两入口不得因默认值分叉
        val tables = DiscipleTables()
        tables.insert(makeDisciple(id = "1")) // insert 写入 skills.aptitude=50
        // 模拟旧档：清空 aptitudes 列（自愈前的读档窗口）
        tables.aptitudes[1] = DiscipleTables.DEFAULT_APTITUDE
        val data = GameData(gameYear = 5, gameMonth = 3)

        val objectRate = calculator.calculateDiscipleCultivationPerPhase(
            makeDisciple(id = "1", skills = SkillStats(aptitude = DiscipleTables.DEFAULT_APTITUDE)),
            data, tables
        )
        val columnRate = calculator.calculateCultivationPerPhaseById(1, data, tables)
        assertEquals("列缺失默认50：object=$objectRate column=$columnRate", objectRate, columnRate, 1e-9)
    }

    @Test
    fun `grief sentinel -1 with tampered negative year stays equivalent`() {
        // F2 回归：篡改存档使 gameYear 为负 + griefEndYears 哨兵 -1 时，
        // 列直读路径必须与 assemble 路径（takeIf 过滤哨兵 → null）严格一致
        val tables = DiscipleTables()
        val d = makeDisciple(realm = 9)
        tables.insert(d)
        tables.griefEndYears[1] = DiscipleTables.GRIEF_YEAR_NULL_SENTINEL

        val data = GameData(gameYear = -5, gameMonth = 1)
        val objectRate = calculator.calculateDiscipleCultivationPerPhase(d, data, tables)
        val columnRate = calculator.calculateCultivationPerPhaseById(1, data, tables)
        assertEquals(
            "哨兵 -1 + 负年份：object=$objectRate column=$columnRate",
            objectRate, columnRate, 1e-9
        )
    }
}
