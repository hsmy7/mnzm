// GameDatabaseMigrationsV62.kt — v61→v62 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V62 = "GameDatabase"

/**
 * v61→v62: 弟子属性单列化（装备重构 B1，方案 §15 / §5.1）。
 *
 * `disciples` 表列面变化（@Embedded 平铺列）：
 * - **删 12 列**：`basePhysicalAttack/baseMagicAttack/basePhysicalDefense/baseMagicDefense`、
 *   `physicalAttackVariance/magicAttackVariance/physicalDefenseVariance/magicDefenseVariance`、
 *   `pillPhysicalAttackBonus/pillMagicAttackBonus/pillPhysicalDefenseBonus/pillMagicDefenseBonus`；
 * - **增 7 列**：`baseAttack/baseDefense/innateDamageType/attackVariance/defenseVariance/
 *   pillAttackBonus/pillDefenseBonus`（均带 DEFAULT）。
 *
 * ## 回填口径（Q7 取和 × k=1；与 `DiscipleSerializer` 读面归一化逐条一致）
 * - `baseAttack  = basePhysicalAttack + baseMagicAttack`
 * - `baseDefense = basePhysicalDefense + baseMagicDefense`
 * - `attackVariance  = (physicalAttackVariance  + magicAttackVariance)  / 2`
 * - `defenseVariance = (physicalDefenseVariance + magicDefenseVariance) / 2`
 * - `pillAttackBonus  = pillPhysicalAttackBonus  + pillMagicAttackBonus`
 * - `pillDefenseBonus = pillPhysicalDefenseBonus + pillMagicDefenseBonus`
 * - `innateDamageType` 按**首灵根**派生（金/土→PHYSICAL，水/木/火→MAGIC，空串兜底
 *   PHYSICAL）——与 Kotlin `InnateDamageType.deriveFromRoot` 同口径（模板命中路径
 *   的首灵根即模板派生源，两者结果一致），由 `InnateDamageTypeGuardTest` 钉住。
 *   战力公式为攻防取和线性式 ⇒ 迁移前后总战力不变（S20，`LegacyStatMigrationTest`）。
 *
 * ## 实现与安全
 * create-copy-drop-rename 重建 `disciples`（列定义逐字取自 62.json 对应 schema，
 * 删列 + 增列一次到位；V61 `rebuildTableDroppingColumns` 同模式先例，禁
 * `ALTER TABLE DROP COLUMN`）；升级前由 `GameDatabase.backupDatabaseForMigration`
 * 落文件级备份，降级依赖该备份恢复。62.json 由 KSP 自动导出。
 */
@Suppress("LongMethod") // 列面 12 删 7 增 + 回填清单逐列平铺，拆函数反而遮蔽"清单完整性"（V11ToV20 同先例）
internal val MIGRATION_61_62 = object : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 幂等：单列化已生效（baseAttack 已存在）时直接返回（迁移中断重试安全，
        // 与 V61 rebuildTableDroppingColumns 的"待删列不存在即返回"同语义）
        if (columnExists(db, "disciples", "baseAttack")) return
        db.execSQL("ALTER TABLE `disciples` RENAME TO `disciples_v61_old`")
        // 列定义 = 61.json 现状 − 12 删列 + 7 增列（逐字对齐 GameDatabase v62 实体）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `disciples` (" +
                "`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`surname` TEXT NOT NULL, `realm` INTEGER NOT NULL, `realmLayer` INTEGER NOT NULL, " +
                "`cultivation` REAL NOT NULL, `cultivationCheckpoint` REAL NOT NULL, " +
                "`cultivationCheckpointGameMonth` INTEGER NOT NULL, `spiritRootType` TEXT NOT NULL, " +
                "`isAlive` INTEGER NOT NULL, `gender` TEXT NOT NULL, `portraitRes` TEXT NOT NULL, " +
                "`templateId` TEXT NOT NULL DEFAULT '', `manualIds` TEXT NOT NULL, " +
                "`manualMasteries` TEXT NOT NULL, `status` TEXT NOT NULL, `statusData` TEXT NOT NULL, " +
                "`cultivationSpeedBonus` REAL NOT NULL, `cultivationSpeedDuration` INTEGER NOT NULL, " +
                "`discipleType` TEXT NOT NULL, `cultivationCompletionMonth` INTEGER NOT NULL DEFAULT 0, " +
                "`manualCompletionMonth` INTEGER NOT NULL DEFAULT 0, " +
                "`manualCompletionPhase` INTEGER NOT NULL DEFAULT 1, " +
                "`equipmentNurturingCompletionMonth` INTEGER NOT NULL DEFAULT 0, " +
                "`equipmentNurturingCompletionPhase` INTEGER NOT NULL DEFAULT 1, " +
                "`baseHp` INTEGER NOT NULL, `baseMp` INTEGER NOT NULL, " +
                "`baseAttack` INTEGER NOT NULL DEFAULT 0, `baseDefense` INTEGER NOT NULL DEFAULT 0, " +
                "`baseSpeed` INTEGER NOT NULL, " +
                "`hpVariance` INTEGER NOT NULL, `mpVariance` INTEGER NOT NULL, " +
                "`attackVariance` INTEGER NOT NULL DEFAULT 0, `defenseVariance` INTEGER NOT NULL DEFAULT 0, " +
                "`innateDamageType` TEXT NOT NULL DEFAULT '', " +
                "`speedVariance` INTEGER NOT NULL, " +
                "`totalCultivation` INTEGER NOT NULL, `breakthroughCount` INTEGER NOT NULL, " +
                "`breakthroughFailCount` INTEGER NOT NULL, `currentHp` INTEGER NOT NULL, " +
                "`currentMp` INTEGER NOT NULL, " +
                "`pillAttackBonus` INTEGER NOT NULL DEFAULT 0, `pillDefenseBonus` INTEGER NOT NULL DEFAULT 0, " +
                "`pillHpBonus` INTEGER NOT NULL, `pillMpBonus` INTEGER NOT NULL, " +
                "`pillSpeedBonus` INTEGER NOT NULL, `pillCritRateBonus` REAL NOT NULL, " +
                "`pillCritEffectBonus` REAL NOT NULL, `pillCultivationSpeedBonus` REAL NOT NULL, " +
                "`pillSkillExpSpeedBonus` REAL NOT NULL, `pillNurtureSpeedBonus` REAL NOT NULL, " +
                "`pillEffectDuration` INTEGER NOT NULL, `activePillCategory` TEXT NOT NULL, " +
                "`weaponId` TEXT NOT NULL, `armorId` TEXT NOT NULL, `bootsId` TEXT NOT NULL, " +
                "`accessoryId` TEXT NOT NULL, `weaponNurture` TEXT NOT NULL, `armorNurture` TEXT NOT NULL, " +
                "`bootsNurture` TEXT NOT NULL, `accessoryNurture` TEXT NOT NULL, " +
                "`storageBagItems` TEXT NOT NULL, `storageBagSpiritStones` INTEGER NOT NULL, " +
                "`spiritStones` INTEGER NOT NULL, " +
                "`intelligence` INTEGER NOT NULL, `charm` INTEGER NOT NULL, " +
                "`comprehension` INTEGER NOT NULL, `artifactRefining` INTEGER NOT NULL, " +
                "`pillRefining` INTEGER NOT NULL, `spiritPlanting` INTEGER NOT NULL, " +
                "`mining` INTEGER NOT NULL, `teaching` INTEGER NOT NULL, `morality` INTEGER NOT NULL, " +
                "`salaryPaidCount` INTEGER NOT NULL, `salaryMissedCount` INTEGER NOT NULL, " +
                "`alchemyLevel` INTEGER NOT NULL, `alchemyPromotionCount` INTEGER NOT NULL, " +
                "`forgeLevel` INTEGER NOT NULL, `forgePromotionCount` INTEGER NOT NULL, " +
                "`usage_usedFunctionalPillTypes` TEXT NOT NULL, `usage_recruitedMonth` INTEGER NOT NULL, " +
                "`usage_hasReviveEffect` INTEGER NOT NULL, `usage_hasClearAllEffect` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`, `slot_id`))"
        )
        // 回填复制：普通列直拷；攻防/方差/丹药取和；固有属性按首灵根派生
        //（substr+instr 取 spiritRootType 首段；空串/未收录元素走 ELSE PHYSICAL）
        db.execSQL(
            "INSERT INTO `disciples` SELECT " +
                "`id`, `slot_id`, `name`, `surname`, `realm`, `realmLayer`, `cultivation`, " +
                "`cultivationCheckpoint`, `cultivationCheckpointGameMonth`, `spiritRootType`, `isAlive`, " +
                "`gender`, `portraitRes`, `templateId`, `manualIds`, `manualMasteries`, `status`, " +
                "`statusData`, `cultivationSpeedBonus`, `cultivationSpeedDuration`, `discipleType`, " +
                "`cultivationCompletionMonth`, `manualCompletionMonth`, `manualCompletionPhase`, " +
                "`equipmentNurturingCompletionMonth`, `equipmentNurturingCompletionPhase`, " +
                "`baseHp`, `baseMp`, " +
                "`basePhysicalAttack` + `baseMagicAttack`, " +
                "`basePhysicalDefense` + `baseMagicDefense`, " +
                "`baseSpeed`, " +
                "`hpVariance`, `mpVariance`, " +
                "(`physicalAttackVariance` + `magicAttackVariance`) / 2, " +
                "(`physicalDefenseVariance` + `magicDefenseVariance`) / 2, " +
                "CASE substr(`spiritRootType`, 1, instr(`spiritRootType` || ',', ',') - 1) " +
                "WHEN 'metal' THEN 'PHYSICAL' WHEN 'earth' THEN 'PHYSICAL' " +
                "WHEN 'water' THEN 'MAGIC' WHEN 'wood' THEN 'MAGIC' WHEN 'fire' THEN 'MAGIC' " +
                "ELSE 'PHYSICAL' END, " +
                "`speedVariance`, " +
                "`totalCultivation`, `breakthroughCount`, `breakthroughFailCount`, `currentHp`, " +
                "`currentMp`, " +
                "`pillPhysicalAttackBonus` + `pillMagicAttackBonus`, " +
                "`pillPhysicalDefenseBonus` + `pillMagicDefenseBonus`, " +
                "`pillHpBonus`, `pillMpBonus`, `pillSpeedBonus`, `pillCritRateBonus`, " +
                "`pillCritEffectBonus`, `pillCultivationSpeedBonus`, `pillSkillExpSpeedBonus`, " +
                "`pillNurtureSpeedBonus`, `pillEffectDuration`, `activePillCategory`, " +
                "`weaponId`, `armorId`, `bootsId`, `accessoryId`, `weaponNurture`, `armorNurture`, " +
                "`bootsNurture`, `accessoryNurture`, `storageBagItems`, `storageBagSpiritStones`, " +
                "`spiritStones`, `intelligence`, `charm`, `comprehension`, `artifactRefining`, " +
                "`pillRefining`, `spiritPlanting`, `mining`, `teaching`, `morality`, " +
                "`salaryPaidCount`, `salaryMissedCount`, `alchemyLevel`, `alchemyPromotionCount`, " +
                "`forgeLevel`, `forgePromotionCount`, `usage_usedFunctionalPillTypes`, " +
                "`usage_recruitedMonth`, `usage_hasReviveEffect`, `usage_hasClearAllEffect` " +
                "FROM `disciples_v61_old`"
        )
        db.execSQL("DROP TABLE IF EXISTS `disciples_v61_old`")
        for (idxSql in V62_DISCIPLES_INDEX_SQL) {
            db.execSQL(idxSql)
        }
        // ── pills 表：PillEffect 单列化（B1）──
        // 旧物法四列**保留**（@Embedded 字段即列，旧列值由 attackAddTotal/defenseAddTotal
        // 计算属性在读面归一化，构造面只写新列），仅幂等 ADD 两新列（带 DEFAULT 0）。
        if (!columnExists(db, "pills", "attackAdd")) {
            db.execSQL("ALTER TABLE `pills` ADD COLUMN `attackAdd` INTEGER NOT NULL DEFAULT 0")
        }
        if (!columnExists(db, "pills", "defenseAdd")) {
            db.execSQL("ALTER TABLE `pills` ADD COLUMN `defenseAdd` INTEGER NOT NULL DEFAULT 0")
        }
        Log.i(TAG_V62, "Migration 61→62: single-column stat on disciples(12 dropped/7 added) " +
            "and pills(2 added)")
    }
}

/** v61→v62 重建后需恢复的 disciples 索引（与 62.json 实体声明一致） */
internal val V62_DISCIPLES_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples`(`name`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples`(`realm`, `realmLayer`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples`(`isAlive`, `realm`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples`(`isAlive`, `status`)",
    "CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples`(`discipleType`)"
)
