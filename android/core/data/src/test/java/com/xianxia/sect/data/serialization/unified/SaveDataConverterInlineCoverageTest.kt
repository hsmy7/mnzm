package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.AutoBuyEntry
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.BloodRefinementBonusTotal
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.SectBattleRecord
import com.xianxia.sect.core.model.SectLevelClaimRecord
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.core.state.BattleResultUIData
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：确保 SaveDataConverter 内联转换函数覆盖的所有领域模型字段完整。
 *
 * SaveDataConverter 中有一批 private 内联转换函数，处理无独立 Converter 类的领域模型。
 * 当新增字段时，此测试会失败，提示同步更新 SaveDataConverter 中对应的 convertXxx/convertBackXxx。
 */
class SaveDataConverterInlineCoverageTest {

    // ==================== WorldLevel ====================

    private val WORLD_LEVEL_COVERED: Set<String> = setOf(
        "id", "type", "beastType", "realm", "realmLayer",
        "beastName", "guardianName", "caveName",
        "x", "y",
        "spawnYear", "spawnMonth", "expiryYear", "expiryMonth",
        "count", "caveImageIndex", "defeated",
        "beastMaxHp", "beastMaxMp",
        "beastPhysicalAttack", "beastMagicAttack",
        "beastPhysicalDefense", "beastMagicDefense",
        "beastSpeed"
    )

    private val WORLD_LEVEL_COMPUTED: Set<String> = setOf(
        "isBeast", "isCave", "realmName", "isExpired"
    )

    @Test
    fun `all WorldLevel fields are mapped in SaveDataConverter inline converter`() {
        val allFields = WorldLevel::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - WORLD_LEVEL_COMPUTED
        val missing = checkFields - WORLD_LEVEL_COVERED
        val extra = WORLD_LEVEL_COVERED - checkFields
        assertTrue(
            buildMissingMessage("WorldLevel", missing, extra),
            missing.isEmpty()
        )
    }

    // ==================== SpiritFieldPlant ====================

    private val SPIRIT_FIELD_COVERED: Set<String> = setOf(
        "buildingInstanceId", "seedId", "seedName",
        "growTime", "expectedYield",
        "plantYear", "plantMonth",
        "sectId", "completionMonth", "completionPhase"
    )

    @Test
    fun `all SpiritFieldPlant fields are mapped in SaveDataConverter inline converter`() {
        val allFields = SpiritFieldPlant::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - SPIRIT_FIELD_COVERED
        assertTrue(
            buildMissingMessage("SpiritFieldPlant", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== PatrolSlot ====================

    private val PATROL_SLOT_COVERED: Set<String> = setOf(
        "index", "discipleId", "discipleName",
        "discipleRealm", "portraitRes", "buildingInstanceId"
    )

    private val PATROL_SLOT_COMPUTED: Set<String> = setOf(
        "isActive"
    )

    @Test
    fun `all PatrolSlot fields are mapped in SaveDataConverter inline converter`() {
        val allFields = PatrolSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - PATROL_SLOT_COMPUTED
        val missing = checkFields - PATROL_SLOT_COVERED
        assertTrue(
            buildMissingMessage("PatrolSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== PatrolConfig ====================

    private val PATROL_CONFIG_COVERED: Set<String> = setOf(
        "targetRealms", "maxBeastCount", "requireFullStatus"
    )

    @Test
    fun `all PatrolConfig fields are mapped in SaveDataConverter inline converter`() {
        val allFields = PatrolConfig::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - PATROL_CONFIG_COVERED
        assertTrue(
            buildMissingMessage("PatrolConfig", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleResultUIData ====================

    private val BATTLE_RESULT_UI_COVERED: Set<String> = setOf(
        "battleLogId", "victory", "teamMembers",
        "rewards", "lootedItems", "isBeastDefense"
    )

    @Test
    fun `all BattleResultUIData fields are mapped in SaveDataConverter inline converter`() {
        val allFields = BattleResultUIData::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - BATTLE_RESULT_UI_COVERED
        assertTrue(
            buildMissingMessage("BattleResultUIData", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BattleRewardItem ====================

    private val BATTLE_REWARD_COVERED: Set<String> = setOf(
        "itemId", "name", "quantity", "rarity", "type"
    )

    @Test
    fun `all BattleRewardItem fields are mapped in SaveDataConverter inline converter`() {
        val allFields = BattleRewardItem::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - BATTLE_REWARD_COVERED
        assertTrue(
            buildMissingMessage("BattleRewardItem", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== WarehouseGarrisonSlot ====================

    private val GARRISON_SLOT_COVERED: Set<String> = setOf(
        "buildingInstanceId", "discipleId", "discipleName",
        "sectId", "slotIndex"
    )

    private val GARRISON_SLOT_COMPUTED: Set<String> = setOf(
        "isActive"
    )

    @Test
    fun `all WarehouseGarrisonSlot fields are mapped in SaveDataConverter inline converter`() {
        val allFields = WarehouseGarrisonSlot::class.memberProperties
            .map { it.name }
            .toSet()
        val checkFields = allFields - GARRISON_SLOT_COMPUTED
        val missing = checkFields - GARRISON_SLOT_COVERED
        assertTrue(
            buildMissingMessage("WarehouseGarrisonSlot", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== VassalContract ====================

    private val VASSAL_COVERED: Set<String> = setOf(
        "vassalSectId", "establishedYear", "lastTributeYear"
    )

    @Test
    fun `all VassalContract fields are mapped in SaveDataConverter inline converter`() {
        val allFields = VassalContract::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - VASSAL_COVERED
        assertTrue(
            buildMissingMessage("VassalContract", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== MailClaimRecord ====================

    private val MAIL_COVERED: Set<String> = setOf(
        "mailId", "claimedAt", "source"
    )

    @Test
    fun `all MailClaimRecord fields are mapped in SaveDataConverter inline converter`() {
        val allFields = MailClaimRecord::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - MAIL_COVERED
        assertTrue(
            buildMissingMessage("MailClaimRecord", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectLevelClaimRecord ====================

    private val LEVEL_CLAIM_COVERED: Set<String> = setOf(
        "level", "claimedAtEpochMs"
    )

    @Test
    fun `all SectLevelClaimRecord fields are mapped in SaveDataConverter inline converter`() {
        val allFields = SectLevelClaimRecord::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - LEVEL_CLAIM_COVERED
        assertTrue(
            buildMissingMessage("SectLevelClaimRecord", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BloodRefinementProgress ====================

    private val BLOOD_PROGRESS_COVERED: Set<String> = setOf(
        "discipleId", "discipleName", "materialId", "materialName",
        "startYear", "startMonth", "durationMonths",
        "selectedStat", "bonusPercent"
    )

    @Test
    fun `all BloodRefinementProgress fields are mapped in SaveDataConverter inline converter`() {
        val allFields = BloodRefinementProgress::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - BLOOD_PROGRESS_COVERED
        assertTrue(
            buildMissingMessage("BloodRefinementProgress", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BloodRefinementBonusTotal ====================

    private val BLOOD_BONUS_COVERED: Set<String> = setOf(
        "discipleId", "hpBonus", "physicalAttackBonus",
        "magicAttackBonus", "physicalDefenseBonus",
        "magicDefenseBonus", "speedBonus"
    )

    @Test
    fun `all BloodRefinementBonusTotal fields are mapped in SaveDataConverter inline converter`() {
        val allFields = BloodRefinementBonusTotal::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - BLOOD_BONUS_COVERED
        assertTrue(
            buildMissingMessage("BloodRefinementBonusTotal", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== BloodRefinementPctTotal ====================

    private val BLOOD_PCT_COVERED: Set<String> = setOf(
        "discipleId", "hpBonusPct", "physicalAttackBonusPct",
        "magicAttackBonusPct", "physicalDefenseBonusPct",
        "magicDefenseBonusPct", "speedBonusPct"
    )

    @Test
    fun `all BloodRefinementPctTotal fields are mapped in SaveDataConverter inline converter`() {
        val allFields = BloodRefinementPctTotal::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - BLOOD_PCT_COVERED
        assertTrue(
            buildMissingMessage("BloodRefinementPctTotal", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== HeavenlyTrialSaveData ====================

    private val TRIAL_COVERED: Set<String> = setOf(
        "highestClearedLevel", "levelClearCounts",
        "phase1ClearedLevels", "phase2ClearedLevels",
        "claimedRewardLevels"
    )

    @Test
    fun `all HeavenlyTrialSaveData fields are mapped in SaveDataConverter inline converter`() {
        val allFields = HeavenlyTrialSaveData::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - TRIAL_COVERED
        assertTrue(
            buildMissingMessage("HeavenlyTrialSaveData", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SignInState ====================

    private val SIGN_IN_COVERED: Set<String> = setOf(
        "claimedDays", "currentMonth", "currentYear", "claimedMilestones"
    )

    @Test
    fun `all SignInState fields are mapped in SaveDataConverter inline converter`() {
        val allFields = SignInState::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - SIGN_IN_COVERED
        assertTrue(
            buildMissingMessage("SignInState", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== AttackWarning ====================

    private val ATTACK_WARNING_COVERED: Set<String> = setOf(
        "warningId", "attackerSectId", "attackerSectName",
        "stage", "attackMonth", "createdAtMonth"
    )

    @Test
    fun `all AttackWarning fields are mapped in SaveDataConverter inline converter`() {
        val allFields = AttackWarning::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - ATTACK_WARNING_COVERED
        assertTrue(
            buildMissingMessage("AttackWarning", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== SectBattleRecord ====================

    private val BATTLE_RECORD_COVERED: Set<String> = setOf(
        "year", "type"
    )

    @Test
    fun `all SectBattleRecord fields are mapped in SaveDataConverter inline converter`() {
        val allFields = SectBattleRecord::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - BATTLE_RECORD_COVERED
        assertTrue(
            buildMissingMessage("SectBattleRecord", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== YearlyReport ====================

    private val YEARLY_REPORT_COVERED: Set<String> = setOf(
        "year", "totalIncome", "totalExpenditure",
        "incomeBySource", "expenditureByReason",
        "forgeCompleted", "alchemyCompleted", "herbsHarvested",
        "equipmentBySource", "pillBySource", "herbBySource",
        "newDisciples", "deceasedDisciples", "desertedDisciples"
    )

    @Test
    fun `all YearlyReport fields are mapped in SaveDataConverter inline converter`() {
        val allFields = YearlyReport::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - YEARLY_REPORT_COVERED
        assertTrue(
            buildMissingMessage("YearlyReport", missing, emptySet()),
            missing.isEmpty()
        )
    }

    // ==================== AutoBuyEntry ====================

    private val AUTO_BUY_COVERED: Set<String> = setOf(
        "itemName", "itemType", "rarity"
    )

    @Test
    fun `all AutoBuyEntry fields are mapped in SaveDataConverter inline converter`() {
        val allFields = AutoBuyEntry::class.memberProperties
            .map { it.name }
            .toSet()
        val missing = allFields - AUTO_BUY_COVERED
        assertTrue(
            buildMissingMessage("AutoBuyEntry", missing, emptySet()),
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
        sb.appendLine("$className 字段与 SaveDataConverter 内联转换覆盖检查")
        sb.appendLine("========================================")

        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("以下 $className 字段未在 SaveDataConverter 中覆盖：")
            missing.sorted().forEach { field ->
                sb.appendLine("  - $field")
            }
            sb.appendLine()
            sb.appendLine("请为上述每个字段在 SaveDataConverter 中：")
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
