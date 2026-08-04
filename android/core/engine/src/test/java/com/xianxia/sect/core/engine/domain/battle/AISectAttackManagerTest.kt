package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AISectAttackManagerTest {

    @Before
    fun setUp() {
        // executeSectBattle 集成测试依赖 disciple.getFinalStats（statsProvider）与 aisRngManager
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) = DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) = DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(disciple, equipments, manuals, manualProficiencies)
            override fun getFinalStats(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(aggregate, equipments, manuals, manualProficiencies)
            override fun calculateCultivationSpeed(
                disciple: Disciple, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun getBreakthroughChance(
                disciple: Disciple, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
        }
        aisRngManager = GameRngManager()
    }

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
    fun `createDefenseTeam - 所有存活弟子入选不再按IDLE过滤`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 0, status = DiscipleStatus.IDLE),
            makeDisciple("d2", realm = 1, status = DiscipleStatus.ON_MISSION),
            makeDisciple("d3", realm = 2, status = DiscipleStatus.IDLE),
            makeDisciple("d4", realm = 3, status = DiscipleStatus.REFLECTING),
            makeDisciple("d5", realm = 4, status = DiscipleStatus.IDLE)
        )
        val team = AISectAttackManager.createDefenseTeam(disciples)
        // 所有5名存活弟子按realm排序入选
        assertEquals(5, team.size)
        assertEquals(listOf(0, 1, 2, 3, 4), team.map { it.realm })
    }

    @Test
    fun `createDefenseTeam - 已死亡弟子被排除`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 0, isAlive = false),
            makeDisciple("d2", realm = 1, isAlive = true)
        )
        val team = AISectAttackManager.createDefenseTeam(disciples)
        assertEquals(1, team.size)
        assertEquals("d2", team[0].id)
    }

    @Test
    fun `createPlayerDefenseTeam - 所有存活弟子入选不再按IDLE过滤`() {
        val disciples = listOf(
            makeDisciple("d1", realm = 0, status = DiscipleStatus.IDLE),
            makeDisciple("d2", realm = 1, status = DiscipleStatus.GARRISONING),
            makeDisciple("d3", realm = 2, status = DiscipleStatus.IDLE)
        )
        val team = createPlayerDefenseTeam(disciples)
        // 所有3名存活弟子按realm排序入选
        assertEquals(3, team.size)
        assertEquals(listOf(0, 1, 2), team.map { it.realm })
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
        val result = supplementDisciples(
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
        val result = getGarrisonDisciples(
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
        DiscipleStatus.GARRISONING,
        DiscipleStatus.REFINING
    )

    private fun isEligibleForSectDefense(d: Disciple): Boolean {
        return d.isAlive &&
            d.status !in SECT_DEFENSE_EXCLUDED
    }

    @Test
    fun `主宗门防御 - REFLECTING弟子被排除`() {
        val d = makeDisciple("d1", status = DiscipleStatus.REFLECTING)
        assertFalse(isEligibleForSectDefense(d))
    }

    @Test
    fun `主宗门防御 - 血炼中弟子被排除`() {
        val d = makeDisciple("d1",
            status = DiscipleStatus.REFINING)
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

    // ═══════════════════════════════════════════════════════════════
    // 2026-08-04 战斗核查修复回归（G4 AI 弟子技能完整化）
    // ═══════════════════════════════════════════════════════════════

    private fun healManual(id: String, name: String = "回春术") = ManualInstance(
        name = name, rarity = 3, description = "", type = ManualType.SUPPORT,
        stats = emptyMap(), skillName = name, skillDescription = "",
        skillType = "support", skillDamageType = "physical",
        skillHits = 1, skillDamageMultiplier = 0.0, skillCooldown = 2, skillMpCost = 30,
        skillHealPercent = 0.3, skillHealType = "hp", skillBuffType = null,
        skillBuffValue = 0.0, skillBuffDuration = 0, skillBuffsJson = "",
        skillIsAoe = false, skillTargetScope = "team", minRealm = 9
    )

    private fun aoeManual(id: String, name: String = "横扫") = ManualInstance(
        name = name, rarity = 3, description = "", type = ManualType.ATTACK,
        stats = emptyMap(), skillName = name, skillDescription = "",
        skillType = "attack", skillDamageType = "physical",
        skillHits = 1, skillDamageMultiplier = 1.5, skillCooldown = 3, skillMpCost = 40,
        skillHealPercent = 0.0, skillHealType = "hp", skillBuffType = null,
        skillBuffValue = 0.0, skillBuffDuration = 0, skillBuffsJson = "",
        skillIsAoe = true, skillTargetScope = "enemy", minRealm = 9
    )

    @Test
    fun `buildCombatSkills - 支援功法保留skillType与治疗字段`() {
        // G4 回归：原实现手写 CombatSkill 仅传 7 字段，支援功法被默认成 ATTACK 普攻
        val skills = AISectAttackManager.buildCombatSkills(
            mapOf("m1" to healManual("m1"), "m2" to aoeManual("m2")),
            emptyMap()
        )
        val heal = skills.first { it.name == "回春术" }
        assertEquals("支援功法必须保留 SUPPORT 类型", SkillType.SUPPORT, heal.skillType)
        assertEquals(0.3, heal.healPercent, 1e-9)
        assertEquals("team", heal.targetScope)
        val aoe = skills.first { it.name == "横扫" }
        assertTrue("AOE 功法必须保留 isAoe", aoe.isAoe)
        assertEquals("enemy", aoe.targetScope)
    }

    @Test
    fun `executeSectBattle - 防御方支援弟子_施放支援行动`() {
        // G4 集成：ManualDatabase 初始化后，convertToCombatant → buildManualDataForDisciple
        // → buildCombatSkills 全链路，防御方支援功法在宗门战中真实生效（战报出现 support action）
        ManualDatabase.initializeWithManuals(mapOf(
            "manual_heal" to ManualDatabase.ManualTemplate(
                id = "manual_heal", name = "回春术", type = ManualType.SUPPORT,
                rarity = 3, description = "",
                skillName = "回春术", skillType = "support",
                skillDamageMultiplier = 0.0, skillCooldown = 2, skillMpCost = 30,
                skillHealPercent = 0.3, skillHealType = "hp",
                skillTargetScope = "team", minRealm = 9
            )
        ))
        try {
            val healer = Disciple(
                id = "healer", name = "healer", realm = 7, realmLayer = 1, isAlive = true,
                skills = SkillStats(loyalty = 50),
                manualIds = listOf("manual_heal")
            )
            val attacker = Disciple(
                id = "att", name = "att", realm = 7, realmLayer = 1, isAlive = true,
                skills = SkillStats(loyalty = 50)
            )
            val defenderSect = WorldSect(id = "s_def")
            val result = AISectAttackManager.executeSectBattle(
                attackers = listOf(attacker),
                defenderSect = defenderSect,
                defenderDisciples = listOf(healer)
            )
            val actions = result.rounds.flatMap { it.actions }
            assertTrue(
                "防御方支援弟子应施放支援行动（修复前支援功法变弱普攻）：actions=$actions",
                actions.any { it.type == "support" }
            )
        } finally {
            ManualDatabase.resetForTest()
        }
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
