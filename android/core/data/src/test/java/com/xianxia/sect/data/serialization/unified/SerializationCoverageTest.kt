package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.GameData
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：确保所有 GameData 字段都被云存档序列化路径覆盖。
 *
 * 当新增 GameData 字段时，此测试会失败，并提示开发者同步更新 3 个文件：
 * 1. SerializableGameData.kt — 添加 @ProtoNumber(n) 字段
 * 2. SaveDataConverter.convertGameData() — 正向映射
 * 3. SaveDataConverter.convertBackGameData() — 反向映射
 *
 * 注意：@Ignore 注解的 Retention 为 SOURCE，无法通过运行时反射检测。
 * 因此所有排除集合均显式声明。新增 @Ignore 字段时需同步更新 IGNORE_FIELDS。
 */
class SerializationCoverageTest {

    /**
     * GameData 中已确认被 `convertGameData()` 覆盖的字段。
     *
     * 此列表通过逐行比对 `SaveDataConverter.convertGameData()` 的参数赋值获得。
     * 新增 GameData 字段时，需同时更新此列表和上述 3 个文件。
     */
    private val COVERED_FIELDS: Set<String> = setOf(
        // ==================== 核心字段 ====================
        "sectName",
        "currentSlot",
        "gameYear",
        "gameMonth",
        "gamePhase",
        "spiritStones",
        "midGradeSpiritStones",
        "highGradeSpiritStones",
        "spiritHerbs",
        "sectCultivation",
        "autoSaveIntervalMonths",
        "yearlySalary",
        "yearlySalaryEnabled",

        // ==================== 世界地图 ====================
        "worldMapSects",
        "sectDetails",
        "aiSectDisciples",
        "exploredSects",
        "scoutInfo",
        "manualProficiencies",
        "sectRelations",

        // ==================== 商人 ====================
        "travelingMerchantItems",
        "merchantLastRefreshYear",
        "merchantRefreshCount",
        "merchantRefreshChances",
        "merchantLastRefreshChanceGrantYear",
        "playerListedItems",
        "merchantAcquisitionItems",
        "merchantAcquisitionLastRefreshYear",
        "autoBuyList",

        // ==================== 招募与洞府 ====================
        "recruitList",
        "lastRecruitYear",
        "cultivatorCaves",
        "caveExplorationTeams",
        "aiCaveTeams",

        // ==================== 配方与技能 ====================
        "unlockedRecipes",
        "unlockedManuals",

        // ==================== 存档元数据 ====================
        "lastSaveTime",
        "saveVersion",

        // ==================== 建筑/槽位/生产 ====================
        "elderSlots",
        "spiritMineSlots",
        "spiritMineExpansions",
        "spiritMineLastSettledMonth",
        "librarySlots",
        "productionSlots",
        "placedBuildings",
        "spiritFieldPlants",
        "activeSectId",
        "residenceSlots",
        "warehouseGarrisons",
        "patrolSlots",
        "patrolConfig",
        "patrolConfigs",
        "pendingPatrolBattleResults",

        // ==================== 外交与关系 ====================
        "alliances",
        "vassalContracts",
        "playerAllianceSlots",

        // ==================== 政策与设置 ====================
        "sectPolicies",
        "openRecruitmentLastPaidMonth",

        // ==================== 兑换码 ====================
        "usedRedeemCodes",

        // ==================== 玩家保护 ====================
        "playerProtectionEnabled",
        "playerProtectionStartYear",
        "playerHasAttackedAI",

        // ==================== 任务 ====================
        "activeMissions",
        "availableMissions",

        // ==================== 灵石/血炼/超度 ====================
        "bloodRefinements",
        "activeBloodRefinements",
        "bloodRefinementBonusTotals",
        "bloodRefinementPctTotals",
        "heavenlyTrialState",
        "signInState",
        "isGameOver",

        // ==================== AI/附庸/攻击 ====================
        "aiSectPersonalities",
        "suzerainSectId",
        "lastYearSpiritStoneIncome",
        "activeAttackWarnings",
        "shownWarningStageIds",
        "sectAttackCooldowns",
        "sectBattleRecords",
        "gameEventRecords",

        // ==================== 引导 ====================
        "guideClaimedRewardIds",
        "guideCounters",

        // ==================== 地图种子 ====================
        "mapSeed",

        // ==================== 年度报告 ====================
        "annualIncomeBySource",
        "annualExpenditureByReason",
        "annualTotalIncome",
        "annualTotalExpenditure",
        "annualAlchemyCount",
        "annualForgeCount",
        "annualHerbCount",
        "annualNewDisciples",
        "annualDeceasedDisciples",
        "annualDesertedDisciples",
        "annualTheftCount",
        "theftJudgementsThisMonth",
        "annualEquipmentBySource",
        "annualPillBySource",
        "annualHerbBySource",
        "yearlyReports",

        // ==================== 自动招募/伴侣/装备/功法设置 ====================
        "autoRecruitSpiritRootFilter",
        "daoCompanionBannedRootCounts",
        "daoCompanionConsentRequired",
        "patrolBattleResultPopup",
        "autoSellMidGradeForPurchase",
        "autoSellHighGradeForPurchase",
        "showAllAvailableDisciples",
        "breakthroughAutoPillFocused",
        "breakthroughAutoPillRootCounts",
        "autoEquipFromWarehouseFocused",
        "autoEquipFromWarehouseRootCounts",
        "autoLearnFromWarehouseFocused",
        "autoLearnFromWarehouseRootCounts",

        // ==================== 邮件 ====================
        "mailRecords",
        "sectLevelClaimRecords",

        // ==================== 世界关卡/RNG ====================
        "worldLevels",
        "worldLevelLastRefreshMonth",
        "rngStates"
    )

    /**
     * 因 @Ignore 注解而被排除的运行时字段（不持久化到数据库/云存档）。
     *
     * 注意：Room @Ignore 的 Retention 为 SOURCE，无法通过运行时反射检测，
     * 因此必须显式维护此列表。新增 @Ignore 字段时请同步更新。
     */
    private val IGNORE_FIELDS: Set<String> = setOf(
        "aiSectBeastSkipCooldowns",   // 运行时 AI 妖兽跳过冷却
        "aiBeastEncounterTargets",    // 运行时 AI 妖兽遭遇目标
        "lockedBeastIds",             // 运行时妖兽锁定
        "aiSectBeastDirectTargets",   // 运行时 AI 妖兽直攻目标
        "battleTeams",                // 运行时战斗队伍（旧 schema 迁移）
        "usedTeamNumbers"             // 运行时队伍编号复用
    )

    /**
     * 故意不序列化到云存档的 GameData 字段及原因。
     *
     * 这些字段存在 Room 数据库中供本地使用，但不属于云存档的游戏状态。
     */
    private val INTENTIONALLY_EXCLUDED: Map<String, String> = mapOf(
        "id"            to "Room 复合主键，非游戏字段，不序列化到云存档",
        "slotId"        to "Room 复合主键，非游戏字段，不序列化到云存档",
        "battleTeam"    to "旧 Room schema 兼容字段（battleTeam→battleTeams），逻辑层已废弃",
        "aiBattleTeams" to "旧 AI 战斗队伍字段（v3.0.19 已从序列化移除），仅 Room 兼容"
    )

    /**
     * GameData 类体中仅包含 getter 的计算属性（无 backing field），不在云存档范围内。
     */
    private val COMPUTED_PROPERTIES: Set<String> = setOf(
        "displayTime",                 // 格式化显示时间 getter
        "isPlayerProtected",           // 玩家保护状态计算 getter
        "playerProtectionRemainingYears", // 玩家保护剩余年数计算 getter
        "worldMap",                    // 世界地图聚合 getter
        "buildings",                   // 建筑状态聚合 getter
        "economy",                     // 经济状态聚合 getter
        "organization",                // 组织架构聚合 getter
        "exploration"                  // 探索状态聚合 getter
    )

    @Test
    fun `all GameData fields are mapped in cloud save serialization`() {
        // 1. 获取 GameData 所有属性（反射）
        val allFields = GameData::class.memberProperties
            .map { it.name }
            .toSet()

        // 2. 计算所有排除字段
        val allExcluded = IGNORE_FIELDS +
            INTENTIONALLY_EXCLUDED.keys +
            COMPUTED_PROPERTIES

        // 3. 过滤出需要检查的字段
        val checkFields = allFields
            .filterNot { it in allExcluded }
            .toSet()

        // 4. 找出缺失和多余的字段
        val missing = checkFields - COVERED_FIELDS
        val extra = COVERED_FIELDS - checkFields

        // 5. 断言
        assertTrue(
            buildMissingFieldMessage(missing, extra, allExcluded),
            missing.isEmpty()
        )
    }

    private fun buildMissingFieldMessage(
        missing: Set<String>,
        extra: Set<String>,
        allExcluded: Set<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("GameData 字段与云存档序列化覆盖检查")
        sb.appendLine("========================================")

        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("以下 GameData 字段未在云存档序列化路径中覆盖：")
            missing.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
            sb.appendLine()
            sb.appendLine("请为上述每个字段同步更新以下 4 个文件：")
            sb.appendLine()
            sb.appendLine("  1. SerializableGameData.kt")
            sb.appendLine("     添加 @ProtoNumber(N) 字段")
            sb.appendLine()
            sb.appendLine("  2. SaveDataConverter.convertGameData()")
            sb.appendLine("     添加正向映射赋值")
            sb.appendLine()
            sb.appendLine("  3. SaveDataConverter.convertBackGameData()")
            sb.appendLine("     添加反向映射赋值")
            sb.appendLine()
            sb.appendLine("  4. 此测试文件 SerializationCoverageTest.kt")
            sb.appendLine("     将字段名加入 COVERED_FIELDS set")
        }

        if (extra.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("警告：COVERED_FIELDS 中存在下列字段，")
            sb.appendLine("但在 GameData 的存储属性中未找到（可能已被移除或重命名）：")
            extra.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
        }

        sb.appendLine()
        sb.appendLine("========================================")
        sb.appendLine("当前排除规则汇总（${allExcluded.size} 个字段）：")
        sb.appendLine("  - @Ignore 运行时字段: ${IGNORE_FIELDS.size} 个")
        sb.appendLine("  - 故意排除字段: ${INTENTIONALLY_EXCLUDED.size} 个")
        sb.appendLine("  - 计算属性: ${COMPUTED_PROPERTIES.size} 个")
        sb.appendLine()
        sb.appendLine("如果新增的字段不应序列化到云存档:")
        sb.appendLine("  - 运行时瞬态字段: 加入 IGNORE_FIELDS 并添加 @Ignore 注解")
        sb.appendLine("  - Room 主键/旧兼容字段: 加入 INTENTIONALLY_EXCLUDED")
        sb.appendLine("  - 计算属性（仅 getter）: 加入 COMPUTED_PROPERTIES")
        sb.appendLine("========================================")

        return sb.toString()
    }
}
