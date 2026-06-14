package com.xianxia.sect.core.config

import kotlinx.serialization.Serializable

/**
 * 游戏配置数据 (JSON 可序列化版本)
 *
 * 与 assets/config/game_config.json 一一对应。
 * 所有字段均带默认值，保证 assets 加载失败或字段缺失时仍可正常兜底。
 *
 * 注意：本结构仅覆盖 GameConfig 中的 const val 基本类型字段。
 * 复杂 Map/List 数据 (Realm.CONFIGS, Rarity.CONFIGS, Beast.TYPES 等) 仍保留在 GameConfig 中硬编码，
 * 不在本结构范围内。
 */
@Serializable
data class GameConfigData(
    val version: String = "4.0.00",
    val game: GameSection = GameSection(),
    val disciple: DiscipleSection = DiscipleSection(),
    val elder: ElderSection = ElderSection(),
    val time: TimeSection = TimeSection(),
    val cultivation: CultivationSection = CultivationSection(),
    val production: ProductionSection = ProductionSection(),
    val herbGarden: HerbGardenSection = HerbGardenSection(),
    val warehouse: WarehouseSection = WarehouseSection(),
    val rarity: RaritySection = RaritySection(),
    val starting: StartingSection = StartingSection(),
    val playerProtection: PlayerProtectionSection = PlayerProtectionSection(),
    val performance: PerformanceSection = PerformanceSection(),
    val logs: LogsSection = LogsSection(),
    val battle: BattleSection = BattleSection(),
    val policyConfig: PolicyConfigSection = PolicyConfigSection(),
    val lawEnforcement: LawEnforcementSection = LawEnforcementSection(),
    val ai: AISection = AISection(),
    val sectMap: SectMapSection = SectMapSection(),
    val worldMap: WorldMapSection = WorldMapSection(),
    val diplomacy: DiplomacySection = DiplomacySection()
) {
    @Serializable
    data class GameSection(
        val name: String = "模拟宗门",
        val version: String = "4.0.00",
        val autoSaveIntervalSeconds: Long = 60L,
        val autoSaveDebounceMs: Long = 30000L,
        val maxSaveSlots: Int = 5
    )

    @Serializable
    data class DiscipleSection(
        val maxDisciples: Int = 1000,
        val recruitCost: Long = 1000L,
        val minLoyalty: Int = 0,
        val maxLoyalty: Int = 100,
        val minAge: Int = 5,
        val maxAge: Int = 100,
        val protectionMonths: Int = 12
    )

    @Serializable
    data class ElderSection(
        val realmViceSectMaster: Int = 4,
        val realmLawEnforcement: Int = 5,
        val realmElder: Int = 6,
        val realmPreachingMaster: Int = 7
    )

    @Serializable
    data class TimeSection(
        val tickInterval: Long = 100L,
        val ticksPerSecond: Int = 10,
        val secondsPerRealMonth: Int = 6,
        val daysPerMonth: Int = 30,
        val phasesPerMonth: Int = 3,
        val monthsPerYear: Int = 12,
        val maxExploreTime: Int = 12,
        val highFrequencyUpdateInterval: Long = 1000L,
        val lowFrequencyUpdateInterval: Long = 2000L
    )

    @Serializable
    data class CultivationSection(
        val baseSpeed: Double = 8.0,
        val realmSpeedBonusThreshold: Int = 3,
        val realmSpeedBonus: Double = 1.5,
        val dailyHpMpRecoveryRate: Double = 0.05
    )

    @Serializable
    data class ProductionSection(
        val spiritMineBaseOutputPerMiner: Int = 160,
        val spiritMineMiningThreshold: Int = 70,
        val spiritMineMiningBonusRate: Double = 0.02
    )

    @Serializable
    data class HerbGardenSection(
        val auraRadiusTiles: Double = 6.0
    )

    @Serializable
    data class WarehouseSection(
        val baseCapacity: Int = 50,
        val capacityPerBuilding: Int = 50
    )

    @Serializable
    data class RaritySection(
        val priceMultiplier: Double = 0.9,
        val sellPriceMultiplier: Double = 0.8
    )

    @Serializable
    data class StartingSection(
        val spiritStones: Int = 2000,
        val reputation: Int = 100,
        val spiritHerbs: Int = 50
    )

    @Serializable
    data class PlayerProtectionSection(
        val protectionYears: Int = 100
    )

    @Serializable
    data class PerformanceSection(
        val maxTickSamples: Int = 100,
        val maxBatchSamples: Int = 100,
        val batchThreshold: Int = 50,
        val updateIntervalMs: Long = 200L,
        val highFrequencyIntervalMs: Long = 200L,
        val lowFrequencyIntervalMs: Long = 1000L
    )

    @Serializable
    data class LogsSection(
        val maxBattleLogs: Int = 100,
        val maxEventLogs: Int = 200,
        val maxMonthlyEventLogs: Int = 50,
        val maxExplorationLogs: Int = 100
    )

    @Serializable
    data class BattleSection(
        val maxTeamSize: Int = 7,
        val minBeastCount: Int = 3,
        val maxBeastCount: Int = 11,
        val maxTurns: Int = 25,
        val critMultiplier: Double = 1.5,
        val maxDodgeChance: Double = 0.5,
        val maxSkillDodgeChance: Double = 0.3,
        val dodgePerSpeedDiff: Double = 0.005,
        val maxBattleDurationMs: Long = 5000L,
        val battleTimeoutWarningMs: Long = 3000L,
        val defenseConstant: Double = 500.0,
        val damageVariancePercent: Double = 20.0,
        val minDamage: Int = 1,
        val elderSlots: Int = 2,
        val discipleSlots: Int = 8,
        val minFormationSize: Int = 10,
        val realmGap: RealmGapSection = RealmGapSection()
    ) {
        @Serializable
        data class RealmGapSection(
            val damageBonusPerRealm: Double = 0.5,
            val damagePenaltyPerRealm: Double = 0.5,
            val instantKillGap: Int = 3
        )
    }

    @Serializable
    data class PolicyConfigSection(
        val spiritMineBoostCost: Long = 0L,
        val enhancedSecurityCost: Long = 3000L,
        val alchemyIncentiveCost: Long = 3000L,
        val forgeIncentiveCost: Long = 3000L,
        val herbCultivationCost: Long = 3000L,
        val cultivationSubsidyCost: Long = 4000L,
        val manualResearchCost: Long = 4000L,
        val spiritMineBoostName: String = "灵矿增产",
        val enhancedSecurityName: String = "增强治安",
        val alchemyIncentiveName: String = "丹道激励",
        val forgeIncentiveName: String = "锻造激励",
        val herbCultivationName: String = "灵药培育",
        val cultivationSubsidyName: String = "修行津贴",
        val manualResearchName: String = "功法研习",
        val spiritMineBoostBaseEffect: Double = 0.2,
        val enhancedSecurityBaseEffect: Double = 0.2,
        val alchemyIncentiveBaseEffect: Double = 0.1,
        val forgeIncentiveBaseEffect: Double = 0.1,
        val herbCultivationBaseEffect: Double = 0.2,
        val cultivationSubsidyBaseEffect: Double = 0.15,
        val manualResearchBaseEffect: Double = 0.2,
        val viceSectMasterIntelligenceBase: Int = 50,
        val viceSectMasterIntelligenceStep: Int = 5,
        val viceSectMasterIntelligenceBonusPerStep: Double = 0.01,
        val herbGardenElderSpiritBase: Int = 80,
        val herbGardenElderSpiritStep: Int = 4,
        val herbGardenElderMax: Double = 0.2,
        val herbGardenDiscipleSpiritBase: Int = 50,
        val herbGardenDiscipleSpiritStep: Int = 5,
        val herbGardenDiscipleMax: Double = 0.2
    )

    @Serializable
    data class LawEnforcementSection(
        val loyaltyThreshold: Int = 30,
        val moralityThreshold: Int = 30,
        val probPerPoint: Double = 0.03,
        val maxProb: Double = 0.9,
        val theftMinRatio: Double = 0.01,
        val theftMaxRatio: Double = 0.05,
        val baseCaptureRate: Double = 0.0,
        val intelligenceBase: Int = 50,
        val elderBonusPerPoint: Double = 0.01,
        val discipleIntelligenceStep: Int = 5,
        val discipleBonusPerStep: Double = 0.01,
        val reflectionYears: Int = 5,
        val newDiscipleProtectionMonths: Int = 12
    )

    @Serializable
    data class AISection(
        val minDisciplesForAttack: Int = 10,
        val powerRatioThreshold: Double = 0.8,
        val teamSize: Int = 10,
        val maxBattleTurns: Int = 25,
        val powerWeights: PowerWeightsSection = PowerWeightsSection()
    ) {
        @Serializable
        data class PowerWeightsSection(
            val realmBase: Double = 100.0,
            val equipmentRarity: Double = 20.0,
            val equipmentLevel: Double = 5.0,
            val manualRarity: Double = 15.0,
            val manualLevel: Double = 3.0,
            val manualMastery: Double = 0.5,
            val talentRarity: Double = 10.0
        )
    }

    @Serializable
    data class SectMapSection(
        val tileSize: Int = 64,
        val worldWidthCells: Int = 48,
        val worldHeightCells: Int = 48
    )

    @Serializable
    data class WorldMapSection(
        val mapWidth: Int = 1698,
        val mapHeight: Int = 926,
        val sectRadius: Int = 20,
        val minDistance: Int = 34,
        val maxConnectionDistance: Double = 200.0,
        val borderPadding: Int = 34,
        val targetSectCount: Int = 80,
        val maxAttempts: Int = 50000,
        val initialSectFavor: Int = 50,
        val connectionDistanceLimit: Double = 280.0,
        val targetConnectionsPerSect: Int = 3,
        val maxConnectionsPerSect: Int = 5,
        val minConnectionsPerSect: Int = 2,
        val relaxationIterations: Int = 5,
        val relaxationStrength: Double = 0.4,
        val kNearestNeighbors: Int = 6,
        val crossingPenalty: Double = 20.0,
        val clusterMinCount: Int = 5,
        val clusterMaxCount: Int = 9,
        val clusterMinRadius: Double = 70.0,
        val clusterMaxRadius: Double = 200.0,
        val isolatedSectMin: Int = 5,
        val isolatedSectMax: Int = 10,
        val minSectDistance: Double = 28.0,
        val pathWaypointMin: Int = 2,
        val pathWaypointMax: Int = 4,
        val pathCurveStrength: Double = 0.05,
        val caveMinSectDistance: Double = 28.0,
        val caveMinPathDistance: Double = 20.0,
        val caveMinCaveDistance: Double = 20.0,
        val levelMinDistance: Double = 20.0
    )

    @Serializable
    data class DiplomacySection(
        val minAllianceFavor: Int = 80,
        val allianceDurationYears: Int = 5,
        val maxAllianceSlotsDefault: Int = 3,
        val diplomaticEventChance: Double = 0.12,
        val favorDecayNoGiftYears: Int = 1,
        val favorDecayAmount: Int = 1,
        val favorDecayThreshold: Int = 80,
        val minFavor: Int = 0,
        val maxFavor: Int = 100,
        val allianceScore: AllianceScoreSection = AllianceScoreSection(),
        val breakPenalty: BreakPenaltySection = BreakPenaltySection()
    ) {
        @Serializable
        data class AllianceScoreSection(
            val threshold: Int = 80,
            val probabilityDivisor: Double = 200.0,
            val maxAiAlliances: Int = 2
        )

        @Serializable
        data class BreakPenaltySection(
            val spiritStonePenaltyRatio: Double = 0.1
        )
    }
}
