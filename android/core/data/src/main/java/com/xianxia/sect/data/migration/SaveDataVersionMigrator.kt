package com.xianxia.sect.data.migration

import android.util.Log
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.data.model.SaveData

/**
 * 存档数据版本（saveVersion）顺序迁移器。
 *
 * 从 StorageEngine.migrateSaveDataIfNeeded 提取（2026-08-04 云读档管线统一）：
 * 本地读档（StorageEngine.loadFromDatabaseInternal）与云存档加载
 * （SaveLoadViewModel.performCloudLoad / performCloudDownload）共用同一迁移管线，
 * 避免旧云档跳过迁移导致数据语义不一致（修炼值未缩放/外交关系未升级）。
 *
 * 迁移链：
 * - v0→1 (v4.0.13): 修炼基础值等比缩小为 1/10。
 * - v1→2: 将所有 sectRelations 的 acquainted 设为 true。
 */
object SaveDataVersionMigrator {
    private const val TAG = "SaveDataVersionMigrator"

    /** 当前存档数据版本号（迁移链终点） */
    const val CURRENT_SAVE_VERSION = 2

    /**
     * 顺序迁移旧版存档数据至当前版本；已是当前版本（saveVersion >= [CURRENT_SAVE_VERSION]）
     * 时原样返回。
     *
     * 版本号边界校验（T10，2026-08-04）：
     * - 负版本号：原行为按 v0 迁移——已缩放数据被二次缩放，现显式拒绝
     * - 高于当前版本：原行为原样返回——Int.MAX 伪造版本绕过 v0→1 缩放，现显式拒绝
     *   （3+ 版本存档出现在 2 版本 App 上 = 降级安装或篡改，拒绝是数据保护正确语义）
     *
     * @param saveData 待迁移存档
     * @return 迁移结果：[MigrationResult.Migrated] 数据可信；[MigrationResult.Rejected] 版本号非法，调用方必须中止加载
     */
    @Suppress("ReturnCount") // 版本边界守卫多早退，拒绝路径为守卫风格
    fun migrate(saveData: SaveData): MigrationResult {
        val gd = saveData.gameData
        if (gd.saveVersion < 0) {
            return MigrationResult.Rejected("saveVersion=${gd.saveVersion} 为负数，判定存档非法")
        }
        if (gd.saveVersion > CURRENT_SAVE_VERSION) {
            return MigrationResult.Rejected(
                "saveVersion=${gd.saveVersion} 高于当前版本 $CURRENT_SAVE_VERSION" +
                    "（可能来自更高版本应用，拒绝加载防数据损坏）"
            )
        }
        if (gd.saveVersion >= CURRENT_SAVE_VERSION) return MigrationResult.Migrated(saveData)

        var currentGd = gd
        var currentDisciples = saveData.disciples

        // ── Migration v0→1: cultivation scaling ──
        if (currentGd.saveVersion < 1) {
            val scaleFactor = 10.0
            Log.i(
                TAG,
                "Migrating save data v0→1: scaling cultivation by 1/$scaleFactor (slot ${gd.slotId})"
            )

            currentGd = currentGd.copy(
                sectCultivation = currentGd.sectCultivation / scaleFactor,
                saveVersion = 1,
                recruitList = currentGd.recruitList.map { d -> d.scaleCultivation(scaleFactor) },
                aiSectDisciples = currentGd.aiSectDisciples.mapValues { (_, list) ->
                    list.map { d -> d.scaleCultivation(scaleFactor) }
                }
            )
            currentDisciples = currentDisciples.map { it.scaleCultivation(scaleFactor) }

            Log.i(TAG, "Migration v0→1 complete: ${currentDisciples.size} disciples scaled")
        }

        // ── Migration v1→2: set all sectRelations to acquainted ──
        if (currentGd.saveVersion < 2) {
            Log.i(
                TAG,
                "Migrating save data v1→2: setting all sectRelations acquainted (slot ${gd.slotId})"
            )

            currentGd = currentGd.copy(
                saveVersion = 2,
                sectRelations = currentGd.sectRelations.map { it.copy(acquainted = true) }
            )

            Log.i(TAG, "Migration v1→2 complete: ${currentGd.sectRelations.size} relations updated")
        }

        return MigrationResult.Migrated(
            saveData.copy(
                gameData = currentGd,
                disciples = currentDisciples
            )
        )
    }

    /**
     * 将弟子的修炼值和战力值同步缩放（向上取整，宁可多不可少）。
     */
    private fun Disciple.scaleCultivation(factor: Double): Disciple {
        return this.copy(
            cultivation = this.cultivation / factor,
            combat = this.combat.copy(
                totalCultivation = kotlin.math.ceil(
                    this.combat.totalCultivation / factor
                ).toLong()
            )
        )
    }
}

/**
 * 存档版本迁移结果（T10，2026-08-04）。
 *
 * [Migrated] 数据可信可继续加载；[Rejected] 版本号非法，调用方必须中止加载
 * （本地读档走备份恢复、云读档弹错误提示），不得使用数据。
 */
sealed interface MigrationResult {
    /** 迁移成功（可能原样返回，如已是当前版本） */
    data class Migrated(val data: SaveData) : MigrationResult

    /** 版本号非法，数据不可信 */
    data class Rejected(val reason: String) : MigrationResult
}
