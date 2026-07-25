package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.ExploredSectInfo
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.MineSlot
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：确保 ManualConverter（及委托的 TeamAndBattleConverter、SlotConverter、
 * WorldAndSectConverter）覆盖的所有领域模型字段完整。
 *
 * 当新增字段时，此测试会失败，提示同步更新对应 converter。
 */
class ManualConverterCoverageTest {

    // ==================== ManualInstance ====================

    /**
     * ManualConverter 仅序列化 ManualInstance 的基本字段。
     * 技能相关字段（skillName、skillType 等）不通过此路径持久化
     * （由 ManualStack 的独立序列化覆盖），因此被排除。
     */
    private val MANUAL_COVERED: Set<String> = setOf(
        "id", "name", "rarity", "type", "stats", "description"
    )

    private val MANUAL_EXCLUDED: Set<String> = setOf(
        "slotId",               // Room 复合主键
        "skillName",            // 功法技能字段，不在此序列化路径
        "skillDescription",     // 功法技能字段
        "skillType",            // 功法技能字段
        "skillDamageType",      // 功法技能字段
        "skillHits",            // 功法技能字段
        "skillDamageMultiplier",// 功法技能字段
        "skillCooldown",        // 功法技能字段
        "skillMpCost",          // 功法技能字段
        "skillHealPercent",     // 功法技能字段
        "skillHealFixed",       // 功法技能字段
        "skillHealType",        // 功法技能字段
        "skillBuffType",        // 功法技能字段
        "skillBuffValue",       // 功法技能字段
        "skillBuffDuration",    // 功法技能字段
        "skillBuffsJson",       // 功法技能字段
        "skillIsAoe",           // 功法技能字段
        "skillTargetScope",     // 功法技能字段
        "skillShieldPercent",   // 功法技能字段
        "skillTurnAdvancePercent",  // 功法技能字段
        "skillDamageSharePercent",  // 功法技能字段
        "skillDamageLinkPercent",   // 功法技能字段
        "minRealm",             // 功法最低境界，不在此路径
        "ownerId",              // 功法所有者
        "isLearned"             // 功法学习状态
    )

    private val MANUAL_COMPUTED: Set<String> = setOf(
        "basePrice", "skill", "cultivationSpeedPercent",
        "rarityColor", "rarityName"
    )

    @Test
    fun `all ManualInstance fields are mapped in ManualConverter`() {
        val allFields = ManualInstance::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = MANUAL_EXCLUDED + MANUAL_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - MANUAL_COVERED
        val extra = MANUAL_COVERED - checkFields
        assertTrue(
            buildMissingMessage("ManualInstance", missing, extra),
            missing.isEmpty()
        )
    }

    // ==================== ManualProficiencyData ====================

    private val MANUAL_PROF_COVERED: Set<String> = setOf(
        "manualId", "manualName", "proficiency",
        "maxProficiency", "level", "masteryLevel"
    )

    @Test
    fun `all ManualProficiencyData fields are mapped in ManualConverter`() {
        val allFields = ManualProficiencyData::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - MANUAL_PROF_COVERED
        assertTrue(
            buildMissingMessage("ManualProficiencyData", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== ActiveMission ====================

    /**
     * ActiveMission 的 rewards 字段被展平；enemyType 和 triggerChance 不由此路径序列化。
     */
    private val ACTIVE_MISSION_COVERED: Set<String> = setOf(
        "id", "missionId", "missionName", "template",
        "difficulty", "discipleIds", "discipleNames", "discipleRealms",
        "startYear", "startMonth", "duration", "rewards", "missionType"
    )

    private val ACTIVE_MISSION_EXCLUDED: Set<String> = setOf(
        "enemyType",       // 不由反序列化路径恢复
        "triggerChance"    // 不由反序列化路径恢复
    )

    private val ACTIVE_MISSION_COMPUTED: Set<String> = setOf(
        "memberCount"
    )

    @Test
    fun `all ActiveMission fields are mapped in ManualConverter`() {
        val allFields = ActiveMission::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = ACTIVE_MISSION_EXCLUDED + ACTIVE_MISSION_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - ACTIVE_MISSION_COVERED
        assertTrue(
            buildMissingMessage("ActiveMission", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== Mission ====================

    private val MISSION_COVERED: Set<String> = setOf(
        "id", "template", "name", "description",
        "difficulty", "duration", "rewards", "missionType",
        "createdYear", "createdMonth"
    )

    private val MISSION_EXCLUDED: Set<String> = setOf(
        "enemyType",       // 不由反序列化路径恢复
        "triggerChance"    // 不由反序列化路径恢复
    )

    private val MISSION_COMPUTED: Set<String> = setOf(
        "memberCount"
    )

    @Test
    fun `all Mission fields are mapped in ManualConverter`() {
        val allFields = Mission::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = MISSION_EXCLUDED + MISSION_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - MISSION_COVERED
        assertTrue(
            buildMissingMessage("Mission", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== MissionRewardConfig ====================

    private val REWARD_CONFIG_COVERED: Set<String> = setOf(
        "spiritStones", "spiritStonesMax",
        "materialCountMin", "materialCountMax",
        "materialMinRarity", "materialMaxRarity",
        "pillCountMin", "pillCountMax",
        "pillMinRarity", "pillMaxRarity",
        "equipmentChance", "equipmentMinRarity", "equipmentMaxRarity",
        "manualChance", "manualMinRarity", "manualMaxRarity",
        "baseSpiritStones", "baseMaterialCountMin", "baseMaterialCountMax",
        "baseMaterialMinRarity", "baseMaterialMaxRarity"
    )

    @Test
    fun `all MissionRewardConfig fields are mapped in ManualConverter`() {
        val allFields = MissionRewardConfig::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - REWARD_CONFIG_COVERED
        assertTrue(
            buildMissingMessage("MissionRewardConfig", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== ExplorationTeam (teamAndBattleConverter) ====================

    private val EXPLORATION_TEAM_COVERED: Set<String> = setOf(
        "id", "name", "memberIds",
        "status", "scoutTargetSectId",
        "startYear", "startMonth", "duration", "progress"
    )

    private val EXPLORATION_TEAM_EXCLUDED: Set<String> = setOf(
        "slotId",           // Room 复合主键
        "caveId",           // 不由此路径序列化
        "caveName",         // 不由此路径序列化
        "dungeon",          // 不由此路径序列化
        "dungeonName",      // 不由此路径序列化
        "memberNames",      // 不由此路径序列化
        "startDay",         // 不由此路径序列化
        "scoutTargetSectName", // 不由此路径序列化
        "currentX",         // 运行时位置，不序列化
        "currentY",         // 运行时位置，不序列化
        "targetX",          // 运行时位置，不序列化
        "targetY",          // 运行时位置，不序列化
        "moveProgress",     // 运行时移动进度
        "arrivalYear",      // 运行时到达时间
        "arrivalMonth",     // 运行时到达时间
        "arrivalDay",       // 运行时到达时间
        "route",            // 运行时路径
        "currentRouteIndex", // 运行时路径索引
        "currentSegmentProgress", // 运行时段进度
        "pityCounterEquipment",   // 运行时保底计数器
        "pityCounterPill",        // 运行时保底计数器
        "pityCounterManual"       // 运行时保底计数器
    )

    private val EXPLORATION_TEAM_COMPUTED: Set<String> = setOf(
        "isTraveling", "isExploring", "isComplete",
        "isScouting", "isMoving"
    )

    @Test
    fun `all ExplorationTeam fields are mapped in TeamAndBattleConverter`() {
        val allFields = ExplorationTeam::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = EXPLORATION_TEAM_EXCLUDED + EXPLORATION_TEAM_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - EXPLORATION_TEAM_COVERED
        assertTrue(
            buildMissingMessage("ExplorationTeam", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleLog (teamAndBattleConverter) ====================

    private val BATTLE_LOG_COVERED: Set<String> = setOf(
        "id", "timestamp", "year", "month",
        "type", "attackerName", "defenderName",
        "result", "details", "drops", "dungeonName",
        "teamMembers", "enemies", "rounds"
    )

    private val BATTLE_LOG_EXCLUDED: Set<String> = setOf(
        "slotId",        // Room 复合主键
        "teamId",        // 不由此路径序列化
        "turns",         // 运行时
        "teamCasualties", // 运行时
        "beastsDefeated", // 运行时
        "battleResult"   // 运行时
    )

    private val BATTLE_LOG_COMPUTED: Set<String> = setOf(
        "displayTime"
    )

    @Test
    fun `all BattleLog fields are mapped in TeamAndBattleConverter`() {
        val allFields = BattleLog::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = BATTLE_LOG_EXCLUDED + BATTLE_LOG_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - BATTLE_LOG_COVERED
        assertTrue(
            buildMissingMessage("BattleLog", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleLogRound (teamAndBattleConverter) ====================

    private val ROUND_COVERED: Set<String> = setOf(
        "roundNumber", "actions"
    )

    @Test
    fun `all BattleLogRound fields are mapped in TeamAndBattleConverter`() {
        val allFields = BattleLogRound::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - ROUND_COVERED
        assertTrue(
            buildMissingMessage("BattleLogRound", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleLogAction (teamAndBattleConverter) ====================

    private val ACTION_COVERED: Set<String> = setOf(
        "type", "attacker", "attackerType", "target",
        "damage", "damageType", "isCrit", "isKill",
        "message", "skillName"
    )

    @Test
    fun `all BattleLogAction fields are mapped in TeamAndBattleConverter`() {
        val allFields = BattleLogAction::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - ACTION_COVERED
        assertTrue(
            buildMissingMessage("BattleLogAction", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleLogMember (teamAndBattleConverter) ====================

    private val MEMBER_COVERED: Set<String> = setOf(
        "id", "name", "realm", "hp", "maxHp",
        "mp", "maxMp", "isAlive", "portraitRes"
    )

    private val MEMBER_EXCLUDED: Set<String> = setOf(
        "realmName",     // 运行时计算
        "realmLayer"     // 不由此路径序列化
    )

    @Test
    fun `all BattleLogMember fields are mapped in TeamAndBattleConverter`() {
        val allFields = BattleLogMember::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - MEMBER_EXCLUDED
        val missing = checkFields - MEMBER_COVERED
        assertTrue(
            buildMissingMessage("BattleLogMember", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleLogEnemy (teamAndBattleConverter) ====================

    private val ENEMY_COVERED: Set<String> = setOf(
        "id", "name", "realm", "hp", "maxHp",
        "isAlive", "portraitRes"
    )

    private val ENEMY_EXCLUDED: Set<String> = setOf(
        "realmName",     // 运行时计算
        "realmLayer"     // 不由此路径序列化
    )

    @Test
    fun `all BattleLogEnemy fields are mapped in TeamAndBattleConverter`() {
        val allFields = BattleLogEnemy::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - ENEMY_EXCLUDED
        val missing = checkFields - ENEMY_COVERED
        assertTrue(
            buildMissingMessage("BattleLogEnemy", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== Alliance (teamAndBattleConverter) ====================

    private val ALLIANCE_COVERED: Set<String> = setOf(
        "id", "sectIds", "startYear", "initiatorId", "envoyDiscipleId"
    )

    @Test
    fun `all Alliance fields are mapped in TeamAndBattleConverter`() {
        val allFields = Alliance::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - ALLIANCE_COVERED
        assertTrue(
            buildMissingMessage("Alliance", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== MineSlot (slotConverter) ====================

    private val MINE_SLOT_COVERED: Set<String> = setOf(
        "index", "discipleId", "discipleName",
        "output", "efficiency", "isActive"
    )

    @Test
    fun `all MineSlot fields are mapped in SlotConverter`() {
        val allFields = MineSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - MINE_SLOT_COVERED
        assertTrue(
            buildMissingMessage("MineSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== PlantSlotData (slotConverter) ====================

    private val PLANT_SLOT_COVERED: Set<String> = setOf(
        "index", "status", "seedId", "seedName",
        "startYear", "startMonth", "growTime", "expectedYield"
    )

    private val PLANT_SLOT_COMPUTED: Set<String> = setOf(
        "isGrowing", "isIdle"
    )

    @Test
    fun `all PlantSlotData fields are mapped in SlotConverter`() {
        val allFields = PlantSlotData::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - PLANT_SLOT_COMPUTED
        val missing = checkFields - PLANT_SLOT_COVERED
        assertTrue(
            buildMissingMessage("PlantSlotData", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== CultivatorCave (slotConverter) ====================

    private val CAVE_COVERED: Set<String> = setOf(
        "id", "name", "ownerRealm", "ownerRealmName",
        "x", "y", "spawnYear", "spawnMonth",
        "expiryYear", "expiryMonth", "isExplored",
        "exploredByTeamId", "status", "canOperate",
        "isOwned", "connectedSects", "mineSlots", "occupationTime"
    )

    private val CAVE_COMPUTED: Set<String> = setOf(
        "isAvailable", "isExpired"
    )

    @Test
    fun `all CultivatorCave fields are mapped in SlotConverter`() {
        val allFields = CultivatorCave::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - CAVE_COMPUTED
        val missing = checkFields - CAVE_COVERED
        assertTrue(
            buildMissingMessage("CultivatorCave", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== CaveExplorationTeam (slotConverter) ====================

    private val CAVE_TEAM_COVERED: Set<String> = setOf(
        "id", "caveId", "caveName", "memberIds",
        "startYear", "startMonth", "duration", "status"
    )

    private val CAVE_TEAM_EXCLUDED: Set<String> = setOf(
        "memberNames",  // 不由此路径序列化
        "startX",       // 运行时位置
        "startY",       // 运行时位置
        "targetX",      // 运行时位置
        "targetY",      // 运行时位置
        "currentX",     // 运行时位置
        "currentY",     // 运行时位置
        "moveProgress"  // 运行时移动进度
    )

    private val CAVE_TEAM_COMPUTED: Set<String> = setOf(
        "isTraveling", "isExploring", "isComplete", "isMoving"
    )

    @Test
    fun `all CaveExplorationTeam fields are mapped in SlotConverter`() {
        val allFields = CaveExplorationTeam::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = CAVE_TEAM_EXCLUDED + CAVE_TEAM_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - CAVE_TEAM_COVERED
        assertTrue(
            buildMissingMessage("CaveExplorationTeam", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== ElderSlots (slotConverter) ====================

    private val ELDER_SLOTS_COVERED: Set<String> = setOf(
        "viceSectMaster", "herbGardenElder", "alchemyElder", "forgeElder",
        "outerElder", "preachingElder", "preachingMasters",
        "lawEnforcementElder", "lawEnforcementDisciples",
        "innerElder", "recruitingElder",
        "qingyunPreachingElder", "qingyunPreachingMasters",
        "herbGardenDisciples", "alchemyDisciples",
        "forgeDisciples", "spiritMineDeaconDisciples"
    )

    @Test
    fun `all ElderSlots fields are mapped in SlotConverter`() {
        val allFields = ElderSlots::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - ELDER_SLOTS_COVERED
        assertTrue(
            buildMissingMessage("ElderSlots", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== DirectDiscipleSlot (slotConverter) ====================

    private val DIRECT_SLOT_COVERED: Set<String> = setOf(
        "index", "discipleId", "discipleName",
        "discipleRealm", "discipleSpiritRootColor", "sectId"
    )

    private val DIRECT_SLOT_COMPUTED: Set<String> = setOf(
        "isActive"
    )

    @Test
    fun `all DirectDiscipleSlot fields are mapped in SlotConverter`() {
        val allFields = DirectDiscipleSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - DIRECT_SLOT_COMPUTED
        val missing = checkFields - DIRECT_SLOT_COVERED
        assertTrue(
            buildMissingMessage("DirectDiscipleSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SpiritMineSlot (slotConverter) ====================

    private val SPIRIT_MINE_COVERED: Set<String> = setOf(
        "index", "discipleId", "discipleName",
        "output", "sectId"
    )

    private val SPIRIT_MINE_EXCLUDED: Set<String> = setOf(
        "consecutiveMiningMonths",  // 运行时字段
        "buildingInstanceId"        // 运行时/新版字段，尚未在 converter 中覆盖
    )

    private val SPIRIT_MINE_COMPUTED: Set<String> = setOf(
        "isActive"
    )

    @Test
    fun `all SpiritMineSlot fields are mapped in SlotConverter`() {
        val allFields = SpiritMineSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = SPIRIT_MINE_EXCLUDED + SPIRIT_MINE_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - SPIRIT_MINE_COVERED
        assertTrue(
            buildMissingMessage("SpiritMineSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== LibrarySlot (slotConverter) ====================

    private val LIBRARY_COVERED: Set<String> = setOf(
        "index", "discipleId", "discipleName"
    )

    private val LIBRARY_EXCLUDED: Set<String> = setOf(
        "buildingInstanceId"  // 新版字段，尚未在 converter 中覆盖
    )

    private val LIBRARY_COMPUTED: Set<String> = setOf(
        "isActive"
    )

    @Test
    fun `all LibrarySlot fields are mapped in SlotConverter`() {
        val allFields = LibrarySlot::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = LIBRARY_EXCLUDED + LIBRARY_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - LIBRARY_COVERED
        assertTrue(
            buildMissingMessage("LibrarySlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== ResidenceSlot (slotConverter) ====================

    private val RESIDENCE_COVERED: Set<String> = setOf(
        "buildingInstanceId", "slotIndex",
        "discipleId", "discipleName"
    )

    private val RESIDENCE_COMPUTED: Set<String> = setOf(
        "isActive"
    )

    @Test
    fun `all ResidenceSlot fields are mapped in SlotConverter`() {
        val allFields = ResidenceSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - RESIDENCE_COMPUTED
        val missing = checkFields - RESIDENCE_COVERED
        assertTrue(
            buildMissingMessage("ResidenceSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BuildingSlot (slotConverter) ====================

    private val BUILDING_SLOT_COVERED: Set<String> = setOf(
        "id", "type", "discipleId", "discipleName",
        "recipeId", "recipeName", "status",
        "startYear", "startMonth"
    )

    private val BUILDING_SLOT_EXCLUDED: Set<String> = setOf(
        "slotId",       // Room 复合主键
        "buildingId",   // 不由此路径序列化
        "slotIndex",    // 不由此路径序列化
        "duration"      // 不由此路径序列化
    )

    @Test
    fun `all BuildingSlot fields are mapped in SlotConverter`() {
        val allFields = BuildingSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - BUILDING_SLOT_EXCLUDED
        val missing = checkFields - BUILDING_SLOT_COVERED
        assertTrue(
            buildMissingMessage("BuildingSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== ProductionSlot (slotConverter) ====================

    private val PRODUCTION_SLOT_COVERED: Set<String> = setOf(
        "id", "slotIndex", "buildingType", "buildingId",
        "status", "recipeId", "recipeName",
        "startYear", "startMonth", "duration",
        "assignedDiscipleId", "assignedDiscipleName",
        "successRate",
        "outputItemId", "outputItemName", "outputItemRarity"
    )

    private val PRODUCTION_SLOT_EXCLUDED: Set<String> = setOf(
        "slotId",                   // Room 复合主键
        "baseDuration",             // 惰性结算运行时字段
        "requiredMaterials",        // 不在序列化路径中
        "outputItemSlot",           // 不在序列化路径中
        "expectedYield",            // 惰性结算运行时字段
        "autoRestartEnabled",       // 运行时设置
        "completionMonth",          // 惰性结算运行时字段
        "completionPhase",          // 惰性结算运行时字段
        "buildingInstanceId"        // 新版字段，尚未在 converter 中覆盖
    )

    private val PRODUCTION_SLOT_COMPUTED: Set<String> = setOf(
        "isIdle", "isWorking", "isCompleted", "slotType"
    )

    @Test
    fun `all ProductionSlot fields are mapped in SlotConverter`() {
        val allFields = ProductionSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val allExcluded = PRODUCTION_SLOT_EXCLUDED + PRODUCTION_SLOT_COMPUTED
        val checkFields = allFields - allExcluded
        val missing = checkFields - PRODUCTION_SLOT_COVERED
        assertTrue(
            buildMissingMessage("ProductionSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== WorldSect (worldAndSectConverter) ====================

    private val WORLD_SECT_COVERED: Set<String> = setOf(
        "id", "name", "level", "levelName", "x", "y", "distance",
        "isPlayerSect", "discovered", "isKnown", "relation",
        "disciples", "maxRealm",
        "isOccupied", "occupierTeamId", "occupierTeamName",
        "allianceId", "allianceStartYear",
        "isRighteous", "isPlayerOccupied",
        "isUnderAttack", "attackerSectId", "occupierSectId"
    )

    private val WORLD_SECT_EXCLUDED: Set<String> = setOf(
        "occupierBattleTeamId",  // 不由此路径序列化
        "garrisonSlots"          // 驻军槽位，不由此路径序列化
    )

    @Test
    fun `all WorldSect fields are mapped in WorldAndSectConverter`() {
        val allFields = WorldSect::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - WORLD_SECT_EXCLUDED
        val missing = checkFields - WORLD_SECT_COVERED
        assertTrue(
            buildMissingMessage("WorldSect", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectDetail (worldAndSectConverter) ====================

    private val SECT_DETAIL_COVERED: Set<String> = setOf(
        "sectId", "mineSlots", "occupationTime",
        "isOwned", "expiryYear", "expiryMonth",
        "scoutInfo", "tradeItems", "tradeLastRefreshYear",
        "lastGiftYear", "warehouse", "giftPreference"
    )

    private val SECT_DETAIL_EXCLUDED: Set<String> = setOf(
        "portraitRes"  // 运行时计算/UI 字段
    )

    @Test
    fun `all SectDetail fields are mapped in WorldAndSectConverter`() {
        val allFields = SectDetail::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - SECT_DETAIL_EXCLUDED
        val missing = checkFields - SECT_DETAIL_COVERED
        assertTrue(
            buildMissingMessage("SectDetail", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectScoutInfo (worldAndSectConverter) ====================

    private val SCOUT_INFO_COVERED: Set<String> = setOf(
        "sectId", "sectName", "scoutYear", "scoutMonth",
        "discipleCount", "maxRealm", "resources",
        "isKnown", "disciples", "expiryYear", "expiryMonth"
    )

    @Test
    fun `all SectScoutInfo fields are mapped in WorldAndSectConverter`() {
        val allFields = SectScoutInfo::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - SCOUT_INFO_COVERED
        assertTrue(
            buildMissingMessage("SectScoutInfo", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== ExploredSectInfo (worldAndSectConverter) ====================

    private val EXPLORED_INFO_COVERED: Set<String> = setOf(
        "sectId", "sectName", "year", "month", "duration",
        "memberIds", "memberNames", "events", "rewards",
        "battleCount", "casualties", "discipleCount", "maxRealm"
    )

    @Test
    fun `all ExploredSectInfo fields are mapped in WorldAndSectConverter`() {
        val allFields = ExploredSectInfo::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - EXPLORED_INFO_COVERED
        assertTrue(
            buildMissingMessage("ExploredSectInfo", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== MerchantItem (worldAndSectConverter) ====================

    private val MERCHANT_ITEM_COVERED: Set<String> = setOf(
        "id", "name", "type", "itemId", "rarity",
        "price", "quantity", "description",
        "obtainedYear", "obtainedMonth", "grade"
    )

    @Test
    fun `all MerchantItem fields are mapped in WorldAndSectConverter`() {
        val allFields = MerchantItem::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - MERCHANT_ITEM_COVERED
        assertTrue(
            buildMissingMessage("MerchantItem", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectWarehouse (worldAndSectConverter) ====================

    private val WAREHOUSE_COVERED: Set<String> = setOf(
        "items", "spiritStones"
    )

    private val WAREHOUSE_EXCLUDED: Set<String> = setOf(
        "midGradeSpiritStones",   // 新版字段，尚未在 converter 中覆盖
        "highGradeSpiritStones"   // 新版字段，尚未在 converter 中覆盖
    )

    @Test
    fun `all SectWarehouse fields are mapped in WorldAndSectConverter`() {
        val allFields = SectWarehouse::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - WAREHOUSE_EXCLUDED
        val missing = checkFields - WAREHOUSE_COVERED
        assertTrue(
            buildMissingMessage("SectWarehouse", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== WarehouseItem (worldAndSectConverter) ====================

    private val WAREHOUSE_ITEM_COVERED: Set<String> = setOf(
        "itemId", "itemName", "itemType", "rarity", "quantity"
    )

    @Test
    fun `all WarehouseItem fields are mapped in WorldAndSectConverter`() {
        val allFields = WarehouseItem::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - WAREHOUSE_ITEM_COVERED
        assertTrue(
            buildMissingMessage("WarehouseItem", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectRelation (worldAndSectConverter) ====================

    private val SECT_RELATION_COVERED: Set<String> = setOf(
        "sectId1", "sectId2", "favor",
        "lastInteractionYear", "noGiftYears", "acquainted"
    )

    @Test
    fun `all SectRelation fields are mapped in WorldAndSectConverter`() {
        val allFields = SectRelation::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - SECT_RELATION_COVERED
        assertTrue(
            buildMissingMessage("SectRelation", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectPolicies (worldAndSectConverter) ====================

    private val SECT_POLICIES_COVERED: Set<String> = setOf(
        "spiritMineBoost", "enhancedSecurity",
        "alchemyIncentive", "forgeIncentive", "herbCultivation",
        "cultivationSubsidy", "manualResearch",
        "autoPlant", "autoAlchemy", "autoForge",
        "autoMineFocused", "autoMineRootCounts", "autoMineThreshold",
        "autoPlantFocused", "autoPlantRootCounts", "autoPlantThreshold",
        "autoAlchemyFocused", "autoAlchemyRootCounts", "autoAlchemyThreshold",
        "autoForgeFocused", "autoForgeRootCounts", "autoForgeThreshold",
        "autoSingleResidenceFocused", "autoSingleResidenceRootCounts", "autoSingleResidenceThreshold",
        "autoMultiResidenceFocused", "autoMultiResidenceRootCounts", "autoMultiResidenceThreshold",
        "openRecruitment", "asceticTraining", "curfew",
        "rewardPunish", "strictTraining", "relaxedMgmt",
        "spiritSpring", "frugality", "moralEducation", "benevolentGovernance"
    )

    @Test
    fun `all SectPolicies fields are mapped in WorldAndSectConverter`() {
        val allFields = SectPolicies::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - SECT_POLICIES_COVERED
        assertTrue(
            buildMissingMessage("SectPolicies", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== 工具方法 ====================

    private fun buildMissingMessage(
        className: String,
        missing: Set<String>,
        extra: Set<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("$className 字段与序列化覆盖检查")
        sb.appendLine("========================================")

        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("以下 $className 字段未在对应 converter 中覆盖：")
            missing.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
            sb.appendLine()
            sb.appendLine("请为上述每个字段：")
            sb.appendLine("  1. 在 convertXxx() 中添加正向映射")
            sb.appendLine("  2. 在 convertBackXxx() 中添加反向映射")
            sb.appendLine("  3. 将此测试的 COVERED 列表中添加字段名")
        }

        if (extra.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("警告：COVERED 中存在下列字段，")
            sb.appendLine("但在 $className 中未找到（可能已被移除或重命名）：")
            extra.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
        }

        sb.appendLine()
        sb.appendLine("========================================")
        return sb.toString()
    }
}
